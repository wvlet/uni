package wvlet.uni.json

import wvlet.uni.test.UniTest
import wvlet.uni.test.empty
import wvlet.uni.test.defined

case class Test(json: String, yaml: String)

class YAMLFormatterTest extends UniTest:

  val testData = Seq(
    // json, yaml
    // Empty objects and arrays are kept, so they read back as themselves, not null
    Test("""{}""", """{}"""),
    Test("""{"a":[]}""", """a: []"""),
    // Words YAML would read as another type are quoted
    Test(
      """{"a":"true","b":"null","c":"123","d":"yes"}""",
      """a: "true"
b: "null"
c: "123"
d: "yes""""
    ),
    Test(
      """{"a":"tab\there","b":"it's"}""",
      """a: "tab\there"
b: it's"""
    ),
    Test("""{"a":1}""", """a: 1"""),
    Test("""{"a":true}""", """a: true"""),
    Test("""{"a":false}""", """a: false"""),
    Test("""{"a":null}""", """a: null"""),
    Test("""{"a":1.1}""", """a: 1.1"""),
    Test("""{"a":"hello"}""", """a: hello"""),
    Test("""{"a":"hello world"}""", """a: hello world"""),
    Test(
      """{"/v1/users/{id}":{"$ref":"#/c"},"a":"x: y","b":" pad"}""",
      """/v1/users/{id}:
  $ref: "#/c"
a: "x: y"
b: " pad""""
    ),
    Test(
      json = """{"a":[1, 2]}""",
      yaml =
        """a:
               |  - 1
               |  - 2""".stripMargin
    ),
    Test(
      json = """{"a":[true, false]}""",
      yaml =
        """a:
               |  - true
               |  - false""".stripMargin
    ),
    Test(
      json = """{"a":[1, true, false, null, 1.1, "hello", "hello world"]}""",
      yaml =
        """a:
               |  - 1
               |  - true
               |  - false
               |  - null
               |  - 1.1
               |  - hello
               |  - hello world""".stripMargin
    ),
    Test(json = """{"a":{}}""", yaml = """a: {}"""),
    Test(
      json = """{"a":{"b":1}}""",
      yaml =
        """a:
               |  b: 1""".stripMargin
    ),
    Test(
      json = """{"a":{"b":1, "c":true}}""",
      yaml =
        """a:
               |  b: 1
               |  c: true""".stripMargin
    ),
    Test(
      json = """{"a":{"b":1, "c":true, "d":[1, 2, 3], "e":{"f":1}}}""",
      yaml =
        """a:
               |  b: 1
               |  c: true
               |  d:
               |    - 1
               |    - 2
               |    - 3
               |  e:
               |    f: 1""".stripMargin
    ),
    Test(
      json = """{"a":{"b":{"c":{"d":[1, 2, 3]}}}}""",
      yaml =
        """a:
               |  b:
               |    c:
               |      d:
               |        - 1
               |        - 2
               |        - 3""".stripMargin
    ),
    Test(json = """{"200":"ok"}}""", yaml = """"200": ok""".stripMargin),
    Test(
      json = """{"a":[{"b":1, "c":2}, {"b":3, "c":4}]}""",
      yaml =
        """a:
               |  - b: 1
               |    c: 2
               |  - b: 3
               |    c: 4""".stripMargin
    ),
    // A final `|` block needs a line break after it to keep its own
    Test(json = """{"a":"line 1\nline 2\n"}""", yaml = "a: |\n  line 1\n  line 2\n"),
    Test(
      json = """{"description":"ends with a line break\n"}""",
      yaml = "description: |\n  ends with a line break\n"
    ),
    Test(
      json = """{"description":"multi-line\nstrings"}""",
      yaml =
        """description: |-
          |  multi-line
          |  strings""".stripMargin
    ),
    Test(
      json = """{"description":"with quote(')"}""",
      yaml = """description: with quote(')""".stripMargin
    )
  )

  test("format json into YAML") {
    testData.foreach { x =>
      val json         = x.json
      val expectedYaml = x.yaml

      val yaml = YAMLFormatter.toYaml(json)
      yaml shouldBe expectedYaml
    }
  }

  test("written YAML reads back as the same value") {
    import wvlet.uni.text.yaml.YAML
    Seq("""{"<<":{"a":1},"b":"yes","200":"ok","c":[[],{}],"d":"line\n"}""", """{"x":[{"<<":1}]}""")
      .foreach { json =>
        val v = JSON.parseAny(json)
        YAML.parse(YAMLFormatter.toYaml(v)) shouldBe v
      }
    // A plain << still merges
    YAML.parse("base: &b {a: 1}\nm:\n  <<: *b\n  c: 2\n") shouldBe
      JSON.parseAny("""{"base":{"a":1},"m":{"c":2,"a":1}}""")
  }

end YAMLFormatterTest
