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

import wvlet.uni.dom.RxElement
import scala.collection.mutable.ListBuffer

/**
  * A framework-agnostic mixin that tracks [[Mounted]] elements so they can be unmounted together.
  *
  * Mount through this trait's [[mount]] instead of the top-level one, then call [[cleanupMounted]]
  * once per test to detach every container and run Rx cleanup — leaving `document.body` clean for
  * the next test, so tests don't leak DOM into one another.
  *
  * It is deliberately decoupled from `UniTest` (which lives in a separate module) so it can be
  * reused from any test framework. The `uni-dom-test` module provides `DomTestSupport`, which wires
  * this into `UniTest.after`.
  */
trait DomTestSession:
  private val mountedElements = ListBuffer.empty[Mounted]

  /** Mount an element and register it for automatic cleanup by [[cleanupMounted]]. */
  def mount(element: RxElement): Mounted =
    val m = DomTesting.mount(element)
    mountedElements += m
    m

  /** Unmount every element mounted through this session, in reverse order. */
  def cleanupMounted(): Unit =
    mountedElements
      .reverseIterator
      .foreach { m =>
        try
          m.unmount()
        catch
          case _: Throwable =>
            () // best-effort cleanup; never fail a test on teardown
      }
    mountedElements.clear()
