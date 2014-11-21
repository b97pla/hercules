package hercules.external.program

import akka.event.NoLogging

import com.typesafe.config.ConfigFactory

import hercules.entities.illumina.{ IlluminaProcessingUnitFetcher, IlluminaProcessingUnitFetcherTest }

import java.io.File
import java.util.Properties

import org.apache.commons.io.FileUtils

import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers

class SisyphusTest extends FlatSpec with Matchers with BeforeAndAfterAll {

  implicit val log = NoLogging

  // Create the necessary folder hierarchy, including one runfolder, before we start any test
  override def beforeAll() = {
    IlluminaProcessingUnitFetcherTest.listOfDirsThatNeedSetupAndTeardown.map(x => x.mkdirs())
    IlluminaProcessingUnitFetcherTest.createRunFolders(1)
  }
  // Remove all folders created for test purposes when we're done
  override def afterAll() = {
    IlluminaProcessingUnitFetcherTest.tearDownRunfolders()
    IlluminaProcessingUnitFetcherTest.listOfDirsThatNeedSetupAndTeardown.map(x => FileUtils.deleteDirectory(x))
  }

  // Set custom config options
  val cfg = new Properties()
  cfg.setProperty("general.sisyphusInstallLocation", "")
  cfg.setProperty("general.sisyphusLogLocation", "")

  // Create the test object  with a custom config
  val sisyphus = new Sisyphus(config = ConfigFactory.parseProperties(cfg))

  "A Sisyphus object" should "be able to cleanup" in {

    // Fetch a ProcessingUnit representing the test runfolder
    val unit = IlluminaProcessingUnitFetcher().searchForProcessingUnitName(
      IlluminaProcessingUnitFetcherTest.runfolders.head.getName,
      IlluminaProcessingUnitFetcherTest.fetcherConfig).get

    // Include files that should be caught by file globs, as well as the rest of the files
    val createdPaths = Seq(
      "rsync123.log",
      "rsyncABC.log",
      "rsync.log"
    ) ++ Sisyphus.runFolderPaths

    // Create files and folders in the runfolder
    implicit val runfolder = new File(unit.uri)
    assert(createdPaths.forall(s => {
      val f = new File(runfolder.getPath() + File.separator + s)
      if (s.contains('.')) f.createNewFile()
      else f.mkdir()
    }))

    // Perform cleanup
    sisyphus.cleanup(unit)

    // Verify that all created files and folders were removed
    assert(createdPaths.forall { !new File(_).exists() })
  }

}