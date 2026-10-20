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
package wvlet.uni.dom.testing

import wvlet.uni.rx.Rx

import scala.concurrent.Promise
import scala.scalajs.concurrent.JSExecutionContext

/**
  * Wait for the first value of `rx` that satisfies `p`, failing with `Rx.TimeoutException` after
  * `timeoutMillis`. Browser events such as `resize` or a media-query `change` arrive in a later
  * rendering step, not synchronously with the action that caused them.
  */
def awaitValue[A](rx: Rx[A], timeoutMillis: Long = 2000)(p: A => Boolean): Rx[A] = Rx
  .single(())
  .flatMap { _ =>
    val found = Promise[A]()
    val c     = rx.run(v =>
      if p(v) then
        found.trySuccess(v)
    )
    Rx.future(found.future)(using JSExecutionContext.queue)
      .timeout(timeoutMillis)
      .transformRx { result =>
        c.cancel
        Rx.fromTry(result)
      }
  }
