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
package wvlet.uni.dom

import org.scalajs.dom
import wvlet.uni.rx.{Cancelable, Rx, RxVar}

import scala.collection.mutable
import scala.scalajs.js

/**
  * Current state of modifier keys.
  */
case class Modifiers(ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean)

object Modifiers:
  val none: Modifiers = Modifiers(ctrl = false, alt = false, shift = false, meta = false)

/**
  * Parsed keyboard shortcut combination.
  *
  * @param key
  *   The main key (e.g., "s", "escape", "enter")
  * @param ctrl
  *   Whether Ctrl is required
  * @param alt
  *   Whether Alt is required
  * @param shift
  *   Whether Shift is required
  * @param meta
  *   Whether Meta (Cmd on Mac, Windows key) is required
  */
case class KeyCombination(
    key: String,
    ctrl: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
    meta: Boolean = false
):
  /**
    * Check if this combination matches a keyboard event.
    */
  def matches(e: dom.KeyboardEvent): Boolean =
    e.key.toLowerCase == key && e.ctrlKey == ctrl && e.altKey == alt && e.shiftKey == shift &&
      e.metaKey == meta

  /**
    * String representation of this combination.
    */
  def toShortcutString: String =
    val parts = mutable.ArrayBuffer[String]()
    if ctrl then
      parts += "ctrl"
    if alt then
      parts += "alt"
    if shift then
      parts += "shift"
    if meta then
      parts += "meta"
    parts += key
    parts.mkString("+")

end KeyCombination

object KeyCombination:
  /**
    * Parse a shortcut string into a KeyCombination.
    *
    * Supported formats:
    *   - "escape" - Single key
    *   - "ctrl+s" - Modifier + key
    *   - "ctrl+shift+p" - Multiple modifiers
    *   - "meta+k" or "cmd+k" - Command key
    */
  def parse(shortcut: String): KeyCombination =
    val parts = shortcut.toLowerCase.split("\\+").map(_.trim)
    KeyCombination(
      key = parts.last,
      ctrl = parts.contains("ctrl"),
      alt = parts.contains("alt"),
      shift = parts.contains("shift"),
      meta = parts.contains("meta") || parts.contains("cmd")
    )

end KeyCombination

/**
  * Global keyboard shortcut management.
  *
  * Usage:
  * {{{
  *   import wvlet.uni.dom.all.*
  *
  *   // Bind a global shortcut
  *   val cancel = Keyboard.bind("ctrl+s", () => save())
  *
  *   // Bind multiple shortcuts
  *   val cancel = Keyboard.bindAll(
  *     "ctrl+s" -> (() => save()),
  *     "escape" -> (() => closeModal()),
  *     "ctrl+shift+p" -> (() => openCommandPalette())
  *   )
  *
  *   // Scoped shortcuts (only when element focused)
  *   input(
  *     Keyboard.scoped(
  *       "enter" -> (() => submit()),
  *       "escape" -> (() => cancel())
  *     )
  *   )
  *
  *   // Track key state reactively
  *   Keyboard.isPressed("shift").map { pressed =>
  *     if pressed then "Multi-select mode" else "Single select"
  *   }
  *
  *   // Don't forget to cancel when done
  *   cancel.cancel
  * }}}
  */
object Keyboard:
  private class KeyboardState extends Cancelable:
    private val pressedKeysVar: RxVar[Set[String]] = Rx.variable(Set.empty[String])
    private val modifiersVar: RxVar[Modifiers]     = Rx.variable(Modifiers.none)
    private val bindings: mutable.Map[String, mutable.ArrayBuffer[() => Unit]] = mutable.Map.empty

    private val keydownHandler: js.Function1[dom.KeyboardEvent, Unit] =
      (e: dom.KeyboardEvent) =>
        val key = e.key.toLowerCase
        pressedKeysVar.update(_ + key)
        modifiersVar :=
          Modifiers(ctrl = e.ctrlKey, alt = e.altKey, shift = e.shiftKey, meta = e.metaKey)

        // Check for matching shortcuts
        val combo = KeyCombination(
          key = key,
          ctrl = e.ctrlKey,
          alt = e.altKey,
          shift = e.shiftKey,
          meta = e.metaKey
        )
        val comboStr = combo.toShortcutString
        bindings
          .get(comboStr)
          .foreach { handlers =>
            if handlers.nonEmpty then
              e.preventDefault()
              handlers.foreach(_())
          }

    private val keyupHandler: js.Function1[dom.KeyboardEvent, Unit] =
      (e: dom.KeyboardEvent) =>
        pressedKeysVar.update(_ - e.key.toLowerCase)
        modifiersVar :=
          Modifiers(ctrl = e.ctrlKey, alt = e.altKey, shift = e.shiftKey, meta = e.metaKey)

    dom.window.addEventListener("keydown", keydownHandler)
    dom.window.addEventListener("keyup", keyupHandler)

    def addBinding(shortcut: String, handler: () => Unit): Cancelable =
      val combo    = KeyCombination.parse(shortcut)
      val key      = combo.toShortcutString
      val handlers = bindings.getOrElseUpdate(key, mutable.ArrayBuffer.empty)
      handlers += handler
      Cancelable { () =>
        handlers -= handler
        if handlers.isEmpty then
          bindings.remove(key)
      }

    def pressedKeys: Set[String]       = pressedKeysVar.get
    def rxPressedKeys: Rx[Set[String]] = pressedKeysVar
    def currentModifiers: Modifiers    = modifiersVar.get
    def rxModifiers: Rx[Modifiers]     = modifiersVar

    override def cancel: Unit =
      dom.window.removeEventListener("keydown", keydownHandler)
      dom.window.removeEventListener("keyup", keyupHandler)
      bindings.clear()

  end KeyboardState

  private lazy val instance: KeyboardState = KeyboardState()

  /**
    * Bind a global keyboard shortcut.
    *
    * @param shortcut
    *   Shortcut string (e.g., "ctrl+s", "escape", "ctrl+shift+p")
    * @param handler
    *   Handler to execute when shortcut is pressed
    * @return
    *   Cancelable to remove the binding
    */
  def bind(shortcut: String, handler: () => Unit): Cancelable = instance.addBinding(
    shortcut,
    handler
  )

  /**
    * Bind multiple global keyboard shortcuts.
    *
    * @param bindings
    *   Pairs of shortcut strings and handlers
    * @return
    *   Cancelable to remove all bindings
    */
  def bindAll(bindings: (String, () => Unit)*): Cancelable =
    val cancelables = bindings.map { case (shortcut, handler) =>
      bind(shortcut, handler)
    }
    Cancelable.merge(cancelables)

  /**
    * Create a scoped keyboard shortcut modifier that only fires when the element has focus.
    *
    * @param bindings
    *   Pairs of shortcut strings and handlers
    * @return
    *   DomNode modifier to add to an element
    */
  def scoped(bindings: (String, () => Unit)*): DomNode =
    val parsedBindings = bindings.map { case (shortcut, handler) =>
      KeyCombination.parse(shortcut) -> handler
    }
    val onkeydown = HtmlTags.handler[dom.KeyboardEvent]("onkeydown")
    onkeydown { (e: dom.KeyboardEvent) =>
      parsedBindings
        .find(_._1.matches(e))
        .foreach { case (_, handler) =>
          e.preventDefault()
          e.stopPropagation()
          handler()
        }
    }

  /**
    * Check if a specific key is currently pressed.
    *
    * @param key
    *   Key name (e.g., "shift", "a", "escape")
    * @return
    *   Reactive boolean indicating if the key is pressed
    */
  def isPressed(key: String): Rx[Boolean] = instance.rxPressedKeys.map(_.contains(key.toLowerCase))

  /**
    * Get the current state of modifier keys.
    */
  def modifiers: Rx[Modifiers] = instance.rxModifiers

  /**
    * Get the current modifier state synchronously.
    */
  def currentModifiers: Modifiers = instance.currentModifiers

  /**
    * Check if a key is currently pressed (synchronous).
    */
  def isPressedNow(key: String): Boolean = instance.pressedKeys.contains(key.toLowerCase)

  /**
    * Stop all keyboard listeners. Call this when the application is shutting down.
    */
  def stop(): Unit = instance.cancel

end Keyboard
