import sbtide.Keys.ideSkipProject

// `core.jvm` / `.js` / `.native` (Project) are used where a ProjectReference is expected (e.g. the
// jvmProjects/jsProjects/nativeProjects lists); sbt 2.x requires opting into that implicit conversion.
import scala.language.implicitConversions

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / organization := "org.wvlet.uni"

// Use dynamic snapshot version strings for non tagged versions
ThisBuild / dynverSonatypeSnapshots := true
// Use coursier friendly version separator
ThisBuild / dynverSeparator := "-"

// For Sonatype
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value)
    Some("central-snapshots" at centralSnapshots)
  else
    localStaging.value
}

// Publishing command aliases
addCommandAlias("publishSnapshots", s"projectJVM/publish; projectJS/publish; projectNative/publish")
addCommandAlias("publishJSSigned", s"projectJS/publishSigned")
addCommandAlias("publishNativeSigned", s"projectNative/publishSigned")

val SCALA_3                             = "3.8.4"
val JS_JAVA_LOGGING_VERSION             = "1.0.0"
val JUNIT_PLATFORM_VERSION              = "6.1.1"
val SCALA_NATIVE_TEST_INTERFACE_VERSION = "0.5.12"
val SBT_TEST_INTERFACE_VERSION          = "1.0"

// Common build settings
val buildSettings = Seq[Setting[?]](
  description        := "Scala 3 unified utility library",
  scalaVersion       := SCALA_3,
  crossScalaVersions := List(SCALA_3),
  crossPaths         := true,
  publishMavenStyle  := true,
  licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(url("https://github.com/wvlet/uni")),
  scmInfo  :=
    Some(
      ScmInfo(
        browseUrl = url("https://github.com/wvlet/uni"),
        connection = "scm:git:git@github.com:wvlet/uni.git"
      )
    ),
  developers :=
    List(
      Developer(
        id = "leo",
        name = "Taro L. Saito",
        email = "leo@xerial.org",
        url = url("http://xerial.org/leo")
      )
    ),
  Test / parallelExecution := false,
  // Use UniTest for testing
  libraryDependencies ++=
    Seq(
      // For PreDestroy, PostConstruct annotations
      "javax.annotation" % "javax.annotation-api" % "1.3.2" % Test
    ),
  testFrameworks += new TestFramework("wvlet.uni.test.Framework")
)

val jsBuildSettings = Seq[Setting[?]](
  // Use Node.js environment for tests (required for FileSystem tests).
  // sbt 2.x caches setting values and a JSEnv is not serializable, so wrap it in Def.uncached.
  Test / jsEnv := Def.uncached(new org.scalajs.jsenv.nodejs.NodeJSEnv()),
  // Enable ES modules for Node.js module imports
  scalaJSLinkerConfig ~= {
    _.withModuleKind(ModuleKind.ESModule)
  },
  libraryDependencies ++=
    Seq(
      // For java.security.SecureRandom in Scala.js (used by ULID, reached through the Weaver
      // derivation chain). Must be a compile dependency so downstream JS apps (e.g. Electron)
      // can link uni's main code, not just uni's own tests.
      ("org.scala-js" %% "scalajs-java-securerandom" % "1.0.0").cross(CrossVersion.for3Use2_13),
      // For using java.time.Instant in Scala.js
      ("org.scala-js" %% "scalajs-java-time" % "1.0.0").cross(CrossVersion.for3Use2_13),
      // For scheduling with timer
      "org.scala-js" %% "scala-js-macrotask-executor" % "1.1.1",
      // For Fetch API and DOM access
      "org.scala-js" %% "scalajs-dom" % "2.8.1"
    )
)

val nativeBuildSettings = Seq[Setting[?]](
  // Scala Native specific settings
  libraryDependencies ++=
    Seq(
      // For using java.time libraries
      "org.ekrich" %% "sjavatime" % "1.5.0"
    ),
  // Link against libcurl for HTTP client support, and zlib for gzip compression
  nativeConfig ~= {
    _.withLinkingOptions(_ ++ Seq("-lcurl", "-lz"))
  }
)

val noPublish = Seq(
  publishArtifact := false,
  publish         := {},
  publishLocal    := {},
  publish / skip  := true,
  // This must be Nil to use crossScalaVersions of individual modules in `+ projectJVM/xxxx` tasks
  crossScalaVersions := Nil,
  // Explicitly skip the doc task because protobuf related Java files causes no type found error
  Compile / doc / sources                := Seq.empty,
  Compile / packageDoc / publishArtifact := false,
  // Do not check binary compatibility for unpublished projects
  // mimaPreviousArtifacts := Set.empty
  // Skip importing aggregated projects in IntelliJ IDEA
  ideSkipProject := true
)

// Remove warning as ideSkipProject is used only for IntelliJ IDEA
Global / excludeLintKeys ++= Set(ideSkipProject)

// Root project aggregating others
lazy val root = project
  .in(file("."))
  // sbt 2.x derives each project's output dir from its name, so the root must not reuse the
  // `uni` library project's name (they would collide on target/out/jvm/.../uni).
  .settings(buildSettings, name := "uni-root", publish / skip := true)
  .aggregate((jvmProjects ++ jsProjects ++ nativeProjects) *)

lazy val jvmProjects: Seq[ProjectReference] = Seq(core.jvm, uni.jvm, netty, bookExamples, test.jvm)

lazy val jsProjects: Seq[ProjectReference]     = Seq(core.js, uni.js, domTest, test.js)
lazy val nativeProjects: Seq[ProjectReference] = Seq(core.native, uni.native, test.native)

lazy val projectJVM = project
  .settings(noPublish)
  .settings(
    // Use a stable coverage directory name without containing scala version
    // coverageDataDir := target.value
  )
  .aggregate(jvmProjects *)

lazy val projectJS = project.settings(noPublish).aggregate(jsProjects *)

lazy val projectNative = project.settings(noPublish).aggregate(nativeProjects *)

// Core library with logging and reactive streams
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("uni-core"))
  .settings(
    buildSettings,
    name        := "uni-core",
    description := "Core utilities: logging and reactive streams"
  )
  .jsSettings(
    jsBuildSettings,
    libraryDependencies ++=
      Seq(
        ("org.scala-js" %% "scalajs-java-logging" % JS_JAVA_LOGGING_VERSION).cross(
          CrossVersion.for3Use2_13
        )
      )
  )
  .nativeSettings(nativeBuildSettings)

// The 'uni' library for Scala JVM, Scala.js and Scala Native
lazy val uni = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("uni"))
  .settings(buildSettings, name := "uni", description := "Scala unified core library")
  .jsSettings(jsBuildSettings)
  .nativeSettings(nativeBuildSettings)
  .dependsOn(core, test % Test)

// uni-test - Lightweight testing framework with AirSpec syntax
lazy val test = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("uni-test"))
  .settings(
    buildSettings,
    name           := "uni-test",
    description    := "Lightweight testing framework with AirSpec syntax",
    testFrameworks := Seq(new TestFramework("wvlet.uni.test.Framework"))
  )
  .jvmSettings(
    libraryDependencies ++=
      Seq(
        // JVM uses sbt test-interface
        "org.scala-sbt" % "test-interface" % SBT_TEST_INTERFACE_VERSION,
        // JUnit Platform for IDE integration (IntelliJ, VS Code)
        // junit-platform-commons contains @Testable annotation for IDE source-level discovery
        "org.junit.platform" % "junit-platform-commons"  % JUNIT_PLATFORM_VERSION,
        "org.junit.platform" % "junit-platform-engine"   % JUNIT_PLATFORM_VERSION % Provided,
        "org.junit.platform" % "junit-platform-launcher" % JUNIT_PLATFORM_VERSION % Provided
      )
  )
  .jsSettings(
    jsBuildSettings,
    libraryDependencies ++=
      Seq(
        // Scala.js uses scalajs-test-interface for proper test discovery. This is the JVM-side
        // test interface, published only as scalajs-test-interface_2.13. In a Scala.js project on
        // sbt 2.x any cross-version (`%%` or `.cross(for3Use2_13)`) injects the _sjs1 platform
        // suffix, so name the fixed _2.13 artifact directly with a single `%`.
        "org.scala-js" % "scalajs-test-interface_2.13" % scalaJSVersion
      )
  )
  .nativeSettings(
    nativeBuildSettings,
    libraryDependencies ++=
      Seq(
        // Scala Native uses native test-interface
        "org.scala-native" %% "test-interface" % SCALA_NATIVE_TEST_INTERFACE_VERSION
      )
  )
  .dependsOn(core)

val NETTY_VERSION = "4.2.15.Final"

lazy val netty = project
  .in(file("uni-netty"))
  .settings(
    buildSettings,
    name        := "uni-netty",
    description := "Netty-based HTTP server for uni",
    libraryDependencies ++=
      Seq(
        "io.netty"  % "netty-handler"                % NETTY_VERSION,
        "io.netty"  % "netty-codec-http"             % NETTY_VERSION,
        ("io.netty" % "netty-transport-native-epoll" % NETTY_VERSION).classifier("linux-x86_64"),
        ("io.netty" % "netty-transport-native-epoll" % NETTY_VERSION).classifier("linux-aarch_64")
      )
  )
  .dependsOn(uni.jvm, test.jvm % Test)

// Compile-checked code examples from The Uni Book (docs/book). Not published;
// exists so CI catches API drift in the book's runnable snippets.
lazy val bookExamples = project
  .in(file("book-examples"))
  .settings(
    buildSettings,
    noPublish,
    name        := "uni-book-examples",
    description := "Compile-checked code examples from The Uni Book"
  )
  .dependsOn(uni.jvm, netty, test.jvm % Test)

// uni-dom-test - Tests for uni-dom in a real headless browser (Chromium via Playwright)
lazy val domTest = project
  .in(file("uni-dom-test"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    buildSettings,
    jsBuildSettings,
    noPublish,
    name        := "uni-dom-test",
    description := "Tests for uni-dom in a headless browser (Playwright)",
    // Run DOM tests in a real headless Chromium via Playwright. This provides a faithful
    // browser DOM AND supports ES modules (jsBuildSettings sets ModuleKind.ESModule),
    // unlike the outdated jsdom which only supports plain scripts. Chromium also matches
    // the Electron renderer, so these tests exercise web and Electron app code alike.
    // sbt 2.x caches setting values and a JSEnv is not serializable, so wrap it in Def.uncached.
    Test / jsEnv :=
      Def.uncached(
        new wvlet.uni.jsenv.playwright.PlaywrightJSEnv(browserName = "chromium", headless = true)
      )
  )
  .dependsOn(uni.js, test.js % Test)
