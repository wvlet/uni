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

import org.scalajs.dom.{Headers, RequestInit, RequestRedirect}
import wvlet.uni.rx.{Rx, RxVar}

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.*
import scala.util.{Failure, Success}

/**
  * HTTP channel implementation using the Fetch API for Scala.js.
  *
  * This implementation supports only async operations since blocking is not possible in JavaScript.
  */
class FetchChannel extends HttpAsyncChannel:
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  override def send(request: HttpRequest, config: HttpClientConfig): Rx[HttpResponse] =
    val future = fetch(request).flatMap { resp =>
      val head = readResponseHead(resp)
      resp.arrayBuffer().toFuture.map(buf => head.withContent(toContent(buf)))
    }

    Rx.future(future)

  end send

  override def sendStreaming(request: HttpRequest, config: HttpClientConfig): Rx[Array[Byte]] =
    val source = Rx.variable[Option[Array[Byte]]](None)
    fetch(request).foreach(resp => readChunks(resp, source))
    source.filter(_.isDefined).map(_.get)

  override def sendStreaming(
      request: HttpRequest,
      config: HttpClientConfig,
      onResponse: HttpResponse => Unit
  ): Rx[Array[Byte]] =
    val source = Rx.variable[Option[Array[Byte]]](None)

    fetch(request).onComplete {
      case Failure(e) =>
        source.setException(e)
      case Success(resp) =>
        try
          val head = readResponseHead(resp)
          onResponse(head)
          if head.isSuccessful then
            readChunks(resp, source)
          else
            resp
              .arrayBuffer()
              .toFuture
              .onComplete {
                case Success(buf) =>
                  source.setException(HttpException.fromResponse(head.withContent(toContent(buf))))
                case Failure(e) =>
                  source.setException(e)
              }
        catch
          case e: Throwable =>
            source.setException(e)
    }

    source.filter(_.isDefined).map(_.get)

  end sendStreaming

  private def fetch(request: HttpRequest): Future[org.scalajs.dom.Response] =
    org.scalajs.dom.fetch(request.fullUri, buildRequestInit(request)).toFuture

  private def readResponseHead(resp: org.scalajs.dom.Response): HttpResponse =
    val headerBuilder = HttpMultiMap.newBuilder
    resp
      .headers
      .foreach { h =>
        headerBuilder.add(h(0), h(1))
      }
    Response(HttpStatus.ofCode(resp.status), headerBuilder.result())

  private def toContent(buf: ArrayBuffer): HttpContent =
    val bytes = new Int8Array(buf).toArray
    if bytes.isEmpty then
      HttpContent.Empty
    else
      HttpContent.bytes(bytes)

  private def readChunks(resp: org.scalajs.dom.Response, source: RxVar[Option[Array[Byte]]]): Unit =
    if resp.body == null then
      // No body, e.g., 204 No Content
      source.stop()
    else
      readBody(resp, source)

  private def readBody(resp: org.scalajs.dom.Response, source: RxVar[Option[Array[Byte]]]): Unit =
    val reader = resp.body.getReader()

    def process(): Unit = reader
      .read()
      .toFuture
      .onComplete {
        case Failure(e) =>
          reader.releaseLock()
          source.setException(e)
        case Success(result) =>
          if result.done then
            reader.releaseLock()
            source.stop()
          else
            try
              val chunk = new Int8Array(result.value.buffer).toArray
              source.set(Some(chunk))
              process()
            catch
              case e: Throwable =>
                reader.releaseLock()
                source.setException(e)
      }

    process()

  end readBody

  override def close(): Unit = ()

  private def buildRequestInit(request: HttpRequest): RequestInit =
    new RequestInit:
      method = request.method.name.asInstanceOf[org.scalajs.dom.HttpMethod]
      headers =
        new Headers(
          request
            .wireHeaders
            .entries
            .map { case (k, v) =>
              js.Array(k, v)
            }
            .toJSArray
        )
      // Redirects handled by DefaultHttpAsyncClient
      redirect = RequestRedirect.manual
      body =
        if request.content.isEmpty then
          js.undefined
        else
          request.content.toContentBytes.toTypedArray

end FetchChannel
