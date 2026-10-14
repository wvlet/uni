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
package wvlet.uni.http

import wvlet.uni.log.LogSupport

/**
  * Handler for Server-Sent Events (SSE).
  *
  * Implement this trait to receive callbacks when SSE events are received from a server. The
  * handler provides methods for the full lifecycle of an SSE connection:
  *
  *   - `onConnect`: Called when the SSE connection is established
  *   - `onEvent`: Called for each received event
  *   - `onError`: Called when an error occurs
  *   - `onCompletion`: Called when the stream ends normally
  *
  * Example:
  * {{{
  * val handler = new ServerSentEventHandler {
  *   override def onEvent(e: ServerSentEvent): Unit = {
  *     println(s"Received event: \${e.data}")
  *   }
  * }
  *
  * client.sendSSE(request.withEventHandler(handler))
  * }}}
  */
trait ServerSentEventHandler extends LogSupport:
  /**
    * Called when the SSE connection is established and the initial response is received.
    *
    * @param response
    *   The HTTP response from the server
    */
  def onConnect(response: HttpResponse): Unit = ()

  /**
    * Called when an SSE event is received.
    *
    * @param e
    *   The received event
    */
  def onEvent(e: ServerSentEvent): Unit

  /**
    * Called when an error occurs during SSE processing.
    *
    * The default implementation logs the error.
    *
    * @param e
    *   The exception that occurred
    */
  def onError(e: Throwable): Unit = error(s"SSE error: ${e.getMessage}", e)

  /**
    * Called when the SSE stream ends normally (server closes the connection).
    */
  def onCompletion(): Unit = ()

end ServerSentEventHandler

object ServerSentEventHandler:
  /**
    * An empty handler that does nothing. Useful as a default or placeholder.
    */
  val empty: ServerSentEventHandler =
    new ServerSentEventHandler:
      override def onEvent(e: ServerSentEvent): Unit = ()
      override def onError(e: Throwable): Unit       = ()

  /**
    * Creates a handler that only processes events.
    *
    * @param f
    *   Function to call for each event
    */
  def apply(f: ServerSentEvent => Unit): ServerSentEventHandler =
    new ServerSentEventHandler:
      override def onEvent(e: ServerSentEvent): Unit = f(e)

end ServerSentEventHandler
