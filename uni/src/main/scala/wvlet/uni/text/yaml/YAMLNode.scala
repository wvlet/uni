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
package wvlet.uni.text.yaml

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.text.Span
import wvlet.uni.weaver.Weaver

/**
  * A parsed YAML document that keeps its source text. `toYaml` writes it back: unchanged nodes
  * print their original text byte for byte, including comments, blank lines, and line breaks, and
  * changed or new nodes print in a canonical block style that keeps their recorded scalar style.
  *
  * {{{
  *   val doc = YAML.parseDocument("# Server\nport: 8080  # default\n")
  *   doc.set(Seq("port"), 9090).toYaml  // "# Server\nport: 9090  # default\n"
  * }}}
  */
case class YAMLDocument(root: YAMLNode, source: YAMLSource = YAMLSource.none):
  def toYaml: String = YAMLWriter.write(this)

  /**
    * Resolve the document into a [[JSONValue]], as [[YAML.parse]] does
    */
  def toJSON: JSONValue = YAMLResolver.resolve(this)

  /**
    * Set the value at the path, keeping comments and the text around it. Missing mapping keys are
    * added at the end of their mapping, along with missing parent mappings. An `Int` segment
    * indexes a sequence; the index equal to the size appends.
    */
  def set(path: Seq[String | Int], value: JSONValue): YAMLDocument = copy(root =
    YAMLEdit.set(root, path, value)
  )

  def set[A](path: Seq[String | Int], value: A)(using weaver: Weaver[A]): YAMLDocument = set(
    path,
    JSON.parseAny(weaver.toJson(value))
  )

  /**
    * Remove the mapping entry or sequence item at the path, with the comments above it. A missing
    * path leaves the document unchanged.
    */
  def remove(path: Seq[String | Int]): YAMLDocument = copy(root = YAMLEdit.remove(root, path))

  /**
    * Change the document so that it resolves to `value`, editing only the parts that differ
    */
  def update(value: JSONValue): YAMLDocument = copy(root = YAMLEdit.update(root, toJSON, value))

end YAMLDocument

/**
  * How a scalar is written
  */
enum ScalarStyle:
  case Plain
  case SingleQuoted
  case DoubleQuoted

  /**
    * `|`
    */
  case Literal

  /**
    * `>`
    */
  case Folded

/**
  * A YAML node. Scalars keep their text as written, before resolving to a type: `Scalar("8080")` in
  * plain style resolves to a number, and in a quoted style to a string.
  */
enum YAMLNode:
  def source: YAMLSource

  case Scalar(
      value: String,
      style: ScalarStyle = ScalarStyle.Plain,
      anchor: Option[String] = None,
      tag: Option[String] = None,
      source: YAMLSource = YAMLSource.none
  )

  case Mapping(
      entries: Seq[YAMLEntry],
      flow: Boolean = false,
      anchor: Option[String] = None,
      tag: Option[String] = None,
      source: YAMLSource = YAMLSource.none
  )

  case Sequence(
      items: Seq[YAMLNode],
      flow: Boolean = false,
      anchor: Option[String] = None,
      tag: Option[String] = None,
      source: YAMLSource = YAMLSource.none
  )

  case Alias(name: String, source: YAMLSource = YAMLSource.none)

  /**
    * The content fields, compared with [[YAMLSource]]'s record to detect edits
    */
  private[yaml] def content: Product =
    this match
      case Scalar(v, s, a, t, _) =>
        (v, s, a, t)
      case Mapping(e, f, a, t, _) =>
        (e, f, a, t)
      case Sequence(i, f, a, t, _) =>
        (i, f, a, t)
      case Alias(n, _) =>
        Tuple1(n)

  private[yaml] def anchorOf: Option[String] =
    this match
      case s: Scalar =>
        s.anchor
      case m: Mapping =>
        m.anchor
      case s: Sequence =>
        s.anchor
      case _: Alias =>
        None

  private[yaml] def tagOf: Option[String] =
    this match
      case s: Scalar =>
        s.tag
      case m: Mapping =>
        m.tag
      case s: Sequence =>
        s.tag
      case _: Alias =>
        None

  private[yaml] def withSource(s: YAMLSource): YAMLNode =
    this match
      case n: Scalar =>
        n.copy(source = s)
      case n: Mapping =>
        n.copy(source = s)
      case n: Sequence =>
        n.copy(source = s)
      case n: Alias =>
        n.copy(source = s)

  private[yaml] def unchanged: Boolean = YAMLSource.same(source.parsed, content)

end YAMLNode

object YAMLNode:

  /**
    * Build a node from a JSON value, choosing plain style where the text reads back as the same
    * value
    */
  def fromJSON(v: JSONValue): YAMLNode = YAMLWriter.fromJSON(v)

case class YAMLEntry(key: YAMLNode, value: YAMLNode)

/**
  * The source text a node was parsed from, attached so that [[YAMLDocument.toYaml]] can write
  * unchanged nodes back exactly. It does not take part in equality.
  *
  * @param raw
  *   the exact text of the node, including its anchor and tag
  * @param leading
  *   the text between the previous token and this node: line breaks, indentation, and comment lines
  *   above a mapping key; `: ` before a mapping value; `- ` before a sequence item. For a document,
  *   the text before the root node
  * @param comment
  *   a comment on the same line after the node, with the spaces before it. For a document, the text
  *   after the root node
  * @param opening
  *   for a block collection, the text before its first child (an anchor or tag)
  * @param indent
  *   the column of a block collection's children
  * @param span
  *   the offsets of `raw` in the input
  */
final class YAMLSource private[yaml] (
    val raw: String,
    val leading: String,
    val comment: String,
    val opening: String,
    val indent: Int,
    val span: Span,
    private[yaml] val parsed: Product | Null,
    // The offset in the parser's text, for error messages
    private[yaml] val offset: Int = -1
):
  def isEmpty: Boolean = parsed == null

  override def equals(other: Any): Boolean = other.isInstanceOf[YAMLSource]
  override def hashCode: Int               = 0
  override def toString: String            =
    if isEmpty then
      "YAMLSource.none"
    else
      s"YAMLSource(${span})"

  /**
    * Keep the text around the node, but mark its content as changed
    */
  private[yaml] def detached: YAMLSource = YAMLSource(
    "",
    leading,
    comment,
    "",
    indent,
    Span.NoSpan,
    null,
    offset
  )

end YAMLSource

object YAMLSource:
  /**
    * The source of a node built in code
    */
  val none: YAMLSource = YAMLSource("", "", "", "", -1, Span.NoSpan, null)

  /**
    * Is the content still what was parsed, so that the source text can be printed as is?
    */
  private[yaml] def same(parsed: Product | Null, current: Product): Boolean =
    parsed match
      case null =>
        false
      case p: Product =>
        p.productArity == current.productArity &&
        p.productIterator
          .zip(current.productIterator)
          .forall { (a, b) =>
            a.asInstanceOf[AnyRef].eq(b.asInstanceOf[AnyRef]) || a == b
          }

end YAMLSource
