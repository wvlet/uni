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
package wvlet.uni.test

import wvlet.uni.rx.*
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * Thrown when an Rx returned from a test body completes without emitting a value, which means
  * assertions inside its map/flatMap never ran
  */
class RxCompletedWithoutValue
    extends Exception(
      "The Rx returned by the test completed without emitting a value, so its assertions did not run. " +
        "To test for an empty stream, assert on rx.lastOption.toRx instead"
    )

private[test] object RxTestRunner:

  /**
    * Resolve with the first value of the Rx. Unlike Rx.await, an empty completion fails rather than
    * resolving to (), so a stream that ends early cannot pass a test vacuously.
    */
  def firstValue(rx: RxOps[?])(using ExecutionContext): Future[Any] =
    val p          = Promise[Any]()
    val cancelable =
      RxRunner.runOnce(rx) {
        case OnNext(v) =>
          p.trySuccess(v)
        case OnError(e) =>
          p.tryFailure(e)
        case OnCompletion =>
          p.tryFailure(RxCompletedWithoutValue())
      }
    p.future.andThen(_ => cancelable.cancel)

end RxTestRunner
