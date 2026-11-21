package example.gallery

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given

import scala.language.implicitConversions

/** The Uni logo and small stroke icons drawn with the SVG DSL, as in uni-harness. */
object Icons:

  def logo: RxElement = img(src -> "uni-logo.svg", alt -> "Uni", cls -> "gallery-logo")

  private def icon(children: RxElement*): RxElement = svg(
    viewBox        -> "0 0 24 24",
    fill           -> "none",
    stroke         -> "currentColor",
    strokeWidth    -> "1.7",
    strokeLinecap  -> "round",
    strokeLinejoin -> "round",
    cls            -> "icon",
    children
  )

  private def p(data: String): RxElement = path(d -> data)

  def controls: RxElement = icon(
    rect(x    -> 3.5, y   -> 7, width -> 17, height -> 10, rx -> 5),
    circle(cx -> 15.5, cy -> 12, r    -> 2.5)
  )

  def overlays: RxElement = icon(
    rect(x -> 3.5, y -> 3.5, width -> 12, height -> 12, rx -> 2.5),
    p("M8.5 20.5h9.5a2.5 2.5 0 0 0 2.5-2.5V8.5")
  )

  def collections: RxElement = icon(
    p("M8.5 6.5h12M8.5 12h12M8.5 17.5h12M4 6.5h.01M4 12h.01M4 17.5h.01")
  )

  def layout: RxElement = icon(
    rect(x -> 3.5, y -> 4.5, width -> 17, height -> 15, rx -> 2.5),
    p("M9.5 4.5v15M9.5 12h11")
  )

  def search: RxElement = icon(circle(cx -> 11, cy -> 11, r -> 6.5), p("M20 20l-4.2-4.2"))
  def eye: RxElement    = icon(
    p("M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z"),
    circle(cx -> 12, cy -> 12, r -> 2.8)
  )

  def code: RxElement  = icon(p("M9 8l-4 4 4 4M15 8l4 4-4 4"))
  def split: RxElement = icon(
    rect(x -> 3.5, y -> 4.5, width -> 17, height -> 15, rx -> 2.5),
    p("M12 4.5v15")
  )

  def copy: RxElement = icon(
    rect(x -> 8.5, y -> 8.5, width -> 11, height -> 11, rx -> 2.2),
    p("M15.5 5.5A2 2 0 0 0 13.5 4.5h-7a2 2 0 0 0-2 2v7a2 2 0 0 0 1 1.7")
  )

  def check: RxElement    = icon(p("M5 12.5l4.5 4.5L19 7.5"))
  def external: RxElement = icon(
    p("M13.5 4.5h6v6M19.5 4.5L11 13M17.5 14v4a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h4")
  )

  def book: RxElement = icon(
    p("M4.5 5.5A2 2 0 0 1 6.5 3.5h13v14h-13a2 2 0 0 0-2 2z"),
    p("M4.5 19.5a2 2 0 0 0 2 2h13v-4")
  )

  def file: RxElement = icon(
    p("M14 3.5H7a1.5 1.5 0 0 0-1.5 1.5v14A1.5 1.5 0 0 0 7 20.5h10a1.5 1.5 0 0 0 1.5-1.5V8z"),
    p("M14 3.5V8h4.5")
  )

  def panelLeft: RxElement = icon(
    rect(x -> 3.5, y -> 4.5, width -> 17, height -> 15, rx -> 2.5),
    p("M9.5 4.5v15")
  )

  def sun: RxElement = icon(
    circle(cx -> 12, cy -> 12, r -> 3.8),
    p(
      "M12 2.8v2M12 19.2v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2.8 12h2M19.2 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"
    )
  )

  def moon: RxElement    = icon(p("M19.5 14.6A7.8 7.8 0 1 1 9.4 4.5a6.2 6.2 0 0 0 10.1 10.1z"))
  def monitor: RxElement = icon(
    rect(x -> 3, y -> 4.5, width -> 18, height -> 12, rx -> 2),
    p("M8.5 20h7M12 16.5V20")
  )

  def github: RxElement = icon(
    p(
      "M9 19c-4.3 1.4-4.3-2.5-6-3m12 5v-3.5c0-1 .1-1.4-.5-2 2.8-.3 5.5-1.4 5.5-6a4.6 4.6 0 0 0-1.3-3.2 4.2 4.2 0 0 0-.1-3.2s-1.1-.3-3.5 1.3a12.3 12.3 0 0 0-6.2 0C6.5 2.8 5.4 3.1 5.4 3.1a4.2 4.2 0 0 0-.1 3.2A4.6 4.6 0 0 0 4 9.5c0 4.6 2.7 5.7 5.5 6-.6.6-.6 1.2-.5 2V21"
    )
  )

  def sparkle: RxElement = icon(
    p("M12 3.5l1.7 4.8 4.8 1.7-4.8 1.7L12 16.5l-1.7-4.8L5.5 10l4.8-1.7z"),
    p("M18.5 15.5l.6 1.6 1.6.6-1.6.6-.6 1.6-.6-1.6-1.6-.6 1.6-.6z")
  )

  def crosshair: RxElement = icon(
    circle(cx -> 12, cy -> 12, r -> 7.5),
    p("M12 2.5v4M12 17.5v4M2.5 12h4M17.5 12h4")
  )

  def sliders: RxElement = icon(
    p("M4 7h9M17 7h3M4 12h3M11 12h9M4 17h11M19 17h1"),
    circle(cx -> 15, cy -> 7, r  -> 2),
    circle(cx -> 9, cy  -> 12, r -> 2),
    circle(cx -> 17, cy -> 17, r -> 2)
  )

  /** The icon of a sidebar section. */
  def forGroup(id: String): RxElement =
    id match
      case "controls" =>
        controls
      case "overlays" =>
        overlays
      case "collections" =>
        collections
      case _ =>
        layout

end Icons
