package hercules.actors.demultiplexing

import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.Assertions.assert
import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.testkit.{ TestProbe, TestActorRef, TestKit, ImplicitSender }
import hercules.demultiplexing.Demultiplexer
import hercules.entities.illumina.HiSeqProcessingUnit
import hercules.entities.illumina.IlluminaProcessingUnit
import hercules.config.processingunit.IlluminaProcessingUnitConfig
import java.io.File
import java.net.URI
import akka.actor.Props
import hercules.test.utils.StepParent
import hercules.demultiplexing.DemultiplexingResult
import akka.actor.PoisonPill
import hercules.protocols.HerculesMainProtocol
import hercules.exceptions.HerculesExceptions
import scala.concurrent.{ duration, Future, ExecutionContext }
import java.io.PrintWriter

class SisyphusDemultiplexingExecutorActorTest extends TestKit(ActorSystem("SisyphusDemultiplexingExecutorActorTest"))
    with ImplicitSender
    with FlatSpecLike
    with BeforeAndAfterAll
    with Matchers {

  import duration._

  // The processing unit to send that we will return
  val runfolder = new File("runfolder1")
  val processingUnit: IlluminaProcessingUnit =
    new HiSeqProcessingUnit(
      new IlluminaProcessingUnitConfig(
        new File("Samplesheet1"),
        new File("DefaultQC"),
        Some(new File("DefaultProg"))),
      runfolder.toURI)

  val logFile = new File("fake.log")
  val writer = new PrintWriter(logFile)
  val logText = "To be or not to be, that is the question?"
  writer.println(logText)
  writer.close()

  // A fake fetcher class which will just return the processing untis
  // defined above.
  class FakeDemultiplexer(succeed: Boolean, exception: Option[Throwable] = None) extends Demultiplexer {
    var cleanUpRan: Boolean = false

    def cleanup(unit: hercules.entities.ProcessingUnit): Unit =
      cleanUpRan = true
    def demultiplex(unit: hercules.entities.ProcessingUnit)(implicit executor: ExecutionContext): Future[hercules.demultiplexing.DemultiplexingResult] =
      if (exception.isEmpty) Future.successful(new DemultiplexingResult(unit, succeed, Some(logText)))
      else Future.failed(exception.get)
  }

  val parent = TestProbe()
  val successDemuxActor = TestActorRef(
    SisyphusDemultiplexingExecutorActor.props(
      new FakeDemultiplexer(succeed = true)),
    parent.ref,
    "SisyphusDemultiplexingExecutorActor_Success")

  val failDemuxActor = TestActorRef(
    SisyphusDemultiplexingExecutorActor.props(
      new FakeDemultiplexer(succeed = false)),
    parent.ref,
    "SisyphusDemultiplexingExecutorActor_Failure")

  val exceptionText = "Test-case-generated exception"
  val exceptionDemuxActor = TestActorRef(
    SisyphusDemultiplexingExecutorActor.props(
      new FakeDemultiplexer(
        succeed = false,
        Some(HerculesExceptions.ExternalProgramException(exceptionText, processingUnit)))),
    parent.ref,
    "SisyphusDemultiplexingExecutorActor_Exception")

  override def afterAll(): Unit = {
    successDemuxActor.stop()
    failDemuxActor.stop()
    exceptionDemuxActor.stop()
    system.shutdown()
    logFile.delete()
    runfolder.delete()
    Thread.sleep(1000)
  }

  "A SisyphusDemultiplexingExecutorActor" should " respond with Idle status when idle" in {

    successDemuxActor ! HerculesMainProtocol.RequestExecutorAvailabilityMessage
    expectMsg(3 second, HerculesMainProtocol.Idle)
  }

  it should " forward a handled RequestDemultiplexingProcessingUnitMessage to the parent" in {

    successDemuxActor ! HerculesMainProtocol.RequestDemultiplexingProcessingUnitMessage
    parent.expectMsg(3 second, HerculesMainProtocol.RequestDemultiplexingProcessingUnitMessage)

  }

  it should " reject a StartDemultiplexingProcessingUnitMessage if the runfolder could not be found " in {

    runfolder.delete()
    successDemuxActor ! HerculesMainProtocol.StartDemultiplexingProcessingUnitMessage(processingUnit)
    expectMsg(3 second, HerculesMainProtocol.Reject(Some(s"The run folder path " + new File(processingUnit.uri) + " could not be found")))

  }

  it should " accept a StartDemultiplexingProcessingUnitMessage if idle and the runfolder can be found " in {

    runfolder.mkdir()
    successDemuxActor ! HerculesMainProtocol.StartDemultiplexingProcessingUnitMessage(processingUnit)
    expectMsg(3 second, HerculesMainProtocol.Acknowledge)

  }

  it should " forward success message to parent if the demultiplexing was successful" in {

    parent.expectMsg(3 second, HerculesMainProtocol.FinishedDemultiplexingProcessingUnitMessage(processingUnit))

  }

  it should "forward a fail message to parent if the demultiplexing failed " in {

    failDemuxActor ! HerculesMainProtocol.StartDemultiplexingProcessingUnitMessage(processingUnit)
    parent.expectMsg(3 second, HerculesMainProtocol.FailedDemultiplexingProcessingUnitMessage(processingUnit, logText))

  }

  it should "send a fail message if the demultiplexing generated an exception " in {

    exceptionDemuxActor ! HerculesMainProtocol.StartDemultiplexingProcessingUnitMessage(processingUnit)
    parent.expectMsg(3 second, HerculesMainProtocol.FailedDemultiplexingProcessingUnitMessage(processingUnit, exceptionText))

  }

  // @TODO How can we check the internal state of the actor, i.e. that the become/unbecome logic is working?

}