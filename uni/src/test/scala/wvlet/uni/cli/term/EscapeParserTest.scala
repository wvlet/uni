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
package wvlet.uni.cli.term

import wvlet.uni.test.UniTest

import scala.collection.mutable

class EscapeParserTest extends UniTest:
  // Records actions as readable strings
  private class Recorder extends EscapeHandler:
    val actions                              = mutable.ArrayBuffer.empty[String]
    override def print(codePoint: Int): Unit =
      actions += s"print(${String(Character.toChars(codePoint))})"

    override def execute(code: Int): Unit = actions += s"exec(${code})"
    override def csi(
        params: CsiParams,
        prefix: Char,
        intermediates: String,
        finalChar: Char
    ): Unit =
      val ps = (0 until params.size).map(i =>
        s"${
            if params.isSubParam(i) then
              ":"
            else
              ""
          }${params(i, -1)}"
      )
      val pre =
        if prefix == 0 then
          ""
        else
          prefix.toString
      actions += s"csi(${pre}${ps.mkString(",")}${intermediates}${finalChar})"

    override def esc(intermediates: String, finalChar: Char): Unit =
      actions += s"esc(${intermediates}${finalChar})"

    override def osc(data: String): Unit = actions += s"osc(${data})"

  end Recorder

  private def parse(chunks: String*): Seq[String] =
    val r = Recorder()
    val p = EscapeParser(r)
    chunks.foreach(c => p.feed(c.getBytes("UTF-8")))
    r.actions.toSeq

  test("print text and execute controls") {
    parse("a\r\nb") shouldBe Seq("print(a)", "exec(13)", "exec(10)", "print(b)")
  }

  test("parse CSI parameters, defaults, and private markers") {
    parse("\u001b[1;2H") shouldBe Seq("csi(1,2H)")
    parse("\u001b[;5H") shouldBe Seq("csi(-1,5H)")
    parse("\u001b[H") shouldBe Seq("csi(H)")
    parse("\u001b[?1049h") shouldBe Seq("csi(?1049h)")
    parse("\u001b[2 q") shouldBe Seq("csi(2 q)")
  }

  test("parse colon sub-parameters") {
    parse("\u001b[38:2::255:0:0m") shouldBe Seq("csi(38,:2,:-1,:255,:0,:0m)")
  }

  test("parse escape sequences with intermediates") {
    parse("\u001b7\u001b(0") shouldBe Seq("esc(7)", "esc((0)")
  }

  test("parse OSC terminated by BEL or ST") {
    parse("\u001b]0;title\u0007") shouldBe Seq("osc(0;title)")
    parse("\u001b]2;日本\u001b\\x") shouldBe Seq("osc(2;日本)", "print(x)")
  }

  test("ignore DCS strings") {
    parse("\u001bPq#0;2;0;0;0\u001b\\ok") shouldBe Seq("print(o)", "print(k)")
  }

  test("decode UTF-8 and sequences split across feeds") {
    val bytes = "日\u001b[31m".getBytes("UTF-8")
    val r     = Recorder()
    val p     = EscapeParser(r)
    bytes.foreach(b => p.feed(Array(b)))
    r.actions.toSeq shouldBe Seq("print(日)", "csi(31m)")
  }

  test("replace invalid UTF-8") {
    val r = Recorder()
    EscapeParser(r).feed(Array(0xff.toByte, 'a'.toByte))
    r.actions.toSeq shouldBe Seq("print(�)", "print(a)")
  }

  test("CAN aborts a sequence and ESC restarts one") {
    parse("\u001b[3\u0018x") shouldBe Seq("print(x)")
    parse("\u001b[3\u001b[4m") shouldBe Seq("csi(4m)")
  }

  test("CAN after ESC inside an OSC string does not swallow the next character") {
    parse("\u001b]0;t\u001b\u0018abc") shouldBe Seq("print(a)", "print(b)", "print(c)")
  }

end EscapeParserTest
