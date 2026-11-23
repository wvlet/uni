package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx

import scala.language.implicitConversions

/**
  * The Get started page: the build files, the stylesheet, the imports, a minimal app shown live in
  * an example card, and how to run it. The versions in the snippets come from the gallery's own
  * build ([[BuildInfo]]), so they match the release the gallery was built from.
  */
class StartView(page: GuidePage, app: Story) extends RxElement:
  import StartView.*

  private def step(n: Int, sectionId: String, title: String, body: DomNode*): RxElement = section(
    cls             -> "start-step",
    id              -> sectionId,
    data("section") -> sectionId,
    h2(cls -> "start-step__title", span(cls -> "start-step__num", n.toString), title),
    body
  )

  private def docsLink(path: String, content: DomNode*): RxElement = a(
    href   -> s"https://wvlet.org/uni/${path}",
    target -> "_blank",
    rel    -> "noopener",
    content
  )

  override def render: RxElement = div(
    cls -> "page page--guide",
    PageHeader(
      page.name,
      page.summary,
      steps.map((sectionId, title) => PageHeader.Link(sectionId, title, page.id)),
      trailing = Seq(
        a(
          cls    -> "story-link start-version",
          href   -> "https://github.com/wvlet/uni/releases",
          target -> "_blank",
          rel    -> "noopener",
          s"Uni ${BuildInfo.uniVersion} release notes"
        )
      )
    ),
    step(
      1,
      "build",
      "Add Uni to your build",
      p(
        "Uni runs on Scala ",
        BuildInfo.scalaVersion,
        " and sbt 2.x. Every release is on the wvlet Maven repository; Maven Central gets only some, so add the resolver. ",
        "The build has two projects: ",
        code("app"),
        ", the Scala.js code that runs in the browser, and ",
        code("server"),
        ", a JVM server that serves the ",
        code("web"),
        " folder during development."
      ),
      CodeBlock("project/build.properties", buildProperties),
      CodeBlock("project/plugins.sbt", pluginsSbt),
      CodeBlock("build.sbt", buildSbt),
      CodeBlock("layout", layout),
      p(
        cls -> "start-note",
        "ESModule output in ",
        code("web/js"),
        " loads with a plain ",
        code("<script type=\"module\">"),
        " and works with Vite too. More in the ",
        docsLink("guide/installation", "installation guide"),
        " and the ",
        docsLink("build/sbt-uni#uni-ui-stylesheet", "sbt-uni page"),
        "."
      )
    ),
    step(
      2,
      "css",
      "Load uni-ui.css",
      p(
        code("uni-ui.css"),
        " is a plain CSS file that styles every uni-ui component. Scala sets only structure and state: each part gets a ",
        code("data-slot"),
        " name and ",
        code("data-state"),
        " attributes, and the stylesheet draws them. Without it, components render as unstyled HTML."
      ),
      p(
        "It ships inside the uni-ui jar at ",
        code(UniUiCssResource),
        ". The ",
        code("uniUiCssFile"),
        " setting above tells sbt-uni where to copy it. The copy runs on every compile, so the stylesheet always matches the uni-ui version you link. Load it once, before your own CSS:"
      ),
      CodeBlock("web/index.html", cssLink),
      p(
        "Colors, corner radii, and fonts are ",
        code("--uni-*"),
        " CSS variables, with light and dark values. The ",
        code("data-theme"),
        " attribute on ",
        code("<html>"),
        " picks the scheme (",
        code("light"),
        " or ",
        code("dark"),
        "), and without it the OS setting decides. To use your own colors, set the variables in your stylesheet:"
      ),
      CodeBlock("web/style.css", themeCss),
      p(
        "Every rule sits in the ",
        code("uni"),
        " cascade layer, so unlayered app CSS always wins over it, without ",
        code("!important"),
        ". With Vite, import the file from your stylesheet instead. If your app uses other layers, such as Tailwind CSS v4's, declare their order first:"
      ),
      CodeBlock("style.css", tailwindCss),
      p(
        cls -> "start-note",
        "The ",
        a(href -> "#/colors", "Colors"),
        " page shows every color variable in both schemes, and the ",
        a(href -> "#/theming", "Theming"),
        " page shows overrides live. More in the ",
        docsLink("dom/theming", "theming guide"),
        "."
      )
    ),
    step(
      3,
      "imports",
      "Import the DSL",
      p(
        "Every example in this gallery assumes these imports. The ",
        code("given"),
        " import lets you put a ",
        code("String"),
        ", ",
        code("Int"),
        ", ",
        code("Rx"),
        ", ",
        code("Seq"),
        ", or ",
        code("Option"),
        " directly in the markup, and Scala 3 needs ",
        code("implicitConversions"),
        " for that. Drop ",
        code("wvlet.uni.ui.*"),
        " if you use uni-dom alone."
      ),
      CodeBlock("app/src/main/scala/Main.scala", imports)
    ),
    step(
      4,
      "app",
      "Write your first app",
      p(
        "A component extends ",
        code("RxElement"),
        " and implements ",
        code("render"),
        ". ",
        code("renderTo(\"app\")"),
        " mounts it into ",
        code("<div id=\"app\">"),
        ". Below the imports, ",
        code("Main.scala"),
        " holds this code, and the preview is what you'll see:"
      ),
      div(
        cls -> "start-example",
        section(
          cls             -> "example-section",
          data("example") -> app.id,
          // The card stacks the code below the preview when it is too narrow for both
          StoryView(Rx.const(app), Rx.variable(ViewMode.Split), embed = false)
        )
      ),
      CodeBlock("web/index.html", indexHtml)
    ),
    step(
      5,
      "run",
      "Run it",
      p(
        "The server is a few lines of Uni. ",
        code("StaticFiles"),
        " serves the files under ",
        code("web"),
        ", ",
        code("index.html"),
        " for ",
        code("/"),
        ", and it refuses paths that would reach outside the folder. ",
        "It runs on the JVM's built-in server, which needs JDK 24 or later:"
      ),
      CodeBlock("server/src/main/scala/Server.scala", serverCode),
      p(
        "One sbt command relinks the app and restarts the server whenever a file changes. ",
        code("uniRestart"),
        " runs the server in the background, so ",
        code("~"),
        " keeps watching:"
      ),
      CodeBlock("terminal", runCommands),
      p(
        "Open http://localhost:8080 and reload after each change. When the app needs an API, add a handler to the same server: ",
        docsLink("http/server", "REST server"),
        " and ",
        docsLink("http/rpc", "RPC"),
        ". For hot reload and npm packages, ",
        docsLink("book/ch13-00-vite", "bundle with Vite"),
        "; for a desktop app, follow the ",
        docsLink("http/electron-tutorial", "Electron tutorial"),
        "."
      ),
      div(
        cls -> "start-next",
        a(
          cls  -> "start-next__card",
          href -> "#/button",
          span(cls -> "start-next__title", "Components"),
          span(cls -> "start-next__hint", "Every uni-ui component, live next to its code")
        ),
        docsLink(
          "dom/",
          cls -> "start-next__card",
          span(cls -> "start-next__title", "Web UI docs"),
          span(cls -> "start-next__hint", "RxElement, the HTML DSL, Rx, theming, and testing")
        ),
        docsLink(
          "http/electron-tutorial",
          cls -> "start-next__card",
          span(cls -> "start-next__title", "Electron tutorial"),
          span(cls -> "start-next__hint", "The same views in a desktop app, with typed RPC")
        )
      )
    )
  )

end StartView

object StartView:
  /** The page's sections, for its table of contents: element id and title. */
  val steps: Seq[(String, String)] = Seq(
    "build"   -> "1. Build",
    "css"     -> "2. CSS",
    "imports" -> "3. Imports",
    "app"     -> "4. First app",
    "run"     -> "5. Run"
  )

  private def v: String = BuildInfo.uniVersion

  val buildProperties: String = s"sbt.version=${BuildInfo.sbtVersion}"

  val pluginsSbt: String =
    s"""resolvers += "wvlet" at "https://repo.wvlet.org/maven2"
       |addSbtPlugin("org.scala-js"  % "sbt-scalajs" % "${BuildInfo.scalaJsVersion}")
       |addSbtPlugin("org.wvlet.uni" % "sbt-uni"     % "${v}")""".stripMargin

  val buildSbt: String =
    s"""import org.scalajs.linker.interface.ModuleKind
       |
       |ThisBuild / scalaVersion := "${BuildInfo.scalaVersion}"
       |ThisBuild / resolvers += "wvlet" at "https://repo.wvlet.org/maven2"
       |
       |// The browser app, linked into web/js
       |lazy val app = project
       |  .enablePlugins(ScalaJSPlugin, UniPlugin)
       |  .settings(
       |    libraryDependencies ++= Seq(
       |      "org.wvlet.uni" %% "uni"    % "${v}",
       |      "org.wvlet.uni" %% "uni-ui" % "${v}"
       |    ),
       |    scalaJSUseMainModuleInitializer := true,
       |    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
       |    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := (ThisBuild / baseDirectory).value / "web" / "js",
       |    uniUiCssFile := (ThisBuild / baseDirectory).value / "web" / "uni-ui.css"
       |  )
       |
       |// The development server, which serves web/
       |lazy val server = project
       |  .enablePlugins(UniPlugin)
       |  .settings(
       |    libraryDependencies += "org.wvlet.uni" %% "uni" % "${v}",
       |    // Start from the build root, where StaticFiles("web") finds web/
       |    uniRestart / baseDirectory := (ThisBuild / baseDirectory).value
       |  )""".stripMargin

  val layout: String =
    """build.sbt
      |project/build.properties
      |project/plugins.sbt
      |app/src/main/scala/Main.scala        # the browser app
      |server/src/main/scala/Server.scala   # the development server
      |web/index.html
      |web/style.css                        # your own styles, after uni-ui.css
      |# sbt writes web/js/ and web/uni-ui.css""".stripMargin

  /** Where the uni-ui jar keeps its stylesheet */
  val UniUiCssResource: String = "wvlet/uni/ui/uni-ui.css"

  val cssLink: String =
    """<link rel="stylesheet" href="uni-ui.css" />
      |<link rel="stylesheet" href="style.css" />""".stripMargin

  val themeCss: String =
    """:root {
      |  --uni-accent: #6d28d9;
      |  --uni-accent-2: #8b5cf6;
      |  --uni-accent-soft: color-mix(in srgb, var(--uni-accent) 14%, transparent);
      |  --uni-radius: 4px;
      |}""".stripMargin

  val tailwindCss: String =
    """@layer theme, base, uni, components, utilities;
      |@import 'tailwindcss';
      |@import './uni-ui.css';""".stripMargin

  val imports: String =
    """import wvlet.uni.dom.all.*
      |import wvlet.uni.dom.all.given
      |import wvlet.uni.rx.Rx
      |import wvlet.uni.ui.*
      |import scala.language.implicitConversions""".stripMargin

  val indexHtml: String =
    """<!doctype html>
      |<html lang="en">
      |  <head>
      |    <meta charset="utf-8" />
      |    <title>Hello Uni</title>
      |    <link rel="stylesheet" href="uni-ui.css" />
      |    <link rel="stylesheet" href="style.css" />
      |  </head>
      |  <body>
      |    <div id="app"></div>
      |    <script type="module" src="js/main.js"></script>
      |  </body>
      |</html>""".stripMargin

  val serverCode: String =
    """import wvlet.uni.http.{Http, StaticFiles}
      |
      |object Server:
      |  def main(args: Array[String]): Unit =
      |    Http
      |      .server
      |      .withPort(8080)
      |      .withFilter(StaticFiles("web"))
      |      .start()
      |      .awaitTermination()""".stripMargin

  val runCommands: String = """sbt "~;app/fastLinkJS;server/uniRestart""""

end StartView
