package hercules.actors.master

import akka.actor.Actor
import akka.actor.ActorRef
import com.typesafe.config.Config
import akka.actor.ActorLogging
import akka.testkit.{ ImplicitSender, TestActorRef, TestKit, TestProbe }
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import hercules.config.masters.MasterActorConfig
import hercules.actors.masters.SisyphusMasterActor
import hercules.entities.illumina.IlluminaProcessingUnit
import hercules.entities.illumina.HiSeqProcessingUnit
import hercules.config.processingunit.IlluminaProcessingUnitConfig
import java.io.File
import akka.actor.Props
import java.net.URI
import org.scalatest.FlatSpecLike
import hercules.protocols.HerculesMainProtocol._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import scala.concurrent.{ duration, Future, ExecutionContext }
import hercules.actors.utils.MasterLookup
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import akka.util.Timeout
import akka.contrib.pattern.ClusterClient.SendToAll
import akka.actor.ActorSystem
import akka.actor.actorRef2Scala
import akka.contrib.pattern.ClusterSingletonManager
import akka.actor.PoisonPill
import akka.actor.AddressFromURIString
import akka.contrib.pattern.ClusterClient
import akka.actor.RootActorPath
import akka.persistence.SnapshotSelectionCriteria
import org.scalatest.BeforeAndAfterEach
import hercules.actors.masters.MasterState

class SisyphusMasterActorTest() extends TestKit(ActorSystem("SisyphusMasterActorTestSystem"))
    with FlatSpecLike
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Matchers {

  import duration._

  // The processing units that we will return
  val processingUnit: IlluminaProcessingUnit =
    new HiSeqProcessingUnit(
      new IlluminaProcessingUnitConfig(
        new File("Samplesheet1"),
        new File("DefaultQC"),
        Some(new File("DefaultProg"))),
      new URI("/path/to/runfolder1"))

  // The processing units that we will return
  val processingUnit2: IlluminaProcessingUnit =
    new HiSeqProcessingUnit(
      new IlluminaProcessingUnitConfig(
        new File("Samplesheet2"),
        new File("DefaultQC"),
        Some(new File("DefaultProg"))),
      new URI("/path/to/runfolder2"))

  class AcceptingFakeActor(master: ActorRef, testProbe: ActorRef) extends Actor with ActorLogging {
    import context.dispatcher
    implicit val timeout = Timeout(5.seconds)

    def receive() = {
      case RequestDemultiplexingProcessingUnitMessage =>
        master ! RequestDemultiplexingProcessingUnitMessage
      case message: StartDemultiplexingProcessingUnitMessage => {
        sender ! Acknowledge
        testProbe ! message
      }
      case RequestProcessingUnitMessageToForget =>
        master ! RequestProcessingUnitMessageToForget
      case message: ForgetProcessingUnitMessage => {
        sender ! Acknowledge
        testProbe ! message
      }
    }
  }

  class RejectingFakeActor(master: ActorRef, testProbe: ActorRef) extends Actor with ActorLogging {
    import context.dispatcher
    implicit val timeout = Timeout(5.seconds)

    def receive() = {
      case RequestDemultiplexingProcessingUnitMessage =>
        master ! RequestDemultiplexingProcessingUnitMessage
      case message: StartDemultiplexingProcessingUnitMessage => {
        sender ! Reject
        testProbe ! message
      }
      case RequestProcessingUnitMessageToForget =>
        master ! RequestProcessingUnitMessageToForget
      case message: ForgetProcessingUnitMessage => {
        sender ! Reject
        testProbe ! message
      }
    }
  }

  //val sisyphusMaster = 
  var masterActor = system.actorOf(Props(new SisyphusMasterActor(new MasterActorConfig(false, 1000)) { override def persistenceId = "SisyphusMasterActorTesting" }))

  var acceptingFakeActor = system.actorOf(Props(new AcceptingFakeActor(masterActor, testActor)))
  var rejectingFakeActor = system.actorOf(Props(new RejectingFakeActor(masterActor, testActor)))

  override def beforeEach(): Unit = {
    masterActor = system.actorOf(Props(new SisyphusMasterActor(new MasterActorConfig(false, 1000)) { override def persistenceId = "SisyphusMasterActorTesting" }))
    acceptingFakeActor = system.actorOf(Props(new AcceptingFakeActor(masterActor, testActor)))
    rejectingFakeActor = system.actorOf(Props(new RejectingFakeActor(masterActor, testActor)))
    Thread.sleep(500)
  }

  override def afterEach(): Unit = {
    system.stop(acceptingFakeActor)
    system.stop(rejectingFakeActor)
    system.stop(masterActor)
    Thread.sleep(500)
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    Thread.sleep(1000)
  }

  "A SisyphusMasterActor" should " place FoundProcessingUnitMessage in messagesNotYetProcessed" in {
    masterActor.tell(FoundProcessingUnitMessage(processingUnit), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }
    masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
    within(10.seconds) { expectMsg(MasterState(Set(FoundProcessingUnitMessage(processingUnit)), Set(), Set())) }
  }

  "A SisyphusMasterActor" should " should send back StartDemultiplexingProcessingUnitMessage and place inProcessing state " in {
    masterActor.tell(FoundProcessingUnitMessage(processingUnit), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    acceptingFakeActor.tell(RequestDemultiplexingProcessingUnitMessage, testActor)
    within(10.seconds) { expectMsg(StartDemultiplexingProcessingUnitMessage(processingUnit)) }

    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(StartDemultiplexingProcessingUnitMessage(processingUnit)), Set()))
    }, 10.second, 1000.milli)
  }

  "A SisyphusMasterActor" should " remove a unit from the inProcessing state when it has finished" in {
    masterActor.tell(FoundProcessingUnitMessage(processingUnit), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    acceptingFakeActor.tell(RequestDemultiplexingProcessingUnitMessage, testActor)
    within(10.seconds) { expectMsg(StartDemultiplexingProcessingUnitMessage(processingUnit)) }

    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(StartDemultiplexingProcessingUnitMessage(processingUnit)), Set()))
    }, 10.second, 1000.milli)

    masterActor.tell(FinishedDemultiplexingProcessingUnitMessage(processingUnit), testActor)
    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(), Set()))
    }, 10.second, 1000.milli)
  }

  "A SisyphusMasterActor" should " not move the ProcessingUnit from messagesNotYetProcessed if the unit is rejected" in {
    masterActor.tell(FoundProcessingUnitMessage(processingUnit), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    rejectingFakeActor.tell(RequestDemultiplexingProcessingUnitMessage, testActor)
    within(10.seconds) { expectMsg(StartDemultiplexingProcessingUnitMessage(processingUnit)) }

    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(FoundProcessingUnitMessage(processingUnit)), Set(), Set()))
    }, 10.second, 1000.milli)
  }

  "A SisyphusMasterActor" should " be able to forget a ProcessingUnit" in {
    masterActor.tell(FoundProcessingUnitMessage(processingUnit), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    masterActor.tell(ForgetDemultiplexingProcessingUnitMessage(processingUnit.name), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    acceptingFakeActor.tell(RequestProcessingUnitMessageToForget, testActor)
    within(10.seconds) { expectMsg(ForgetProcessingUnitMessage(processingUnit.name)) }

    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(), Set()))
    }, 10.second, 500.milli)
  }

  "A SisyphusMasterActor" should " not forget a ProcessingUnit if the actor rejects it" in {
    masterActor.tell(FoundProcessingUnitMessage(processingUnit), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    masterActor.tell(ForgetDemultiplexingProcessingUnitMessage(processingUnit.name), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    rejectingFakeActor.tell(RequestProcessingUnitMessageToForget, testActor)
    within(10.seconds) { expectMsg(ForgetProcessingUnitMessage(processingUnit.name)) }

    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(ForgetProcessingUnitMessage(processingUnit.name)), Set(), Set()))
    }, 10.second, 500.milli)
  }

  "A SisyphusMasterActor" should " place a failed ProcessingUnit in failedMessages state" in {
    masterActor.tell(FoundProcessingUnitMessage(processingUnit), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    acceptingFakeActor.tell(RequestDemultiplexingProcessingUnitMessage, testActor)
    within(10.seconds) { expectMsg(StartDemultiplexingProcessingUnitMessage(processingUnit)) }
    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(StartDemultiplexingProcessingUnitMessage(processingUnit)), Set()))
    }, 10.second, 500.milli)

    masterActor.tell(FailedDemultiplexingProcessingUnitMessage(processingUnit, "Unknown"), testActor)
    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(), Set(FailedDemultiplexingProcessingUnitMessage(processingUnit, "Unknown"))))
    }, 10.second, 500.milli)
  }

  "A SisyphusMasterActor" should " be able to forget a failed ProcessingUnit" in {
    masterActor.tell(FoundProcessingUnitMessage(processingUnit), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    acceptingFakeActor.tell(RequestDemultiplexingProcessingUnitMessage, testActor)
    within(10.seconds) { expectMsg(StartDemultiplexingProcessingUnitMessage(processingUnit)) }
    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(StartDemultiplexingProcessingUnitMessage(processingUnit)), Set()))
    }, 10.second, 500.milli)

    masterActor.tell(FailedDemultiplexingProcessingUnitMessage(processingUnit, "Unknown"), testActor)
    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(), Set(FailedDemultiplexingProcessingUnitMessage(processingUnit, "Unknown"))))
    }, 10.second, 500.milli)
    masterActor.tell(ForgetDemultiplexingProcessingUnitMessage(processingUnit.name), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    acceptingFakeActor.tell(RequestProcessingUnitMessageToForget, testActor)
    within(10.seconds) { expectMsg(ForgetProcessingUnitMessage(processingUnit.name)) }
    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(), Set()))
    }, 10.second, 500.milli)
  }

  "A SisyphusMasterActor" should " not be able to forget processingUnit that is being processed" in {
    masterActor.tell(FoundProcessingUnitMessage(processingUnit), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    acceptingFakeActor.tell(RequestDemultiplexingProcessingUnitMessage, testActor)
    within(10.seconds) { expectMsg(StartDemultiplexingProcessingUnitMessage(processingUnit)) }

    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(StartDemultiplexingProcessingUnitMessage(processingUnit)), Set()))
    }, 10.second, 500.milli)

    masterActor.tell(ForgetDemultiplexingProcessingUnitMessage(processingUnit.name), testActor)
    within(10.seconds) { expectMsg(Reject(Some("ProcessingUnit runfolder1 is being processed"))) }

    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(StartDemultiplexingProcessingUnitMessage(processingUnit)), Set()))
    }, 10.second, 500.milli)
  }

  "A SisyphusMasterActor" should " be able to restart a failed ProcessingUnit" in {
    masterActor.tell(FoundProcessingUnitMessage(processingUnit), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    acceptingFakeActor.tell(RequestDemultiplexingProcessingUnitMessage, testActor)
    within(10.seconds) { expectMsg(StartDemultiplexingProcessingUnitMessage(processingUnit)) }
    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(StartDemultiplexingProcessingUnitMessage(processingUnit)), Set()))
    }, 10.second, 500.milli)

    masterActor.tell(FailedDemultiplexingProcessingUnitMessage(processingUnit, "Unknown"), testActor)
    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(), Set(FailedDemultiplexingProcessingUnitMessage(processingUnit, "Unknown"))))
    }, 10.second, 500.milli)
    masterActor.tell(RestartDemultiplexingProcessingUnitMessage(processingUnit.name), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(FoundProcessingUnitMessage(processingUnit)), Set(), Set()))
    }, 10.second, 500.milli)
  }

  "A SisyphusMasterActor" should " not be able to restart a ProcessUnit that hasn't failed" in {
    masterActor.tell(FoundProcessingUnitMessage(processingUnit), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    acceptingFakeActor.tell(RequestDemultiplexingProcessingUnitMessage, testActor)
    within(10.seconds) { expectMsg(StartDemultiplexingProcessingUnitMessage(processingUnit)) }

    masterActor.tell(RestartDemultiplexingProcessingUnitMessage(processingUnit.name), testActor)
    within(10.seconds) { expectMsg(Reject(Some("Couldn't find unit runfolder1 requested to restart."))) }

    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(StartDemultiplexingProcessingUnitMessage(processingUnit)), Set()))
    }, 10.second, 500.milli)
  }

  "A SisyphusMasterActor" should " be able to get state for multiple ProcessingUnits" in {
    masterActor.tell(FoundProcessingUnitMessage(processingUnit), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    masterActor.tell(FoundProcessingUnitMessage(processingUnit2), testActor)
    within(10.seconds) { expectMsg(Acknowledge) }

    acceptingFakeActor.tell(RequestDemultiplexingProcessingUnitMessage, testActor)
    within(10.seconds) { expectMsg(StartDemultiplexingProcessingUnitMessage(processingUnit)) }
    within(10.seconds) { expectMsg(StartDemultiplexingProcessingUnitMessage(processingUnit2)) }

    awaitAssert({
      masterActor.tell(RequestMasterState(Some(processingUnit.name)), testActor)
      expectMsg(MasterState(Set(), Set(StartDemultiplexingProcessingUnitMessage(processingUnit)), Set()))
    }, 10.second, 500.milli)
    awaitAssert({
      masterActor.tell(RequestMasterState(), testActor)
      expectMsg(MasterState(Set(), Set(StartDemultiplexingProcessingUnitMessage(processingUnit2), StartDemultiplexingProcessingUnitMessage(processingUnit)), Set()))
    }, 10.second, 500.milli)
  }
}