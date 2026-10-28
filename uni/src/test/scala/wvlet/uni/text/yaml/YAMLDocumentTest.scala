package wvlet.uni.text.yaml

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest

class YAMLDocumentTest extends UniTest:

  private val config =
    """# Server config
      |server:
      |  name: api   # inline comment
      |  ports:
      |    - 80
      |    - 443
      |
      |  # TLS settings
      |  tls:
      |    enabled: true
      |desc: |
      |  line1
      |  line2
      |list: [a, b]
      |base: &base
      |  x: 1
      |dev:
      |  <<: *base
      |""".stripMargin

  private val doc = YAML.parseDocument(config)

  test("write an unchanged document back exactly") {
    doc.toYaml shouldBe config
    doc.toJSON shouldBe YAML.parse(config)
  }

  test("set a scalar, keeping the comment after it") {
    doc.set(Seq("server", "name"), "web").toYaml shouldBe config.replace("name: api ", "name: web ")
    doc.set(Seq("server", "ports", 1), 8443).toYaml shouldBe config.replace("443", "8443")
    doc.set(Seq("list", 1), "c d").toYaml shouldBe config.replace("[a, b]", "[a, c d]")
  }

  test("keep the scalar style of a replaced value") {
    YAML.parseDocument("a: 'x'  # c\n").set(Seq("a"), "y").toYaml shouldBe "a: 'y'  # c\n"
    YAML.parseDocument("a: \"x\"\n").set(Seq("a"), "y").toYaml shouldBe "a: \"y\"\n"
    doc.set(Seq("desc"), "new1\nnew2\n").toYaml shouldBe
      config.replace("line1\n  line2", "new1\n  new2")
    // A string that would read as another type is quoted
    YAML.parseDocument("a: x\n").set(Seq("a"), "123").toYaml shouldBe "a: \"123\"\n"
    YAML.parseDocument("a: x\n").set(Seq("a"), 123).toYaml shouldBe "a: 123\n"
  }

  test("add entries at the end of their mapping") {
    doc.set(Seq("server", "tls", "cert"), "/etc/cert.pem").toYaml shouldBe
      config.replace("enabled: true\n", "enabled: true\n    cert: /etc/cert.pem\n")
    doc.set(Seq("server", "ports", 2), 8080).toYaml shouldBe
      config.replace("- 443\n", "- 443\n    - 8080\n")
    doc.set(Seq("db", "hosts"), JSON.parse("""[{"host":"a","port":1},"b"]""")).toYaml shouldBe
      config + """db:
        |  hosts:
        |    - host: a
        |      port: 1
        |    - b
        |""".stripMargin
  }

  test("remove entries with the comments above them") {
    doc.remove(Seq("server", "tls")).toYaml shouldBe
      config.replace("\n  # TLS settings\n  tls:\n    enabled: true\n", "")
    doc.remove(Seq("server", "name")).toYaml shouldBe
      config.replace("  name: api   # inline comment\n", "")
    doc.remove(Seq("server", "ports", 0)).toYaml shouldBe config.replace("    - 80\n", "")
    doc.remove(Seq("server")).toYaml shouldBe
      "# Server config\n" + config.substring(config.indexOf("desc:"))
    doc.remove(Seq("missing", "key")) shouldBe doc
    doc.remove(Seq("missing")).toYaml shouldBe config
  }

  test("move a comment to the front when the first entry is removed") {
    YAML.parseDocument("a:\n  x: 1\n  # about y\n  y: 2\n").remove(Seq("a", "x")).toYaml shouldBe
      "a:\n  # about y\n  y: 2\n"
  }

  test("update a document to a new value, rewriting only what differs") {
    val updated = doc.update(
      JSON.parse(
        """{"server":{"name":"api","ports":[80],"tls":{"enabled":false}},"desc":"line1\nline2\n","list":["a","b"],"base":{"x":1},"dev":{"x":1},"extra":{"k":"v: w"}}"""
      )
    )
    updated.toYaml shouldBe
      config.replace("    - 443\n", "").replace("enabled: true", "enabled: false") +
      "extra:\n  k: \"v: w\"\n"
  }

  test("fill an empty document") {
    YAML.parseDocument("").set(Seq("a"), 1).toYaml shouldBe "a: 1"
    YAML.parseDocument("---\n").set(Seq("a", "b"), 1).toYaml shouldBe "---\na:\n  b: 1\n"
    YAML.parseDocument("a:\n").set(Seq("a", "b"), 1).toYaml shouldBe "a:\n  b: 1\n"
    YAML.parseDocument("a: ~\n").set(Seq("a", 0), "x").toYaml shouldBe "a:\n  - x\n"
  }

  test("use the document's line breaks for new text") {
    YAML.parseDocument("a: 1\r\nb: 2\r\n").set(Seq("c", "d"), 1).toYaml shouldBe
      "a: 1\r\nb: 2\r\nc:\r\n  d: 1\r\n"
  }

  test("edit sequences of mappings") {
    YAML.parseDocument("- a\n- b: 1\n  c: 2\n").set(Seq(1, "d"), 3).toYaml shouldBe
      "- a\n- b: 1\n  c: 2\n  d: 3\n"
    YAML.parseDocument("- a\n- b\n").remove(Seq(0)).toYaml shouldBe "- b\n"
  }

  test("keep anchors on replaced values") {
    val d = YAML.parseDocument("a: &x 1\nb: *x\n").set(Seq("a"), 2)
    d.toYaml shouldBe "a: &x 2\nb: *x\n"
    d.toJSON.toJSON shouldBe """{"a":2,"b":2}"""
  }

  test("reject paths through other nodes") {
    intercept[IllegalArgumentException] {
      doc.set(Seq("server", "name", "x"), 1)
    }
    intercept[IndexOutOfBoundsException] {
      doc.set(Seq("server", "ports", 5), 1)
    }
  }

  test("build documents in code") {
    YAMLDocument(
      YAMLNode.fromJSON(JSON.parse("""{"name":"x","tags":["a","b c"],"empty":{},"n":null}"""))
    ).toYaml shouldBe "name: x\ntags:\n  - a\n  - b c\nempty: {}\nn: null"
  }

  test("edit the example in the docs") {
    val d = YAML.parseDocument("""# Server settings
        |server:
        |  host: localhost
        |  port: 8080  # default port
        |
        |  # Set in production
        |  tls: false
        |""".stripMargin)
    d.set(Seq("server", "port"), 9090)
      .set(Seq("server", "tags"), Seq("a", "b"))
      .remove(Seq("server", "tls"))
      .toYaml shouldBe """# Server settings
        |server:
        |  host: localhost
        |  port: 9090  # default port
        |  tags:
        |    - a
        |    - b
        |""".stripMargin
  }

end YAMLDocumentTest
