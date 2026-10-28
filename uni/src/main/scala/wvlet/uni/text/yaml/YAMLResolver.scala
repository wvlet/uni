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

import wvlet.uni.json.JSON.*
import wvlet.uni.text.Span
import wvlet.uni.text.parser.LineReader
import wvlet.uni.text.parser.TextParseException

import scala.collection.mutable

/**
  * Resolve [[YAMLNode]] trees into [[JSONValue]]s: scalars with the YAML 1.2 core schema or their
  * tag, aliases to their anchored values, and `<<` merge keys.
  *
  * @param text
  *   the normalized text that node offsets point into, for error messages
  */
private[yaml] class YAMLResolver(text: String, maxAliasNodes: Long):
  import YAMLNode.*
  import YAMLParser.*

  // anchor name -> (value, the number of nodes the value expands to)
  private val anchors        = mutable.Map.empty[String, (JSONValue, Long)]
  private var nodeCount      = 0L
  private var aliasNodeCount = 0L

  /**
    * Resolve a document root. Anchors do not carry over between documents.
    */
  def document(root: YAMLNode): JSONValue =
    anchors.clear()
    value(root)

  private def value(n: YAMLNode): JSONValue =
    val countStart = nodeCount
    val v          =
      n match
        case s: Scalar =>
          nodeCount += 1
          scalar(s)
        case Alias(name, src) =>
          alias(name, src.offset)
        case m: Mapping =>
          nodeCount += 1
          val builder = MappingBuilder()
          m.entries
            .foreach { e =>
              val k = key(e.key, m.flow)
              builder.add(k, value(e.value), e.key.source.offset)
            }
          builder.result()
        case s: Sequence =>
          nodeCount += 1
          JSONArray(s.items.map(value).toIndexedSeq)
    n.anchorOf.foreach(a => anchors(a) = (v, (nodeCount - countStart).max(1)))
    v

  private def key(k: YAMLNode, flow: Boolean): String =
    def complex: Nothing = error("Complex (non-scalar) mapping keys are not supported", k)
    k match
      case s: Scalar =>
        if flow then
          // A flow key is a node of its own, so an anchor on it refers to the resolved value
          value(s)
        else
          s.anchorOf.foreach(a => anchors(a) = (JSONString(s.value), 1L))
        s.value
      case Alias(name, src) =>
        alias(name, src.offset) match
          case JSONString(s) =>
            s
          case _: JSONObject | _: JSONArray =>
            complex
          case other =>
            other.toJSON
      case _ =>
        complex

  private def alias(name: String, offset: Int): JSONValue =
    anchors.get(name) match
      case Some((v, size)) =>
        aliasNodeCount += size
        nodeCount += size
        if aliasNodeCount > maxAliasNodes then
          error(s"Aliases expand to more than ${maxAliasNodes} nodes", offset)
        v
      case None =>
        error(s"Unknown alias: *${name}", offset)

  /**
    * Resolve a scalar with the YAML 1.2 core schema, or with its tag
    */
  private def scalar(n: Scalar): JSONValue =
    val s                           = n.value
    def fail(kind: String): Nothing = error(s"Invalid ${kind} value: ${s}", n)
    n.tagOf match
      case Some("!!str") | Some("!") =>
        JSONString(s)
      case Some("!!int") =>
        parseInt(s).getOrElse(fail("integer"))
      case Some("!!float") =>
        parseFloat(s)
          .orElse(
            parseInt(s).collect { case JSONLong(v) =>
              JSONDouble(v.toDouble)
            }
          )
          .getOrElse(fail("float"))
      case Some("!!bool") =>
        parseBool(s).getOrElse(fail("boolean"))
      case Some("!!null") =>
        if isNull(s) then
          JSONNull()
        else
          fail("null")
      case _ if n.style == ScalarStyle.Plain =>
        resolvePlain(s)
      case _ =>
        JSONString(s)

  end scalar

  private def error(message: String, node: YAMLNode): Nothing = error(message, node.source.offset)

  private def error(message: String, offset: Int): Nothing =
    if offset < 0 then
      // A node built in code has no position
      throw TextParseException(message, 0)
    else
      throw TextParseException(
        LineReader.formatError(text, message, offset),
        offset,
        Span.at(offset)
      )

  /**
    * Accumulates mapping entries, rejecting duplicate keys and applying `<<` merge keys
    */
  private class MappingBuilder:
    private val entries = mutable.ArrayBuffer.empty[(String, JSONValue)]
    private val keys    = mutable.Set.empty[String]
    private val merges  = mutable.ArrayBuffer.empty[JSONObject]

    def add(key: String, value: JSONValue, keyOffset: Int): Unit =
      if key == "<<" then
        value match
          case o: JSONObject =>
            merges += o
          case JSONArray(xs) if xs.forall(_.isInstanceOf[JSONObject]) =>
            merges ++=
              xs.collect { case o: JSONObject =>
                o
              }
          case _ =>
            error("A merge key (<<) value must be a mapping or a sequence of mappings", keyOffset)
      else
        if keys.contains(key) then
          error(s"Duplicate mapping key: ${key}", keyOffset)
        keys += key
        entries += key -> value

    def result(): JSONObject =
      for
        m      <- merges
        (k, v) <- m.v
        if !keys.contains(k)
      do
        keys += k
        entries += k -> v
      JSONObject(entries.toSeq)

  end MappingBuilder

end YAMLResolver

private[yaml] object YAMLResolver:

  def resolve(doc: YAMLDocument): JSONValue =
    val text = LineReader.normalizeLineBreaks(doc.source.raw.stripPrefix("\uFEFF"))
    YAMLResolver(text, YAML.defaultMaxAliasNodes).document(doc.root)
