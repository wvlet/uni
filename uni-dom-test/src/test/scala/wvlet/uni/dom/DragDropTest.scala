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
import wvlet.uni.test.UniTest
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import scala.language.implicitConversions
import wvlet.uni.rx.Rx

class DragDropTest extends UniTest:

  test("DragData case class holds data"):
    val data = DragData("item", "item-123")
    data.kind shouldBe "item"
    data.data shouldBe "item-123"
    data.effectAllowed shouldBe "all"

  test("DragData with custom effectAllowed"):
    val data = DragData("task", "task-456", "copy")
    data.effectAllowed shouldBe "copy"

  test("DragState.empty has correct defaults"):
    val state = DragState.empty
    state.isDragging shouldBe false
    state.data shouldBe None
    state.overElement shouldBe None

  test("DragState case class holds state"):
    val data  = DragData("item", "123")
    val state = DragState(isDragging = true, data = Some(data), overElement = None)
    state.isDragging shouldBe true
    state.data shouldBe Some(data)

  test("DragDrop.draggable with DragData returns DomNode"):
    val data = DragData("item", "item-123")
    val node = DragDrop.draggable(data)
    node shouldMatch { case _: DomNode =>
    }

  test("DragDrop.draggable with kind and data returns DomNode"):
    val node = DragDrop.draggable("item", "item-123")
    node shouldMatch { case _: DomNode =>
    }

  test("DragDrop.dropZone returns DomNode"):
    val node = DragDrop.dropZone { data =>
      ()
    }
    node shouldMatch { case _: DomNode =>
    }

  test("DragDrop.dropZone with accept filter returns DomNode"):
    val node =
      DragDrop.dropZone("item", "task") { data =>
        ()
      }
    node shouldMatch { case _: DomNode =>
    }

  test("DragDrop.fileDropZone returns DomNode"):
    val node = DragDrop.fileDropZone { files =>
      ()
    }
    node shouldMatch { case _: DomNode =>
    }

  test("DragDrop.state returns Rx[DragState]"):
    val state = DragDrop.state
    state shouldMatch { case _: Rx[?] =>
    }

  test("DragDrop.isDragging returns Rx[Boolean]"):
    val dragging = DragDrop.isDragging
    dragging shouldMatch { case _: Rx[?] =>
    }

  test("DragDrop.isDraggingNow returns Boolean"):
    val dragging = DragDrop.isDraggingNow
    dragging shouldBe false

  test("DragDrop.currentState returns DragState"):
    val state = DragDrop.currentState
    state shouldMatch { case DragState(_, _, _) =>
    }

  test("DragDrop.onDragStart returns DomNode"):
    val node = DragDrop.onDragStart(_ => ())
    node shouldMatch { case _: DomNode =>
    }

  test("DragDrop.onDragEnd returns DomNode"):
    val node = DragDrop.onDragEnd(_ => ())
    node shouldMatch { case _: DomNode =>
    }

  test("DragDrop.onDragOver returns DomNode"):
    val node = DragDrop.onDragOver(_ => ())
    node shouldMatch { case _: DomNode =>
    }

  test("DragDrop.onDragEnter returns DomNode"):
    val node = DragDrop.onDragEnter(_ => ())
    node shouldMatch { case _: DomNode =>
    }

  test("DragDrop.onDragLeave returns DomNode"):
    val node = DragDrop.onDragLeave(_ => ())
    node shouldMatch { case _: DomNode =>
    }

  test("Draggable can be attached to element"):
    val elem = div(DragDrop.draggable("item", "item-1"), "Drag me")
    elem shouldMatch { case _: RxElement =>
    }

  test("Drop zone can be attached to element"):
    var dropped: Option[DragData] = None
    val elem                      = div(
      DragDrop.dropZone { data =>
        dropped = Some(data)
      },
      "Drop here"
    )
    elem shouldMatch { case _: RxElement =>
    }

  test("File drop zone can be attached to element"):
    var files: Seq[dom.File] = Seq.empty
    val elem                 = div(
      DragDrop.fileDropZone { f =>
        files = f
      },
      "Drop files here"
    )
    elem shouldMatch { case _: RxElement =>
    }

  test("Draggable renders with draggable attribute"):
    val elem           = div(DragDrop.draggable("item", "123"), "Drag")
    val (node, cancel) = DomRenderer.createNode(elem)

    node match
      case e: dom.Element =>
        e.getAttribute("draggable") shouldBe "true"
      case _ =>
        fail("Expected Element")

    cancel.cancel

  test("DragDrop.state emits values reactively"):
    var result: DragState = DragState.empty
    val cancel            = DragDrop
      .state
      .run { v =>
        result = v
      }
    result shouldMatch { case DragState(_, _, _) =>
    }
    cancel.cancel

  test("DomNode.group creates DomNodeGroup"):
    val group = DomNode.group(
      HtmlTags.attr("draggable")("true"),
      HtmlTags.attr("class")("draggable")
    )
    group shouldMatch { case _: DomNodeGroup =>
    }

  test("DomNodeGroup renders all nodes"):
    val elem = div(
      DomNode.group(HtmlTags.attr("data-a")("1"), HtmlTags.attr("data-b")("2")),
      "Content"
    )
    val (node, cancel) = DomRenderer.createNode(elem)

    node match
      case e: dom.Element =>
        e.getAttribute("data-a") shouldBe "1"
        e.getAttribute("data-b") shouldBe "2"
      case _ =>
        fail("Expected Element")

    cancel.cancel

end DragDropTest
