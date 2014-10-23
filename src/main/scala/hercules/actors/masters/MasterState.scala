package hercules.actors.masters

import hercules.protocols.HerculesMainProtocol.ProcessingUnitMessage
import hercules.entities.ProcessingUnit
import hercules.actors.masters.MasterStateProtocol._

case class MasterState(
    val messagesNotYetProcessed: Set[ProcessingUnitMessage] = Set(),
    val messagesInProcessing: Set[ProcessingUnitMessage] = Set(),
    val failedMessages: Set[ProcessingUnitMessage] = Set()) {

  /**
   * Manipulate the state of the message sets depending on what message has been
   * sent
   * @param x the SetStateMessage describing the message to add or remove
   * @return the MasterState after the manipulation
   */
  def manipulateState(x: SetStateMessage): MasterState = {
    def add[A](l: Set[A], e: A): Set[A] = l + e
    def sub[A](l: Set[A], e: A): Set[A] = l - e

    x match {
      case AddToMessageNotYetProcessed(message) =>
        this.copy(messagesNotYetProcessed = manipulateStateList(messagesNotYetProcessed, message, add))

      case RemoveFromMessageNotYetProcessed(message) =>
        this.copy(messagesNotYetProcessed = manipulateStateList(messagesNotYetProcessed, message, sub))

      case AddToMessagesInProcessing(message) =>
        this.copy(messagesInProcessing = manipulateStateList(messagesInProcessing, message, add))

      case RemoveFromMessagesInProcessing(message) =>
        this.copy(messagesInProcessing = manipulateStateList(messagesInProcessing, message, sub))

      case AddToFailedMessages(message) =>
        this.copy(failedMessages = manipulateStateList(failedMessages, message, add))

      case RemoveFromFailedMessages(message) =>
        this.copy(failedMessages = manipulateStateList(failedMessages, message, sub))
    }
  }

  private def manipulateStateList[A](lst: Set[A], elem: Option[A], op: (Set[A], A) => Set[A]): Set[A] =
    if (elem.nonEmpty) op(lst, elem.get)
    else lst

  /**
   * Gets the master state as is, or filtered for the unitName option.
   * @param unitName set to None to get state for all units
   * @return a MasteState filtered for unitName (if present)
   */
  def findStateOfUnit(unitName: Option[String]): MasterState = {
    if (unitName.isDefined)
      MasterState(
        messagesNotYetProcessed =
          messagesNotYetProcessed.filter(p => p.unit.name == unitName.get),
        messagesInProcessing =
          messagesInProcessing.filter(p => p.unit.name == unitName.get),
        failedMessages =
          failedMessages.filter(p => p.unit.name == unitName.get))
    else
      this
  }

  //@TODO It whould be awesome to attach a to Json method here to make it
  // easy to drop this to json from the REST API later. /JD 20140929
  def toJson = ???

}