package wvlet.uni.text.yaml

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest
import wvlet.uni.text.parser.TextParseException

class YAMLStreamTest extends UniTest:

  private val manifests =
    """# Deployment
      |kind: Deployment
      |spec:
      |  replicas: 1  # scale here
      |
      |# The service
      |---
      |kind: Service
      |ports: [80, 443]
      |...
      |---
      |# Config
      |kind: ConfigMap
      |""".stripMargin

  private val docs = YAML.parseDocuments(manifests)

  test("read every document of a stream") {
    docs.size shouldBe 3
    YAML.writeStream(docs) shouldBe manifests
    docs.map(_.toJSON) shouldBe YAML.parseAll(manifests)
  }

  test("give comment lines before a --- marker to the document below") {
    docs(0).toYaml shouldBe "# Deployment\nkind: Deployment\nspec:\n  replicas: 1  # scale here\n"
    docs(1).toYaml shouldBe "\n# The service\n---\nkind: Service\nports: [80, 443]\n...\n"
    docs(2).toYaml shouldBe "---\n# Config\nkind: ConfigMap\n"
  }

  test("edit one document, keeping the others byte for byte") {
    val edited = docs.updated(0, docs(0).set(Seq("spec", "replicas"), 3))
    YAML.writeStream(edited) shouldBe manifests.replace("replicas: 1", "replicas: 3")
    val ports = docs.updated(1, docs(1).set(Seq("ports", 2), 8080))
    YAML.writeStream(ports) shouldBe manifests.replace("[80, 443]", "[80, 443, 8080]")
  }

  test("remove documents with the comments above them") {
    YAML.writeStream(docs.patch(1, Nil, 1)) shouldBe
      "# Deployment\nkind: Deployment\nspec:\n  replicas: 1  # scale here\n---\n# Config\nkind: ConfigMap\n"
    YAML.writeStream(docs.drop(1)) shouldBe
      manifests.substring(manifests.indexOf("\n# The service"))
  }

  test("add --- markers and line breaks for new or moved documents") {
    val added = docs :+ YAMLDocument(YAMLNode.fromJSON(JSON.parse("""{"kind":"Secret"}""")))
    YAML.writeStream(added) shouldBe manifests + "---\nkind: Secret"
    YAML.parseAll(YAML.writeStream(added)).size shouldBe 4

    // The first document has no marker, so moving it after another one adds one
    val moved = YAML.writeStream(Seq(docs(2), docs(0)))
    moved shouldBe
      "---\n# Config\nkind: ConfigMap\n---\n# Deployment\nkind: Deployment\nspec:\n  replicas: 1  # scale here\n"
    // A document after `...` needs no marker
    val bare = YAML.parseDocuments("a: 1\n...\nb: 2")
    YAML.writeStream(bare.reverse) shouldBe "b: 2\n---\na: 1\n...\n"
    YAML.writeStream(Seq(bare(0), bare(0))) shouldBe "a: 1\n...\na: 1\n...\n"
  }

  test("keep the text of a stream without documents") {
    YAML.parseDocuments("").map(_.toJSON) shouldBe Seq(JSONNull())
    YAML.writeStream(YAML.parseDocuments("# nothing here\n")) shouldBe "# nothing here\n"
    YAML.writeStream(Nil) shouldBe ""
  }

  test("keep CRLF line breaks in new text") {
    val crlf = YAML.parseDocuments("a: 1\r\n---\r\nb: 2\r\n")
    YAML.writeStream(crlf :+ YAMLDocument(YAMLNode.fromJSON(JSONLong(3)))) shouldBe
      "a: 1\r\n---\r\nb: 2\r\n---\r\n3"
  }

  test("report errors at their line in the stream") {
    val e = intercept[TextParseException] {
      YAML.parseDocuments("a: 1\n---\nb: *x\n")
    }
    e.getMessage.contains("line 3") shouldBe true
    // An edited document reports lines within its own text
    val d  = YAML.parseDocuments("a: 1\n---\nb: &x 1\nc: *x\n")(1)
    val e2 = intercept[TextParseException] {
      d.remove(Seq("b")).toJSON
    }
    e2.getMessage.contains("line 3") shouldBe true
  }

  test("edit the examples in the docs") {
    val docs = YAML.parseDocuments("""# Deployment
        |kind: Deployment
        |replicas: 1
        |---
        |# Service
        |kind: Service
        |""".stripMargin)
    YAML.writeStream(docs.updated(0, docs(0).set(Seq("replicas"), 3))) shouldBe """# Deployment
        |kind: Deployment
        |replicas: 3
        |---
        |# Service
        |kind: Service
        |""".stripMargin
    val app = YAML.parseDocuments("# app\nname: api\n---\n# db\nname: db\n")
    YAML.writeStream(app.updated(1, app(1).set(Seq("name"), "pg"))) shouldBe
      "# app\nname: api\n---\n# db\nname: pg\n"
  }

  test("still reject streams in parseDocument") {
    val e = intercept[IllegalArgumentException] {
      YAML.parseDocument("a: 1\n---\nb: 2")
    }
    e.getMessage.contains("YAML.parseDocuments") shouldBe true
  }

end YAMLStreamTest
