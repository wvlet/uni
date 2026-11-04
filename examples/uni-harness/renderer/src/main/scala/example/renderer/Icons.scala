package example.renderer

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given

import scala.language.implicitConversions

/** Small stroke icons drawn with the SVG DSL, so the example needs no icon package. */
object Icons:
  private def icon(children: RxElement*): RxElement = svg(
    viewBox        -> "0 0 24 24",
    fill           -> "none",
    stroke         -> "currentColor",
    strokeWidth    -> "1.8",
    strokeLinecap  -> "round",
    strokeLinejoin -> "round",
    cls            -> "size-5",
    children
  )

  private def p(data: String): RxElement = path(d -> data)

  def chat: RxElement   = icon(p("M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"))
  def folder: RxElement = icon(
    p("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
  )

  def file: RxElement = icon(
    p("M14 3H6a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8z"),
    p("M14 3v5h5")
  )

  def plus: RxElement    = icon(p("M12 5v14M5 12h14"))
  def arrowUp: RxElement = icon(p("M12 19V5M6 11l6-6 6 6"))

  def sliders: RxElement = icon(
    p("M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1"),
    circle(cx -> 15, cy -> 6, r  -> 2),
    circle(cx -> 9, cy  -> 12, r -> 2),
    circle(cx -> 17, cy -> 18, r -> 2)
  )

  def panelLeft: RxElement = icon(
    rect(x -> 3, y -> 4, width -> 18, height -> 16, rx -> 2),
    p("M9 4v16")
  )

  def panelRight: RxElement = icon(
    rect(x -> 3, y -> 4, width -> 18, height -> 16, rx -> 2),
    p("M15 4v16")
  )

end Icons
