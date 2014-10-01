package hercules.config.notification

import com.typesafe.config.Config
import scala.collection.JavaConversions._
import hercules.protocols.NotificationChannelProtocol._

object TrelloNotificationConfig {
  
  def getConfig(conf: Config): TrelloNotificationConfig = {
    
    val apiKey = conf.getString("api_key")
    val apiToken = conf.getString("api_token")
    val board = conf.getString("board")
    val channels = asScalaBuffer(
      conf.getStringList("channels")
      ).toSeq.map(
        NotificationConfig.stringToChannel)
        
    new TrelloNotificationConfig(
        apiKey,
        apiToken,
        board,
        channels)
  }
}
case class TrelloNotificationConfig(
    val apiKey: String,
    val apiToken: String,
    val board: String,
    val channels: Seq[NotificationChannel]
    ) extends NotificationConfig {

}