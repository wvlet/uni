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
package wvlet.uni.design.lifecycle

import wvlet.uni.design.Design
import wvlet.uni.test.UniTest

import scala.collection.mutable.ListBuffer

object EagerInitFailureTest:
  val events = ListBuffer.empty[String]

  class Resource extends AutoCloseable:
    events += "open resource"
    override def close(): Unit = events += "close resource"

  class LeakyResource extends AutoCloseable:
    events += "open leaky resource"
    override def close(): Unit = throw IllegalStateException("close failed")

  class Broken(r: Resource):
    throw IllegalStateException("init failed")

  class BrokenAfterLeaky(r: LeakyResource):
    throw IllegalStateException("init failed")

/**
  * A session that fails while eagerly building its bindings is never returned to the caller, so it
  * must close what it had already built itself.
  */
class EagerInitFailureTest extends UniTest:
  import EagerInitFailureTest.*

  private val design =
    Design.newSilentDesign.bindSingleton[Resource].bindSingleton[Broken].withProductionMode

  test("newSession closes the objects built before an eager binding failed") {
    events.clear()
    val e = intercept[Throwable](design.newSession)
    events.toList shouldBe List("open resource", "close resource")
    e.getMessage shouldContain "init failed"
  }

  test("withSession closes them too") {
    events.clear()
    intercept[Throwable](design.withSession(_ => fail("the session should not start")))
    events.toList shouldBe List("open resource", "close resource")
  }

  test("an error while closing is attached to the original failure") {
    events.clear()
    val d =
      Design
        .newSilentDesign
        .bindSingleton[LeakyResource]
        .bindSingleton[BrokenAfterLeaky]
        .withProductionMode
    val e = intercept[Throwable](d.newSession)
    e.getMessage shouldContain "init failed"
    e.getSuppressed.map(_.getMessage).exists(_.contains("close failed")) shouldBe true
  }

end EagerInitFailureTest
