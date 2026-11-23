package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.Clipboard
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.ColorScheme

import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

/** How a token's swatch is drawn: a fill, or a small card casting the token as its shadow. */
enum TokenKind:
  case Fill,
    Shadow

/** A uni-ui color variable (`--uni-<name>`) and what uses it. */
case class ColorToken(name: String, usage: String, kind: TokenKind = TokenKind.Fill):
  def variable: String = s"--uni-${name}"
  def ref: String      = s"var(${variable})"

/** A row of swatches: the tokens of one role. */
case class ColorGroup(id: String, title: String, summary: String, tokens: Seq[ColorToken])

/** The two themes uni-ui.css defines, as `data-theme` values. */
enum Theme:
  case Light,
    Dark

  def attr: String = toString.toLowerCase

object ColorsPage:
  val groups: Seq[ColorGroup] = Seq(
    ColorGroup(
      "backgrounds",
      "Backgrounds",
      "From the window down to raised controls.",
      Seq(
        ColorToken("canvas", "The app's window background, behind every panel (set it on <body>)"),
        ColorToken("surface", "Inputs, selects, tables, and the keyboard keys of Kbd"),
        ColorToken("panel", "Translucent panes: tab panels, FoldablePane, dock windows, StatusBar"),
        ColorToken("raised", "Default buttons, selected tabs, dock tabs, and other raised controls")
      )
    ),
    ColorGroup(
      "text",
      "Text",
      "From primary text to placeholders.",
      Seq(
        ColorToken("fg", "Primary text and icons"),
        ColorToken("muted", "Secondary text: hints, descriptions, inactive tabs"),
        ColorToken("faint", "Placeholders, drag grips, close buttons, and empty states")
      )
    ),
    ColorGroup(
      "lines",
      "Lines & fills",
      "Borders, hover fills, and focus rings.",
      Seq(
        ColorToken("line", "Borders and dividers"),
        ColorToken("hover", "The fill under a hovered row, tab, or button"),
        ColorToken("focus", "Keyboard focus rings on buttons, list rows, and table cells")
      )
    ),
    ColorGroup(
      "accent",
      "Accent",
      "The brand teal. Change accent and accent-2 to re-brand every component.",
      Seq(
        ColorToken("accent", "Primary buttons (gradient start) and the border of a focused field"),
        ColorToken("accent-2", "Primary buttons (gradient end)"),
        ColorToken("accent-fg", "Text and icons on the accent"),
        ColorToken(
          "accent-soft",
          "The halo of a focused field, selected rows, focused menu items, and accent badges"
        ),
        ColorToken("glow", "The dot of an accent badge; one value in both themes")
      )
    ),
    ColorGroup(
      "status",
      "Status",
      "Danger and success, each with a soft fill.",
      Seq(
        ColorToken(
          "danger",
          "Danger buttons, invalid fields, the edge of an error toast, and danger badges"
        ),
        ColorToken("danger-soft", "The halo of an invalid field and the fill of a danger badge"),
        ColorToken("success", "The edge of a success toast"),
        ColorToken(
          "success-soft",
          "A soft success fill for an app's own UI, the pair of danger-soft"
        )
      )
    ),
    ColorGroup(
      "overlays",
      "Overlays",
      "Menus, popovers, dialogs, and tooltips over the page.",
      Seq(
        ColorToken("backdrop", "The dim layer behind a Dialog"),
        ColorToken(
          "overlay-shadow",
          "The shadow of menus, popovers, selects, and dialogs",
          TokenKind.Shadow
        ),
        ColorToken("tooltip-bg", "Tooltip background"),
        ColorToken("tooltip-fg", "Tooltip text")
      )
    )
  )

  val tokens: Seq[ColorToken] = groups.flatMap(_.tokens)

  /** `--uni-*` variables that are not colors: stacking, radii, fonts, and motion. */
  def isColor(name: String): Boolean =
    !Seq("z-", "radius", "font", "duration", "overlay-shift").exists(name.startsWith)

  val page: GuidePage = GuidePage(
    "colors",
    "Colors",
    "Every uni-ui color variable, in light and dark, with what it is used for. Click a swatch to copy its var(), or a value to copy the value.",
    scheme => ColorsView(scheme)
  )

end ColorsPage

/**
  * Reads `--uni-*` variables as the cascade resolves them in each theme. A hidden frame per theme
  * loads the page's own stylesheets with `data-theme` forced on its `<html>`, so the values include
  * an app's overrides, and the page being viewed keeps its theme.
  */
class ThemeProbe(names: Seq[String]):
  private val vars = Rx.variable(Map.empty[Theme, Map[String, String]])

  /** The values read so far, by theme and variable name; empty until each frame loads. */
  def values: Rx[Map[Theme, Map[String, String]]] = vars
  private var frames                              = Seq.empty[dom.html.IFrame]

  private def styles: String =
    val nodes = dom.document.querySelectorAll("link[rel=stylesheet], style")
    (0 until nodes.length)
      .map(nodes(_))
      .map {
        case l: dom.html.Link =>
          s"""<link rel="stylesheet" href="${l.href}">"""
        case s =>
          s.outerHTML
      }
      .mkString

  def start(): Unit =
    val head = styles
    frames = Theme
      .values
      .toSeq
      .map { theme =>
        val f = dom.document.createElement("iframe").asInstanceOf[dom.html.IFrame]
        f.setAttribute("aria-hidden", "true")
        f.tabIndex = -1
        f.className = "theme-probe"
        f.addEventListener(
          "load",
          (_: dom.Event) =>
            val root = f.contentDocument.documentElement
            val cs   = f.contentWindow.getComputedStyle(root)
            val read = names.map(n => n -> cs.getPropertyValue(n).trim).toMap
            vars.update(_ + (theme -> read))
        )
        f.setAttribute(
          "srcdoc",
          s"""<!doctype html><html data-theme="${theme
              .attr}"><head>${head}</head><body></body></html>"""
        )
        dom.document.body.appendChild(f)
        f
      }

  def stop(): Unit =
    frames.foreach(_.remove())
    frames = Nil

end ThemeProbe

/**
  * The Colors page: a row of swatches per role, like Tailwind's color palette. Each swatch shows
  * the light theme on its left half and the dark theme on its right, read live by a [[ThemeProbe]].
  * The Light and Dark buttons in the header switch the gallery's color scheme, so the page around
  * the swatches can be seen in either theme.
  */
class ColorsView(scheme: RxVar[ColorScheme]) extends RxElement:
  private val probe   = ThemeProbe(ColorsPage.tokens.map(_.variable))
  private val copied  = Rx.variable(Option.empty[(String, Boolean)])
  private var timer   = 0
  private var mounted = false

  override def onMount(node: Any): Unit =
    mounted = true
    probe.start()

  override def beforeUnmount: Unit =
    mounted = false
    probe.stop()
    dom.window.clearTimeout(timer)

  private def copy(key: String, text: String): Unit =
    given ExecutionContext = ExecutionContext.global
    Clipboard
      .writeText(text)
      .onComplete { r =>
        // The copy may finish after the page is gone, e.g. behind a permission prompt
        if mounted then
          copied := Some((key, r.isSuccess))
          dom.window.clearTimeout(timer)
          timer = dom.window.setTimeout(() => copied := None, 1500)
      }

  /** "Copied" on a token's chip after copying its var() or one of its values. */
  private def status(name: String): Rx[String] = copied.map {
    case Some((k, ok)) if k == name || k.startsWith(s"${name}:") =>
      if ok then
        "Copied"
      else
        "Couldn't copy"
    case _ =>
      ""
  }

  private def half(t: ColorToken, theme: Theme, vs: Map[String, String]): RxElement =
    val v      = vs.getOrElse(t.variable, "")
    val canvas = vs.getOrElse("--uni-canvas", "transparent")
    t.kind match
      case TokenKind.Fill =>
        // Painted over the theme's canvas, so translucent fills look as they do in use
        span(
          cls                -> "swatch-half",
          data("theme-half") -> theme.attr,
          styleAttr          -> s"background: linear-gradient(${v}, ${v}), ${canvas}"
        )
      case TokenKind.Shadow =>
        span(
          cls                -> "swatch-half",
          data("theme-half") -> theme.attr,
          styleAttr          -> s"background: ${canvas}",
          span(
            cls       -> "swatch-shadow",
            styleAttr ->
              s"box-shadow: ${v}; background: ${vs.getOrElse("--uni-raised", "transparent")}"
          )
        )

  private def swatch(t: ColorToken): RxElement =
    val vs = probe.values
    div(
      cls           -> "swatch",
      data("token") -> t.name,
      button(
        cls          -> "swatch-chip",
        data("copy") -> t.ref,
        titleAttr    -> s"Copy ${t.ref}",
        ariaLabel    -> s"Copy ${t.ref}",
        onclick      -> { () =>
          copy(t.name, t.ref)
        },
        vs.map(m => Theme.values.toSeq.map(th => half(t, th, m.getOrElse(th, Map.empty)))),
        span(cls -> "swatch-status", status(t.name))
      ),
      code(cls -> "swatch-name", t.variable),
      div(
        cls -> "swatch-values",
        vs.map(m =>
          Theme
            .values
            .toSeq
            .map { th =>
              val v = m.get(th).flatMap(_.get(t.variable)).filter(_.nonEmpty)
              button(
                cls                 -> "swatch-value",
                data("copy")        -> v.getOrElse(""),
                data("theme-value") -> th.attr,
                titleAttr           -> s"Copy the ${th.attr} value: ${v.getOrElse("")}",
                disabled            -> v.isEmpty,
                onclick             -> { () =>
                  v.foreach(copy(s"${t.name}:${th.attr}", _))
                },
                th match
                  case Theme.Light =>
                    Icons.sun
                  case Theme.Dark =>
                    Icons.moon
                ,
                span(v.getOrElse("—"))
              )
            }
        )
      ),
      p(cls -> "swatch-usage", t.usage)
    )

  end swatch

  /**
    * Shows the gallery in `s`, pressed while it is the chosen scheme. Pressing it again goes back
    * to following the OS setting.
    */
  private def schemeButton(s: ColorScheme, icon: RxElement): RxElement = button(
    cls           -> "colors-scheme",
    data("theme") -> s.toString.toLowerCase,
    ariaPressed   -> scheme.map(c => (c == s).toString),
    titleAttr     ->
      scheme.map(c =>
        if c == s then
          "Follow the system color scheme"
        else
          s"Show the gallery in ${s.label.toLowerCase}"
      ),
    onclick -> { () =>
      scheme.update(c =>
        if c == s then
          ColorScheme.System
        else
          s
      )
    },
    icon,
    s.label
  )

  override def render: RxElement = div(
    cls -> "page colors-page",
    PageHeader(
      "Colors",
      ColorsPage.page.summary,
      ColorsPage.groups.map(g => PageHeader.Link(g.id, g.title, ColorsPage.page.id)),
      overline = Seq("Foundations", "Colors"),
      trailing = Seq(
        div(
          cls       -> "colors-legend",
          role      -> "group",
          ariaLabel -> "Color scheme",
          schemeButton(ColorScheme.Light, Icons.sun),
          schemeButton(ColorScheme.Dark, Icons.moon)
        ),
        a(
          cls    -> "story-link",
          href   -> "https://wvlet.org/uni/dom/theming",
          target -> "_blank",
          Icons.book,
          "Theming"
        )
      )
    ),
    ColorsPage
      .groups
      .map(g =>
        section(
          cls             -> "colors-group",
          id              -> s"colors-${g.id}",
          data("section") -> g.id,
          div(
            cls -> "example-head",
            h2(cls -> "example-title", g.title),
            p(cls  -> "example-hint", g.summary)
          ),
          div(cls -> "swatch-row", g.tokens.map(swatch))
        )
      )
  )

end ColorsView
