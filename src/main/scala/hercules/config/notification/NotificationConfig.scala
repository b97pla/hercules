package hercules.config.notification

import hercules.protocols.NotificationChannelProtocol._

object NotificationConfig {
  
  def stringToChannel(str:String): NotificationChannel = str match {
    case "progress" => Progress
    case "info" => Info
    case "warning" => Warning
    case "critical" => Critical
  }
  
}
/**
 * Base class for configuring a notification
 */
abstract class NotificationConfig {
  val channels: Seq[NotificationChannel]
}