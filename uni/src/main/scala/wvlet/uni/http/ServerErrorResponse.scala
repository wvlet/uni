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

import java.util.concurrent.atomic.AtomicReference

/**
  * Decorations a filter registers for the server's generic 500. When a handler fails without
  * recovering, the server renders the 500 itself, after every filter has returned, so a filter
  * can't add headers to it the usual way. A filter registers a decoration here instead (e.g.
  * [[CorsFilter]] adds its `Access-Control-*` headers) and passes the failure on unchanged, so the
  * server and an outer [[HttpAccessLogFilter]] still see the handler's exception.
  *
  * Every server backend attaches one to each request under [[ServerErrorResponse.Key]] before
  * calling the handler.
  */
private[http] class ServerErrorResponse:
  private val decoration = AtomicReference[Response => Response](identity)

  def decorate(f: Response => Response): Unit = decoration.getAndUpdate(_.andThen(f))

  def apply(response: Response): Response = decoration.get()(response)

  /** The request to pass to the handler, with this holder attached */
  def attachTo(request: Request): Request = request.withAttribute(ServerErrorResponse.Key, this)

private[http] object ServerErrorResponse:
  val Key: RequestAttribute[ServerErrorResponse] = RequestAttribute("serverErrorResponse")

  /** Register `f` on the request's holder, if the server attached one */
  def decorate(request: Request, f: Response => Response): Unit = request
    .attribute(Key)
    .foreach(_.decorate(f))
