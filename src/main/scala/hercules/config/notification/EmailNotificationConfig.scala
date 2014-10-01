package hercules.config.notification

import com.typesafe.config.Config
import scala.collection.JavaConversions._
import hercules.protocols.NotificationChannelProtocol._

object EmailNotificationConfig {

  /** Create and return a EmailNotificationConfig from the supplied 
   *  configuration.
   */
  def getEmailNotificationConfig(conf: Config): EmailNotificationConfig = {
    val emailRecipients = asScalaBuffer(conf.getStringList("recipients")).toSeq
    val emailSender = conf.getString("sender")
    val emailSMTPHost = conf.getString("smtp_host")
    val emailSMTPPort = conf.getInt("smtp_port")
    val emailPrefix = conf.getString("prefix")
    val emailChannels = asScalaBuffer(
      conf.getStringList("channels")
      ).toSeq.map(
        NotificationConfig.stringToChannel)
    val emailNumRetries = conf.getInt("num_retries")
    val emailRetryInterval = conf.getInt("retry_interval")
    new EmailNotificationConfig(
      emailRecipients,
      emailSender,
      emailSMTPHost,
      emailSMTPPort,
      emailPrefix,
      emailNumRetries,
      emailRetryInterval,
      emailChannels
    )
  }
  
}

/**
 * Base class for configuring an email notification
 */
case class EmailNotificationConfig(
  val recipients: Seq[String],
  val sender: String,
  val smtpHost: String,
  val smtpPort: Int,
  val prefix: String,
  val numRetries: Int,
  val retryInterval: Int,
  val channels: Seq[NotificationChannel]) extends NotificationConfig {
}
