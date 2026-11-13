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
package wvlet.uni.ui.base

import org.scalajs.dom
import wvlet.uni.dom.{DomNode, EventListenerBinding, Keyboard}
import wvlet.uni.rx.RxVar
import wvlet.uni.ui.{ChangeCause, ChangeGate, ChangeHandler, ChangeReason}

/**
  * Recalls earlier entries of a text field with ArrowUp and ArrowDown, like a terminal or a chat
  * composer. ArrowUp on the first line goes back one entry; ArrowDown on the last line goes
  * forward, and past the newest entry it brings back the text that was being written.
  *
  * {{{
  *   val draft   = Rx.variable("")
  *   val history = InputHistory()
  *   textarea(value.bind(draft), history.keys(draft), Keyboard.onEnter { () =>
  *     history.add(draft.get)
  *     send(draft.get)
  *     draft := ""
  *   })
  * }}}
  *
  * Edits to a recalled entry are not kept when moving on to another one. To keep the history across
  * sessions, save [[entries]] and pass them back as `initial`.
  *
  * @param capacity
  *   the number of entries kept; the oldest go first
  */
class InputHistory(capacity: Int = 100, initial: Seq[String] = Nil):
  private var items  = initial.takeRight(capacity).toVector
  private var cursor = items.size
  private var draft  = ""

  /** The entries, oldest first. */
  def entries: Seq[String] = items

  /** True while showing an entry rather than the text being written. */
  def recalling: Boolean = cursor < items.size

  /**
    * Adds an entry and goes back to the text being written. Blank entries and repeats of the newest
    * entry are not added.
    */
  def add(entry: String): Unit =
    if !entry.isBlank && !items.lastOption.contains(entry) then
      items = (items :+ entry).takeRight(capacity)
    reset()

  /** Goes back to the text being written, without changing the entries. */
  def reset(): Unit =
    cursor = items.size
    draft = ""

  /**
    * The entry before the one shown, or None at the oldest. `current` is the text in the field; it
    * is kept as the draft when leaving it.
    */
  def previous(current: String): Option[String] =
    if cursor == 0 then
      None
    else
      if cursor == items.size then
        draft = current
      cursor -= 1
      Some(items(cursor))

  /** The entry after the one shown, the saved draft past the newest, or None when not recalling. */
  def next(): Option[String] =
    if cursor >= items.size then
      None
    else
      cursor += 1
      if cursor == items.size then
        Some(draft)
      else
        Some(items(cursor))

  /**
    * Binds ArrowUp and ArrowDown on an `<input>` or `<textarea>` to recall entries into `value`.
    * The keys move the caret as usual until it is on the first line (ArrowUp) or the last line
    * (ArrowDown). A recall is a change to `value` with the `NavigationKey` reason, which `onChange`
    * sees first and can cancel.
    */
  def keys(value: RxVar[String], onChange: ChangeHandler = _ => ()): DomNode = EventListenerBinding(
    "keydown",
    {
      case e: dom.KeyboardEvent
          if !e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey && !Keyboard.isComposing(e) =>
        e.currentTarget match
          case field: (dom.HTMLInputElement | dom.HTMLTextAreaElement) =>
            val (text, caret, caretEnd) =
              field match
                case i: dom.HTMLInputElement =>
                  (i.value, i.selectionStart, i.selectionEnd)
                case t: dom.HTMLTextAreaElement =>
                  (t.value, t.selectionStart, t.selectionEnd)
            val collapsed = caret == caretEnd
            val recalled  =
              e.key match
                case "ArrowUp" if collapsed && !text.take(caret).contains('\n') =>
                  val saved = (cursor, draft)
                  previous(text).map(v => (v, saved))
                case "ArrowDown" if collapsed && !text.drop(caret).contains('\n') =>
                  val saved = (cursor, draft)
                  next().map(v => (v, saved))
                case _ =>
                  None
            recalled.foreach { case (v, (savedCursor, savedDraft)) =>
              e.preventDefault()
              val cause = ChangeCause.of(ChangeReason.NavigationKey, e)
              if ChangeGate.write(value, "value", v, cause, onChange) then
                // The binding updated the field; put the caret at the end, as a terminal does
                field match
                  case i: dom.HTMLInputElement =>
                    i.value = v
                    i.setSelectionRange(v.length, v.length)
                  case t: dom.HTMLTextAreaElement =>
                    t.value = v
                    t.setSelectionRange(v.length, v.length)
              else
                cursor = savedCursor
                draft = savedDraft
            }
          case _ =>
      case _ =>
    }
  )

end InputHistory
