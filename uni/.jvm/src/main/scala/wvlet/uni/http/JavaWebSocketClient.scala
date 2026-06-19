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

import wvlet.uni.rx.Rx

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, CompletionStage}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Promise}

/**
  * JVM WebSocket client backed by `java.net.http.WebSocket` (Java 11+), which performs the
  * handshake, masking, and framing. This adapter bridges the JDK listener/socket to the shared
  * [[WebSocketHandler]] / [[WebSocketContext]].
  */
class JavaWebSocketClient extends WebSocketClient:

  private given ExecutionContext = ExecutionContext.parasitic

  // pingIntervalMillis (client heartbeat) is wired in a follow-up PR; ignored for now.
  override def connect(
      uri: String,
      handler: WebSocketHandler,
      pingIntervalMillis: Int
  ): Rx[WebSocketContext] =
    val opened   = Promise[WebSocketContext]()
    val listener = JavaWebSocketListener(handler, Request.get(uri), opened)
    JavaWebSocketClient
      .sharedHttpClient
      .newWebSocketBuilder()
      .buildAsync(URI.create(uri), listener)
      .exceptionally { e =>
        // buildAsync wraps the cause in a CompletionException.
        opened.tryFailure(Option(e.getCause).getOrElse(e))
        null
      }
    Rx.future(opened.future)

end JavaWebSocketClient

object JavaWebSocketClient:
  // One HttpClient (and its thread pools) shared across all client instances — Http.webSocketClient
  // builds a new JavaWebSocketClient per call, so a per-instance HttpClient would leak threads.
  private lazy val sharedHttpClient: HttpClient = HttpClient.newHttpClient()

  def apply(): JavaWebSocketClient = new JavaWebSocketClient()

/**
  * JDK `WebSocket.Listener` that reassembles message fragments and delivers them to a
  * [[WebSocketHandler]], guaranteeing `onClose` exactly once.
  */
private class JavaWebSocketListener(
    handler: WebSocketHandler,
    connectRequest: Request,
    opened: Promise[WebSocketContext]
) extends WebSocket.Listener:

  private val textBuffer   = StringBuilder()
  private val binaryBuffer = java.io.ByteArrayOutputStream()
  private val closed       = AtomicBoolean(false)
  @volatile
  private var ctx: WebSocketContext = null

  override def onOpen(webSocket: WebSocket): Unit =
    // Request all messages up front so the JDK keeps delivering without per-message request() calls.
    webSocket.request(Long.MaxValue)
    val c = JavaWebSocketContext(webSocket, connectRequest)
    ctx = c
    try
      handler.onOpen(c)
    catch
      case scala.util.control.NonFatal(e) =>
        WebSocketDispatcher.safeOnError(handler, c, e)
    opened.trySuccess(c)

  override def onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
    textBuffer.append(data)
    if last then
      val message = textBuffer.toString
      textBuffer.setLength(0)
      deliver(() => handler.onTextMessage(ctx, message))
    null

  override def onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage[?] =
    val chunk = new Array[Byte](data.remaining)
    data.get(chunk)
    binaryBuffer.write(chunk)
    if last then
      val message = binaryBuffer.toByteArray
      binaryBuffer.reset()
      deliver(() => handler.onBinaryMessage(ctx, message))
    null

  override def onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
    notifyClose()
    null

  override def onError(webSocket: WebSocket, error: Throwable): Unit =
    // Transport/protocol errors map to onClose (not onError), matching the server backends — onError
    // is reserved for exceptions thrown by handler callbacks (see WebSocketDispatcher). A pre-open
    // failure still surfaces on the connect Rx via the promise.
    opened.tryFailure(error)
    notifyClose()

  private def deliver(action: () => Unit): Unit =
    try
      action()
    catch
      case scala.util.control.NonFatal(e) =>
        WebSocketDispatcher.safeOnError(handler, ctx, e)

  private def notifyClose(): Unit =
    if closed.compareAndSet(false, true) && ctx != null then
      try
        handler.onClose(ctx)
      catch
        case scala.util.control.NonFatal(_) =>
          ()

end JavaWebSocketListener

/**
  * [[WebSocketContext]] over a JDK `java.net.http.WebSocket`. `request` is the connect request.
  * Sends are serialized through a future chain (the JDK forbids overlapping sends).
  */
private class JavaWebSocketContext(webSocket: WebSocket, override val request: Request)
    extends WebSocketContext:

  // The JDK WebSocket requires each send to complete before the next is issued (else it throws
  // IllegalStateException), so serialize sends through a single CompletableFuture chain. The
  // `exceptionally` keeps the chain alive after a failed send rather than wedging all later sends.
  private val sendLock                                = Object()
  private var sendChain: CompletableFuture[WebSocket] = CompletableFuture.completedFuture(webSocket)

  private def enqueue(send: WebSocket => CompletableFuture[WebSocket]): Unit = sendLock
    .synchronized {
      sendChain = sendChain.thenCompose(ws => send(ws)).exceptionally(_ => webSocket)
    }

  override def send(text: String): Unit = enqueue(_.sendText(text, true))

  override def send(data: Array[Byte]): Unit = enqueue(_.sendBinary(ByteBuffer.wrap(data), true))

  override def close(): Unit = close(WebSocket.NORMAL_CLOSURE, "")

  override def close(statusCode: Int, reason: String): Unit = enqueue(
    _.sendClose(statusCode, reason)
  )

end JavaWebSocketContext
