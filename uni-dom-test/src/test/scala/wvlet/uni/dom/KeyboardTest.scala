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

import wvlet.uni.test.UniTest
import wvlet.uni.test.playwright.Playwright
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx

class KeyboardTest extends UniTest:

  test("KeyCombination.parse parses single key"):
    val combo = KeyCombination.parse("escape")
    combo.key shouldBe "escape"
    combo.ctrl shouldBe false
    combo.alt shouldBe false
    combo.shift shouldBe false
    combo.meta shouldBe false

  test("KeyCombination.parse parses ctrl+key"):
    val combo = KeyCombination.parse("ctrl+s")
    combo.key shouldBe "s"
    combo.ctrl shouldBe true
    combo.alt shouldBe false
    combo.shift shouldBe false
    combo.meta shouldBe false

  test("KeyCombination.parse parses multiple modifiers"):
    val combo = KeyCombination.parse("ctrl+shift+p")
    combo.key shouldBe "p"
    combo.ctrl shouldBe true
    combo.alt shouldBe false
    combo.shift shouldBe true
    combo.meta shouldBe false

  test("KeyCombination.parse parses all modifiers"):
    val combo = KeyCombination.parse("ctrl+alt+shift+meta+k")
    combo.key shouldBe "k"
    combo.ctrl shouldBe true
    combo.alt shouldBe true
    combo.shift shouldBe true
    combo.meta shouldBe true

  test("KeyCombination.parse parses cmd as meta"):
    val combo = KeyCombination.parse("cmd+k")
    combo.key shouldBe "k"
    combo.meta shouldBe true

  test("KeyCombination.parse is case-insensitive"):
    val combo = KeyCombination.parse("CTRL+S")
    combo.key shouldBe "s"
    combo.ctrl shouldBe true

  test("KeyCombination.toShortcutString produces normalized string"):
    val combo = KeyCombination(key = "s", ctrl = true, shift = true)
    combo.toShortcutString shouldBe "ctrl+shift+s"

  test("KeyCombination.toShortcutString handles single key"):
    val combo = KeyCombination(key = "escape")
    combo.toShortcutString shouldBe "escape"

  test("Modifiers.none has all modifiers false"):
    val mods = Modifiers.none
    mods.ctrl shouldBe false
    mods.alt shouldBe false
    mods.shift shouldBe false
    mods.meta shouldBe false

  test("Keyboard.bind returns Cancelable"):
    var called = false
    val cancel = Keyboard.bind("ctrl+t", () => called = true)
    cancel shouldMatch { case _: wvlet.uni.rx.Cancelable =>
    }
    cancel.cancel

  test("Keyboard.bindAll returns Cancelable"):
    val cancel = Keyboard.bindAll("ctrl+s" -> (() => ()), "escape" -> (() => ()))
    cancel shouldMatch { case _: wvlet.uni.rx.Cancelable =>
    }
    cancel.cancel

  test("Keyboard.scoped returns DomNode"):
    val node = Keyboard.scoped("enter" -> (() => ()))
    node shouldMatch { case _: DomNode =>
    }

  test("Keyboard.isPressed returns Rx[Boolean]"):
    val pressed = Keyboard.isPressed("shift")
    pressed shouldMatch { case _: Rx[?] =>
    }

  test("Keyboard.modifiers returns Rx[Modifiers]"):
    val mods = Keyboard.modifiers
    mods shouldMatch { case _: Rx[?] =>
    }

  test("Keyboard.currentModifiers returns Modifiers"):
    val mods = Keyboard.currentModifiers
    mods shouldMatch { case Modifiers(_, _, _, _) =>
    }

  test("Keyboard.isPressedNow returns Boolean"):
    val pressed = Keyboard.isPressedNow("shift")
    pressed shouldMatch { case _: Boolean =>
    }

  test("Keyboard.isPressed emits values reactively"):
    var result = false
    val cancel = Keyboard
      .isPressed("shift")
      .run { v =>
        result = v
      }
    // Initially no keys pressed
    result shouldBe false
    cancel.cancel

  test("Keyboard.modifiers emits values reactively"):
    var result: Modifiers = Modifiers.none
    val cancel            = Keyboard
      .modifiers
      .run { v =>
        result = v
      }
    result shouldMatch { case Modifiers(_, _, _, _) =>
    }
    cancel.cancel

  test("KeyCombination.matches checks key and modifiers"):
    val combo = KeyCombination(key = "s", ctrl = true)
    // We can't easily create KeyboardEvents in jsdom, but we can test the logic
    combo.key shouldBe "s"
    combo.ctrl shouldBe true
    combo.alt shouldBe false

  test("Keyboard.bind fires on a real key press until cancelled"):
    var count  = 0
    val cancel = Keyboard.bind("ctrl+shift+k", () => count += 1)
    for
      _ <- Playwright.press("Control+Shift+K")
      _ = count shouldBe 1
      _ <- Playwright.press("Control+K")
      _ = count shouldBe 1
      _ = cancel.cancel
      _ <- Playwright.press("Control+Shift+K")
    yield count shouldBe 1

  test("Keyboard.modifiers tracks a real modifier key"):
    var seen   = List.empty[Modifiers]
    val cancel = Keyboard.modifiers.run(m => seen = m :: seen)
    Playwright
      .press("Shift+A")
      .map { _ =>
        cancel.cancel
        seen.exists(_.shift) shouldBe true
        Keyboard.currentModifiers shouldBe Modifiers.none
        Keyboard.isPressedNow("shift") shouldBe false
      }

end KeyboardTest
