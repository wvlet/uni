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

  private var root: Option[dom.Element] = None

  override def onMount(node: Any): Unit = root = Some(node.asInstanceOf[dom.Element])

  private def scrollTo(e: dom.Event, sectionId: String): Unit =
    e.preventDefault()
    root.flatMap(r => Option(r.querySelector(s"#${sectionId}"))).foreach(_.scrollIntoView(true))

  private def step(n: Int, sectionId: String, title: String, body: DomNode*): RxElement = section(
    cls -> "start-step",
    id  -> sectionId,
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
    div(
      cls -> "story-hero",
      div(
        cls -> "story-hero__inner",
        h1(cls -> "story-title", page.name),
        p(cls  -> "story-summary", page.summary),
        div(
          cls -> "story-meta",
          div(
            cls -> "page-toc",
            steps.map((sectionId, title) =>
              a(
                cls     -> "page-toc__link",
                href    -> s"#/${page.id}",
                onclick -> { (e: dom.Event) =>
                  scrollTo(e, sectionId)
                },
                title
              )
            )
          ),
          div(cls -> "story-meta__spacer"),
          a(
            cls    -> "story-link start-version",
            href   -> "https://github.com/wvlet/uni/releases",
            target -> "_blank",
            rel    -> "noopener",
            s"Uni ${BuildInfo.uniVersion} release notes"
          )
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
        "The sbt-uni plugin copies ",
        code("uni-ui.css"),
        " out of the uni-ui jar on every compile, so the stylesheet matches the version you link."
      ),
      CodeBlock("project/build.properties", buildProperties),
      CodeBlock("project/plugins.sbt", pluginsSbt),
      CodeBlock("build.sbt", buildSbt),
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
        "Load the stylesheet once, before your own. Its rules sit in the ",
        code("uni"),
        " cascade layer, so unlayered app CSS always wins over them."
      ),
      CodeBlock("web/index.html", cssLink),
      p(
        "With Vite, import it from your stylesheet instead. If your app uses other layers, such as Tailwind CSS v4's, declare their order first:"
      ),
      CodeBlock("style.css", tailwindCss)
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
      CodeBlock("src/main/scala/Main.scala", imports)
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
      p("Keep sbt relinking as you edit, and serve the web folder from a second terminal:"),
      CodeBlock("terminal", runCommands),
      p(
        "Open http://localhost:8080 and reload after each change. For hot reload and npm packages, ",
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
       |lazy val app = project
       |  .in(file("."))
       |  .enablePlugins(ScalaJSPlugin, UniPlugin)
       |  .settings(
       |    libraryDependencies ++= Seq(
       |      "org.wvlet.uni" %% "uni"    % "${v}",
       |      "org.wvlet.uni" %% "uni-ui" % "${v}"
       |    ),
       |    scalaJSUseMainModuleInitializer := true,
       |    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
       |    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "web" / "js",
       |    uniUiCssFile := baseDirectory.value / "web" / "uni-ui.css"
       |  )""".stripMargin

  val cssLink: String = """<link rel="stylesheet" href="uni-ui.css" />"""

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
      |  </head>
      |  <body>
      |    <div id="app"></div>
      |    <script type="module" src="js/main.js"></script>
      |  </body>
      |</html>""".stripMargin

  val runCommands: String =
    """sbt ~fastLinkJS
      |python3 -m http.server -d web 8080""".stripMargin

end StartView
