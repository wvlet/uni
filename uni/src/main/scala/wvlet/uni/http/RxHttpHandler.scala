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

/**
  * Async HTTP handler that returns Rx[Response]. This is the platform-neutral server-side contract
  * implemented by every backend (Netty on JVM, Node.js on Scala.js, ...).
  */
trait RxHttpHandler:
  def handle(request: Request): Rx[Response]

object RxHttpHandler:

  def apply(f: Request => Rx[Response]): RxHttpHandler = (request: Request) => f(request)

  def fromSync(handler: HttpHandler): RxHttpHandler =
    (request: Request) => Rx.single(handler.handle(request))

  def fromFunction(f: Request => Response): RxHttpHandler =
    (request: Request) => Rx.single(f(request))

  val notFound: RxHttpHandler = _ => Rx.single(Response.notFound)

/**
  * Async filter for transforming HTTP requests and responses
  */
trait RxHttpFilter:
  def apply(request: Request, next: RxHttpHandler): Rx[Response]

  def andThen(other: RxHttpFilter): RxHttpFilter =
    val self = this
    (request: Request, next: RxHttpHandler) =>
      self.apply(request, (req: Request) => other.apply(req, next))

object RxHttpFilter:
  val identity: RxHttpFilter = (request, next) => next.handle(request)

  def apply(f: (Request, RxHttpHandler) => Rx[Response]): RxHttpFilter =
    (request: Request, next: RxHttpHandler) => f(request, next)

  def chain(filters: Seq[RxHttpFilter]): RxHttpFilter =
    filters.foldRight(identity) { (filter, acc) =>
      filter.andThen(acc)
    }
