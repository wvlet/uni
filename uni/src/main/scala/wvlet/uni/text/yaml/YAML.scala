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

import wvlet.uni.json.JSON.JSONNull
import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.weaver.Weaver

/**
  * Read YAML 1.2 (core schema) into [[JSONValue]]s, and decode them into Scala types with
  * [[Weaver]]. [[parseDocument]] keeps comments and formatting for editing YAML in place.
  *
  * {{{
  *   case class Config(name: String, port: Int = 8080) derives Weaver
  *
  *   YAML.fromYaml[Config]("name: api\nport: 9090")  // Config(api, 9090)
  * }}}
  *
  * Parse errors throw [[wvlet.uni.text.parser.TextParseException]] with the line and column.
  */
object YAML:

  /**
    * The default limit on the number of nodes that aliases (`*name`) may expand to. It stops
    * "billion laughs" inputs, where nested aliases expand exponentially.
    */
  val defaultMaxAliasNodes: Long = 1_000_000L

  /**
    * Parse a single YAML document. An empty document is [[JSONNull]].
    */
  def parse(yaml: String): JSONValue =
    parseAll(yaml) match
      case Seq() =>
        JSONNull()
      case Seq(doc) =>
        doc
      case docs =>
        throw IllegalArgumentException(
          s"Expected a single YAML document, but found ${docs.size}. Use YAML.parseAll instead"
        )

  /**
    * Parse every document of a multi-document (`---`-separated) YAML stream
    */
  def parseAll(yaml: String, maxAliasNodes: Long = defaultMaxAliasNodes): Seq[JSONValue] =
    val parser   = YAMLParser(yaml)
    val roots    = parser.parseStream()
    val resolver = YAMLResolver(parser.text, maxAliasNodes)
    roots.map(resolver.document)

  /**
    * Parse a single YAML document into a [[YAMLDocument]] that keeps comments and formatting, so it
    * can be edited and written back with [[YAMLDocument.toYaml]]. The document is checked as
    * [[parse]] does.
    */
  def parseDocument(yaml: String): YAMLDocument = parseDocumentWithValue(yaml)._1

  /**
    * Parse a document and resolve its value in one pass
    */
  private[text] def parseDocumentWithValue(yaml: String): (YAMLDocument, JSONValue) =
    val parser = YAMLParser(yaml)
    val doc    = parser.parseDocument()
    (doc, YAMLResolver(parser.text, defaultMaxAliasNodes).document(doc.root))

  /**
    * Parse a single YAML document and decode it into `A`
    */
  def fromYaml[A](yaml: String)(using weaver: Weaver[A]): A = weaver.fromJSONValue(parse(yaml))

end YAML
