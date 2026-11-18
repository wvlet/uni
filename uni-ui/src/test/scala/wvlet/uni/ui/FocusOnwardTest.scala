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
package wvlet.uni.ui

import org.scalajs.dom
import wvlet.uni.dom.all.{cls, div}
import wvlet.uni.dom.all.given
import wvlet.uni.dom.*
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.{Rx, RxVar}

import scala.language.implicitConversions

// Without uni-ui.css overlays have no exit animation, as under prefers-reduced-motion
class FocusOnwardTest extends DomTestSupport:

  private def find(selector: String): dom.HTMLElement = dom
    .document
    .querySelector(selector)
    .asInstanceOf[dom.HTMLElement]

  private def active: dom.Element = dom.document.activeElement

  // A row of buttons, each opening the dialog for its row
  private def rowButton(r: Int, target: RxVar[Int], open: RxVar[Boolean]): DomNode =
    Button(s"Delete ${r}").onClick { () =>
      target := r
      open   := true
    }(cls -> s"row-${r}")

  // Opens the dialog from row `r` and presses its confirm button
  private def confirmFrom(r: Int): Rx[Unit] =
    val opener = find(s".row-${r}")
    opener.focus()
    fireEvent.click(opener)
    settle().flatMap { _ =>
      // Focus moved into the dialog
      (active eq opener) shouldBe false
      fireEvent.click(find(".uni-button--danger"))
      settle()
    }

  private def confirmDialog(open: RxVar[Boolean], rows: RxVar[Seq[Int]], target: RxVar[Int]) =
    AlertDialog(open, title = "Delete this row?")
      .danger
      .onConfirm(() => rows := rows.get.filterNot(_ == target.get))

  test("focus goes to the next row when a dialog's action deletes the row that opened it") {
    val rows   = Rx.variable(Seq(1, 2, 3))
    val target = Rx.variable(0)
    val open   = Rx.variable(false)
    // Not keyed: every row renders again, so the opener's neighbors are new elements
    mount(
      div(
        div(cls -> "list", rows.map(_.map(r => rowButton(r, target, open)))),
        confirmDialog(open, rows, target)
      )
    )
    confirmFrom(2).map { _ =>
      rows.get shouldBe Seq(1, 3)
      active.classList.contains("row-3") shouldBe true
    }
  }

  test("focus goes to the next row in a keyed list too") {
    val rows   = Rx.variable(Seq(1, 2, 3))
    val target = Rx.variable(0)
    val open   = Rx.variable(false)
    mount(
      div(
        div(cls -> "list", rows.each(identity)(r => rowButton(r, target, open))),
        confirmDialog(open, rows, target)
      )
    )
    confirmFrom(2).map { _ =>
      active.classList.contains("row-3") shouldBe true
    }
  }

  test("deleting the last row moves focus to the row before it") {
    val rows   = Rx.variable(Seq(1, 2, 3))
    val target = Rx.variable(0)
    val open   = Rx.variable(false)
    mount(
      div(
        div(cls -> "list", rows.map(_.map(r => rowButton(r, target, open)))),
        confirmDialog(open, rows, target)
      )
    )
    confirmFrom(3).map { _ =>
      active.classList.contains("row-2") shouldBe true
    }
  }

  test("a row deleted right after the dialog closes still moves focus onward") {
    val rows   = Rx.variable(Seq(1, 2, 3))
    val target = Rx.variable(0)
    val open   = Rx.variable(false)
    mount(
      div(
        div(cls -> "list", rows.map(_.map(r => rowButton(r, target, open)))),
        // Closes first, so focus returns to the opener, and then removes it
        Dialog(
          open,
          content = Seq(
            Button("Delete")
              .danger
              .onClick { () =>
                open := false
                rows := rows.get.filterNot(_ == target.get)
              }
          )
        )
      )
    )
    confirmFrom(2).map { _ =>
      rows.get shouldBe Seq(1, 3)
      active.classList.contains("row-3") shouldBe true
    }
  }

end FocusOnwardTest
