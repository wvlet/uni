package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.*
import wvlet.uni.util.Result

import scala.language.implicitConversions

/**
  * One stream on a [[Timeline]]: its label and the Rx to watch. `Lane.run` watches it with
  * `RxRunner.run`, which ends at the first error, instead of `RxRunner.runContinuously`, which
  * passes an `OnError` and keeps going.
  */
case class Lane(label: String, rx: RxOps[?], continuous: Boolean = true)

object Lane:
  def run(label: String, rx: RxOps[?]): Lane = Lane(label, rx, continuous = false)

/**
  * A marble diagram: one lane per stream, drawing each event it emits on a shared time axis. A
  * value is a dot with the value, an `OnError` a red cross (the lane goes on), and the end a bar,
  * red when the stream failed. Each lane subscribes while the timeline is mounted, so it runs its
  * own copy of the stream, and shows the stream's latest value next to its label.
  *
  * The axis covers the last [[Timeline.windowMs]] milliseconds and scrolls while events arrive. It
  * stops once every marble has scrolled out, and the next event starts a fresh axis.
  */
class Timeline(lanes: Lane*) extends RxElement:
  import Timeline.*

  private val marbles = lanes.map(_ => Rx.variable(Seq.empty[Marble]))
  private val latest  = lanes.map(_ => Rx.variable(Option.empty[Marble]))
  private val ended   = lanes.map(_ => Rx.variable(Option.empty[Marble]))
  private val shift   = Rx.variable(0.0)
  // Where each lane's last marble sits and its half width, in ms, so marbles don't overlap
  private val lastX    = Array.fill(lanes.size)(Double.NegativeInfinity)
  private val lastHalf = Array.fill(lanes.size)(0.0)
  private var front    = 0.0
  private var root     = Option.empty[dom.Element]
  private var origin   = 0.0
  private var lastAt   = Double.NegativeInfinity
  private var frame    = 0
  private var running  = false
  private var mounted  = false
  private var watching = Cancelable.empty

  private def now: Double = dom.window.performance.now()

  // Called for every event: after an idle axis, start again from an empty one. A stream ends at
  // its OnCompletion, so the lane ignores anything after it.
  private def add(i: Int, kind: Kind, text: String): Unit =
    // Cancelling a lane on unmount can still report an end, which nothing would show
    if mounted && ended(i).get.isEmpty then
      draw(i, kind, text)

  private def draw(i: Int, kind: Kind, text: String): Unit =
    val t = now
    if t - lastAt > windowMs then
      restart(t)
    lastAt = t
    // Marbles of events close in time sit side by side, slightly later than they happened
    val at   = t - origin
    val half = msPerPx * Marble.widthPx(kind, text) / 2
    val x    = math.max(at, lastX(i) + lastHalf(i) + half + msPerPx * 2)
    lastX(i) = x
    lastHalf(i) = half
    front = math.max(front, x)
    val m = Marble(at, x, kind, text)
    marbles(i).update(ms => (ms :+ m).takeRight(maxMarbles))
    kind match
      case Kind.Next =>
        latest(i) := Some(m)
      case Kind.Done | Kind.Failed =>
        ended(i) := Some(m)
      case Kind.Error =>
    tick()

  // Scroll the axis on animation frames until the newest marble has left it
  private def tick(): Unit =
    if mounted && !running then
      running = true
      def step(): Unit =
        val t = now
        shift := math.max(0.0, math.max(t - origin, front) - windowMs * 0.85)
        if mounted && t - lastAt <= windowMs then
          frame = dom.window.requestAnimationFrame(_ => step())
        else
          running = false
      step()

  // An empty axis whose left edge is a little before `t`, so the first marble shows whole
  private def restart(t: Double): Unit =
    origin = t - lead
    front = 0.0
    shift := 0.0
    lastX.indices.foreach(i => lastX(i) = Double.NegativeInfinity)
    marbles.foreach(_ := Seq.empty)

  // The axis' time per pixel, from the width of a lane
  private def msPerPx: Double =
    val width = root
      .flatMap(r => Option(r.querySelector(".tl-track")))
      .map(_.clientWidth.toDouble)
      .filter(_ > 0)
      .getOrElse(600.0)
    windowMs / width

  override def onMount(node: Any): Unit =
    mounted = true
    root = Some(node.asInstanceOf[dom.Element])
    origin = now - lead
    watching = Cancelable.merge(
      lanes
        .zipWithIndex
        .map { (lane, i) =>
          if lane.continuous then
            RxRunner.runContinuously(lane.rx) {
              case OnNext(v) =>
                add(i, Kind.Next, show(v))
              case OnError(e) =>
                add(i, Kind.Error, e.getMessage)
              case OnCompletion(Result.Failure(e)) =>
                add(i, Kind.Failed, e.getMessage)
              case OnCompletion(_) =>
                add(i, Kind.Done, "")
            }
          else
            RxRunner.run(lane.rx) {
              case OnNext(v) =>
                add(i, Kind.Next, show(v))
              case OnCompletion(Result.Failure(e)) =>
                add(i, Kind.Failed, e.getMessage)
              case OnCompletion(_) =>
                add(i, Kind.Done, "")
            }
        }
    )

  end onMount

  override def beforeUnmount: Unit =
    mounted = false
    watching.cancel
    dom.window.cancelAnimationFrame(frame)
    running = false

  private def marble(m: Marble): RxElement =
    val (glyph, title) =
      m.kind match
        case Kind.Next =>
          (m.text, s"OnNext(${m.text})")
        case Kind.Error =>
          ("✕", s"OnError(${m.text}): the stream goes on")
        case Kind.Done =>
          ("", "OnCompletion(Success): the stream ended")
        case Kind.Failed =>
          ("✕", s"OnCompletion(Failure(${m.text})): the stream ended")
    span(
      cls       -> s"marble marble--${m.kind.toString.toLowerCase}",
      styleAttr -> s"left: ${pct(m.x)}%",
      titleAttr -> s"${title} at ${f"${m.at / 1000}%.1f"}s",
      ariaLabel -> title,
      role      -> "img",
      span(cls -> "marble__glyph", glyph)
    )

  private def lane(l: Lane, i: Int): RxElement = div(
    cls          -> "tl-lane",
    role         -> "group",
    ariaLabel    -> l.label,
    data("lane") -> l.label,
    div(
      cls -> "tl-head",
      code(cls -> "tl-label", l.label),
      Rx.join(latest(i), ended(i))
        .map { (last, finish) =>
          val value = last.map(m => span(cls -> "tl-value", m.text))
          val state =
            finish match
              case Some(m) if m.kind == Kind.Failed =>
                span(cls -> "tl-state tl-state--failed", "failed")
              case Some(_) =>
                span(cls -> "tl-state tl-state--done", "completed")
              case None =>
                span(cls -> "tl-state", "live")
          span(cls -> "tl-status", value, state)
        }
    ),
    div(
      cls -> "tl-track",
      div(
        cls       -> "tl-strip",
        styleAttr -> shift.map(s => s"transform: translateX(-${pct(s)}%)"),
        marbles(i).map(_.map(marble))
      )
    )
  )

  override def render: RxElement = div(
    cls -> "timeline",
    lanes.zipWithIndex.map((l, i) => lane(l, i)),
    div(cls -> "tl-axis", span(s"${windowMs / 1000} s"), span("now →"))
  )

end Timeline

object Timeline:
  /** How much time the axis shows. */
  val windowMs: Int = 10000

  private val maxMarbles = 200

  // The strip is 50 windows wide, so marbles and the grid scroll as one
  private val stripWindows = 50

  enum Kind:
    case Next,
      Error,
      Done,
      Failed

  // How far before the first event the axis starts
  private val lead = 400.0

  /** An event `at` milliseconds after the axis started, drawn at `x`. */
  case class Marble(at: Double, x: Double, kind: Kind, text: String)

  object Marble:
    /** About how wide a marble is drawn, from its CSS. */
    def widthPx(kind: Kind, text: String): Double =
      kind match
        case Kind.Next =>
          math.max(22.0, 14.0 + 6.7 * text.length)
        case Kind.Error =>
          22.0
        case Kind.Done | Kind.Failed =>
          6.0

  private def pct(ms: Double): Double = ms / (windowMs.toDouble * stripWindows) * 100

  private def show(v: Any): String =
    val s = v.toString
    if s.isEmpty then
      "\"\""
    else if s.length > 18 then
      s.take(17) + "…"
    else
      s

end Timeline
