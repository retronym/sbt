package sbt.internal.benchmarks

import java.io.File
import java.net.{ URI, URLClassLoader }
import java.nio.file.{ Files, Path }
import java.util.concurrent.{ Callable, TimeUnit }

import org.openjdk.jmh.annotations
import org.openjdk.jmh.annotations.{ Fork => _, Scope => _, State => _, _ }
import sbt.BuildPaths.{ globalBaseDirectory, projectStandard }
import sbt.Def.ScopeLocal
import sbt.Keys.scalaVersion
import sbt.internal.BuildStreams.{ Streams, mkStreams }
import sbt.internal.Load._
import sbt.internal._
import sbt.internal.util.Settings
import sbt.io.IO
import sbt.util.Show
import sbt.{ ApplicationID => _, _ }
import sbt.Def.{ ScopeLocal, ScopedKey, Setting }
import xsbti._

import scala.collection.mutable

// TODO duplication with SettingQueryTest

@org.openjdk.jmh.annotations.State(org.openjdk.jmh.annotations.Scope.Benchmark)
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 6, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 6, time = 10, timeUnit = TimeUnit.SECONDS)
@org.openjdk.jmh.annotations.Fork(value = 3)
class SbtBenchmark {

  private var work: () => Unit = () => ???

  @Setup
  def setup(): Unit = {

    val baseDir: Path = Files createTempDirectory "sbt-setting-query-test"
    val globalDir: Path = Files createTempDirectory "sbt-setting-query-test-global-dir"
    val bootDir: Path = Files createTempDirectory "sbt-setting-query-test-boot-dir"
    val ivyHome1: Path = Files createTempDirectory "sbt-setting-query-test-ivy-home"
    val logFile: File = File.createTempFile("sbt", ".log")

    val baseFile: File = baseDir.toFile
    val baseUri: URI = IO directoryURI baseFile
    IO assertAbsolute baseUri

    val globalDirFile: File = globalDir.toFile

    def ??? : Nothing = {
      Thread.dumpStack();
      throw new NotImplementedError
    }

    val noopLoader: ClassLoader = new URLClassLoader(Array(), null)

    object NoGlobalLock extends GlobalLock {
      def apply[T](lockFile: File, run: Callable[T]) = run.call()
    }
    val projectSettings: Seq[Setting[_]] = Seq(scalaVersion := "2.12.1")

    val appConfig: AppConfiguration = new AppConfiguration {
      def baseDirectory(): File = baseFile
      def arguments(): Array[String] = Array()
      def provider(): AppProvider = new AppProvider {
        def scalaProvider(): ScalaProvider = new ScalaProvider {
          scalaProvider =>
          def launcher(): Launcher = new Launcher {
            def getScala(version: String): ScalaProvider = getScala(version, "")
            def getScala(version: String, reason: String): ScalaProvider =
              getScala(version, reason, "org.scala-lang")
            def getScala(version: String, reason: String, scalaOrg: String): ScalaProvider =
              scalaProvider

            def app(id: ApplicationID, version: String): AppProvider = ???

            def topLoader(): ClassLoader = noopLoader
            def globalLock(): GlobalLock = NoGlobalLock
            def bootDirectory(): File = bootDir.toFile
            def ivyRepositories(): Array[Repository] = Array()
            def appRepositories(): Array[Repository] = Array()
            def isOverrideRepositories: Boolean = false
            def ivyHome(): File = ivyHome1.toFile
            def checksums(): Array[String] = Array()
          }
          def version(): String = "2.12.1"

          def loader(): ClassLoader = noopLoader
          def jars(): Array[File] = Array(libraryJar, compilerJar)

          def libraryJar(): File = new File("scala-library.jar")
          def compilerJar(): File = new File("scala-compiler.jar")

          def app(id: ApplicationID): AppProvider = ???
        }

        def id(): ApplicationID = sbt.ApplicationID(
          "org.scala-sbt",
          "sbt",
          "0.13.13",
          "sbt.xMain",
          components = Seq(),
          crossVersionedValue = CrossValue.Disabled,
          extra = Seq()
        )

        def loader(): ClassLoader = noopLoader

        def entryPoint(): Class[_] = ???
        def mainClass(): Class[_ <: AppMain] = ???
        def newMain(): AppMain = ???

        def mainClasspath(): Array[File] = Array()

        def components(): ComponentProvider = new ComponentProvider {
          def componentLocation(id: String): File = ???
          def component(componentID: String): Array[File] = ???
          def defineComponent(componentID: String, components: Array[File]): Unit = ???
          def addToComponent(componentID: String, components: Array[File]): Boolean = ???
          def lockFile(): File = ???
        }
      }
    }
    def work(): Unit = {
      val state: State =
        StandardMain
          .initialState(appConfig, initialDefinitions = Seq(), preCommands = Seq())
          .put(globalBaseDirectory, globalDirFile)

      val config0 = defaultPreGlobal(state, baseFile, globalDirFile, state.log)
      val config = defaultWithGlobal(state, baseFile, config0, globalDirFile, state.log)

      val buildUnit: BuildUnit = {
        val loadedPlugins: LoadedPlugins =
          noPlugins(projectStandard(baseFile),
                    config.copy(pluginManagement = config.pluginManagement.forPlugin))

        val project: Project = {
          val project0 = Project("t", baseFile) settings projectSettings
          val fileToLoadedSbtFileMap = new mutable.HashMap[File, LoadedSbtFile]
          val autoPlugins = loadedPlugins.detected.deducePluginsFromProject(project0, state.log)
          val injectSettings = config.injectSettings
          resolveProject(project0,
                         autoPlugins,
                         loadedPlugins,
                         injectSettings,
                         fileToLoadedSbtFileMap,
                         state.log)
        }

        val projects: Seq[Project] = Seq(project)
        val builds: Seq[BuildDef] = BuildDef.defaultAggregated(project.id, Nil) :: Nil
        val defs: LoadedDefinitions =
          new LoadedDefinitions(baseFile, Nil, noopLoader, builds, projects, Nil)
        new BuildUnit(baseUri, baseFile, defs, loadedPlugins)
      }

      val (partBuildUnit: PartBuildUnit, projectRefs: List[ProjectReference]) = loaded(buildUnit)
      val partBuildUnits: Map[URI, PartBuildUnit] = Map(buildUnit.uri -> partBuildUnit)
      val allProjectRefs: Map[URI, List[ProjectReference]] = Map(buildUnit.uri -> projectRefs)
      checkAll(allProjectRefs, partBuildUnits)

      val partBuild: PartBuild = new PartBuild(baseUri, partBuildUnits)
      val loadedBuild: LoadedBuild = resolveProjects(partBuild)

      val units: Map[URI, LoadedBuildUnit] = loadedBuild.units

      val settings: Seq[Setting[_]] = finalTransforms(
        buildConfigurations(loadedBuild, getRootProject(units), config.injectSettings))
      val delegates: Scope => Seq[Scope] = defaultDelegates(loadedBuild)
      val scopeLocal: ScopeLocal = EvaluateTask.injectStreams
      val display: Show[ScopedKey[_]] = Project showLoadingKey loadedBuild

      val data: Settings[Scope] = Def.make(settings)(delegates, scopeLocal, display)
      val extra: KeyIndex => BuildUtil[_] = index => BuildUtil(baseUri, units, index, data)

      val index: StructureIndex = structureIndex(data, settings, extra, units)
      val streams: State => Streams = mkStreams(units, baseUri, data)

      val structure: BuildStructure =
        new BuildStructure(units, baseUri, settings, data, index, streams, delegates, scopeLocal)

      structure
    }
    this.work = () => work()
  }

  @Benchmark def run(): Unit = {
    this.work.apply()
  }
}
