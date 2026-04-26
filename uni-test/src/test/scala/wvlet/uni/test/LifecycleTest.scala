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

/**
  * Verifies that the lifecycle hooks (beforeAll/afterAll/before/after) fire in the expected order
  * around test bodies. Each hook records a marker on a class-level mutable list and the tests
  * assert against the running trace.
  *
  * The runner invokes `beforeAll` once before the test queue, `afterAll` once after it, and
  * `before` / `after` around every individual `test(...)` body. We can verify the per-test hooks by
  * reading the trace inside the test body itself.
  */
class LifecycleTest extends UniTest:
  private val trace = scala.collection.mutable.ListBuffer.empty[String]

  override protected def beforeAll: Unit = trace += "beforeAll"
  override protected def afterAll: Unit  = trace += "afterAll"
  override protected def before: Unit    = trace += "before"
  override protected def after: Unit     = trace += "after"

  test("beforeAll has already fired before the first test") {
    trace.toList shouldBe List("beforeAll", "before")
  }

  test("before fires again for subsequent tests") {
    // Trace from the previous test: beforeAll, before, after; then before fires again.
    trace.toList shouldBe List("beforeAll", "before", "after", "before")
  }

  test("after has fired between tests") {
    // Confirms the previous test's `after` ran before this one's `before`.
    val expected = List("beforeAll", "before", "after", "before", "after", "before")
    trace.toList shouldBe expected
  }

end LifecycleTest
