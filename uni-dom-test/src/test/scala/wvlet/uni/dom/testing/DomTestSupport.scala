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

import wvlet.uni.test.UniTest

/**
  * Mix into a `UniTest` to render and interact with `uni-dom` components in the headless browser.
  *
  * Use `mount(element)` to render; every mounted element is automatically unmounted after each test
  * via the `after` hook, so tests stay isolated. `fireEvent` and the query helpers are available
  * through the `wvlet.uni.dom.testing.*` import.
  *
  * {{{
  *   class MyUiTest extends UniTest with DomTestSupport:
  *     test("clicking +1 increments the counter"):
  *       val m = mount(Counter())
  *       fireEvent.click(m.getByText("+1"))
  *       m.text shouldContain "count=1"
  * }}}
  *
  * This 3-line trait lives in test scope on purpose (it couples `DomTestSession` from the published
  * `uni` artifact to the `uni-test` framework). Downstream desktop apps can declare the identical
  * trait, or depend on a future `uni-dom-testkit` module.
  */
trait DomTestSupport extends UniTest with DomTestSession:
  override protected def after: Unit =
    cleanupMounted()
    super.after
