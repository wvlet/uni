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
package wvlet.uni.json

import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest

class JSONCTest extends UniTest:

  test("parse JSONC with line comments") {
    val jsonc =
      """{
      |  // This is a comment
      |  "key": "value"
      |}""".stripMargin
    val v = JSON.parse(jsonc)
    v shouldMatch { case obj: JSONObject =>
      obj.get("key") shouldBe Some(JSONString("value"))
    }
  }

  test("parse JSONC with block comments") {
    val jsonc =
      """{
      |  /* block comment */
      |  "key": 42
      |}""".stripMargin
    val v = JSON.parse(jsonc)
    v shouldMatch { case obj: JSONObject =>
      obj.get("key") shouldBe Some(JSONLong(42))
    }
  }

  test("parse JSONC with inline trailing comments") {
    val jsonc =
      """{
      |  "host": "localhost", // server host
      |  "port": 5432
      |}""".stripMargin
    val v = JSON.parse(jsonc)
    v shouldMatch { case obj: JSONObject =>
      obj.get("host") shouldBe Some(JSONString("localhost"))
      obj.get("port") shouldBe Some(JSONLong(5432))
      val hostVal = obj.v.find(_._1 == "host").get._2
      hostVal.trailingComment.isDefined shouldBe true
      hostVal.trailingComment.get.text shouldBe "// server host"
    }
  }

  test("parse JSONC with trailing commas in objects") {
    val jsonc = """{"a": 1, "b": 2,}"""
    val v     = JSON.parse(jsonc)
    v shouldMatch { case obj: JSONObject =>
      obj.get("a") shouldBe Some(JSONLong(1))
      obj.get("b") shouldBe Some(JSONLong(2))
    }
  }

  test("parse JSONC with trailing commas in arrays") {
    val jsonc = """[1, 2, 3,]"""
    val v     = JSON.parse(jsonc)
    v shouldMatch { case arr: JSONArray =>
      arr.size shouldBe 3
    }
  }

  test("parse JSONC with multi-line block comment") {
    val jsonc =
      """{
      |  /*
      |   * Multi-line
      |   * block comment
      |   */
      |  "key": "value"
      |}""".stripMargin
    val v = JSON.parse(jsonc)
    v shouldMatch { case obj: JSONObject =>
      obj.get("key") shouldBe Some(JSONString("value"))
    }
  }

  test("preserve leading comments on values") {
    val jsonc =
      """{
      |  // comment about name
      |  "name": "test"
      |}""".stripMargin
    val v = JSON.parse(jsonc)
    v shouldMatch { case obj: JSONObject =>
      val nameVal = obj.v.find(_._1 == "name").get._2
      nameVal.leadingComments.size shouldBe 1
      nameVal.leadingComments.head.text shouldBe "// comment about name"
      nameVal.leadingComments.head.isLineComment shouldBe true
      nameVal.leadingComments.head.commentBody shouldBe "comment about name"
    }
  }

  test("preserve trailing inline comments") {
    val jsonc = """[1, 2 /* count */]"""
    val v     = JSON.parse(jsonc)
    v shouldMatch { case arr: JSONArray =>
      arr.v(1).trailingComment.isDefined shouldBe true
      arr.v(1).trailingComment.get.isBlockComment shouldBe true
      arr.v(1).trailingComment.get.commentBody shouldBe "count"
    }
  }

  test("round-trip JSONC with comments via format") {
    val jsonc =
      """{
      |  // Database settings
      |  "host": "localhost", // server host
      |  "port": 5432
      |}""".stripMargin
    val v         = JSON.parse(jsonc)
    val formatted = JSON.format(v)
    formatted shouldContain "// Database settings"
    formatted shouldContain "// server host"
    formatted shouldContain "\"host\": \"localhost\""
  }

  test("toJSON strips comments") {
    val jsonc =
      """{
      |  // comment
      |  "key": "value" // trailing
      |}""".stripMargin
    val v    = JSON.parse(jsonc)
    val json = v.toJSON
    (json.contains("//")) shouldBe false
    json shouldContain "\"key\""
  }

  test("toJSONC includes comments") {
    val v = JSONString("hello")
    v.leadingComments = Seq(JSONComment("// greeting"))
    v.trailingComment = Some(JSONComment("// end"))
    val jsonc = v.toJSONC
    jsonc shouldContain "// greeting"
    jsonc shouldContain "\"hello\""
    jsonc shouldContain "// end"
  }

  test("JSONComment helper methods") {
    val line = JSONComment("// line comment")
    line.isLineComment shouldBe true
    line.isBlockComment shouldBe false
    line.commentBody shouldBe "line comment"

    val block = JSONComment("/* block comment */")
    block.isLineComment shouldBe false
    block.isBlockComment shouldBe true
    block.commentBody shouldBe "block comment"
  }

  test("parse standard JSON still works") {
    val json = """{"id": 1, "name": "test"}"""
    val v    = JSON.parse(json)
    v shouldMatch { case obj: JSONObject =>
      obj.get("id") shouldBe Some(JSONLong(1))
      obj.get("name") shouldBe Some(JSONString("test"))
    }
  }

  test("parseAny with JSONC comments") {
    JSON.parseAny("// comment\n42") shouldBe JSONLong(42)
    JSON.parseAny("/* block */ true") shouldBe JSONBoolean(true)
  }

  test("nested JSONC with comments") {
    val jsonc =
      """{
      |  "db": {
      |    // connection settings
      |    "host": "localhost",
      |    "port": 5432
      |  }
      |}""".stripMargin
    val v = JSON.parse(jsonc)
    v shouldMatch { case obj: JSONObject =>
      obj.get("db").get shouldMatch { case db: JSONObject =>
        val hostVal = db.v.find(_._1 == "host").get._2
        hostVal.leadingComments.size shouldBe 1
        hostVal.leadingComments.head.commentBody shouldBe "connection settings"
      }
    }
  }

  test("empty object and array with comments") {
    val obj = JSON.parse("{ /* empty */ }")
    obj shouldMatch { case _: JSONObject =>
    }
    obj.leadingComments.size shouldBe 1
    obj.leadingComments.head.commentBody shouldBe "empty"

    val arr = JSON.parse("[ /* empty */ ]")
    arr shouldMatch { case _: JSONArray =>
    }
    arr.leadingComments.size shouldBe 1
  }

  test("toJSONC recurses into nested structures") {
    val jsonc =
      """{
        |  // comment
        |  "key": "value"
        |}""".stripMargin
    val v      = JSON.parse(jsonc)
    val jsonc2 = v.toJSONC
    jsonc2 shouldContain "// comment"
    jsonc2 shouldContain "\"key\""
  }

  test("comments between array elements") {
    val jsonc =
      """[
      |  1,
      |  // separator
      |  2,
      |  3
      |]""".stripMargin
    val v = JSON.parse(jsonc)
    v shouldMatch { case arr: JSONArray =>
      arr.size shouldBe 3
      arr.v(1).leadingComments.size shouldBe 1
      arr.v(1).leadingComments.head.commentBody shouldBe "separator"
    }
  }

end JSONCTest
