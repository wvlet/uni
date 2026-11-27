package wvlet.uni.json

import wvlet.uni.json.JSON.*
import wvlet.uni.text.yaml.YAMLDocument
import wvlet.uni.text.yaml.YAMLNode

/**
  * Write JSON as block-style YAML that [[wvlet.uni.text.yaml.YAML.parse]] reads back as the same
  * value:
  *
  * {{{
  *   YAMLFormatter.toYaml("""{"name":"api","ports":[80,443],"tags":[]}""")
  *   // name: api
  *   // ports:
  *   //   - 80
  *   //   - 443
  *   // tags: []
  * }}}
  *
  * It is [[wvlet.uni.text.yaml.YAMLNode.fromJSON]] written with
  * [[wvlet.uni.text.yaml.YAMLDocument.toYaml]]: strings are plain where they read back as the same
  * string, literal blocks when they span lines, and double-quoted otherwise.
  */
object YAMLFormatter:
  def toYaml(json: String): String = toYaml(JSON.parseAny(json))

  def toYaml(value: JSONValue): String = YAMLDocument(YAMLNode.fromJSON(value)).toYaml

end YAMLFormatter
