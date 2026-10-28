package wvlet.uni.text.yaml

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest
import wvlet.uni.text.parser.TextParseException
import wvlet.uni.weaver.Weaver

case class SkillHeader(name: String, description: String = "") derives Weaver

class FrontmatterTest extends UniTest:

  test("split frontmatter from the body") {
    val f = Frontmatter.parse("---\nname: review\ndescription: Review a PR\n---\n# Body\ntext\n")
    f.data.map(_.toJSON) shouldBe Some("""{"name":"review","description":"Review a PR"}""")
    f.body shouldBe "# Body\ntext\n"
    f.as[SkillHeader] shouldBe SkillHeader("review", "Review a PR")
  }

  test("keep the body's line breaks and report header errors with file lines") {
    val f = Frontmatter.parse("--- \r\nname: x\r\n---\t\r\nline1\r\nline2\r\n")
    f.as[SkillHeader] shouldBe SkillHeader("x")
    f.body shouldBe "line1\r\nline2\r\n"

    val e = intercept[TextParseException] {
      Frontmatter.parse("---\nname: x\nname: y\n---\nbody")
    }
    e.getMessage shouldContain "line 3, column 1"
  }

  test("handle texts without frontmatter") {
    Frontmatter.parse("# Title\n") shouldBe Frontmatter(None, "# Title\n")
    Frontmatter.parse("---\nno closing line\n") shouldBe Frontmatter(None, "---\nno closing line\n")
    Frontmatter.parse("---") shouldBe Frontmatter(None, "---")
  }

  test("handle empty frontmatter and edge cases") {
    val empty = Frontmatter.parse("---\n---\nbody")
    empty.data shouldBe Some(JSONNull())
    empty.body shouldBe "body"
    // An empty header decodes as an empty mapping, so a required field is reported as missing
    intercept[Exception] {
      empty.as[SkillHeader]
    }

    Frontmatter.parse("---\nname: x\n---").body shouldBe ""
    Frontmatter.parse("---\nname: x\n...\nbody").body shouldBe "body"
    Frontmatter.parse("\uFEFF---\r\nname: x\r\n---\r\nbody").as[SkillHeader] shouldBe
      SkillHeader("x")
  }

  test("write an updated header back, keeping its comments") {
    val text = "---\n# header\nname: x  # the name\n---\nbody\n"
    val f    = Frontmatter.parse(text)
    f.toText shouldBe text
    f.update(JSON.parse("""{"name":"y","tags":["a"]}""")).toText shouldBe
      "---\n# header\nname: y  # the name\ntags:\n  - a\n---\nbody\n"
    f.copy(data = None).toText shouldBe "body\n"
    Frontmatter.parse("---\n---\nbody").update(JSON.parse("""{"a":1}""")).toText shouldBe
      "---\na: 1\n---\nbody"
  }

  test("edit the example in the docs") {
    val text =
      "---\n# Owned by the docs team\nname: review  # the skill name\n---\n# Instructions\n"
    Frontmatter
      .parse(text)
      .update(JSON.parse("""{"name":"review-pr","tags":["git"]}"""))
      .toText shouldBe
      "---\n# Owned by the docs team\nname: review-pr  # the skill name\ntags:\n  - git\n---\n# Instructions\n"
  }

  test("add a header to a text without one") {
    Frontmatter.parse("body\n").update(JSON.parse("""{"name":"y"}""")).toText shouldBe
      "---\nname: y\n---\nbody\n"
    Frontmatter(Some(JSON.parse("""{"name":"y"}""")), "body").toText shouldBe
      "---\nname: y\n---\nbody"
  }

end FrontmatterTest
