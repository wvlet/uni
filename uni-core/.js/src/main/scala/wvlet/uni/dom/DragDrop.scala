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
import org.scalajs.dom.DataTransfer
import wvlet.uni.rx.{Cancelable, Rx, RxVar}

import scala.scalajs.js
import scala.scalajs.js.JSON

/**
  * Data being transferred during a drag operation.
  *
  * @param kind
  *   Type identifier for the data (e.g., "item", "task", "file")
  * @param data
  *   The actual data being transferred (will be JSON serialized for transfer)
  * @param effectAllowed
  *   Allowed drop effects: "none", "copy", "move", "link", "copyMove", "copyLink", "linkMove",
  *   "all"
  */
case class DragData(kind: String, data: String, effectAllowed: String = "all")

/**
  * Current state of a drag operation.
  *
  * @param isDragging
  *   Whether a drag is currently in progress
  * @param data
  *   The data being dragged, if any
  * @param overElement
  *   The element currently being dragged over, if any
  */
case class DragState(isDragging: Boolean, data: Option[DragData], overElement: Option[dom.Element])

object DragState:
  val empty: DragState = DragState(isDragging = false, data = None, overElement = None)

/**
  * Drag and drop utilities for building interactive UIs.
  *
  * Usage:
  * {{{
  *   import wvlet.uni.dom.all.*
  *
  *   // Make an element draggable
  *   div(
  *     DragDrop.draggable("item", "item-123"),
  *     "Drag me"
  *   )
  *
  *   // Create a drop zone
  *   div(
  *     DragDrop.dropZone("item") { data =>
  *       println(s"Dropped: \${data.data}")
  *     },
  *     cls -> "drop-area",
  *     "Drop here"
  *   )
  *
  *   // File drop zone
  *   div(
  *     DragDrop.fileDropZone { files =>
  *       files.foreach(f => println(s"File: \${f.name}"))
  *     },
  *     "Drop files here"
  *   )
  *
  *   // React to drag state
  *   DragDrop.isDragging.map { dragging =>
  *     if dragging then div(cls -> "drag-overlay", "Drop anywhere")
  *     else DomNode.empty
  *   }
  * }}}
  */
object DragDrop:
  private val dataTypePrefix = "application/x-uni-"

  private class DragDropState extends Cancelable:
    private val stateVar: RxVar[DragState] = Rx.variable(DragState.empty)

    def state: Rx[DragState]       = stateVar
    def currentState: DragState    = stateVar.get
    def isDragging: Rx[Boolean]    = stateVar.map(_.isDragging)
    def currentlyDragging: Boolean = stateVar.get.isDragging

    def startDrag(data: DragData): Unit =
      stateVar := DragState(isDragging = true, data = Some(data), overElement = None)

    def setOverElement(elem: Option[dom.Element]): Unit =
      val current = stateVar.get
      if current.isDragging then
        stateVar := current.copy(overElement = elem)

    def endDrag(): Unit = stateVar := DragState.empty

    override def cancel: Unit = stateVar := DragState.empty

  end DragDropState

  private lazy val instance: DragDropState = DragDropState()

  /**
    * Reactive stream of the current drag state.
    */
  def state: Rx[DragState] = instance.state

  /**
    * Reactive stream indicating if a drag is in progress.
    */
  def isDragging: Rx[Boolean] = instance.isDragging

  /**
    * Check if a drag is currently in progress (synchronous).
    */
  def isDraggingNow: Boolean = instance.currentlyDragging

  /**
    * Get the current drag state (synchronous).
    */
  def currentState: DragState = instance.currentState

  /**
    * Make an element draggable with the specified data.
    *
    * @param data
    *   The DragData to transfer
    * @return
    *   DomNode modifier
    */
  def draggable(data: DragData): DomNode =
    val draggableAttr = HtmlTags.attr("draggable")
    val ondragstart   = HtmlTags.handler[dom.DragEvent]("ondragstart")
    val ondragend     = HtmlTags.handler[dom.DragEvent]("ondragend")

    DomNode.group(
      draggableAttr("true"),
      ondragstart { (e: dom.DragEvent) =>
        val dt = e.dataTransfer
        dt.effectAllowed = data.effectAllowed.asInstanceOf[dom.DataTransferEffectAllowedKind]
        dt.setData(s"${dataTypePrefix}${data.kind}", data.data)
        dt.setData("text/plain", data.data) // Fallback
        instance.startDrag(data)
      },
      ondragend { (_: dom.DragEvent) =>
        instance.endDrag()
      }
    )

  /**
    * Make an element draggable with the specified kind and data.
    *
    * @param kind
    *   Type identifier for the data
    * @param data
    *   The data to transfer (as string)
    * @return
    *   DomNode modifier
    */
  def draggable(kind: String, data: String): DomNode = draggable(DragData(kind, data))

  /**
    * Create a drop zone that accepts any draggable.
    *
    * @param onDrop
    *   Handler called when data is dropped
    * @return
    *   DomNode modifier
    */
  def dropZone(onDrop: DragData => Unit): DomNode = dropZoneInternal(
    acceptAll = true,
    Seq.empty,
    onDrop
  )

  /**
    * Create a drop zone that accepts specific kinds of data.
    *
    * @param accept
    *   Kinds of data to accept
    * @param onDrop
    *   Handler called when matching data is dropped
    * @return
    *   DomNode modifier
    */
  def dropZone(accept: String*)(onDrop: DragData => Unit): DomNode = dropZoneInternal(
    acceptAll = false,
    accept,
    onDrop
  )

  private def dropZoneInternal(
      acceptAll: Boolean,
      accept: Seq[String],
      onDrop: DragData => Unit
  ): DomNode =
    val ondragover  = HtmlTags.handler[dom.DragEvent]("ondragover")
    val ondragenter = HtmlTags.handler[dom.DragEvent]("ondragenter")
    val ondragleave = HtmlTags.handler[dom.DragEvent]("ondragleave")
    val ondropH     = HtmlTags.handler[dom.DragEvent]("ondrop")

    DomNode.group(
      ondragover { (e: dom.DragEvent) =>
        e.preventDefault() // Required to allow drop
        // Let browser determine appropriate dropEffect based on effectAllowed
        // Don't override dropEffect to avoid blocking copy/link drags
      },
      ondragenter { (e: dom.DragEvent) =>
        e.preventDefault()
        e.currentTarget match
          case elem: dom.Element =>
            instance.setOverElement(Some(elem))
          case _ =>
            ()
      },
      ondragleave { (e: dom.DragEvent) =>
        // Only clear if leaving the actual element (not a child)
        if e.currentTarget == e.target then
          instance.setOverElement(None)
      },
      ondropH { (e: dom.DragEvent) =>
        e.preventDefault()
        instance.setOverElement(None)

        val dt = e.dataTransfer

        // Try to find matching data
        val dataOpt =
          if acceptAll then
            // Try to get any uni data type
            val types = dt.types.toSeq.filter(_.startsWith(dataTypePrefix))
            types
              .headOption
              .map { t =>
                val kind = t.stripPrefix(dataTypePrefix)
                DragData(kind, dt.getData(t))
              }
              .orElse {
                // Fallback to text/plain
                val text = dt.getData("text/plain")
                if text.nonEmpty then
                  Some(DragData("text", text))
                else
                  None
              }
          else
            // Look for specific accepted types
            accept
              .flatMap { kind =>
                val data = dt.getData(s"${dataTypePrefix}${kind}")
                if data.nonEmpty then
                  Some(DragData(kind, data))
                else
                  None
              }
              .headOption

        dataOpt.foreach(onDrop)
        instance.endDrag()
      }
    )

  end dropZoneInternal

  /**
    * Create a drop zone for files from the operating system.
    *
    * @param onFiles
    *   Handler called with dropped files
    * @return
    *   DomNode modifier
    */
  def fileDropZone(onFiles: Seq[dom.File] => Unit): DomNode =
    val ondragover  = HtmlTags.handler[dom.DragEvent]("ondragover")
    val ondragenter = HtmlTags.handler[dom.DragEvent]("ondragenter")
    val ondragleave = HtmlTags.handler[dom.DragEvent]("ondragleave")
    val ondropH     = HtmlTags.handler[dom.DragEvent]("ondrop")

    DomNode.group(
      ondragover { (e: dom.DragEvent) =>
        e.preventDefault()
        e.dataTransfer.dropEffect = "copy".asInstanceOf[dom.DataTransferDropEffectKind]
      },
      ondragenter { (e: dom.DragEvent) =>
        e.preventDefault()
        // Start tracking drag state for external file drags
        if !instance.currentlyDragging then
          instance.startDrag(DragData("file", "", "copy"))
        e.currentTarget match
          case elem: dom.Element =>
            instance.setOverElement(Some(elem))
          case _ =>
            ()
      },
      ondragleave { (e: dom.DragEvent) =>
        if e.currentTarget == e.target then
          instance.setOverElement(None)
          // End drag state when leaving (for external file drags)
          instance.endDrag()
      },
      ondropH { (e: dom.DragEvent) =>
        e.preventDefault()
        instance.setOverElement(None)

        val files = e.dataTransfer.files
        if files.length > 0 then
          val fileSeq = (0 until files.length).map(files(_))
          onFiles(fileSeq)

        instance.endDrag()
      }
    )

  end fileDropZone

  /**
    * Create a handler for drag start events.
    *
    * @param handler
    *   Function called when drag starts
    * @return
    *   DomNode modifier
    */
  def onDragStart(handler: dom.DragEvent => Unit): DomNode =
    val ondragstart = HtmlTags.handler[dom.DragEvent]("ondragstart")
    ondragstart(handler)

  /**
    * Create a handler for drag end events.
    *
    * @param handler
    *   Function called when drag ends
    * @return
    *   DomNode modifier
    */
  def onDragEnd(handler: dom.DragEvent => Unit): DomNode =
    val ondragend = HtmlTags.handler[dom.DragEvent]("ondragend")
    ondragend(handler)

  /**
    * Create a handler for drag over events.
    *
    * @param handler
    *   Function called when dragging over the element
    * @return
    *   DomNode modifier
    */
  def onDragOver(handler: dom.DragEvent => Unit): DomNode =
    val ondragover = HtmlTags.handler[dom.DragEvent]("ondragover")
    ondragover(handler)

  /**
    * Create a handler for drag enter events.
    *
    * @param handler
    *   Function called when drag enters the element
    * @return
    *   DomNode modifier
    */
  def onDragEnter(handler: dom.DragEvent => Unit): DomNode =
    val ondragenter = HtmlTags.handler[dom.DragEvent]("ondragenter")
    ondragenter(handler)

  /**
    * Create a handler for drag leave events.
    *
    * @param handler
    *   Function called when drag leaves the element
    * @return
    *   DomNode modifier
    */
  def onDragLeave(handler: dom.DragEvent => Unit): DomNode =
    val ondragleave = HtmlTags.handler[dom.DragEvent]("ondragleave")
    ondragleave(handler)

  /**
    * Stop tracking drag state. Call this when the application is shutting down.
    */
  def stop(): Unit = instance.cancel

end DragDrop
