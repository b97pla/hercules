package hercules.actors

import akka.actor.Actor
import akka.actor.ActorLogging

/** 
 *  The base trait for all Hercules actors. All actors (which are not 
 *  spinned up in a very local context, e.g. annonymous actors) should extend
 *  this class. 
 */
trait HerculesActor extends Actor with ActorLogging { 
}