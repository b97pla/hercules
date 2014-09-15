package hercules.actors.processingunitwatcher

import java.io.File
import com.typesafe.config.ConfigFactory
import akka.actor.Props
import hercules.actors.HerculesActor
import hercules.entities.illumina.IlluminaProcessingUnit
import scala.concurrent.duration._
import hercules.protocols.HerculesMainProtocol
import hercules.config.processingunit.ProcessingUnitConfig
import hercules.config.processingunit.IlluminaProcessingUnitConfig
import hercules.config.processing.IlluminaProcessingUnitWatcherConfig

object IlluminaProcessingUnitWatcherExecutorActor {

  /**
   * Factory method for creating a IlluminaProcessingUnitExecutorActor
   * Loads it's configuration from the IlluminaProcessingUnitExecutorActor.conf
   * @param configFile the configFile to load
   * @returns a Props of IlluminaProcessingUnitExecutorActor
   */
  def props(): Props = {

    val generalConfig = ConfigFactory.load()
    val conf = generalConfig.getConfig("remote.actors").withFallback(generalConfig)
    
    val runfolderPath = conf.getString("general.runFolderPath")
    val samplesheetPath = conf.getString("general.samplesheetPath")

    val customQCConfigurationRoot = conf.getString("general.customQCConfigurationFilesRoot")
    val defaultQCConfigFile = conf.getString("general.defaultQCConfigFile")

    val customProgamConfigurationRoot = conf.getString("general.customProgramConfigFilesRoot")
    val defaultProgramConfigurationFile = conf.getString("general.defaultProgramConfigFile")
    val interval = conf.getInt("general.checkForRunfoldersInterval")

    val config = new IlluminaProcessingUnitWatcherConfig(runfolderPath,
      samplesheetPath,
      customQCConfigurationRoot,
      defaultQCConfigFile,
      customProgamConfigurationRoot,
      defaultProgramConfigurationFile,
      interval)

    Props(new IlluminaProcessingUnitWatcherExecutorActor(config))
  }

  object IlluminaProcessingUnitWatcherExecutorActorProtocol {
    case class ProcessingUnitSequenceMessage(seq: Seq[IlluminaProcessingUnit])
    case object CheckForRunfolders
  }
}

class IlluminaProcessingUnitWatcherExecutorActor(config: IlluminaProcessingUnitWatcherConfig)
    extends HerculesActor with ProcessingUnitWatcherActor {

  import IlluminaProcessingUnitWatcherExecutorActor.IlluminaProcessingUnitWatcherExecutorActorProtocol._

  import context.dispatcher

  val checkForRunfolder =
    context.system.scheduler.schedule(10.seconds, config.checkForRunfoldersInterval.seconds, self, {
      CheckForRunfolders
    })

  // Make sure that the scheduled event stops if the actors does.
  override def postStop() = {
    checkForRunfolder.cancel()
  }

  // Just pass the message on to the parent (the IlluminaProcessingUnitWatcherActor)
  def receive = {

    case CheckForRunfolders => {
      log.info("Looking for new runfolders!")

      def result =
        IlluminaProcessingUnit.checkForReadyProcessingUnits(
          new File(config.runfolderRootPath),
          new File(config.samplesheetPath),
          new File(config.qcControlConfigPath),
          new File(config.defaultQCConfigFile),
          new File(config.programConfigPath),
          new File(config.defaultProgramConfigFile),
          log)

      self ! ProcessingUnitSequenceMessage(result)
    }
    case ProcessingUnitSequenceMessage(seq) => {
      for (unit <- seq)
        context.parent ! HerculesMainProtocol.FoundProcessingUnitMessage(unit)
    }
  }

}