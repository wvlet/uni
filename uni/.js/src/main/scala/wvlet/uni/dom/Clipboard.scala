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

import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.util.{Failure, Success}

/**
  * Clipboard API for reading and writing to the system clipboard.
  *
  * Usage:
  * {{{
  *   import wvlet.uni.dom.all.*
  *
  *   // Write text to clipboard
  *   button(
  *     onclick -> { () =>
  *       Clipboard.writeText("Hello, World!").foreach { _ =>
  *         println("Copied!")
  *       }
  *     },
  *     "Copy"
  *   )
  *
  *   // Read text from clipboard
  *   button(
  *     onclick -> { () =>
  *       Clipboard.readText().foreach { text =>
  *         println(s"Pasted: \${text}")
  *       }
  *     },
  *     "Paste"
  *   )
  *
  *   // Listen to clipboard events
  *   div(
  *     Clipboard.onCopy { text =>
  *       println(s"User copied: \${text}")
  *     },
  *     Clipboard.onPaste { text =>
  *       println(s"User pasted: \${text}")
  *     },
  *     input(placeholder -> "Try copy/paste here")
  *   )
  *
  *   // Check if clipboard API is available
  *   if Clipboard.isSupported then
  *     // Use modern clipboard API
  *   else
  *     // Fall back to execCommand
  * }}}
  */
object Clipboard:
  import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

  /**
    * Check if the modern Clipboard API is supported.
    */
  def isSupported: Boolean =
    !js.isUndefined(dom.window.navigator.asInstanceOf[js.Dynamic].clipboard)

  /**
    * Write text to the clipboard.
    *
    * @param text
    *   The text to write
    * @return
    *   Future that completes when the text is written
    */
  def writeText(text: String): Future[Unit] =
    if isSupported then
      val clipboard = dom.window.navigator.asInstanceOf[js.Dynamic].clipboard
      val promise   = clipboard.writeText(text).asInstanceOf[js.Promise[Unit]]
      promise.toFuture
    else
      // Fallback using execCommand
      fallbackCopy(text)

  /**
    * Read text from the clipboard.
    *
    * @return
    *   Future containing the clipboard text
    */
  def readText(): Future[String] =
    if isSupported then
      val clipboard = dom.window.navigator.asInstanceOf[js.Dynamic].clipboard
      val promise   = clipboard.readText().asInstanceOf[js.Promise[String]]
      promise.toFuture
    else
      Future.failed(Exception("Clipboard API not supported and no fallback available for reading"))

  /**
    * Write text to clipboard and return an Rx that emits once when complete.
    *
    * @param text
    *   The text to write
    * @return
    *   Rx that emits Some(true) on success, Some(false) on failure, starts as None
    */
  def writeTextRx(text: String): Rx[Option[Boolean]] =
    val result = Rx.variable[Option[Boolean]](None)
    writeText(text).onComplete {
      case Success(_) =>
        result := Some(true)
      case Failure(_) =>
        result := Some(false)
    }
    result

  /**
    * Create a copy handler that intercepts copy events.
    *
    * @param handler
    *   Function called with the selected text when copy occurs
    * @return
    *   DomNode modifier
    */
  def onCopy(handler: String => Unit): DomNode =
    val oncopy = HtmlTags.handler[dom.ClipboardEvent]("oncopy")
    oncopy { (e: dom.ClipboardEvent) =>
      val text = getSelectedText(e.target)
      handler(text)
    }

  /**
    * Create a cut handler that intercepts cut events.
    *
    * @param handler
    *   Function called with the selected text when cut occurs
    * @return
    *   DomNode modifier
    */
  def onCut(handler: String => Unit): DomNode =
    val oncut = HtmlTags.handler[dom.ClipboardEvent]("oncut")
    oncut { (e: dom.ClipboardEvent) =>
      val text = getSelectedText(e.target)
      handler(text)
    }

  // Get selected text, handling input/textarea elements specially
  private def getSelectedText(target: dom.EventTarget): String =
    target match
      case input: dom.html.Input =>
        val start = input.selectionStart
        val end   = input.selectionEnd
        if start >= 0 && end >= 0 && end > start then
          input.value.substring(start, end)
        else
          ""
      case textarea: dom.html.TextArea =>
        val start = textarea.selectionStart
        val end   = textarea.selectionEnd
        if start >= 0 && end >= 0 && end > start then
          textarea.value.substring(start, end)
        else
          ""
      case _ =>
        val selection = dom.window.getSelection()
        if selection != null then
          selection.toString
        else
          ""

  /**
    * Create a paste handler that intercepts paste events.
    *
    * @param handler
    *   Function called with the pasted text
    * @return
    *   DomNode modifier
    */
  def onPaste(handler: String => Unit): DomNode =
    val onpaste = HtmlTags.handler[dom.ClipboardEvent]("onpaste")
    onpaste { (e: dom.ClipboardEvent) =>
      val data = e.clipboardData
      if data != null then
        val text = data.getData("text/plain")
        handler(text)
    }

  /**
    * Create a paste handler that provides the full ClipboardEvent for custom handling.
    *
    * @param handler
    *   Function called with the ClipboardEvent
    * @return
    *   DomNode modifier
    */
  def onPasteEvent(handler: dom.ClipboardEvent => Unit): DomNode =
    val onpaste = HtmlTags.handler[dom.ClipboardEvent]("onpaste")
    onpaste(handler)

  /**
    * Create a copy handler that customizes what gets copied.
    *
    * @param getText
    *   Function that returns the text to copy (called on copy event)
    * @return
    *   DomNode modifier
    */
  def copyAs(getText: () => String): DomNode =
    val oncopy = HtmlTags.handler[dom.ClipboardEvent]("oncopy")
    oncopy { (e: dom.ClipboardEvent) =>
      e.preventDefault()
      val text = getText()
      e.clipboardData.setData("text/plain", text)
    }

  /**
    * Create a cut handler that customizes what gets cut.
    *
    * @param getText
    *   Function that returns the text to cut (called on cut event)
    * @return
    *   DomNode modifier
    */
  def cutAs(getText: () => String): DomNode =
    val oncut = HtmlTags.handler[dom.ClipboardEvent]("oncut")
    oncut { (e: dom.ClipboardEvent) =>
      e.preventDefault()
      val text = getText()
      e.clipboardData.setData("text/plain", text)
    }

  /**
    * Create a click handler that copies text to clipboard.
    *
    * @param text
    *   The text to copy when clicked
    * @param onSuccess
    *   Optional callback when copy succeeds
    * @param onFailure
    *   Optional callback when copy fails
    * @return
    *   DomNode modifier
    */
  def copyOnClick(
      text: String,
      onSuccess: () => Unit = () => (),
      onFailure: Throwable => Unit = _ => ()
  ): DomNode =
    val onclick = HtmlTags.handler[dom.MouseEvent]("onclick")
    onclick { (_: dom.MouseEvent) =>
      writeText(text).onComplete {
        case Success(_) =>
          onSuccess()
        case Failure(e) =>
          onFailure(e)
      }
    }

  /**
    * Create a click handler that copies dynamic text to clipboard.
    *
    * @param getText
    *   Function that returns the text to copy
    * @param onSuccess
    *   Optional callback when copy succeeds
    * @param onFailure
    *   Optional callback when copy fails
    * @return
    *   DomNode modifier
    */
  def copyOnClickDynamic(
      getText: () => String,
      onSuccess: () => Unit = () => (),
      onFailure: Throwable => Unit = _ => ()
  ): DomNode =
    val onclick = HtmlTags.handler[dom.MouseEvent]("onclick")
    onclick { (_: dom.MouseEvent) =>
      writeText(getText()).onComplete {
        case Success(_) =>
          onSuccess()
        case Failure(e) =>
          onFailure(e)
      }
    }

  // Fallback copy using execCommand (for older browsers)
  private def fallbackCopy(text: String): Future[Unit] =
    val p        = Promise[Unit]()
    val textarea = dom.document.createElement("textarea").asInstanceOf[dom.html.TextArea]
    textarea.value = text
    textarea.style.position = "fixed"
    textarea.style.left = "-9999px"
    textarea.style.top = "-9999px"
    dom.document.body.appendChild(textarea)
    textarea.focus()
    textarea.select()
    try
      val success = dom.document.execCommand("copy")
      if success then
        p.success(())
      else
        p.failure(Exception("execCommand('copy') failed"))
    catch
      case e: Throwable =>
        p.failure(e)
    finally
      dom.document.body.removeChild(textarea)
    p.future

end Clipboard
