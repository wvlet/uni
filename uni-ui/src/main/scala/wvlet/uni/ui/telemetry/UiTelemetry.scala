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
package wvlet.uni.ui.telemetry

import org.scalajs.dom
import wvlet.uni.rx.{Cancelable, RxTimeProvider}
import wvlet.uni.telemetry.AttributeValue
import wvlet.uni.ui.ChangeSource
import wvlet.uni.ui.agent.{AgentBinding, AgentDom, AgentRegistry}

import scala.collection.mutable
import scala.scalajs.js

/**
  * One UI interaction, named by the component's stable ref: `ui.press`, `ui.change`, `ui.open`,
  * `ui.close`, `ui.view`, or `command.run`.
  *
  * @param timeMillis
  *   epoch milliseconds in the browser
  */
case class UiEvent(name: String, timeMillis: Long, attributes: Map[String, AttributeValue]):
  def ref: Option[String] = attributes
    .get("ui.ref")
    .collect { case s: String =>
      s
    }

/**
  * What [[UiTelemetry]] records and where it sends it.
  *
  * @param sink
  *   where batches go: [[UiTelemetrySink.relay]] (to a Uni server's `UiTelemetryRelay`),
  *   [[UiTelemetrySink.datadogRum]], or your own
  * @param sampleRatio
  *   the fraction of page sessions that record anything, decided once at install
  * @param valueKinds
  *   component kinds whose values are recorded (`ui.value`). Values can be what people typed or
  *   picked, so only tab labels are recorded by default. Secret fields are always redacted
  * @param pressKinds
  *   component kinds whose clicks are recorded as `ui.press`
  * @param openKinds
  *   component kinds reported as `ui.open` / `ui.close` as they mount and unmount, such as a
  *   dialog, whose binding exists only while it is open
  * @param openStateKinds
  *   component kinds reported as `ui.open` / `ui.close` when their `open` state changes, such as a
  *   menu, whose binding stays on its trigger
  * @param includeProgrammatic
  *   also record changes the app's own code makes, not only those of people and agents
  * @param mergeWindowMillis
  *   changes to the same component and key within this window, or in one pointer drag, are sent as
  *   one `ui.change` carrying the last value
  * @param attributes
  *   added to every event, e.g. the app version
  */
case class UiTelemetryConfig(
    sink: UiTelemetrySink,
    sampleRatio: Double = 1.0,
    valueKinds: Set[String] = Set("tabs"),
    pressKinds: Set[String] = Set("button", "menuitem"),
    openKinds: Set[String] = Set("dialog", "commandpalette"),
    openStateKinds: Set[String] = Set("menu"),
    includeProgrammatic: Boolean = false,
    mergeWindowMillis: Long = 1000,
    flushIntervalMillis: Long = 5000,
    maxQueueSize: Int = 1000,
    attributes: Map[String, AttributeValue] = Map.empty,
    timeProvider: RxTimeProvider = RxTimeProvider.system
):
  require(
    sampleRatio >= 0.0 && sampleRatio <= 1.0,
    s"sampleRatio must be in [0, 1]: ${sampleRatio}"
  )

  def withSink(s: UiTelemetrySink): UiTelemetryConfig        = copy(sink = s)
  def withSampleRatio(r: Double): UiTelemetryConfig          = copy(sampleRatio = r)
  def withValueKinds(kinds: String*): UiTelemetryConfig      = copy(valueKinds = kinds.toSet)
  def noValues: UiTelemetryConfig                            = copy(valueKinds = Set.empty)
  def withPressKinds(kinds: String*): UiTelemetryConfig      = copy(pressKinds = kinds.toSet)
  def withOpenKinds(kinds: String*): UiTelemetryConfig       = copy(openKinds = kinds.toSet)
  def withOpenStateKinds(kinds: String*): UiTelemetryConfig  = copy(openStateKinds = kinds.toSet)
  def withProgrammaticChanges(b: Boolean): UiTelemetryConfig = copy(includeProgrammatic = b)
  def withMergeWindowMillis(ms: Long): UiTelemetryConfig     = copy(mergeWindowMillis = ms)
  def withFlushIntervalMillis(ms: Long): UiTelemetryConfig   = copy(flushIntervalMillis = ms)
  def withMaxQueueSize(n: Int): UiTelemetryConfig            = copy(maxQueueSize = n)
  def withTimeProvider(p: RxTimeProvider): UiTelemetryConfig = copy(timeProvider = p)
  def withAttribute(key: String, value: AttributeValue): UiTelemetryConfig = copy(attributes =
    attributes.updated(key, value)
  )

  /** Start recording. */
  def install(): UiTelemetry = UiTelemetry(this)

end UiTelemetryConfig

/**
  * Records which uni-ui components people use, with no tracking code in the app. It reads the same
  * component registry as [[wvlet.uni.ui.agent.UiAgent]], so events carry each component's kind and
  * stable ref (`button:save`, or the name given with `UiAgent.ref`):
  *   - `ui.press`: a click or key press on a button or menu item;
  *   - `ui.change`: a committed change through `onChange`, with its reason and source (pointer,
  *     keyboard, or agent), merged per drag and typing burst;
  *   - `ui.open` / `ui.close`: a dialog, menu, or command palette, with `duration.ms` on close;
  *     they are also closed out by [[uninstall]];
  *   - `ui.view`: the time a tab stayed selected (`duration.ms`);
  *   - `command.run`: a command palette command, by its id.
  *
  * Components hidden with `UiAgent.hidden` (or `hidden`, `inert`, `aria-hidden`) are left out, and
  * values are recorded only for `valueKinds`. Events are batched and sent every
  * `flushIntervalMillis` and when the page is hidden. Nothing is recorded before `install` or after
  * [[uninstall]], so asking for consent first is up to the app.
  * {{{
  * val telemetry = UiTelemetry.install(UiTelemetrySink.relay())
  * }}}
  */
class UiTelemetry private (config: UiTelemetryConfig):
  /** A random id for this page session, sent with each batch. */
  val sessionId: String = UiTelemetry.randomHex(16)

  /** Whether this session was sampled; an unsampled session records nothing. */
  val isSampled: Boolean =
    config.sampleRatio >= 1.0 ||
      (config.sampleRatio > 0.0 && scala.util.Random.nextDouble() < config.sampleRatio)

  private val queue       = mutable.ArrayBuffer.empty[UiEvent]
  private var dropped     = 0L
  private var cancelables = Seq.empty[Cancelable]
  private var installed   = false
  // The change waiting to be merged with the next one, and what it is merged by
  private var pending: Option[(UiEvent, String, Option[AnyRef], Long)] = None
  private var pendingTimer: Cancelable                                 = Cancelable.empty
  // When each open component and selected tab started, by binding
  private val opened = js.Map.empty[AgentBinding, (String, String, Long)]
  private val views  = js.Map.empty[AgentBinding, (String, Option[String], Long)]

  private def now: Long = js.Date.now().toLong

  private def start(): Unit =
    installed = true
    val onClick: js.Function1[dom.Event, Unit] = e => press(e)
    dom.document.addEventListener("click", onClick, useCapture = true)
    val onHide: js.Function1[dom.Event, Unit] =
      _ =>
        if dom.document.visibilityState == "hidden" then
          // The page may never come back: report the tab views so far, then keep timing
          views.keys.toSeq.foreach(rollView)
          flush(unloading = true)
    dom.document.addEventListener("visibilitychange", onHide)
    dom.window.addEventListener("pagehide", onHide)
    cancelables = Seq(
      Cancelable { () =>
        dom.document.removeEventListener("click", onClick, useCapture = true)
        dom.document.removeEventListener("visibilitychange", onHide)
        dom.window.removeEventListener("pagehide", onHide)
      },
      AgentRegistry.record(r => change(r)),
      AgentRegistry.watchMounts((e, mounted) => mount(e, mounted)),
      AgentRegistry.watchCommands((id, source) =>
        add(UiEvent("command.run", now, Map("command.id" -> id, "ui.source" -> sourceName(source))))
      ),
      config
        .timeProvider
        .schedulePeriodically(config.flushIntervalMillis, config.flushIntervalMillis)(flush())
    )
    // Components already on the page when telemetry starts
    AgentRegistry.all.foreach(e => mount(e, true))

  /** Stop recording, and send what is queued. */
  def uninstall(): Unit =
    if installed then
      // Close out what is still open, so the session's last view and dialog are reported
      views.keys.toSeq.foreach(endView)
      opened.keys.toSeq.foreach(closing)
      views.clear()
      pendingTimer.cancel
      installed = false
      cancelables.foreach(_.cancel)
      cancelables = Nil
      flush()

  /** Send the queued events now. */
  def flush(unloading: Boolean = false): Unit =
    flushPending()
    val batch = queue.toSeq
    queue.clear()
    if dropped > 0 then
      dom
        .console
        .warn(s"UiTelemetry dropped ${dropped} events: more than ${config.maxQueueSize} queued")
      dropped = 0
    if batch.nonEmpty then
      try
        config.sink.send(UiEventBatch(sessionId, batch), unloading)
      catch
        case e: Throwable =>
          dom.console.warn(s"UiTelemetry sink failed: ${e.getMessage}")

  private def add(e: UiEvent): Unit =
    if installed then
      if queue.size >= config.maxQueueSize then
        dropped += 1
      else
        queue += e.copy(attributes = config.attributes ++ e.attributes)

  private def sourceName(s: ChangeSource): String = s.toString.toLowerCase

  private def target(e: AgentRegistry.Entry): Map[String, AttributeValue] = Map(
    "ui.kind" -> e.binding.kind,
    "ui.ref"  -> AgentRegistry.refOf(e)
  )

  private def press(e: dom.Event): Unit =
    val entry = Iterator
      .iterate(e.target.asInstanceOf[dom.Node])(_.parentNode)
      .takeWhile(_ != null)
      .collect { case el: dom.Element =>
        el
      }
      .flatMap(AgentRegistry.entryOf)
      .nextOption()
    entry
      .filter(en => config.pressKinds.contains(en.binding.kind))
      .filterNot(en => AgentDom.isHidden(en.element) || AgentDom.isDisabled(en.element))
      .foreach { en =>
        // An agent's press is a click inside ChangeSource.withSource(Agent)
        val source = ChangeSource.scopedSource.getOrElse(ChangeSource.of(e))
        add(UiEvent("ui.press", now, target(en) + ("ui.source" -> sourceName(source))))
      }

  private def change(r: AgentRegistry.Reported): Unit =
    val d = r.details
    r.owner
      .flatMap(_.agent)
      .foreach { en =>
        if config.openStateKinds.contains(en.binding.kind) && d.key == "open" then
          // Whoever opens or closes it, app code included, so every open gets its close
          d.value match
            case true =>
              opening(en.binding, en.binding.kind, AgentRegistry.refOf(en))
            case _ =>
              closing(en.binding)
        else if config.includeProgrammatic || d.source != ChangeSource.Programmatic then
          changed(r, en)
      }

  private def changed(r: AgentRegistry.Reported, en: AgentRegistry.Entry): Unit =
    val d     = r.details
    val kind  = en.binding.kind
    var attrs =
      target(en) ++
        Map("ui.key" -> d.key, "ui.reason" -> d.reason.name, "ui.source" -> sourceName(d.source))
    if config.valueKinds.contains(kind) then
      attrs += "ui.value" -> r.text(d.value)
    val event = UiEvent("ui.change", now, attrs)
    val ref   = attrs("ui.ref").asInstanceOf[String]
    val key   = s"${ref}\u0000${d.key}"
    pending match
      case Some((_, k, g, at))
          if k == key && (
            g.exists(x => r.gesture.exists(_ eq x)) ||
              event.timeMillis - at <= config.mergeWindowMillis
          ) =>
        pending = Some((event, key, r.gesture, event.timeMillis))
      case _ =>
        flushPending()
        pending = Some((event, key, r.gesture, event.timeMillis))
    pendingTimer.cancel
    pendingTimer = config.timeProvider.scheduleOnce(config.mergeWindowMillis)(flushPending())
    if kind == "tabs" && views.contains(en.binding) then
      endView(en.binding)
      startView(en.binding, ref)

  private def opening(b: AgentBinding, kind: String, ref: String): Unit =
    if !opened.contains(b) then
      opened(b) = (kind, ref, now)
      add(UiEvent("ui.open", now, Map("ui.kind" -> kind, "ui.ref" -> ref)))

  private def closing(b: AgentBinding): Unit = opened
    .get(b)
    .foreach { (kind, ref, since) =>
      opened.remove(b)
      add(
        UiEvent(
          "ui.close",
          now,
          Map("ui.kind" -> kind, "ui.ref" -> ref, "duration.ms" -> (now - since))
        )
      )
    }

  // Report the view so far and keep timing the same tab
  private def rollView(b: AgentBinding): Unit = views
    .get(b)
    .foreach { (ref, label, _) =>
      endView(b)
      views(b) = (ref, label, now)
    }

  private def flushPending(): Unit =
    pending.foreach((e, _, _, _) => add(e))
    pending = None

  // The selected tab's label is read after the write lands
  private def startView(b: AgentBinding, ref: String): Unit =
    views(b) = (ref, None, now)
    config
      .timeProvider
      .scheduleOnce(0) {
        views.get(b).foreach((r, _, since) => views(b) = (r, b.value, since))
      }

  private def endView(b: AgentBinding): Unit = views
    .get(b)
    .foreach { (ref, label, since) =>
      var attrs = Map[String, AttributeValue](
        "ui.kind"     -> "tabs",
        "ui.ref"      -> ref,
        "duration.ms" -> (now - since)
      )
      if config.valueKinds.contains("tabs") then
        label.foreach(l => attrs += "ui.value" -> l)
      add(UiEvent("ui.view", now, attrs))
    }

  private def mount(e: AgentRegistry.Entry, mounted: Boolean): Unit =
    val kind = e.binding.kind
    if (config.openKinds.contains(kind) && !config.openStateKinds.contains(kind)) || kind == "tabs"
    then
      if mounted then
        // Labels and refs resolve once the element is in the document
        config
          .timeProvider
          .scheduleOnce(0) {
            if installed && e.binding.element.exists(_ eq e.element) &&
              !AgentDom.isHidden(e.element)
            then
              val ref = AgentRegistry.refOf(e)
              if kind == "tabs" then
                if !views.contains(e.binding) then
                  startView(e.binding, ref)
              else
                opening(e.binding, kind, ref)
          }
      else
        // A re-render replaces the element and mounts the binding again right away; wait to see
        config
          .timeProvider
          .scheduleOnce(0) {
            if e.binding.element.isEmpty then
              if kind == "tabs" then
                endView(e.binding)
                views.remove(e.binding)
              else
                closing(e.binding)
          }
    end if

  end mount

end UiTelemetry

object UiTelemetry:
  /** Start recording with the default [[UiTelemetryConfig]]. */
  def install(sink: UiTelemetrySink): UiTelemetry = UiTelemetryConfig(sink).install()

  private[telemetry] def apply(config: UiTelemetryConfig): UiTelemetry =
    val t = new UiTelemetry(config)
    if t.isSampled then
      t.start()
    t

  private[telemetry] def randomHex(bytes: Int): String = (0 until bytes)
    .map(_ => f"${scala.util.Random.nextInt(256)}%02x")
    .mkString

/** The events of one flush, with the page session they belong to. */
case class UiEventBatch(sessionId: String, events: Seq[UiEvent])
