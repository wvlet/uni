package wvlet.uni.text.yaml

import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest
import wvlet.uni.text.parser.TextParseException
import wvlet.uni.weaver.Weaver

case class ServerConfig(name: String, port: Int = 8080, tags: Seq[String] = Nil) derives Weaver

class YAMLTest extends UniTest:

  private def json(yaml: String): String =
    val v = YAML.parse(yaml)
    YAMLRoundTripTest.check(yaml)
    v.toJSON

  private def parseError(yaml: String): TextParseException = intercept[TextParseException] {
    YAML.parse(yaml)
  }

  test("parse scalars with the core schema") {
    json("a: 1") shouldBe """{"a":1}"""
    json("a: -12") shouldBe """{"a":-12}"""
    json("a: 0x1F") shouldBe """{"a":31}"""
    json("a: 0o17") shouldBe """{"a":15}"""
    json("a: 1.5") shouldBe """{"a":1.5}"""
    YAML.parse("a: 1e3") shouldBe JSONObject(Seq("a" -> JSONDouble(1000.0)))
    json("a: true") shouldBe """{"a":true}"""
    json("a: False") shouldBe """{"a":false}"""
    json("a: ~") shouldBe """{"a":null}"""
    json("a: null") shouldBe """{"a":null}"""
    json("a:") shouldBe """{"a":null}"""
    json("a: hello world") shouldBe """{"a":"hello world"}"""
    json("a: yes") shouldBe """{"a":"yes"}"""
    json("a: 1.2.3") shouldBe """{"a":"1.2.3"}"""
    json("a: http://example.com:8080/x") shouldBe """{"a":"http://example.com:8080/x"}"""
  }

  test("parse special floats") {
    YAML.parse("a: .inf") shouldBe JSONObject(Seq("a" -> JSONDouble(Double.PositiveInfinity)))
    YAML.parse("a: -.Inf") shouldBe JSONObject(Seq("a" -> JSONDouble(Double.NegativeInfinity)))
    YAML.parse("a: .nan") shouldMatch {
      case JSONObject(Seq(("a", JSONDouble(d)))) if d.isNaN =>
    }
  }

  test("parse an empty document as null") {
    YAML.parse("") shouldBe JSONNull()
    YAML.parse("# only a comment\n") shouldBe JSONNull()
    YAML.parse("---\n") shouldBe JSONNull()
  }

  test("parse nested block mappings and sequences") {
    val yaml =
      """# config
        |server:
        |  name: api   # inline comment
        |  ports:
        |    - 80
        |    - 443
        |  tls:
        |    enabled: true
        |users:
        |- name: alice
        |  roles: [admin, dev]
        |- name: bob
        |empty:
        |""".stripMargin
    json(yaml) shouldBe
      """{"server":{"name":"api","ports":[80,443],"tls":{"enabled":true}},"users":[{"name":"alice","roles":["admin","dev"]},{"name":"bob"}],"empty":null}"""
  }

  test("parse nested sequences") {
    json("- - a\n  - b\n- - c") shouldBe """[["a","b"],["c"]]"""
    json("-\n  x: 1\n-\n- 3") shouldBe """[{"x":1},null,3]"""
  }

  test("parse flow collections") {
    json("""{a: 1, "b": [x, y, {c: d}], e: }""") shouldBe
      """{"a":1,"b":["x","y",{"c":"d"}],"e":null}"""
    json("[a: 1, b]") shouldBe """[{"a":1},"b"]"""
    json("""{"a":1, 'b':2}""") shouldBe """{"a":1,"b":2}"""
    json("[\n  1,\n  2, # comment\n]") shouldBe "[1,2]"
    json("{a, b: 2}") shouldBe """{"a":null,"b":2}"""
    json("[]") shouldBe "[]"
    json("a: {}") shouldBe """{"a":{}}"""
  }

  test("parse quoted scalars") {
    json("""a: 'it''s'""") shouldBe """{"a":"it's"}"""
    json("a: \"\\_\\L\\P\"") shouldBe
      JSONObject(Seq("a" -> JSONString("\u00a0\u2028\u2029"))).toJSON
    json("""a: "tab\there\nline \u00e9 \x41 \U0001F600"""") shouldBe
      "{\"a\":\"tab\\there\\nline \u00e9 A \uD83D\uDE00\"}"
    json("a: \"123\"") shouldBe """{"a":"123"}"""
    json("a: 'multi\n  line\n\n  text'") shouldBe """{"a":"multi line\ntext"}"""
    json("a: \"joined\\\n  text\"") shouldBe """{"a":"joinedtext"}"""
    json("\"quoted key\": 1") shouldBe """{"quoted key":1}"""
  }

  test("parse multi-line plain scalars") {
    json("a: first\n  second\n\n  third\nb: 2") shouldBe """{"a":"first second\nthird","b":2}"""
    json("- one\n  two") shouldBe """["one two"]"""
  }

  test("parse literal block scalars") {
    json("a: |\n  line1\n  line2\nb: 1") shouldBe """{"a":"line1\nline2\n","b":1}"""
    json("a: |-\n  line1\n  line2\n") shouldBe """{"a":"line1\nline2"}"""
    json("a: |+\n  line1\n\n\nb: 1") shouldBe """{"a":"line1\n\n\n","b":1}"""
    json("a: |\n  def f():\n    return 1\n") shouldBe """{"a":"def f():\n  return 1\n"}"""
    json("a: |2\n   indented\n") shouldBe """{"a":" indented\n"}"""
    json("a: |\n\n  after blank\n") shouldBe """{"a":"\nafter blank\n"}"""
    json("a: |\nb: 1") shouldBe """{"a":"","b":1}"""
    json("a: |\n  no final break") shouldBe """{"a":"no final break"}"""
  }

  test("parse folded block scalars") {
    json("a: >\n  one\n  two\n\n  three\n") shouldBe """{"a":"one two\nthree\n"}"""
    json("a: >-\n  one\n    indented\n  two\n") shouldBe """{"a":"one\n  indented\ntwo"}"""
  }

  test("parse anchors, aliases, and merge keys") {
    val yaml =
      """base: &base
        |  host: localhost
        |  port: 80
        |dev:
        |  <<: *base
        |  port: 8080
        |list: [&x 1, *x]
        |""".stripMargin
    json(yaml) shouldBe
      """{"base":{"host":"localhost","port":80},"dev":{"port":8080,"host":"localhost"},"list":[1,1]}"""
  }

  test("attach anchors before an implicit key to the key") {
    json("&k a: 1\nb: *k") shouldBe """{"a":1,"b":"a"}"""
    json("x: 1\n&k2 c: 2\nd: *k2") shouldBe """{"x":1,"c":2,"d":"c"}"""
    json("- &x name: n\n- *x") shouldBe """[{"name":"n"},"name"]"""
    json("- &m\n  name: n\n- *m") shouldBe """[{"name":"n"},{"name":"n"}]"""
  }

  test("parse tags") {
    json("a: !!str 0x1F") shouldBe """{"a":"0x1F"}"""
    json("a: !!int '42'") shouldBe """{"a":42}"""
    YAML.parse("a: !!float 1") shouldBe JSONObject(Seq("a" -> JSONDouble(1.0)))
    json("a: !custom value") shouldBe """{"a":"value"}"""
    json("a: !!str\nb: 1") shouldBe """{"a":"","b":1}"""
  }

  test("parse multiple documents") {
    YAML.parseAll("a: 1\n---\nb: 2\n...\n--- c\n").map(_.toJSON) shouldBe
      Seq("""{"a":1}""", """{"b":2}""", "\"c\"")
    YAML.parseAll("%YAML 1.2\n---\na: 1").map(_.toJSON) shouldBe Seq("""{"a":1}""")
    Seq(
      "a: 1\n---\nb: 2\n...\n--- c\n",
      "%YAML 1.2\n---\na: 1",
      "# head\na: 1 # c\n\n# before\n---\n# top\nb: [1, 2]\n...\n# after\n",
      "---\n---\n--- # empty\n...\n",
      "a\n...\nb\n",
      "",
      "# only a comment\n",
      "k: |\n  text\n\n---\nx: 1\n"
    ).foreach(YAMLRoundTripTest.checkStream)
    intercept[IllegalArgumentException] {
      YAML.parse("a: 1\n---\nb: 2")
    }
  }

  test("normalize CRLF line breaks") {
    json("a: 1\r\nb:\r\n  - x\r\n") shouldBe """{"a":1,"b":["x"]}"""
  }

  test("decode into case classes with Weaver") {
    YAML.fromYaml[ServerConfig]("name: api\nport: 9090\ntags: [a, b]") shouldBe
      ServerConfig("api", 9090, Seq("a", "b"))
    YAML.fromYaml[ServerConfig]("name: web") shouldBe ServerConfig("web")
  }

  test("report errors with line and column") {
    val e = parseError("a: 1\nb: [1, 2\n")
    e.getMessage shouldContain "Unclosed flow sequence"
    e.getMessage shouldContain "line 2, column 4"

    parseError("a: 1\na: 2").getMessage shouldContain "Duplicate mapping key: a"
    parseError("a: *missing").getMessage shouldContain "Unknown alias: *missing"
    parseError("a: !!int x").getMessage shouldContain "line 1, column 10"
    parseError("a: b: c").getMessage shouldContain "A mapping is not allowed here"
    parseError("a:\n  b:\n    - 1\n   c: 2").getMessage shouldContain "Bad indentation"
    parseError("a:\n  b: 1\n   c: 2").getMessage shouldContain
      "A mapping is not allowed in a multi-line plain scalar"
    parseError("a: \"open").getMessage shouldContain "Unclosed double-quoted string"
    parseError("a:\n\tb: 1").getMessage shouldContain "Tabs are not allowed"
    parseError("? a\n: b").getMessage shouldContain "Explicit mapping keys"
    parseError("[a]: b").getMessage shouldContain "Complex (non-scalar) mapping keys"
  }

  test("limit alias expansion") {
    val laughs =
      """a: &a [x, x, x, x, x, x, x, x, x, x]
        |b: &b [*a, *a, *a, *a, *a, *a, *a, *a, *a, *a]
        |c: &c [*b, *b, *b, *b, *b, *b, *b, *b, *b, *b]
        |d: &d [*c, *c, *c, *c, *c, *c, *c, *c, *c, *c]
        |""".stripMargin
    YAML.parseAll(laughs).size shouldBe 1
    intercept[TextParseException] {
      YAML.parseAll(laughs, maxAliasNodes = 1000)
    }.getMessage shouldContain "Aliases expand to more than 1000 nodes"
  }

end YAMLTest
