package hercules.api.services

import akka.actor.{ ActorRef, ActorSystem }
import akka.contrib.pattern.ClusterClient.SendToAll
import akka.testkit.{ TestActor, TestProbe }
import akka.util.Timeout

import hercules.actors.masters.{ MasterState, MasterStateProtocol }
import hercules.entities.ProcessingUnit
import hercules.protocols.HerculesMainProtocol._

import java.io.File
import java.net.URI

import scala.concurrent.ExecutionContext

object MockBackend {
  def apply(
    system: ActorSystem,
    messagesNotYetProcessed: Set[String] = Set(),
    messagesInProcessing: Set[String] = Set(),
    failedMessages: Set[String] = Set()): TestProbe =
    new MockBackend(
      system,
      MasterState(
        messagesNotYetProcessed.map { (id: String) => FoundProcessingUnitMessage(ProcessingUnitPlaceholder(id)) },
        messagesInProcessing.map { (id: String) => StartDemultiplexingProcessingUnitMessage(ProcessingUnitPlaceholder(id)) },
        failedMessages.map { (id: String) => FailedDemultiplexingProcessingUnitMessage(ProcessingUnitPlaceholder(id), "Testing failure") }
      ))

  case class ProcessingUnitPlaceholder(val name: String) extends ProcessingUnit {
    val uri = new File(name).toURI
    val isFound: Boolean = true
  }
}

class MockBackend(
    _application: ActorSystem,
    val state: MasterState) extends TestProbe(_application: ActorSystem) {

  import MasterStateProtocol._

  // Install an AutoPilot to generate responses on request from the ApiServices
  setAutoPilot(
    new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
        case SendToAll(_, message) => {
          val response = {
            message match {
              case RestartDemultiplexingProcessingUnitMessage(id) =>
                if (state.findStateOfUnit(Some(id)).failedMessages.isEmpty) Reject(Some(s"$id not in failedMessages set"))
                else Acknowledge
              case RequestMasterState(id) =>
                state.findStateOfUnit(id)
              case m: RemoveFromFailedMessages =>
                state.manipulateState(m)
              case ForgetDemultiplexingProcessingUnitMessage(id) =>
                if (state.findStateOfUnit(Some(id)).messagesInProcessing.isEmpty) Acknowledge
                else Reject(Some(s"Processing Unit $id is being processed"))
              case _ =>
                Acknowledge
            }
          }
          sender ! response
          TestActor.KeepRunning
        }
        case _ =>
          TestActor.NoAutoPilot

      }
    }
  )

}
