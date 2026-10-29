package wvlet.uni.text.yaml

import wvlet.uni.test.Assertions

// An Assertions object, not a UniTest, so that it is not run as a test suite of its own
object YAMLRoundTripTest extends Assertions:

  /**
    * Check that the text, with `\n`, `\r\n`, and no final line break, writes back unchanged, also
    * when every collection is marked as changed, and still resolves to the same value when every
    * node is rewritten
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
      // Mark every collection as changed: the children and the text between them print as read
      touchedDocument(doc).toYaml shouldBe v
    checkStream(yaml)

  /**
    * Check that a stream, with the same variants as [[check]], writes back unchanged through
    * [[YAML.parseDocuments]], and still resolves to the same values when every node is rewritten
    */
  def checkStream(yaml: String): Unit =
    val variants = Seq(yaml, yaml.replace("\n", "\r\n"), yaml.stripSuffix("\n"), "\uFEFF" + yaml)
    for v <- variants do
      val docs     = YAML.parseDocuments(v)
      val expected = YAML.parseAll(v).map(_.toJSON)
      YAML.writeStream(docs) shouldBe v
      docs.map(_.toYaml).mkString shouldBe v
      if expected.nonEmpty then
        docs.map(_.toJSON.toJSON) shouldBe expected
      YAML.writeStream(docs.map(touchedDocument)) shouldBe v
      val rewritten = YAML.writeStream(docs.map(d => YAMLDocument(rebuild(d.root), d.source)))
      val reparsed  =
        try
          YAML.parseAll(rewritten).map(_.toJSON)
        catch
          case e: Exception =>
            throw AssertionError(
              s"Failed to read back the rewritten YAML:\n${rewritten}\nfrom:\n${v}",
              e
            )
      reparsed shouldBe expected

  /**
    * A copy of the source that records `content` plus a marker, so that the node counts as changed
    * while the writer still finds the recorded children
    */
  private def touched(src: YAMLSource, content: Product): YAMLSource = YAMLSource(
    src.raw,
    src.leading,
    src.comment,
    src.opening,
    src.indent,
    src.span,
    Tuple.fromArray(content.productIterator.toArray[Any]) ++ Tuple1("touched"),
    src.offset,
    src.closing
  )

  private def touchedDocument(doc: YAMLDocument): YAMLDocument =
    val root = touchAll(doc.root)
    YAMLDocument(root, touched(doc.source, Tuple1(root)))

  /**
    * Copy the tree so that every collection counts as changed, but keeps the source text of its
    * children and the text between them
    */
  private def touchAll(n: YAMLNode): YAMLNode =
    n match
      case m: YAMLNode.Mapping =>
        val c = m.copy(entries = m.entries.map(e => YAMLEntry(touchAll(e.key), touchAll(e.value))))
        c.withSource(touched(m.source, c.content))
      case s: YAMLNode.Sequence =>
        val c = s.copy(items = s.items.map(touchAll))
        c.withSource(touched(s.source, c.content))
      case other =>
        other

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
