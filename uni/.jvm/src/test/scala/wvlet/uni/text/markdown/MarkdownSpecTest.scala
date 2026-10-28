package wvlet.uni.text.markdown

import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.nio.charset.StandardCharsets

case class SpecExample(
    markdown: String,
    html: String,
    example: Int,
    section: String,
    extension: Option[String] = None
) derives Weaver

/**
  * Runs the CommonMark 0.31.2 spec examples and the GitHub Flavored Markdown extension examples
  * (see `src/test/resources/wvlet/uni/text/markdown/README.md`)
  */
class MarkdownSpecTest extends UniTest:

  private def load(name: String): Seq[SpecExample] =
    val in = getClass.getResourceAsStream(s"/wvlet/uni/text/markdown/${name}")
    try Weaver.fromJson[Seq[SpecExample]](String(in.readAllBytes(), StandardCharsets.UTF_8))
    finally in.close()

  /**
    * Examples that are expected to differ from the spec output, with the reason
    */
  private val knownCommonMarkDifferences: Map[Int, String] = Map.empty

  private val knownGfmDifferences: Map[Int, String] = Map(
    652 -> "The tagfilter extension is not implemented: raw HTML is either trusted or escaped"
  )

  private def check(
      examples: Seq[SpecExample],
      config: MarkdownConfig,
      known: Map[Int, String]
  ): Unit =
    val failures   = examples.filter(e => Markdown.toHtml(e.markdown, config) != e.html)
    val unexpected = failures.filterNot(e => known.contains(e.example))
    if unexpected.nonEmpty then
      val details = unexpected
        .take(5)
        .map { e =>
          s"#${e.example} (${e.section})\n--- markdown\n${e.markdown}--- expected\n${e
              .html}--- actual\n${Markdown.toHtml(e.markdown, config)}"
        }
        .mkString("\n")
      fail(
        s"${unexpected.size} of ${examples.size} examples differ: ${unexpected
            .map(_.example)
            .mkString(", ")}\n${details}"
      )
    // Known differences that now pass should be removed from the list
    known.keys.filterNot(id => failures.exists(_.example == id)).toSeq.sorted shouldBe Seq.empty

  test("pass the CommonMark spec examples") {
    check(
      load("commonmark-spec-0.31.2.json"),
      MarkdownConfig().withRawHtml(true).withGfm(false),
      knownCommonMarkDifferences
    )
  }

  test("pass the GitHub Flavored Markdown extension examples") {
    check(load("gfm-extensions.json"), MarkdownConfig().withRawHtml(true), knownGfmDifferences)
  }

end MarkdownSpecTest
