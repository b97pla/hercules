package hercules.actors.notifiers

import akka.actor.Props
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.event.LoggingReceive
import com.typesafe.config.ConfigFactory
import scala.util.Random

import hercules.config.notification.TrelloNotificationConfig

object TrelloNotifierActor {
  
  def startInstance(
    system: ActorSystem): ActorRef = {
      // Append a random string to the actor name to ensure it is unique
      system.actorOf(
        props(), 
        "TrelloNotifierActor_" + List.fill(8)((Random.nextInt(25)+97).toChar).mkString
      )
  }

  /**
   * Create a new TrelloNotifierActor
   */
  def props(): Props = {
    Props(new TrelloNotifierActor())
  }  
}

class TrelloNotifierActor() extends NotifierActor {
  
  // Get a TrelloNotifierConfig object
  val trelloConfig = TrelloNotificationConfig.getConfig(
    ConfigFactory.load().getConfig("notifications.trello")
  )

  
    def receive = LoggingReceive {}
  
}