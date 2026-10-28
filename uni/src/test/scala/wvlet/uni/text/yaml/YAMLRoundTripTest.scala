package wvlet.uni.text.yaml

import wvlet.uni.test.Assertions

// An Assertions object, not a UniTest, so that it is not run as a test suite of its own
object YAMLRoundTripTest extends Assertions:

  /**
    * Check that the text, with `\n`, `\r\n`, and no final line break, writes back unchanged and
    * still resolves to the same value when every node is rewritten
    */
  def check(yaml: String): Unit =
    val variants = Seq(yaml, yaml.replace("\n", "\r\n"), yaml.stripSuffix("\n"), "﻿" + yaml)
    for v <- variants do
      val doc = YAML.parseDocument(v)
      doc.toYaml shouldBe v
      doc.toJSON.toJSON shouldBe YAML.parse(v).toJSON
      // Rewrite every node and check that the text reads back as the same value
      val rewritten = YAMLDocument(rebuild(doc.root), doc.source).toYaml
      val reparsed  =
        try
          YAML.parse(rewritten).toJSON
        catch
          case e: Exception =>
            throw AssertionError(
              s"Failed to read back the rewritten YAML:\n${rewritten}\nfrom:\n${v}",
              e
            )
      reparsed shouldBe YAML.parse(v).toJSON

  /**
    * Copy the tree so that every node counts as changed
    */
  private def rebuild(n: YAMLNode): YAMLNode =
    n match
      case s: YAMLNode.Scalar =>
        s.copy(source = YAMLSource.none)
      case a: YAMLNode.Alias =>
        a.copy(source = YAMLSource.none)
      case m: YAMLNode.Mapping =>
        m.copy(
          entries = m.entries.map(e => YAMLEntry(rebuild(e.key), rebuild(e.value))),
          source = YAMLSource.none
        )
      case s: YAMLNode.Sequence =>
        s.copy(items = s.items.map(rebuild), source = YAMLSource.none)

end YAMLRoundTripTest
