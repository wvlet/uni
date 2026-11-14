/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.ui

import org.scalajs.dom
import wvlet.uni.dom.{DomRenderer, RxElement, SizeRange}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.fireEvent
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest
import wvlet.uni.test.playwright.Playwright

import scala.language.implicitConversions

/**
  * Visual regression tests for the components styled by `uni-ui.css`, in the light and dark themes.
  * Each gallery is compared with the golden `<group>-<theme>-chromium-<os>.png` in
  * `src/test/screenshots`. Text depends on the OS fonts, so goldens are kept per OS.
  *
  * Update goldens with `UNI_PLAYWRIGHT_UPDATE_SNAPSHOTS=1 ./sbt ui/test`.
  */
class VisualTest extends UniTest:

  private val themes = Seq(ColorScheme.Light, ColorScheme.Dark)

  // The sbt build embeds uni-ui.css as UniUiCss.text. Remove it afterwards, since other test
  // classes share the page.
  private val stylesheet = dom.document.createElement("style")
  stylesheet.textContent = UniUiCss.text

  override protected def beforeAll: Unit = dom.document.head.appendChild(stylesheet)
  override protected def afterAll: Unit  = dom.document.head.removeChild(stylesheet)

  /** A fixed-width card on the theme's surface color, so the shot includes the background. */
  private def gallery(content: DomNode*): RxElement = div(
    id    -> "gallery",
    style :=
      "display: inline-flex; flex-direction: column; gap: 12px; width: 360px; padding: 16px; " +
        "background: var(--uni-surface); color: var(--uni-fg); font-family: var(--uni-font-sans);",
    content
  )

  private def row(content: DomNode*): RxElement = div(
    style := "display: flex; align-items: center; gap: 8px;",
    content
  )

  /**
    * Register one test per theme that mounts the gallery and compares it with `<name>-<theme>`.
    * `prepare` runs after mounting, to put a component into a state only events reach (such as a
    * drag), and `release` undoes it once the shot is taken.
    */
  private def visual(
      name: String,
      prepare: dom.Element => Unit = _ => (),
      release: () => Unit = () => ()
  )(elem: => RxElement): Unit = themes.foreach { theme =>
    val themeName = theme.toString.toLowerCase
    test(s"${name} (${themeName})") {
      ColorScheme.applyTo(dom.document.documentElement, theme)
      val (node, c) = DomRenderer.createNode(elem)
      dom.document.body.appendChild(node)
      // Clean up in transformRx even when prepare throws, so the next test finds one #gallery
      Rx.single(prepare(node.asInstanceOf[dom.Element]))
        .flatMap(_ => Playwright.expectScreenshot(s"${name}-${themeName}", selector = "#gallery"))
        .transformRx { result =>
          release()
          c.cancel
          dom.document.body.removeChild(node)
          ColorScheme.applyTo(dom.document.documentElement, ColorScheme.System)
          Rx.fromTry(result)
        }
    }
  }

  visual("button") {
    gallery(
      row(Button("Default"), Button("Primary").primary, Button("Ghost").ghost),
      row(Button("Danger").danger, Button("Disabled").withDisabled(true)),
      row(
        Button("Small").small,
        Button("Small").primary.small,
        IconButton("Close", "×"),
        IconButton("Close", "×").small
      )
    )
  }

  visual("text-field") {
    gallery(
      Input(Rx.variable("")).withPlaceholder("Placeholder"),
      Input(Rx.variable("Some text")),
      Input(Rx.variable("Invalid")).withInvalid(Rx.const(true)),
      Input(Rx.variable("Disabled")).withDisabled(true),
      Textarea(Rx.variable("Line 1\nLine 2")).withRows(3),
      Textarea(Rx.variable("Invalid")).withRows(2).withInvalid(Rx.const(true)),
      Textarea(Rx.variable("Disabled")).withRows(2).withDisabled(true)
    )
  }

  private enum Pick:
    case Output,
      Calls,
      Logs

  visual("tabs") {
    val tabs = TabList(
      Rx.variable(Pick.Calls),
      label = "Views",
      tabs = Seq(
        Tab(Pick.Output, "Output"),
        Tab(Pick.Calls, "Calls", Badge("3")),
        Tab(Pick.Logs, "Logs")
      )
    )
    gallery(tabs, TabPanel(tabs, render = p => div(s"Content of ${p}")))
  }

  // Press the grip so the splitter shows its line, and release it after the shot
  private val range = SizeRange(min = 80, default = 120, max = 200)
  visual(
    "panes",
    prepare =
      root =>
        root
          .querySelectorAll("[role=separator]")
          .foreach(g => fireEvent.pointerDown(g, clientX = 0)),
    release = () => fireEvent.pointerUp(dom.window, clientX = 0)
  ) {
    val width = Rx.variable(120)
    gallery(
      div(
        style := "display: flex; height: 120px; border: 1px solid var(--uni-line);",
        FoldablePane(
          Rx.const(true),
          width = width,
          edge = Edge.Right,
          content = Seq(div("Open pane"))
        ).withSplitter(Splitter(width, range, Edge.Right, "Resize")),
        div(style := "flex: 1; padding: 8px;", "Main"),
        FoldablePane(
          Rx.const(false),
          width = Rx.const(120),
          edge = Edge.Left,
          content = Seq(div("Closed pane"))
        )
      )
    )
  }

  visual("display") {
    gallery(
      row(
        Badge("3"),
        Badge("12").withTone(BadgeTone.Accent),
        Badge("!").withTone(BadgeTone.Danger),
        Badge.dot("New output"),
        Badge.dot("Error").withTone(BadgeTone.Danger)
      ),
      row(Kbd("⌘"), Kbd("Shift"), Kbd("K")),
      StatusBar(span("Ready"), StatusBar.spacer, Kbd("⌘"), Kbd("K"), span("UTF-8"))
    )
  }

  private case class Entry(name: String, kids: Seq[Entry] = Seq.empty)
  private case class Call(method: String, millis: Double)

  visual("collections") {
    val files = Seq(
      Entry("src", Seq(Entry("Main.scala"), Entry("Util.scala"))),
      Entry("docs", Seq(Entry("index.md"))),
      Entry("build.sbt")
    )
    gallery(
      VirtualList(
        Rx.const((1 to 50).toSeq),
        rowHeight = 28,
        key = identity,
        render = i => span(s"Item ${i}")
      ).withSelected(Rx.variable(Some(2)))(styleAttr += "height: 96px"),
      Tree(
        Rx.const(files),
        children = (e: Entry) => e.kids,
        key = _.name,
        render = e => span(e.name)
      ).withExpanded(Rx.variable(Set("src")))
        .withSelected(Rx.variable(Some("Util.scala")))(styleAttr += "height: 140px"),
      DataTable(
        Rx.const(Seq(Call("listConversations", 4.2), Call("send", 12.5), Call("get", 1.5))),
        key = _.method,
        columns = Seq(
          Column[Call]("method", "Method", cell = c => span(c.method)).sortBy(_.method),
          Column[Call]("millis", "Time", cell = c => span(f"${c.millis}%.1f ms"))
            .sortBy(_.millis)
            .withWidth("96px")
            .alignEnd
        )
      ).withSort(Rx.variable(Some(SortBy("millis", SortDirection.Descending))))(
        styleAttr += "height: 132px"
      )
    )
  }

  visual("dock") {
    def text(t: String): RxElement = div(
      style := "padding: 8px 12px; color: var(--uni-muted); font-size: var(--uni-font-size-sm);",
      t
    )
    val layout = Rx.variable(
      DockLayout(
        DockNode.row(
          DockNode.group("details", "activity"),
          DockNode.column(DockNode.group("terminal"), DockNode.group("log", "problems"))
        )
      )
    )
    gallery(
      div(
        style := "display: flex; height: 220px; border: 1px solid var(--uni-line); " +
          "border-radius: var(--uni-radius-lg); overflow: hidden; background: var(--uni-panel);",
        Dock(
          layout,
          label = "Dock",
          panels = Seq(
            DockPanel("details", title = "Details", content = text("Conversation details")),
            DockPanel("activity", title = "Activity", content = text("RPC calls")),
            DockPanel("terminal", title = "Terminal", content = text("$ sbt test")).withClosable(
              false
            ),
            DockPanel("log", title = "Log", content = text("Server started")),
            DockPanel("problems", title = "Problems", content = text("No problems"))
          )
        )
      )
    )
  }

end VisualTest
