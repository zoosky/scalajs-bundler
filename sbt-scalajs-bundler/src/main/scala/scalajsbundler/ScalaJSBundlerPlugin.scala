package scalajsbundler

import org.scalajs.core.tools.io.{FileVirtualJSFile, VirtualJSFile}
import org.scalajs.core.tools.jsdep.ResolvedJSDependency
import org.scalajs.core.tools.linker.backend.ModuleKind
import org.scalajs.jsenv.ComJSEnv
import org.scalajs.sbtplugin.Loggers.sbtLogger2ToolsLogger
import org.scalajs.sbtplugin.{FrameworkDetectorWrapper, ScalaJSPlugin}
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import org.scalajs.sbtplugin.ScalaJSPluginInternal.{scalaJSEnsureUnforked, scalaJSModuleIdentifier, scalaJSRequestsDOM}
import org.scalajs.testadapter.ScalaJSFramework
import sbt.Keys._
import sbt._

object ScalaJSBundlerPlugin extends AutoPlugin {

  override lazy val requires = ScalaJSPlugin

  // Exported keys
  object autoImport {

    val npmUpdate: TaskKey[Unit] =
      taskKey[Unit]("Fetch NPM dependencies")

    val npmDependencies: SettingKey[Seq[(String, String)]] =
      settingKey[Seq[(String, String)]]("NPM dependencies (libraries that your program uses)")

    val npmDevDependencies: SettingKey[Seq[(String, String)]] =
      settingKey[Seq[(String, String)]]("NPM dev dependencies (libraries that the build uses)")

    val webpack: TaskKey[Seq[File]] =
      taskKey[Seq[File]]("Bundle the output of a Scala.js stage using webpack")

    val webpackConfigFile: SettingKey[Option[File]] =
      settingKey[Option[File]]("Configuration file to use with webpack")

    val webpackEntries: TaskKey[Seq[(String, File)]] =
      taskKey[Seq[(String, File)]]("Webpack entry bundles")

    val webpackEmitSourceMaps: SettingKey[Boolean] =
      settingKey[Boolean]("Whether webpack should emit source maps at all")

    val enableReloadWorkflow: SettingKey[Boolean] =
      settingKey[Boolean]("Whether to enable the reload workflow for fastOptJS")

  }

  private val scalaJSBundlerPackageJson =
    TaskKey[File]("scalaJSBundlerPackageJson", "Write a package.json file defining the NPM dependencies of project", KeyRanks.Invisible)

  private val scalaJSBundlerWebpackConfig =
    TaskKey[File]("scalaJSBundlerWebpackConfig", "Write the webpack configuration file", KeyRanks.Invisible)

  private val scalaJSBundlerLauncher =
    TaskKey[Launcher]("scalaJSBundlerLauncher", "Launcher generated by scalajs-bundler", KeyRanks.Invisible)

  private[scalajsbundler] val ensureModuleKindIsCommonJSModule =
    SettingKey[Boolean](
      "ensureModuleKindIsCommonJSModule",
      "Checks that scalaJSModuleKind is set to CommonJSModule",
      KeyRanks.Invisible
    )

  import autoImport._

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(

    scalaJSModuleKind := ModuleKind.CommonJSModule,

    version in webpack := "1.14",

    webpackConfigFile := None,

    // Include the manifest in the produced artifact
    (products in Compile) := (products in Compile).dependsOn(scalaJSBundlerManifest).value,

    enableReloadWorkflow := true,

    ensureModuleKindIsCommonJSModule := {
      if (scalaJSModuleKind.value == ModuleKind.CommonJSModule) true
      else sys.error(s"scalaJSModuleKind must be set to ModuleKind.CommonJSModule in projects where ScalaJSBundler plugin is enabled")
    }

  ) ++
    inConfig(Compile)(perConfigSettings) ++
    inConfig(Test)(perConfigSettings ++ testSettings)

  private lazy val perConfigSettings: Seq[Def.Setting[_]] =
    Seq(
      npmDependencies := Seq.empty,

      npmDevDependencies := Seq.empty,

      webpack in fullOptJS := webpackTask(fullOptJS).value,

      webpack in fastOptJS := Def.taskDyn {
        if (enableReloadWorkflow.value) ReloadWorkflowTasks.webpackTask(configuration.value, fastOptJS)
        else webpackTask(fastOptJS)
      }.value,

      // Override Scala.js’ loadedJSEnv to first run `npm update`
      loadedJSEnv := loadedJSEnv.dependsOn(npmUpdate in fastOptJS).value
    ) ++
    perScalaJSStageSettings(fastOptJS) ++
    perScalaJSStageSettings(fullOptJS)

  private lazy val testSettings: Seq[Setting[_]] =
    Seq(
      npmDependencies ++= (npmDependencies in Compile).value,

      npmDevDependencies ++= (npmDevDependencies in Compile).value,

      // Override Scala.js setting, which does not support the combination of jsdom and CommonJS module output kind
      loadedTestFrameworks := Def.task {
        // use assert to prevent warning about pure expr in stat pos
        assert(scalaJSEnsureUnforked.value)

        val console = scalaJSConsole.value
        val toolsLogger = sbtLogger2ToolsLogger(streams.value.log)
        val frameworks = testFrameworks.value
        val sjsOutput = fastOptJS.value.data

        val env =
          jsEnv.?.value.map {
            case comJSEnv: ComJSEnv => comJSEnv.loadLibs(Seq(ResolvedJSDependency.minimal(FileVirtualJSFile(sjsOutput))))
            case other => sys.error(s"You need a ComJSEnv to test (found ${other.name})")
          }.getOrElse {
            Def.taskDyn[ComJSEnv] {
              assert(ensureModuleKindIsCommonJSModule.value)
              val sjsOutput = fastOptJS.value.data
              // If jsdom is going to be used, then we should bundle the test module into a file that exports the tests to the global namespace
              if ((scalaJSRequestsDOM in fastOptJS).value) Def.task {
                val logger = streams.value.log
                val targetDir = (crossTarget in fastOptJS).value
                val sjsOutputName = sjsOutput.name.stripSuffix(".js")
                val bundle = targetDir / s"$sjsOutputName-bundle.js"

                val writeTestBundleFunction =
                  FileFunction.cached(
                    streams.value.cacheDirectory / "test-loader",
                    inStyle = FilesInfo.hash
                  ) { _ =>
                    logger.info("Writing and bundling the test loader")
                    val loader = targetDir / s"$sjsOutputName-loader.js"
                    JsDomTestEntries.writeLoader(sjsOutput, loader)
                    Webpack.run(loader.absolutePath, bundle.absolutePath)(targetDir, logger)
                    Set.empty
                  }
                writeTestBundleFunction(Set(sjsOutput))
                val file = FileVirtualJSFile(bundle)

                val jsdomDir = installJsdom.value
                new JSDOMNodeJSEnv(jsdomDir).loadLibs(Seq(ResolvedJSDependency.minimal(file)))
              } else Def.task {
                NodeJSEnv().value.loadLibs(Seq(ResolvedJSDependency.minimal(FileVirtualJSFile(sjsOutput))))
              }
            }.value
          }

        // Pretend that we are not using a CommonJS module if jsdom is involved, otherwise that
        // would be incompatible with the way jsdom loads scripts
        val (moduleKind, moduleIdentifier) =
          if ((scalaJSRequestsDOM in fastOptJS).value) (ModuleKind.NoModule, None)
          else (scalaJSModuleKind.value, scalaJSModuleIdentifier.value)

        val detector =
          new FrameworkDetectorWrapper(env, moduleKind, moduleIdentifier).wrapped

        detector.detect(frameworks, toolsLogger).map { case (tf, frameworkName) =>
          val framework =
            new ScalaJSFramework(frameworkName, env, moduleKind, moduleIdentifier, toolsLogger, console)
          (tf, framework)
        }
      }.dependsOn(npmUpdate in fastOptJS).value
    )

  private def perScalaJSStageSettings(stage: TaskKey[Attributed[File]]): Seq[Def.Setting[_]] = Seq(

    npmUpdate in stage := Def.task {
      val log = streams.value.log
      val targetDir = (crossTarget in stage).value
      val jsResources = scalaJSNativeLibraries.value.data
      val packageJsonFile = (scalaJSBundlerPackageJson in stage).value

      val cachedActionFunction =
        FileFunction.cached(
          streams.value.cacheDirectory / "npm-update",
          inStyle = FilesInfo.hash
        ) { _ =>
          log.info("Updating NPM dependencies")
          Npm.run("update")(targetDir, log)
          jsResources.foreach { resource =>
            IO.write(targetDir / resource.relativePath, resource.content)
          }
          Set.empty
        }

      cachedActionFunction(Set(packageJsonFile) ++ jsResources.collect { case f: FileVirtualJSFile => f.file }.to[Set])
      ()
    }.value,

    scalaJSBundlerPackageJson in stage := packageJsonTask(stage).value,

    webpackEntries in stage := {
      val launcherFile = (scalaJSBundlerLauncher in stage).value.file
      val stageFile = stage.value.data
      val name = stageFile.name.stripSuffix(".js")
      Seq(name -> launcherFile)
    },

    // Override Scala.js’ scalaJSLauncher to add support for CommonJSModule
    scalaJSLauncher in stage := {
      val launcher = (scalaJSBundlerLauncher in stage).value
      Attributed[VirtualJSFile](FileVirtualJSFile(launcher.file))(
        AttributeMap.empty.put(name.key, launcher.mainClass)
      )
    },

    scalaJSBundlerLauncher in stage :=
      Launcher.write(
        (crossTarget in stage).value,
        stage.value.data,
        (mainClass in (scalaJSLauncher in stage)).value.getOrElse(sys.error("No main class detected"))
      ),

    scalaJSBundlerWebpackConfig in stage :=
      Webpack.writeConfigFile(
        (webpackEmitSourceMaps in stage).value,
        (webpackEntries in stage).value,
        (crossTarget in stage).value,
        streams.value.log
      ),

    webpackEmitSourceMaps in stage := (emitSourceMaps in stage).value,

    // Override Scala.js’ relativeSourceMaps in case we have to emit source maps in the webpack task, because it does not work with absolute source maps
    relativeSourceMaps in stage := (webpackEmitSourceMaps in stage).value

  )

  def packageJsonTask(stage: TaskKey[Attributed[File]]): Def.Initialize[Task[File]] =
    Def.task {
      val log = streams.value.log
      val targetDir = (crossTarget in stage).value
      val webpackVersion = (version in webpack).value
      val currentConfiguration = configuration.value

      val packageJsonFile = targetDir / "package.json"

      Caching.cached(
        packageJsonFile,
        currentConfiguration.name,
        streams.value.cacheDirectory / "scalajsbundler-package-json"
      ) { () =>
        PackageJson.write(
          log,
          packageJsonFile,
          npmDependencies.value,
          npmDevDependencies.value,
          fullClasspath.value,
          configuration.value,
          webpackVersion
        )
        ()
      }

      packageJsonFile
    }

  def webpackTask(stage: TaskKey[Attributed[File]]): Def.Initialize[Task[Seq[File]]] =
    Def.task {
      assert(ensureModuleKindIsCommonJSModule.value)
      val log = streams.value.log
      val targetDir = (crossTarget in stage).value
      val generatedWebpackConfigFile = (scalaJSBundlerWebpackConfig in stage).value
      val customWebpackConfigFile = (webpackConfigFile in stage).value
      val packageJsonFile = (scalaJSBundlerPackageJson in stage).value
      val entries = (webpackEntries in stage).value

      val cachedActionFunction =
        FileFunction.cached(
          streams.value.cacheDirectory / s"${stage.key.label}-webpack",
          inStyle = FilesInfo.hash
        ) { _ =>
          Webpack.bundle(
            generatedWebpackConfigFile,
            customWebpackConfigFile,
            entries,
            targetDir,
            log
          ).to[Set]
        }
      cachedActionFunction(Set(
        generatedWebpackConfigFile,
        packageJsonFile
      ) ++
        (webpackConfigFile in stage).value.map(Set(_)).getOrElse(Set.empty) ++
        entries.map(_._2).to[Set] + stage.value.data).to[Seq] // Note: the entries should be enough, excepted that they currently are launchers, which do not change even if the scalajs stage output changes
    }.dependsOn(npmUpdate in stage)

  /** @return Installation directory */
  lazy val installJsdom: Def.Initialize[Task[File]] =
    Def.task {
      val installDir = target.value / "scalajs-bundler-jsdom"
      val log = streams.value.log
      if (!installDir.exists()) {
        log.info(s"Installing jsdom in ${installDir.absolutePath}")
        IO.createDirectory(installDir)
        Npm.run("install", "jsdom")(installDir, log)
      }
      installDir
    }

  lazy val scalaJSBundlerManifest: Def.Initialize[Task[File]] =
    Def.task {
      NpmDependencies.writeManifest(
        NpmDependencies(
          (npmDependencies in Compile).value.to[List],
          (npmDependencies in Test).value.to[List],
          (npmDevDependencies in Compile).value.to[List],
          (npmDevDependencies in Test).value.to[List]
        ),
        (classDirectory in Compile).value
      )
    }

}
