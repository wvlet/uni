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
package wvlet.uni.ui.agent

import wvlet.uni.rx.{Cancelable, RxVar}
import wvlet.uni.ui.{ChangeCause, ChangeGate, ChangeReason, ChangeSource}

import scala.collection.mutable

/** Whether a [[HistoryEntry]] is still in effect. */
enum EntryState(val name: String):
  /** In effect, and can be undone. */
  case Live extends EntryState("live")

  /** Reverted by [[UiHistory.undo]], [[UiHistory.undoSource]], or [[UiStage.revert]]. */
  case Undone extends EntryState("undone")

  /** Kept by [[UiStage.accept]]; [[UiHistory.undoSource]] leaves it alone. */
  case Accepted extends EntryState("accepted")

/**
  * A change a uni-ui component made to app state, as recorded by [[UiHistory]].
  *
  * @param label
  *   the component's accessible label when the change was made, or empty
  * @param previous
  *   the value before the change, as text; `<redacted>` for a secret field
  * @param reversible
  *   false for changes that undo leaves alone: overlay `open` state, secret values, and state no
  *   mounted component owns
  * @param stage
  *   the label of the [[UiStage]] the change joined, if any
  */
case class HistoryEntry(
    seq: Long,
    ref: Option[String],
    kind: Option[String],
    label: String,
    key: String,
    previous: String,
    value: String,
    reason: String,
    source: ChangeSource,
    reversible: Boolean,
    state: EntryState,
    stage: Option[String] = None
):
  /** A sentence for people, such as `Agent set Region to Oregon`. */
  def describe: String =
    val who =
      source match
        case ChangeSource.Agent =>
          "Agent"
        case ChangeSource.Programmatic =>
          "App"
        case _ =>
          "User"
    val what =
      if label.nonEmpty then
        label
      else
        ref.orElse(kind).getOrElse(key)
    key match
      case "value" | "selected" =>
        s"${who} set ${what} to ${value}"
      case k =>
        s"${who} changed ${what} ${k} from ${previous} to ${value}"

end HistoryEntry

/** How an undo of one [[HistoryEntry]] ended. */
enum UndoStatus(val name: String):
  /** The previous value is back. */
  case Reverted extends UndoStatus("reverted")

  /**
    * The state changed after the entry, by a person, app code, or a later change that is still in
    * effect, so the newer value stays.
    */
  case Conflict extends UndoStatus("conflict")

  /** The component's `onChange` handler canceled the revert. */
  case Canceled extends UndoStatus("canceled")

  /** No mounted component owns the state any more, so nothing was written. */
  case Missing extends UndoStatus("missing")

  /** The entry is not reversible: see [[HistoryEntry.reversible]]. */
  case Unsupported extends UndoStatus("unsupported")

  /** The entry was already undone or accepted, left the history, or a revert was running. */
  case Skipped extends UndoStatus("skipped")

/** The result of undoing one entry, with the entry as it is afterwards. */
case class UndoResult(entry: HistoryEntry, status: UndoStatus, message: String = "")

/** Whether a [[UiStage]] still collects the agent's changes. */
enum StageState(val name: String):
  case Open     extends StageState("open")
  case Accepted extends StageState("accepted")
  case Reverted extends StageState("reverted")

  /** The history was closed while the stage was open. */
  case Closed extends StageState("closed")

/**
  * The agent's changes made while the stage is open, which the human accepts or reverts together.
  * Changes from other sources made meanwhile are not part of it, so [[revert]] keeps them.
  */
class UiStage private[agent] (val label: String, history: UiHistory):
  private[agent] var current: StageState = StageState.Open

  def state: StageState = current
  def isOpen: Boolean   = current == StageState.Open

  /** The changes that joined this stage, oldest first. */
  def entries: Seq[HistoryEntry] = history.entriesOf(this)

  /** Keeps the changes and closes the stage. Later [[UiHistory.undoSource]] calls skip them. */
  def accept(): Unit = history.acceptStage(this)

  /**
    * Undoes the changes, newest first, and closes the stage. Changes that conflict with a later
    * edit stay, and their results say `conflict`. Returns nothing once the stage is closed.
    */
  def revert(): Seq[UndoResult] = history.revertStage(this)

end UiStage

/**
  * A record of the changes uni-ui components make to app state, by people, app code, and agents,
  * with source-aware undo. Start it with [[UiHistory.record]]:
  *
  * {{{
  *   val history = UiHistory.record()
  *   val stage   = history.stage("Suggested shipping") // the agent's next changes join it
  *   // ... the agent acts, the human reviews and maybe edits ...
  *   stage.accept()                                    // or stage.revert()
  *   history.undoSource(ChangeSource.Agent)            // revert what the agent changed
  * }}}
  *
  * Undo writes the previous value back through the component's `onChange` handler with
  * `ChangeReason.Undo`, so the app can refuse it. It never overwrites a later change to the same
  * state: such an entry reports `conflict`. Only changes to app state written by components are
  * recorded; button presses, menu-item actions, and other app callbacks are not, and cannot be
  * undone. Components hidden from agents are not recorded, and secret values are never kept.
  */
class UiHistory private (limit: Int):
  require(limit > 0, s"limit must be positive: ${limit}")

  private class Record(
      val seq: Long,
      val target: RxVar[Any],
      // The raw values, only for reversible entries, so secrets are never kept. The revert goes
      // through the handler of the component that owns the target then, not the one kept here
      var undoable: Option[(Any, Any)],
      val owned: Boolean,
      val gesture: Option[AnyRef],
      var base: HistoryEntry
  ):
    var state: EntryState      = EntryState.Live
    var stage: Option[UiStage] = None
    def source: ChangeSource   = base.source
    def entry: HistoryEntry    = base.copy(state = state, stage = stage.map(_.label))
    def notUndone: Boolean     = state != EntryState.Undone

  private val records                    = mutable.ArrayDeque.empty[Record]
  private var seq                        = 0L
  private var reverting                  = false
  private var openStage: Option[UiStage] = None
  private var closed                     = false
  private var subscription: Cancelable   = AgentRegistry.record(add)

  private def add(r: AgentRegistry.Reported): Unit =
    // A revert this history makes is not a new change, so undo cannot undo itself
    if !reverting then
      records.lastOption.filter(sameDrag(_, r)) match
        case Some(last) =>
          // One entry per drag: keep the value before the drag and take the newest value
          val value = r.text(r.details.value)
          if value == last.base.previous then
            // The drag ended where it started, so it changed nothing
            records.removeLast()
          else
            last.undoable = last.undoable.map((previous, _) => (previous, r.details.value))
            last.base = last.base.copy(value = value)
        case None =>
          append(r)

  // A drag step of the drag that made `last`, when nothing else was recorded in between. Other
  // writes during a drag, such as an arrow key, stay entries of their own
  private def sameDrag(last: Record, r: AgentRegistry.Reported): Boolean =
    r.gesture.exists(g => last.gesture.exists(_ eq g)) &&
      (last.target eq r.target) && last.state == EntryState.Live &&
      r.details.reason == ChangeReason.Drag && last.base.reason == ChangeReason.Drag.name

  private def append(r: AgentRegistry.Reported): Unit =
    val d          = r.details
    val reversible = r.owner.isDefined && !r.secret && d.key != "open"
    seq += 1
    val rec = Record(
      seq,
      r.target,
      Option.when(reversible)((d.previous, d.value)),
      r.owner.isDefined,
      r.gesture,
      HistoryEntry(
        seq = seq,
        ref = r.owner.flatMap(_.agent).map(AgentRegistry.refOf),
        kind = r.owner.map(_.state.kind),
        label = r.owner.fold("")(o => o.state.label.getOrElse(AgentDom.labelOf(o.element))),
        key = d.key,
        previous = r.text(d.previous),
        value = r.text(d.value),
        reason = d.reason.name,
        source = d.source,
        reversible = reversible,
        state = EntryState.Live
      )
    )
    if d.source == ChangeSource.Agent then
      rec.stage = openStage
    records.append(rec)
    while records.size > limit do
      records.removeHead()

  end append

  /** The recorded changes, oldest first. Keeps the latest `limit`. */
  def entries: Seq[HistoryEntry] = records.map(_.entry).toSeq

  /**
    * Undoes the live changes from `source`, newest first, such as `undoSource(ChangeSource.Agent)`.
    * Accepted changes are left alone. Returns one result per change it tried, and nothing while
    * another revert is running or after [[close]].
    */
  def undoSource(source: ChangeSource): Seq[UndoResult] =
    if reverting || closed then
      Nil
    else
      records
        .filter(r => r.source == source && r.state == EntryState.Live)
        .reverse
        .map(undoOne)
        .toSeq

  /** Undoes one change, unless a later change to the same state is still in effect. */
  def undo(entry: HistoryEntry): UndoResult =
    records.find(_.seq == entry.seq) match
      case None =>
        UndoResult(entry, UndoStatus.Skipped, "The change is no longer in the history")
      case Some(_) if reverting =>
        UndoResult(entry, UndoStatus.Skipped, "Another revert is running")
      case Some(r) =>
        undoOne(r)

  private def undoOne(r: Record): UndoResult =
    def result(s: UndoStatus, message: String = "") = UndoResult(r.entry, s, message)
    // The component that owns the state now, which may have remounted with a new handler
    val owner = AgentRegistry.ownerOf(r.target)
    r.undoable match
      case _ if r.state != EntryState.Live =>
        result(UndoStatus.Skipped, s"The change is already ${r.state.name}")
      case None =>
        result(UndoStatus.Unsupported, unsupportedReason(r))
      case Some(_) if owner.isEmpty =>
        result(UndoStatus.Missing, "The component is not on screen")
      case Some((_, value)) if r.target.get != value =>
        result(UndoStatus.Conflict, "The value changed since")
      case Some(_) if records.exists(o => o.seq > r.seq && (o.target eq r.target) && o.notUndone) =>
        result(UndoStatus.Conflict, "A later change to the same state is still in effect")
      case Some((previous, _)) =>
        val handler = owner.fold(ChangeGate.noHandler)(_.state.changeHandler)
        reverting = true
        val accepted =
          try ChangeGate.write(
              r.target,
              r.entry.key,
              previous,
              ChangeCause(ChangeReason.Undo, ChangeSource.Programmatic),
              handler
            )
          finally reverting = false
        if accepted then
          r.state = EntryState.Undone
          result(UndoStatus.Reverted)
        else
          result(UndoStatus.Canceled, "The app canceled the revert")

  end undoOne

  private def unsupportedReason(r: Record): String =
    if !r.owned then
      "No component owns the state"
    else if r.entry.key == "open" then
      "Opening and closing overlays is not undone"
    else
      "Secret values are not kept"

  /**
    * Opens a stage that the agent's following changes join, until it is accepted or reverted. Only
    * one stage is open at a time.
    *
    * @throws IllegalStateException
    *   when another stage is still open, or after [[close]]
    */
  def stage(label: String): UiStage =
    if closed then
      throw IllegalStateException("The history is closed")
    openStage.foreach { s =>
      throw IllegalStateException(s"Stage '${s.label}' is still open; accept or revert it first")
    }
    val s = UiStage(label, this)
    openStage = Some(s)
    s

  private[agent] def entriesOf(s: UiStage): Seq[HistoryEntry] =
    records.filter(_.stage.contains(s)).map(_.entry).toSeq

  private[agent] def acceptStage(s: UiStage): Unit =
    if s.isOpen then
      records
        .filter(r => r.stage.contains(s) && r.state == EntryState.Live)
        .foreach(_.state = EntryState.Accepted)
      finish(s, StageState.Accepted)

  private[agent] def revertStage(s: UiStage): Seq[UndoResult] =
    if !s.isOpen || reverting then
      Nil
    else
      finish(s, StageState.Reverted)
      records
        .filter(r => r.stage.contains(s) && r.state == EntryState.Live)
        .reverse
        .map(undoOne)
        .toSeq

  private def finish(s: UiStage, state: StageState): Unit =
    s.current = state
    if openStage.contains(s) then
      openStage = None

  /** Stops recording and forgets every change. An open stage is closed without changes. */
  def close(): Unit =
    subscription.cancel
    subscription = Cancelable.empty
    openStage.foreach(finish(_, StageState.Closed))
    records.clear()
    closed = true

end UiHistory

object UiHistory:
  /** Starts recording the changes components make, keeping the latest `limit`. */
  def record(limit: Int = 500): UiHistory = UiHistory(limit)
