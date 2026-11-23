package example.gallery.stories

import example.gallery.{ComponentPage, Story}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*

import scala.language.implicitConversions

/**
  * How uni-ui's look changes through CSS alone. uni-ui.css reads every color, radius, and font from
  * a `--uni-*` variable, so setting the variables on an element restyles the components inside it.
  * The examples set them inline to keep each one self-contained; an app usually sets them once on
  * `:root` in its stylesheet.
  */
object ThemingStories:

  // #region theming/accent
  case class Brand(name: String, accent: String, accent2: String)

  inline def themingAccent: RxElement =
    val brands = Seq(
      Brand("Teal", "#2e7685", "#3c99ad"),
      Brand("Violet", "#6d28d9", "#8b5cf6"),
      Brand("Rose", "#be123c", "#f43f5e"),
      Brand("Amber", "#b45309", "#f59e0b")
    )
    val brand = Rx.variable(brands(1))
    val tab   = Rx.variable("Inbox")

    div(
      cls -> "demo-stack",
      // The variables apply to everything inside this element. color-mix derives the halo and
      // the focus ring from the accent, so one color drives them all.
      styleAttr ->
        brand.map(b =>
          s"--uni-accent: ${b.accent}; --uni-accent-2: ${b.accent2}; " +
            "--uni-accent-soft: color-mix(in srgb, var(--uni-accent) 14%, transparent); " +
            "--uni-focus: color-mix(in srgb, var(--uni-accent) 45%, transparent); " +
            "--uni-hover: color-mix(in srgb, var(--uni-accent) 7%, transparent)"
        ),
      div(cls -> "demo-row", brands.map(b => Button(b.name).small.onClick(() => brand := b))),
      TabList(
        tab,
        label = "Folders",
        tabs = Seq(
          Tab("Inbox", "Inbox", Badge("4").withTone(BadgeTone.Accent)),
          Tab("Sent", "Sent"),
          Tab("Archive", "Archive")
        )
      ),
      div(
        cls -> "demo-row",
        Input(Rx.variable("")).withPlaceholder("Focus me to see the halo"),
        Button("Send").primary
      ),
      p(cls -> "demo-note", brand.map(b => s"--uni-accent: ${b.accent}"))
    )
  end themingAccent
  // #endregion

  // #region theming/palettes
  inline def themingPalettes: RxElement =
    // Each card overrides the surface, text, and accent variables
    def card(name: String, vars: String): RxElement = div(
      styleAttr -> (
        s"${vars}; display: flex; flex-direction: column; gap: 10px; padding: 14px; " +
          "background: var(--uni-surface); color: var(--uni-fg); " +
          "border: 1px solid var(--uni-line); border-radius: var(--uni-radius-lg)"
      ),
      strong(name),
      Input(Rx.variable("")).withPlaceholder("Search"),
      div(
        cls -> "demo-row",
        Button("Save").primary.small,
        Button("Cancel").ghost.small,
        Badge("New").withTone(BadgeTone.Accent)
      )
    )

    div(
      styleAttr ->
        "display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px",
      card(
        "Ocean",
        "--uni-surface: #f0f7fb; --uni-panel: #e3eff6; --uni-raised: #ffffff; --uni-line: #c4dbe8; " +
          "--uni-canvas: #e3eff6; --uni-fg: #0b2a3a; --uni-muted: #48697a; --uni-accent: #0369a1; " +
          "--uni-accent-2: #0ea5e9; " +
          "--uni-accent-soft: color-mix(in srgb, var(--uni-accent) 14%, transparent); " +
          "--uni-focus: color-mix(in srgb, var(--uni-accent) 45%, transparent)"
      ),
      card(
        "Forest",
        "--uni-surface: #f3f7f1; --uni-panel: #e6eee2; --uni-raised: #ffffff; --uni-line: #cbdac4; " +
          "--uni-canvas: #e6eee2; --uni-fg: #1a2e16; --uni-muted: #557050; --uni-accent: #15803d; " +
          "--uni-accent-2: #22c55e; " +
          "--uni-accent-soft: color-mix(in srgb, var(--uni-accent) 14%, transparent); " +
          "--uni-focus: color-mix(in srgb, var(--uni-accent) 45%, transparent)"
      ),
      card(
        "Midnight",
        "color-scheme: dark; --uni-canvas: #0f1320; --uni-surface: #141826; --uni-panel: #1b2033; --uni-raised: #232a40; " +
          "--uni-line: #2f3752; --uni-hover: rgb(255 255 255 / 0.06); --uni-fg: #e6e9f5; " +
          "--uni-muted: #9aa3c0; --uni-faint: #6b7392; --uni-accent: #818cf8; " +
          "--uni-accent-2: #a5b4fc; --uni-accent-fg: #0b1020; " +
          "--uni-accent-soft: color-mix(in srgb, var(--uni-accent) 14%, transparent); " +
          "--uni-focus: color-mix(in srgb, var(--uni-accent) 45%, transparent)"
      )
    )
  end themingPalettes
  // #endregion

  // #region theming/shape
  case class Shape(name: String, vars: String)

  inline def themingShape: RxElement =
    val shapes = Seq(
      Shape("Sharp", "--uni-radius-sm: 0; --uni-radius: 0; --uni-radius-lg: 0"),
      Shape("Default", ""),
      Shape("Round", "--uni-radius-sm: 8px; --uni-radius: 14px; --uni-radius-lg: 20px"),
      Shape("Large text", "--uni-font-size: 16px; --uni-font-size-sm: 14px")
    )
    val shape = Rx.variable(shapes(2))

    div(
      cls -> "demo-stack",
      div(cls -> "demo-row", shapes.map(s => Button(s.name).small.onClick(() => shape := s))),
      div(
        cls       -> "demo-stack demo-narrow",
        styleAttr -> shape.map(_.vars),
        Input(Rx.variable("Ada Lovelace")).withLabel("Name"),
        div(cls -> "demo-row", Button("Save").primary, Button("Cancel"), Badge("Draft"))
      ),
      p(
        cls -> "demo-note",
        shape.map(s =>
          if s.vars.isEmpty then
            "uni-ui.css defaults"
          else
            s.vars
        )
      )
    )
  end themingShape
  // #endregion

  // #region theming/parts
  inline def themingParts: RxElement =
    val tab = Rx.variable("Overview")

    div(
      cls -> "demo-stack parts-demo",
      // Select a component's parts by data-slot, and their state by ARIA or data-state attributes
      style(""".parts-demo [data-slot='button'] { text-transform: uppercase; letter-spacing: 0.06em; }
          |.parts-demo [data-slot='badge'] { border-radius: 999px; }
          |.parts-demo [data-slot='tab'][aria-selected='true'] { color: var(--uni-accent); font-weight: 600; }
          |""".stripMargin),
      TabList(
        tab,
        label = "Sections",
        tabs = Seq(
          Tab("Overview", "Overview"),
          Tab("Activity", "Activity"),
          Tab("Settings", "Settings")
        )
      ),
      div(
        cls -> "demo-row",
        Button("Deploy").primary,
        Button("Logs"),
        Badge("3 failing").withTone(BadgeTone.Danger)
      )
    )
  end themingParts
  // #endregion

  val pages: Seq[ComponentPage] = Seq(
    ComponentPage(
      "theming",
      "Theming",
      "uni-ui.css reads every color, radius, and font from a --uni-* CSS variable. Set the variables on :root to theme the whole app, or on one element to theme only what is inside it.",
      Seq(
        Story(
          "theming/accent",
          "Your brand color",
          "Pick a color: the primary button, the badge, and the focus halo of the field all follow --uni-accent."
        )(themingAccent),
        Story(
          "theming/palettes",
          "Palettes side by side",
          "Each card sets its own surface, text, and accent variables, so one page can hold differently themed regions."
        )(themingPalettes),
        Story(
          "theming/shape",
          "Corners and text size",
          "Switch between Sharp, Round, and Large text: radii and font sizes are variables too."
        )(themingShape),
        Story(
          "theming/parts",
          "Style one part",
          "The buttons are uppercase and the selected tab is bold, through data-slot and aria-selected selectors in plain CSS."
        )(themingParts)
      ),
      api = Nil,
      topic = true,
      docs = "dom/theming"
    )
  )

end ThemingStories
