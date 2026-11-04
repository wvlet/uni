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
package wvlet.uni.dom

import wvlet.uni.test.UniTest

import scala.compiletime.testing.typeCheckErrors

enum Color derives StorageCodec:
  case Red,
    Green,
    Blue

enum Pane(val label: String) derives StorageCodec:
  case Chats extends Pane("Chats")
  case Files extends Pane("Files")

class StorageCodecTest extends UniTest:

  test("derive a codec that stores an enum value by its case name") {
    val codec = summon[StorageCodec[Color]]
    Color.values.map(codec.encode).toSeq shouldBe Seq("Red", "Green", "Blue")
    Color
      .values
      .foreach { c =>
        codec.decode(codec.encode(c)) shouldBe Some(c)
      }
  }

  test("derive a codec for an enum with constructor parameters on the enum") {
    val codec = summon[StorageCodec[Pane]]
    codec.encode(Pane.Files) shouldBe "Files"
    codec.decode("Chats") shouldBe Some(Pane.Chats)
  }

  test("decode an unknown case name as None") {
    val codec = summon[StorageCodec[Color]]
    codec.decode("Purple") shouldBe None
    codec.decode("red") shouldBe None
    codec.decode("") shouldBe None
  }

  test("reject enums with parameterized cases at compile time") {
    val errors = typeCheckErrors("""
      enum Shape derives wvlet.uni.dom.StorageCodec:
        case Circle(radius: Double)
        case Unit
      """)
    errors.map(_.message).mkString shouldContain "cases take no parameters"
  }

end StorageCodecTest
