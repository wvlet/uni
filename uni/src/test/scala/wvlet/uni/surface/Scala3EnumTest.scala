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
package wvlet.uni.surface

import wvlet.uni.test.UniTest
import wvlet.uni.test.empty
import wvlet.uni.test.defined
import scala.jdk.CollectionConverters.*

object Scala3EnumTest extends UniTest:

  enum Color:
    case Red,
      Green,
      Blue

  enum Status(val code: Int):
    case Ok            extends Status(200)
    case Other(c: Int) extends Status(c)

  test("scala 3 enum with a parameterized case") {
    // A parameterized case suppresses the synthesized valueOf, so names can't be resolved
    val s = Surface.of[Status]
    s.isEnum shouldBe true
    s shouldMatch { case e: EnumSurface =>
      e.stringExtractor(classOf[Status], "Ok") shouldBe None
    }
  }

  test("surface of a type with a field of an enum with a parameterized case") {
    case class Holder(status: Status)
    val s = Surface.of[Holder]
    s.params.map(_.surface.isEnum) shouldBe Seq(true)
  }

  test("scala 3 enum") {
    val s = Surface.of[Color]

    s.name shouldBe "Color"
    s.fullName shouldBe "wvlet.uni.surface.Scala3EnumTest.Color"
    s.params shouldBe empty

    s.isEnum shouldBe true
  }

end Scala3EnumTest
