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

import org.scalajs.dom
import wvlet.uni.rx.Cancelable

/**
  * A handle to a `uni-dom` element rendered into a live (headless-browser) DOM for testing.
  *
  * The element is rendered into a fresh `<div>` container appended to `document.body`, so queries
  * and events behave exactly as they would in a running app. Call [[unmount]] (or use it as an
  * `AutoCloseable`) to detach the container and run Rx cleanup; [[DomTestSession]] does this
  * automatically after each test.
  *
  * Query methods are delegated from [[Query]], so `mounted.getByText("Save")` works directly.
  */
class Mounted(val container: dom.Element, private val cancelable: Cancelable) extends AutoCloseable:

  private val q: Query = Query(container)

  /** The first rendered element (the root of the mounted component), if any. */
  def root: Option[dom.Element] = Option(container.firstElementChild)

  /** The concatenated text content of the rendered subtree. */
  def text: String = container.textContent

  /** The inner HTML of the container (useful for debugging failed assertions). */
  def html: String = container.innerHTML

  /** Query helpers scoped to this mounted subtree. */
  def query: Query = q

  // Expose Query's getBy/queryBy methods directly on the handle.
  export q.{
    queryAll,
    query as querySelector,
    get,
    queryAllByTestId,
    queryByTestId,
    getByTestId,
    queryAllByText,
    queryByText,
    getByText
  }

  /** Detach the container from the DOM and cancel all Rx subscriptions. Idempotent. */
  def unmount(): Unit =
    cancelable.cancel
    if container.parentNode != null then
      container.parentNode.removeChild(container)

  override def close(): Unit = unmount()

end Mounted
