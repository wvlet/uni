package example.renderer

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given

import scala.language.implicitConversions

/**
  * The Uni logo (from the docs site, `src/renderer/public/uni-logo.svg`) and small stroke icons
  * drawn with the SVG DSL, so the example needs no icon package.
  */
object Icons:

  /** Uni's unicorn logo. A relative URL works from the Vite dev server and from `file://`. */
  def logo(classes: String): RxElement = img(src -> "uni-logo.svg", alt -> "Uni", cls -> classes)

  private def icon(classes: String, children: RxElement*): RxElement = svg(
    viewBox        -> "0 0 24 24",
    fill           -> "none",
    stroke         -> "currentColor",
    strokeWidth    -> "1.7",
    strokeLinecap  -> "round",
    strokeLinejoin -> "round",
    cls            -> classes,
    children
  )

  private def i(children: RxElement*): RxElement = icon("size-[18px] shrink-0", children*)
  private def p(data: String): RxElement         = path(d -> data)

  def chat: RxElement = i(
    p("M20 14.5a2.5 2.5 0 0 1-2.5 2.5H9l-4.5 3.5V6.5A2.5 2.5 0 0 1 7 4h10.5A2.5 2.5 0 0 1 20 6.5z"),
    p("M8.5 9h7M8.5 12.5h4.5")
  )

  def folder: RxElement = i(
    p("M3.5 7.5A2 2 0 0 1 5.5 5.5h3.6l2 2h7.4a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z")
  )

  def file: RxElement = i(
    p("M14 3.5H7a1.5 1.5 0 0 0-1.5 1.5v14A1.5 1.5 0 0 0 7 20.5h10a1.5 1.5 0 0 0 1.5-1.5V8z"),
    p("M14 3.5V8h4.5")
  )

  def code: RxElement = i(p("M9 8l-4 4 4 4M15 8l4 4-4 4"))

  def sliders: RxElement = i(
    p("M4 7h9M17 7h3M4 12h3M11 12h9M4 17h11M19 17h1"),
    circle(cx -> 15, cy -> 7, r  -> 2),
    circle(cx -> 9, cy  -> 12, r -> 2),
    circle(cx -> 17, cy -> 17, r -> 2)
  )

  def panelLeft: RxElement = i(
    rect(x -> 3.5, y -> 4.5, width -> 17, height -> 15, rx -> 2.5),
    p("M9.5 4.5v15")
  )

  def panelRight: RxElement = i(
    rect(x -> 3.5, y -> 4.5, width -> 17, height -> 15, rx -> 2.5),
    p("M14.5 4.5v15")
  )

  def plus: RxElement   = i(p("M12 5.5v13M5.5 12h13"))
  def search: RxElement = i(circle(cx -> 11, cy -> 11, r -> 6.5), p("M20 20l-4.2-4.2"))
  def copy: RxElement   = i(
    rect(x -> 8.5, y -> 8.5, width -> 11, height -> 11, rx -> 2.2),
    p("M15.5 5.5A2 2 0 0 0 13.5 4.5h-7a2 2 0 0 0-2 2v7a2 2 0 0 0 1 1.7")
  )

  def check: RxElement    = i(p("M5 12.5l4.5 4.5L19 7.5"))
  def activity: RxElement = i(p("M3 12h4l2.5-6.5 5 13L17 12h4"))
  def info: RxElement     = i(circle(cx -> 12, cy -> 12, r -> 8.5), p("M12 11v5M12 8h.01"))
  def bolt: RxElement     = i(p("M13 3.5L5.5 13.5H11l-1 7 7.5-10H12z"))
  def layers: RxElement   = i(
    p("M12 4l8.5 4.5L12 13 3.5 8.5z"),
    p("M3.5 12.5L12 17l8.5-4.5"),
    p("M3.5 16.5L12 21l8.5-4.5")
  )

  def clip: RxElement = i(
    p(
      "M20 11.5l-7.8 7.8a4.6 4.6 0 0 1-6.5-6.5l8-8a3 3 0 0 1 4.3 4.3l-7.9 7.9a1.5 1.5 0 0 1-2.2-2.2l7.2-7.2"
    )
  )

  def chevron: RxElement = icon("size-3.5 shrink-0", p("M9.5 6.5l5.5 5.5-5.5 5.5"))

  def sparkle: RxElement = i(
    p("M12 3.5l1.7 4.8 4.8 1.7-4.8 1.7L12 16.5l-1.7-4.8L5.5 10l4.8-1.7z"),
    p("M18.5 15.5l.6 1.6 1.6.6-1.6.6-.6 1.6-.6-1.6-1.6-.6 1.6-.6z")
  )

  def arrowUp: RxElement = icon("size-4", p("M12 19V5.5M6.5 11L12 5.5 17.5 11"))

  def sun: RxElement = i(
    circle(cx -> 12, cy -> 12, r -> 3.8),
    p(
      "M12 2.8v2M12 19.2v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2.8 12h2M19.2 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"
    )
  )

  def moon: RxElement    = i(p("M19.5 14.6A7.8 7.8 0 1 1 9.4 4.5a6.2 6.2 0 0 0 10.1 10.1z"))
  def monitor: RxElement = i(
    rect(x -> 3, y -> 4.5, width -> 18, height -> 12, rx -> 2),
    p("M8.5 20h7M12 16.5V20")
  )

end Icons
