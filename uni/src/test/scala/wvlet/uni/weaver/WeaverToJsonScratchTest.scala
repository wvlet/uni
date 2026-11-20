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
package wvlet.uni.weaver

import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.msgpack.spi.Unpacker
import wvlet.uni.surface.Surface
import wvlet.uni.test.UniTest

object WeaverToJsonScratchTest:
  case class Item(id: Long, name: String)
  case class Wrapper(label: String)

  /** Packs a Wrapper as a string holding another value's JSON, calling toJson while packing */
  object NestedWeaver extends Weaver[Wrapper]:
    override def pack(p: Packer, v: Wrapper, config: WeaverConfig): Unit = p.packString(
      Weaver.of[Item].toJson(Item(1, v.label))
    )

    override def unpack(u: Unpacker, context: WeaverContext): Unit = u.skipValue

class WeaverToJsonScratchTest extends UniTest:
  import WeaverToJsonScratchTest.*

  test("toJson inside a weaver's pack gets its own packer") {
    NestedWeaver.toJson(Wrapper("x")) shouldBe "\"{\\\"id\\\":1,\\\"name\\\":\\\"x\\\"}\""
    Weaver.of[Item].toJson(Item(2, "after")) shouldBe """{"id":2,"name":"after"}"""
  }

  test("toJson gives the same JSON as unweaving the MessagePack form") {
    val w     = Weaver.fromSurface(Surface.of[Seq[Item]]).asInstanceOf[Weaver[Seq[Item]]]
    val items = (1 to 200).map(i => Item(i, s"item ${i} café"))
    w.toJson(items) shouldBe codec.JSONWeaver.unweave(w.toMsgPack(items))
    // Values larger than the kept scratch buffer still render, and later calls start clean
    val big = Seq(Item(1, "x" * (2 << 20)))
    w.toJson(big) shouldBe codec.JSONWeaver.unweave(w.toMsgPack(big))
    w.toJson(Seq(Item(3, "small"))) shouldBe """[{"id":3,"name":"small"}]"""
  }

  test("toJsonBytes gives toJson's UTF-8 bytes, reusing its builder across calls") {
    val w = Weaver.fromSurface(Surface.of[Seq[Item]]).asInstanceOf[Weaver[Seq[Item]]]
    Seq(
      Seq.empty[Item],
      Seq(Item(1, "plain")),
      Seq(Item(2, "café 日本語 🎉"), Item(3, "quote \" and \\ and \n")),
      (1 to 100).map(i => Item(i, s"item ${i}")),
      Seq(Item(4, "short"))
    ).foreach { items =>
      w.toJsonBytes(items).toSeq shouldBe w.toJson(items).getBytes("UTF-8").toSeq
    }
  }

end WeaverToJsonScratchTest
