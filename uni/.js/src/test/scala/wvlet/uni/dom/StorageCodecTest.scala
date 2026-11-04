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

// More cases than the compiler's default inline depth (32)
// format: off
enum Big derives StorageCodec:
  case
    C0, C1, C2, C3, C4, C5, C6, C7, C8, C9,
    C10, C11, C12, C13, C14, C15, C16, C17, C18, C19,
    C20, C21, C22, C23, C24, C25, C26, C27, C28, C29,
    C30, C31, C32, C33, C34, C35, C36, C37, C38, C39,
    C40, C41, C42, C43, C44, C45, C46, C47, C48, C49,
    C50, C51, C52, C53, C54, C55, C56, C57, C58, C59
// format: on

class StorageCodecTest extends UniTest:

  test("derive a codec for an enum with many cases") {
    val codec = summon[StorageCodec[Big]]
    codec.decode("C59") shouldBe Some(Big.C59)
    codec.encode(Big.C0) shouldBe "C0"
  }

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
    // The compiler names the case that has no singleton value
    errors.map(_.message).mkString shouldContain "singleton value available for Shape.Circle"
  }

end StorageCodecTest
