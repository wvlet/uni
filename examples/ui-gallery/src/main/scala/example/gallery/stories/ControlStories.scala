package example.gallery.stories

import example.gallery.Story
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*

import scala.language.implicitConversions

/**
  * Each story is one `def` inside a `// #region <id>` block. The gallery shows that block as the
  * story's code, so keep everything the demo needs inside it.
  */
object ControlStories:

  // #region controls/buttons
  def buttons: RxElement =
    val clicks = Rx.variable(0)
    val busy   = Rx.variable(false)

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-row",
        Button("Default").onClick(() => clicks.update(_ + 1)),
        Button("Primary").primary.onClick(() => clicks.update(_ + 1)),
        Button("Ghost").ghost.onClick(() => clicks.update(_ + 1)),
        Button("Danger").danger.onClick(() => clicks.update(_ + 1)),
        Button("Small").small.onClick(() => clicks.update(_ + 1))
      ),
      div(
        cls -> "demo-row",
        Button("Save").primary.withDisabled(busy).onClick(() => busy := true),
        Button("Reset").ghost.onClick(() => busy := false),
        span(
          cls -> "demo-note",
          busy.map(b =>
            if b then
              "Saving…"
            else
              "Idle"
          )
        )
      ),
      p(cls -> "demo-note", clicks.map(n => s"Clicked ${n} times"))
    )
  end buttons
  // #endregion

  // #region controls/text-fields
  def textFields: RxElement =
    val name  = Rx.variable("")
    val notes = Rx.variable("")

    div(
      cls -> "demo-stack demo-narrow",
      Input(name)
        .withLabel("Name")
        .withPlaceholder("Ada Lovelace")
        .withInvalid(name.map(_.isEmpty)),
      Textarea(notes).withLabel("Notes").withRows(3).withPlaceholder("Anything else?"),
      p(
        cls -> "demo-note",
        name.map(n =>
          if n.isEmpty then
            "Name is required"
          else
            s"Hello, ${n}!"
        )
      )
    )
  // #endregion

  // #region controls/tabs
  def tabs: RxElement =
    enum Pane:
      case Overview
      case Activity
      case Settings

    val selected = Rx.variable(Pane.Overview)
    val list     = TabList(
      selected,
      label = "Project",
      tabs = Seq(
        Tab(Pane.Overview, "Overview"),
        Tab(Pane.Activity, "Activity", Badge("3").withTone(BadgeTone.Accent)),
        Tab(Pane.Settings, "Settings")
      )
    )

    div(
      cls -> "demo-stack",
      list,
      TabPanel(list, render = pane => div(cls -> "demo-panel", s"The ${pane} tab's content")),
      p(
        cls -> "demo-note",
        "Focus a tab, then press ← →, Home, or End. Press ",
        Kbd("Tab"),
        " to leave."
      )
    )
  // #endregion

  val all: Seq[Story] = Seq(
    Story(
      "controls/buttons",
      "Buttons",
      "Variants, sizes, and a disabled state bound to an Rx.",
      () => buttons
    ),
    Story(
      "controls/text-fields",
      "Text fields",
      "Input and Textarea, bound both ways to an RxVar[String].",
      () => textFields
    ),
    Story(
      "controls/tabs",
      "Tabs",
      "TabList and TabPanel over an enum, with keyboard navigation.",
      () => tabs
    )
  )

end ControlStories
