import sbtide.Keys.ideSkipProject

// `core.jvm` / `.js` / `.native` (Project) are used where a ProjectReference is expected (e.g. the
// jvmProjects/jsProjects/nativeProjects lists); sbt 2.x requires opting into that implicit conversion.
import scala.language.implicitConversions
import wvlet.uni.jsenv.playwright.{PlaywrightConfig, PlaywrightJSEnv}

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / organization := "org.wvlet.uni"

// Use dynamic snapshot version strings for non tagged versions
ThisBuild / dynverSonatypeSnapshots := true
// Use coursier friendly version separator
ThisBuild / dynverSeparator := "-"

// Versions are YYYY.(milestone).patch, so the first two components form the binary-compatible series
ThisBuild / versionScheme := Some("pvp")

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

val SCALA_3                             = "3.9.0"
val JS_JAVA_LOGGING_VERSION             = "1.0.0"
val JUNIT_PLATFORM_VERSION              = "6.1.3"
val SCALA_NATIVE_TEST_INTERFACE_VERSION = "0.5.12"
val SBT_TEST_INTERFACE_VERSION          = "1.0"

// Common build settings
val buildSettings = Seq[Setting[?]](
  description        := "Scala 3 utility libraries for JVM, Scala.js, and Scala Native",
  scalaVersion       := SCALA_3,
  crossScalaVersions := List(SCALA_3),
  crossPaths         := true,
  publishMavenStyle  := true,
  licenses += ("Apache-2.0", uri("https://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(uri("https://wvlet.org/uni/")),
  scmInfo  :=
    Some(
      ScmInfo(
        browseUrl = uri("https://github.com/wvlet/uni"),
        connection = "scm:git:git@github.com:wvlet/uni.git"
      )
    ),
  developers :=
    List(
      Developer(
        id = "leo",
        name = "Taro L. Saito",
        email = "leo@xerial.org",
        url = uri("http://xerial.org/leo")
      )
    ),
  scalacOptions ++= Seq("-deprecation", "-feature"),
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
  },
  // Scaladoc does not support compiler plugins and warns about the Scala Native nscplugin
  Compile / doc / scalacOptions ~= (_.filterNot(_.startsWith("-Xplugin")))
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

lazy val jvmProjects: Seq[ProjectReference] = Seq(
  core.jvm,
  uni.jvm,
  netty,
  bookExamples,
  docSnippets,
  exampleCli.jvm,
  exampleRestServer,
  exampleMcpServer,
  exampleToolkitService,
  test.jvm
)

lazy val jsProjects: Seq[ProjectReference] = Seq(core.js, uni.js, domTest, exampleWebUi, test.js)
lazy val nativeProjects: Seq[ProjectReference] = Seq(
  core.native,
  uni.native,
  exampleCli.native,
  test.native
)

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
    description :=
      "Core of Uni for Scala 3 (JVM, Scala.js, Scala Native): logging, Rx reactive streams, file I/O with gzip/zip and file watching, terminal colors, and a text scanner"
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
  .settings(
    buildSettings,
    name        := "uni",
    description :=
      "Scala 3 utilities for JVM, Scala.js, and Scala Native: object wiring (Design), logging, JSON/MessagePack, object serialization (Weaver), Surface reflection, Rx, HTTP client and router, RPC, OpenAPI, MCP servers, retry and circuit breaker, CLI tools, and reactive DOM UI and Electron for Scala.js"
  )
  .jsSettings(jsBuildSettings)
  .nativeSettings(nativeBuildSettings)
  .dependsOn(core, test % Test)

// uni-test - Lightweight testing framework with AirSpec syntax
lazy val test = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("uni-test"))
  .settings(
    buildSettings,
    name        := "uni-test",
    description :=
      "Lightweight Scala 3 testing framework with AirSpec-style syntax for JVM, Scala.js, and Scala Native: assertions, property-based testing, and JUnit Platform IDE integration",
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

val NETTY_VERSION = "4.2.19.Final"

lazy val netty = project
  .in(file("uni-netty"))
  .settings(
    buildSettings,
    name        := "uni-netty",
    description :=
      "Netty-based HTTP and WebSocket server for Uni: serves Uni HTTP routers and RPC endpoints on the JVM",
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

// Runnable example apps (examples/*), one project per folder so each can be copied as a template.
// Not published; aggregated into projectJVM/JS/Native so CI compiles (and tests) them.
lazy val exampleCli = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("examples/cli-tool"))
  .settings(
    buildSettings,
    noPublish,
    name        := "uni-example-cli",
    description :=
      "Example: a CLI tool with the uni launcher, logging, Weaver JSON, and HTTP client"
  )
  .nativeSettings(
    nativeBuildSettings,
    // The tests are JVM-only (.jvm/src/test); skip linking an empty Native test binary in CI.
    // Build the binary with exampleCliNative/nativeLink.
    Test / loadedTestFrameworks := Def.uncached(Map.empty)
  )
  .dependsOn(uni, test % Test)

lazy val exampleRestServer = project
  .in(file("examples/rest-server"))
  .settings(
    buildSettings,
    noPublish,
    name        := "uni-example-rest-server",
    description := "Example: a REST + RPC server with uni-netty, Router, RPCRouter, and Design"
  )
  .dependsOn(uni.jvm, netty, test.jvm % Test)

lazy val exampleMcpServer = project
  .in(file("examples/mcp-server"))
  .settings(
    buildSettings,
    noPublish,
    name        := "uni-example-mcp-server",
    description := "Example: a stdio MCP server built with wvlet.uni.mcp",
    // `sbt run` as an MCP command: fork so stdin reaches the server and stdout carries only
    // protocol messages
    run / fork         := true,
    run / connectInput := true,
    outputStrategy     := Some(StdoutOutput)
  )
  .dependsOn(uni.jvm, test.jvm % Test)

// Uses the sttp and uPickle versions that Scala Toolkit 0.9.2 pins, so CI keeps checking that the
// Toolkit libraries and Uni work in one build
lazy val exampleToolkitService = project
  .in(file("examples/toolkit-service"))
  .settings(
    buildSettings,
    noPublish,
    name        := "uni-example-toolkit-service",
    description := "Example: a Scala Toolkit (sttp + uPickle) script grown into a Uni service",
    // RepoScript also defines a @main; `run` starts the service
    Compile / run / mainClass := Some("example.toolkit.RepoServer"),
    libraryDependencies ++=
      Seq(
        "com.softwaremill.sttp.client4" %% "core"    % "4.0.21",
        "com.lihaoyi"                   %% "upickle" % "4.4.3"
      )
  )
  .dependsOn(uni.jvm, netty, test.jvm % Test)

lazy val exampleWebUi = project
  .in(file("examples/web-ui"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    buildSettings,
    jsBuildSettings,
    noPublish,
    name                            := "uni-example-web-ui",
    description                     := "Example: a browser UI with RxElement (Scala.js)",
    scalaJSUseMainModuleInitializer := true,
    // Emit main.js next to index.html (examples/web-ui/target/js) so a static server can load it
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "target" / "js",
    // No tests: skip linking an empty Scala.js test module in CI
    Test / loadedTestFrameworks := Def.uncached(Map.empty)
  )
  .dependsOn(uni.js)

// Compile-checks the `scala` code fences of Markdown files in place (see project/MarkdownSnippets.scala
// for the fence modifiers). Free names in snippets resolve to hand-written stubs under
// docs/snippets/src/test/scala, in the generated package of each Markdown file. Not published.
lazy val docSnippets = project
  .in(file("docs/snippets"))
  .settings(
    buildSettings,
    noPublish,
    name        := "uni-doc-snippets",
    description :=
      "Compile-checked code snippets of Markdown files (the uni skill and selected guide pages)",
    // The generator reads the Markdown directly, so sbt 2 must not cache its result
    Test / sourceGenerators +=
      Def
        .task {
          val base = (ThisBuild / baseDirectory).value
          Def.uncached(
            MarkdownSnippets.generate(
              Seq(
                base / "plugins/uni/skills/uni"             -> "snippets.skill",
                base / "docs/guide/uni-vs-scala-toolkit.md" -> "snippets.guide"
              ),
              base,
              (Test / sourceManaged).value / "markdown-snippets"
            )
          )
        }
        .taskValue
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
    // Screenshot goldens (wvlet.uni.test.playwright.Playwright.expectScreenshot) live in
    // src/test/screenshots. Paths are absolute so they don't depend on the JVM's working directory.
    Test / jsEnv :=
      Def.uncached(
        PlaywrightJSEnv(
          PlaywrightConfig(browserName = "chromium", headless = true)
            .withScreenshotDir(
              (target.value / "uni-playwright" / "screenshots").toPath.toAbsolutePath
            )
            .withSnapshotDir(((Test / sourceDirectory).value / "screenshots").toPath.toAbsolutePath)
        )
      )
  )
  .dependsOn(uni.js, test.js % Test)
