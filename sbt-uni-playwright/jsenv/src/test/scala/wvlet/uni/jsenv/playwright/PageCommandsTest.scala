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
package wvlet.uni.jsenv.playwright

import java.nio.file.Paths

class PageCommandsTest extends munit.FunSuite:

  private def args(name: String) = java.util.Map.of[String, Object]("name", name)

  test("accepts plain file-name stems"):
    assertEquals(PageCommands.validName(args("dialog-open_v1.2")), "dialog-open_v1.2")

  test("rejects names that could escape the output directory"):
    Seq("../evil", "a/b", "..", "a..b", "", "c:\\x").foreach { n =>
      intercept[IllegalArgumentException](PageCommands.validName(args(n)))
    }

  test("rejects a missing name"):
    intercept[IllegalArgumentException](PageCommands.validName(java.util.Map.of[String, Object]()))

  test("within keeps paths under the base directory"):
    val base = Paths.get("target", "shots").toAbsolutePath.normalize()
    assertEquals(PageCommands.within(base, "a.png"), base.resolve("a.png"))
    intercept[IllegalArgumentException](PageCommands.within(base, "../a.png"))

  private def kv(entries: (String, Object)*): java.util.Map[String, Object] =
    val m = java.util.HashMap[String, Object]()
    entries.foreach((k, v) => m.put(k, v))
    m

  test("required rejects missing or empty arguments"):
    assertEquals(PageCommands.required(kv("selector" -> "#a"), "selector"), "#a")
    intercept[IllegalArgumentException](PageCommands.required(kv(), "selector"))
    intercept[IllegalArgumentException](PageCommands.required(kv("text" -> ""), "text"))

  test("dimension accepts positive integers only"):
    assertEquals(PageCommands.dimension(kv("width" -> Integer.valueOf(640)), "width"), 640)
    assertEquals(
      PageCommands.dimension(kv("width" -> java.lang.Double.valueOf(480.0)), "width"),
      480
    )
    Seq[Object](Integer.valueOf(0), Integer.valueOf(-1), java.lang.Double.valueOf(1.5), "640")
      .foreach { v =>
        intercept[IllegalArgumentException](PageCommands.dimension(kv("width" -> v), "width"))
      }
    intercept[IllegalArgumentException](PageCommands.dimension(kv(), "width"))

  test("timeout defaults to 5s and rejects negative values"):
    assertEquals(PageCommands.timeout(kv()), PageCommands.defaultTimeoutMillis)
    assertEquals(PageCommands.timeout(kv("timeout" -> Integer.valueOf(100))), 100.0)
    intercept[IllegalArgumentException](PageCommands.timeout(kv("timeout" -> Integer.valueOf(-1))))

end PageCommandsTest
