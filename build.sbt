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
  if isSnapshot.value then
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
val SCALA_NATIVE_TEST_INTERFACE_VERSION = "0.5.13"
val SBT_TEST_INTERFACE_VERSION          = "1.0"

// Common build settings
val buildSettings =
  Seq[Setting[?]](
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
    testFrameworks += new TestFramework("wvlet.uni.test.Framework"),
    // Tests start many servers, and each logs a start line at INFO. Pass
    // -l:wvlet.uni.http.JvmHttpServer=info to testOnly to see them again.
    Test / testOptions +=
      Tests.Argument(
        TestFramework("wvlet.uni.test.Framework"),
        "-l:wvlet.uni.http.JvmHttpServer=warn"
      )
  ) ++
    // Keep -javadoc.jar small for Maven Central's publishing limits (see docs/build/sbt-uni-javadoc.md)
    minimizeScaladoc

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

// Sources shared by the JVM and Scala Native but not Scala.js, such as code that starts threads or
// processes, live in <project>/.jvm-native
val jvmNativeSharedSources = Seq[Setting[?]](
  Compile / unmanagedSourceDirectories +=
    baseDirectory.value.getParentFile / ".jvm-native" / "src" / "main" / "scala",
  Test / unmanagedSourceDirectories +=
    baseDirectory.value.getParentFile / ".jvm-native" / "src" / "test" / "scala"
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
  Compile / packageDoc / publishArtifact := false
  // Do not check binary compatibility for unpublished projects
  // mimaPreviousArtifacts := Set.empty
)

// Root project aggregating others
lazy val root = project
  .in(file("."))
  // sbt 2.x derives each project's output dir from its name, so the root must not reuse the
  // `uni` library project's name (they would collide on target/out/jvm/.../uni).
  .settings(buildSettings, name := "uni-root", publish / skip := true)
  .aggregate((jvmProjects ++ jsProjects ++ nativeProjects)*)

lazy val jvmProjects: Seq[ProjectReference] = Seq(
  core.jvm,
  uni.jvm,
  netty,
  bookExamples,
  docSnippets,
  exampleCli.jvm,
  exampleRepl.jvm,
  exampleRestServer,
  exampleSiteServer,
  exampleMcpServer,
  exampleRpcNative.jvm,
  exampleHttpGarden.jvm,
  test.jvm
)

lazy val jsProjects: Seq[ProjectReference] = Seq(
  core.js,
  uni.js,
  npm,
  ui,
  domTest,
  docSnippetsJS,
  exampleRepl.js,
  exampleWebUi,
  exampleHarnessApi,
  exampleHarnessServer,
  exampleHarnessRenderer,
  exampleUiGallery,
  test.js
)

lazy val nativeProjects: Seq[ProjectReference] = Seq(
  core.native,
  uni.native,
  exampleCli.native,
  exampleRepl.native,
  exampleRpcNative.native,
  exampleHttpGarden.native,
  test.native
)

lazy val projectJVM = project
  .settings(noPublish)
  .settings(
    // Use a stable coverage directory name without containing scala version
    // coverageDataDir := target.value
  )
  .aggregate(jvmProjects*)

lazy val projectJS = project.settings(noPublish).aggregate(jsProjects*)

lazy val projectNative = project.settings(noPublish).aggregate(nativeProjects*)

// Core library with logging and reactive streams
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("uni-core"))
  .settings(
    buildSettings,
    name        := "uni-core",
    description :=
      "Core of Uni for Scala 3 (JVM, Scala.js, Scala Native): logging, Rx reactive streams, object wiring (Design), Surface type reflection, file I/O with gzip/zip and file watching, terminal colors, a text scanner, and reactive DOM rendering for Scala.js"
  )
  .jsSettings(
    jsBuildSettings,
    minimizeJavadoc,
    libraryDependencies ++=
      Seq(
        ("org.scala-js" %% "scalajs-java-logging" % JS_JAVA_LOGGING_VERSION).cross(
          CrossVersion.for3Use2_13
        )
      )
  )
  .nativeSettings(nativeBuildSettings, minimizeJavadoc)

// The 'uni' library for Scala JVM, Scala.js and Scala Native
lazy val uni = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("uni"))
  .settings(
    buildSettings,
    name        := "uni",
    description :=
      "Scala 3 utilities for JVM, Scala.js, and Scala Native: object wiring (Design), logging, JSON/MessagePack, object serialization (Weaver), Surface reflection, Rx, HTTP client and router, RPC, OpenAPI, MCP servers, typed LLM vendor APIs (Anthropic, OpenAI, Gemini, Bedrock), Parquet files and datasets (with Delta Lake and Iceberg tables), object storage (S3, GCS, Azure), retry and circuit breaker, CLI tools, and reactive DOM UI and Electron for Scala.js"
  )
  .jvmSettings(
    jvmNativeSharedSources,
    // Optional: applications that add snappy-java get its native Snappy decoder (SnappyJava.java).
    // Provided keeps it out of uni's runtime dependencies; tests also check the codecs against it
    libraryDependencies += "org.xerial.snappy" % "snappy-java" % "1.1.10.12" % Provided,
    // A library jar has no single entry point (uni ships several CLI mains, such as
    // OpenAPIGeneratorMain), so its manifest names none and packaging does not warn about the choice
    Compile / packageBin / mainClass := None
  )
  .jsSettings(jsBuildSettings, minimizeJavadoc)
  .nativeSettings(nativeBuildSettings, jvmNativeSharedSources, minimizeJavadoc)
  .dependsOn(core, test % Test)

lazy val npmPackage = taskKey[File]("Stage the @wvlet/uni npm package under target/npm")

// @wvlet/uni on npm: a JS-shaped facade over Uni (Parquet first), linked as one ES
// module set so every subpath shares one Scala runtime. Not published to Maven. The package
// itself is staged by `npm/npmPackage` and published by .github/workflows/release-npm.yml
// (adr/2026-11-20-npm-package.md).
lazy val npm = project
  .in(file("uni-npm"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    buildSettings,
    jsBuildSettings,
    noPublish,
    name        := "uni-npm",
    description := "The @wvlet/uni npm package: Uni libraries for TypeScript and JavaScript",
    scalaJSLinkerConfig ~= {
      _.withModuleSplitStyle(org.scalajs.linker.interface.ModuleSplitStyle.FewestModules)
    },
    npmPackage :=
      Def.uncached {
        val _         = (Compile / fullLinkJS).value
        val linkedDir = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
        val npmSrc    = (Compile / sourceDirectory).value / "npm"
        // A fixed path, so CI and the release workflow can find it
        val out = baseDirectory.value / "target" / "npm"
        IO.delete(out)
        IO.createDirectory(out)
        // Every module of the link run: the public entry points plus any shared internal module
        // main.js holds uni-core's own top-level exports (e.g. WvletLogger); the package exports
        // only its subpaths, so leave it out
        (linkedDir * "*.js")
          .get()
          .filter(_.getName != "main.js")
          .foreach(f => IO.copyFile(f, out / f.getName))
        IO.copyDirectory(npmSrc, out)
        IO.copyFile((ThisBuild / baseDirectory).value / "LICENSE", out / "LICENSE")
        val template = IO.read(npmSrc / "package.json")
        IO.write(out / "package.json", template.replace("0.0.0-dev", version.value))
        out
      }
  )
  .dependsOn(uni.js % "compile->compile;test->test", test.js % Test)

// uni-ui - Styled components for uni-dom (Scala.js only), themed by a plain CSS file
lazy val ui = project
  .in(file("uni-ui"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    buildSettings,
    jsBuildSettings,
    name        := "uni-ui",
    description :=
      "UI components for uni-dom (Scala.js): buttons, inputs, tabs, foldable panes, splitters, popovers, menus, dialogs, selects, toasts, collections, and a dock, styled by uni-ui.css with light and dark themes",
    // Component tests render into a real DOM in headless Chromium, like domTest. Screenshot
    // goldens (VisualTest) live in src/test/screenshots; paths are absolute so they don't depend
    // on the JVM's working directory.
    Test / jsEnv :=
      Def.uncached(
        PlaywrightJSEnv(
          PlaywrightConfig(browserName = "chromium", headless = true)
            // Turns on WebMCP (document.modelContext) in Chromium 150+, so UiAgentTest checks
            // installWebMcp against the real API; older Chromium ignores the unknown feature
            .withLaunchArgs(List("--enable-features=WebMCP"))
            .withScreenshotDir(
              (target.value / "uni-playwright" / "screenshots").toPath.toAbsolutePath
            )
            .withSnapshotDir(((Test / sourceDirectory).value / "screenshots").toPath.toAbsolutePath)
        )
      ),
    // The browser cannot read uni-ui.css from the jar, so embed it for the visual tests.
    // The generator reads the CSS file directly, so sbt 2 must not cache its result.
    Test / sourceGenerators +=
      Def
        .task {
          val css = (Compile / resourceDirectory).value / "wvlet" / "uni" / "ui" / "uni-ui.css"
          val out = (Test / sourceManaged).value / "wvlet" / "uni" / "ui" / "UniUiCss.scala"
          Def.uncached {
            val text = IO.read(css)
            require(!text.contains("\"\"\""), s"${css} must not contain triple quotes")
            val q = "\"\"\""
            IO.write(
              out,
              "package wvlet.uni.ui\n\n" +
                "/** The contents of uni-ui.css, generated by build.sbt for the visual tests. */\n" +
                s"object UniUiCss:\n  val text: String = ${q}${text}${q}\n"
            )
            Seq(out)
          }
        }
        .taskValue
  )
  .dependsOn(uni.js, test.js % Test)

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
    minimizeJavadoc,
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
    minimizeJavadoc,
    libraryDependencies ++=
      Seq(
        // Scala Native uses native test-interface
        "org.scala-native" %% "test-interface" % SCALA_NATIVE_TEST_INTERFACE_VERSION
      )
  )
  .dependsOn(core)

val NETTY_VERSION = "4.2.21.Final"

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
  // test->test: the Netty server runs the shared WebSocketRelaySpec
  .dependsOn(uni.jvm % "compile->compile;test->test", test.jvm % Test)

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

// A line-editing REPL with LineReader; the same code runs on the JVM, Node.js, and Scala Native.
lazy val exampleRepl = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("examples/repl"))
  .settings(
    buildSettings,
    noPublish,
    name        := "uni-example-repl",
    description := "Example: a REPL with LineReader (editing, history, completion, highlighting)"
  )
  .jsSettings(jsBuildSettings, scalaJSUseMainModuleInitializer := true)
  .nativeSettings(nativeBuildSettings)
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

// One RPC server, the same code on the JVM (Netty) and as a Scala Native binary (NativeServer, no JVM).
// Benchmark scripts: examples/rpc-native/bench
lazy val exampleRpcNative = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("examples/rpc-native"))
  .settings(
    buildSettings,
    noPublish,
    name        := "uni-example-rpc-native",
    description := "Example: one typed RPC trait served on the JVM (Netty) and Scala Native"
  )
  .nativeSettings(
    nativeBuildSettings,
    nativeConfig ~= { c =>
      // releaseFull + full LTO: in examples/rpc-native/README.md's comparison, it matched the best
      // throughput with the least memory, at a smaller binary than releaseFull alone.
      // UNI_NATIVE_FAST_LINK=true (pull-request CI) builds with releaseFast and no LTO instead:
      // the full-LTO link takes ~2 minutes, and check-rpc-native.sh needs only a working server
      // that links no libcurl. Pushes to main still build and check the releaseFull binary.
      val fastLink = sys.env.get("UNI_NATIVE_FAST_LINK").contains("true")
      val release  =
        if fastLink then
          c.withMode(scala.scalanative.build.Mode.releaseFast)
            .withLTO(scala.scalanative.build.LTO.none)
        else
          c.withMode(scala.scalanative.build.Mode.releaseFull)
            .withLTO(scala.scalanative.build.LTO.full)
      release
        // Immix, Scala Native's default. 0.5.13 fixed the concurrent-allocation corruption
        // (scala-native#5046, #5160) that kept this example on Boehm (adr/2026-10-30). Set
        // UNI_NATIVE_GC=boehm (or commix) to benchmark another collector, as rpc-bench.yml does.
        .withGC(scala.scalanative.build.GC(sys.env.getOrElse("UNI_NATIVE_GC", "immix")))
        // Cap the heap, as docs/http/server.md recommends: uncapped, Immix grew to 266 MB RSS on
        // 18 cores where the server's live data fits in a few MB (GC_MAXIMUM_HEAP_SIZE overrides)
        .withGarbageCollectorConfig(_.withMaxHeapSize(scala.scalanative.build.MemorySize.mb(256)))
        // nativeBuildSettings links libcurl and zlib for every Native project in this repo. A
        // server-only binary needs only zlib (request decompression and response compression):
        // dead-code elimination drops the HTTP client, so leave libcurl out to build what a user's
        // own project gets.
        .withLinkingOptions(c.linkingOptions.filterNot(Set("-lcurl")))
    },
    // Build the test binary in debug mode so it links quickly. It links Boehm, so CI still covers
    // uni_gc_shim.c (BoehmMarkersTest, BoehmHeapTest) for apps that choose Boehm, except on
    // Windows, whose CI runner has no libgc: there it stays on Immix.
    Test / nativeConfig ~= { c =>
      val debug = c
        .withMode(scala.scalanative.build.Mode.debug)
        .withLTO(scala.scalanative.build.LTO.none)
      if scala.util.Properties.isWin then
        debug.withGC(scala.scalanative.build.GC.immix)
      else
        debug.withGC(scala.scalanative.build.GC.boehm)
    }
  )
  .jvmSettings(
    // For bench/build.sh: copy the runtime classpath (jars) to bench/out/lib
    stageBenchJars :=
      Def.uncached {
        val converter = fileConverter.value
        val dir       = baseDirectory.value.getParentFile / "bench" / "out" / "lib"
        IO.delete(dir)
        (Runtime / fullClasspath)
          .value
          .map(a => converter.toPath(a.data).toFile)
          .foreach(jar => IO.copyFile(jar, dir / jar.getName))
        dir
      }
  )
  .jvmConfigure(_.dependsOn(netty))
  .dependsOn(uni, test % Test)

lazy val stageBenchJars = taskKey[File]("Copy the runtime classpath jars for the benchmark")

// Read benchmark for Uni's Parquet reader: compares it with parquet-java on the JVM and measures
// Scala.js (Node.js) and Scala Native over the same files. Run it with examples/parquet-bench/run.sh.
// Kept out of the projectJVM/JS/Native aggregates: parquet-java pulls in Hadoop's client jars,
// which neither Uni nor CI needs.
lazy val exampleParquetBench = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("examples/parquet-bench"))
  .settings(
    buildSettings,
    noPublish,
    name        := "uni-example-parquet-bench",
    description := "Read benchmark: Uni vs. parquet-java on the same files"
  )
  .jvmSettings(
    libraryDependencies ++=
      Seq(
        "org.apache.parquet" % "parquet-hadoop"        % "1.16.0",
        "org.apache.hadoop"  % "hadoop-client-api"     % "3.4.1",
        "org.apache.hadoop"  % "hadoop-client-runtime" % "3.4.1",
        "org.slf4j"          % "slf4j-nop"             % "2.0.16"
      ),
    run / fork := true,
    run / javaOptions ++= Seq("-Xmx4g", "--enable-native-access=ALL-UNNAMED"),
    stageBenchJars :=
      Def.uncached {
        val converter = fileConverter.value
        val dir       = baseDirectory.value.getParentFile / "out" / "lib"
        IO.delete(dir)
        (Runtime / fullClasspath)
          .value
          .map(a => converter.toPath(a.data).toFile)
          .foreach(jar => IO.copyFile(jar, dir / jar.getName))
        dir
      }
  )
  .jsSettings(
    jsBuildSettings,
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass             := Some("example.parquetbench.UniBench")
  )
  .nativeSettings(
    nativeBuildSettings,
    Compile / mainClass := Some("example.parquetbench.UniBench"),
    nativeConfig ~= {
      _.withMode(scala.scalanative.build.Mode.releaseFull)
    }
  )
  .dependsOn(uni)

// HTTP Garden origin: echoes how Uni parsed each request, for differential parser testing.
// Built and run only by .github/workflows/http-garden.yml; see examples/http-garden/README.md
lazy val exampleHttpGarden = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("examples/http-garden"))
  .settings(
    buildSettings,
    noPublish,
    name                := "uni-example-http-garden",
    description         := "HTTP Garden echo origin for Uni's HTTP servers",
    Compile / mainClass := Some("example.garden.GardenEcho")
  )
  .nativeSettings(
    nativeBuildSettings,
    nativeConfig ~= { c =>
      c.withLinkingOptions(c.linkingOptions.filterNot(Set("-lcurl")))
    },
    // Compile-only in the Native CI job (adr/2026-10-27-in-build-examples.md)
    Test / loadedTestFrameworks := Def.uncached(Map.empty)
  )
  .jvmSettings(
    // For examples/http-garden/garden/build.sh: copy the runtime classpath (jars) to garden/out/lib
    stageGardenJars :=
      Def.uncached {
        val converter = fileConverter.value
        val dir       = baseDirectory.value.getParentFile / "garden" / "out" / "lib"
        IO.delete(dir)
        (Runtime / fullClasspath)
          .value
          .map(a => converter.toPath(a.data).toFile)
          .foreach(jar => IO.copyFile(jar, dir / jar.getName))
        dir
      }
  )
  .jvmConfigure(_.dependsOn(netty))
  .dependsOn(uni)

lazy val stageGardenJars = taskKey[File]("Copy the runtime classpath jars for the HTTP Garden run")

// Serves built web files (the ui-gallery site, the web-ui example) during development:
// ./sbt "exampleSiteServer/uniRestart <dir> [port]"
lazy val exampleSiteServer = project
  .in(file("examples/site-server"))
  .enablePlugins(UniPlugin)
  .settings(
    buildSettings,
    noPublish,
    name        := "uni-example-site-server",
    description := "Example: serve a directory of web files with Http.server and StaticFiles",
    // Directories are given relative to the repository root
    uniRestart / baseDirectory := (ThisBuild / baseDirectory).value
  )
  .dependsOn(uni.jvm, netty)

lazy val exampleMcpServer = project
  .in(file("examples/mcp-server"))
  .settings(
    buildSettings,
    noPublish,
    name        := "uni-example-mcp-server",
    description := "Example: a stdio MCP server built with wvlet.uni.ai.mcp",
    // `sbt run` as an MCP command: fork so stdin reaches the server and stdout carries only
    // protocol messages
    run / fork         := true,
    run / connectInput := true,
    outputStrategy     := Some(StdoutOutput)
  )
  // test->test: the Netty server runs the shared WebSocketRelaySpec
  .dependsOn(uni.jvm % "compile->compile;test->test", test.jvm % Test)

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

// uni-harness: a chat workspace shell for Electron and the browser. The Scala.js modules build here,
// so CI compiles and tests them; examples/uni-harness/*.config.mjs link them with vite-plugin-scalajs.
// The modules export functions for Vite to import, so none has a main module initializer.
lazy val exampleHarnessApi = project
  .in(file("examples/uni-harness/api"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    buildSettings,
    jsBuildSettings,
    noPublish,
    name                        := "uni-example-harness-api",
    description                 := "Example: the uni-harness ChatApi and its typed client",
    Test / loadedTestFrameworks := Def.uncached(Map.empty)
  )
  .dependsOn(uni.js)

lazy val exampleHarnessServer = project
  .in(file("examples/uni-harness/server"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    buildSettings,
    jsBuildSettings,
    noPublish,
    name        := "uni-example-harness-server",
    description := "Example: the uni-harness ChatApi served over Electron IPC and Node HTTP"
  )
  .dependsOn(exampleHarnessApi, test.js % Test)

lazy val exampleHarnessRenderer = project
  .in(file("examples/uni-harness/renderer"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    buildSettings,
    jsBuildSettings,
    noPublish,
    name        := "uni-example-harness-renderer",
    description := "Example: the uni-harness UI, tested in headless Chromium",
    // Component tests render into a real DOM, like domTest
    Test / jsEnv :=
      Def.uncached(PlaywrightJSEnv(PlaywrightConfig(browserName = "chromium", headless = true)))
  )
  .dependsOn(exampleHarnessApi, ui, exampleHarnessServer % Test, test.js % Test)

lazy val gallerySite = taskKey[File]("Stage the ui-gallery page with fastLinkJS under target/site")
lazy val galleryDocs = taskKey[File](
  "Stage the ui-gallery page with fullLinkJS in docs/public/gallery"
)

// Copy the gallery's page, its CSS, uni-ui.css, and the linked JS into `out`
def stageGallery(linked: File, web: File, uiCss: File, out: File): File =
  IO.delete(out)
  IO.createDirectory(out)
  IO.copyDirectory(web, out)
  IO.copyFile(uiCss, out / "uni-ui.css")
  (linked * "*.js*").get().foreach(f => IO.copyFile(f, out / f.getName))
  out

// ui-gallery: a live catalog of uni-ui components next to their code, embedded in the docs
// (docs/dom/gallery.md). Each story's code panel shows its `// #region` block, cut out at build time
// by project/StoryRegions.scala, so the code shown is the code that runs.
lazy val exampleUiGallery = project
  .in(file("examples/ui-gallery"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    buildSettings,
    jsBuildSettings,
    noPublish,
    name                            := "uni-example-ui-gallery",
    description                     := "Example: a gallery of uni-ui components with their code",
    scalaJSUseMainModuleInitializer := true,
    // Read uni-ui's doc comments from TASTy, so ApiDoc can show them in each page's API list
    scalacOptions += "-Xread-docs",
    // The generator reads the story sources directly, so sbt 2 must not cache its result
    Compile / sourceGenerators +=
      Def
        .task {
          Def.uncached(
            StoryRegions.generate(
              (Compile / scalaSource).value / "example" / "gallery" / "stories",
              (ThisBuild / baseDirectory).value,
              (Compile / sourceManaged).value,
              "example.gallery"
            )
          )
        }
        .taskValue,
    // The Colors page's test checks its tokens against the variables uni-ui.css declares
    Test / sourceGenerators +=
      Def
        .task {
          Def.uncached(
            CssTokens.generate(
              (ui / Compile / resourceDirectory).value / "wvlet" / "uni" / "ui" / "uni-ui.css",
              (Test / sourceManaged).value,
              "example.gallery"
            )
          )
        }
        .taskValue,
    // The versions the Get started page puts in its build snippets. Between releases `version` is a
    // -SNAPSHOT that users cannot resolve, so the page shows the last release tag instead. A clone
    // without tags (CI test jobs) has none and keeps the SNAPSHOT; the docs build fetches tags.
    Compile / sourceGenerators +=
      Def
        .task {
          val uniVersion =
            if version.value.endsWith("SNAPSHOT") then
              previousStableVersion.value.getOrElse(version.value)
            else
              version.value
          val out = (Compile / sourceManaged).value / "example" / "gallery" / "BuildInfo.scala"
          IO.write(
            out,
            s"""package example.gallery
               |
               |// Generated by build.sbt (exampleUiGallery) for the Get started page
               |object BuildInfo:
               |  val uniVersion: String     = "${uniVersion}"
               |  val scalaVersion: String   = "${scalaVersion.value}"
               |  val scalaJsVersion: String = "${scalaJSVersion}"
               |  val sbtVersion: String     = "${sbtVersion.value}"
               |""".stripMargin
          )
          Seq(out)
        }
        .taskValue,
    // The Get started page's minimal app has a `main` too
    Compile / mainClass := Some("example.gallery.GalleryApp"),
    gallerySite         :=
      Def.uncached {
        val _ = (Compile / fastLinkJS).value
        stageGallery(
          (Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value,
          baseDirectory.value / "web",
          (ui / Compile / resourceDirectory).value / "wvlet" / "uni" / "ui" / "uni-ui.css",
          baseDirectory.value / "target" / "site"
        )
      },
    galleryDocs :=
      Def.uncached {
        val _ = (Compile / fullLinkJS).value
        stageGallery(
          (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value,
          baseDirectory.value / "web",
          (ui / Compile / resourceDirectory).value / "wvlet" / "uni" / "ui" / "uni-ui.css",
          (ThisBuild / baseDirectory).value / "docs" / "public" / "gallery"
        )
      },
    // Every story mounts in headless Chromium, like the uni-ui tests
    Test / jsEnv :=
      Def.uncached(PlaywrightJSEnv(PlaywrightConfig(browserName = "chromium", headless = true)))
  )
  .dependsOn(ui, test.js % Test)

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
      "Compile-checked code snippets of Markdown files (the uni skill and selected doc pages)",
    // The generator reads the Markdown directly, so sbt 2 must not cache its result
    Test / sourceGenerators +=
      Def
        .task {
          val base = (ThisBuild / baseDirectory).value
          Def.uncached(
            MarkdownSnippets.generate(
              Seq(
                base / "plugins/uni/skills/uni"             -> "snippets.skill",
                base / "docs/index.md"                      -> "snippets.home",
                base / "docs/guide/index.md"                -> "snippets.guide",
                base / "docs/guide/uni-vs-scala-toolkit.md" -> "snippets.guide",
                base / "docs/core/unitest.md"               -> "snippets.core",
                base / "docs/core/settings.md"              -> "snippets.core",
                base / "docs/core/canvas.md"                -> "snippets.core",
                base / "docs/http/telemetry.md"             -> "snippets.http",
                base / "docs/cloud"                         -> "snippets.cloud",
                base / "docs/rx/events.md"                  -> "snippets.rx",
                base / "docs/ai"                            -> "snippets.ai",
                base / "docs/blog"                          -> "snippets.blog"
              ),
              base,
              (Test / sourceManaged).value / "markdown-snippets",
              // Scala.js-only; docSnippetsJS compiles it
              exclude = Set(base / "plugins/uni/skills/uni/references/ui.md")
            )
          )
        }
        .taskValue
  )
  .dependsOn(uni.jvm, netty, test.jvm % Test)

// The Scala.js counterpart of docSnippets: compile-checks the snippets of the UI pages (uni-dom and
// uni-ui) against uni.js and uni-ui. Stubs live in docs/snippets-js/src/test/scala. Snippets are
// compiled, not run: a `scala test` fence here would need a browser.
lazy val docSnippetsJS = project
  .in(file("docs/snippets-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    buildSettings,
    jsBuildSettings,
    noPublish,
    name        := "uni-doc-snippets-js",
    description := "Compile-checked Scala.js code snippets of the UI doc pages and skill reference",
    Test / sourceGenerators +=
      Def
        .task {
          val base = (ThisBuild / baseDirectory).value
          Def.uncached(
            MarkdownSnippets.generate(
              Seq(
                base / "plugins/uni/skills/uni/references/ui.md" -> "snippets.skill.references",
                base / "docs/dom"                                -> "snippets.dom"
              ),
              base,
              (Test / sourceManaged).value / "markdown-snippets"
            )
          )
        }
        .taskValue
  )
  .dependsOn(uni.js, ui, test.js % Test)

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
