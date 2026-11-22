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
package wvlet.uni.cloud

import scala.collection.mutable

/**
  * A minimal XML reader for object store responses (S3 and Azure listings, upload ids, errors). It
  * keeps elements, attributes, and text, drops namespace prefixes, comments, and processing
  * instructions, and decodes the predefined and numeric entities. It is not a general XML parser.
  */
private[uni] object XmlReader:
  case class Elem(
      name: String,
      children: Seq[Elem],
      text: String,
      attributes: Map[String, String] = Map.empty
  ):
    def attribute(name: String): Option[String] = attributes.get(name)
    def child(name: String): Option[Elem]       = children.find(_.name == name)
    def all(name: String): Seq[Elem]            = children.filter(_.name == name)
    def childText(name: String): Option[String] = child(name).map(_.text)

  def parse(xml: String): Elem = Parser(xml).parseDocument()

  private class Parser(s: String):
    private var i = 0

    def parseDocument(): Elem =
      skipMisc()
      if i >= s.length || s.charAt(i) != '<' then
        fail("no root element")
      parseElement()

    private def fail(msg: String): Nothing =
      throw CloudException(CloudErrorCode.InvalidResponse, s"Malformed XML response: ${msg}")

    // Skips whitespace, <?...?>, <!--...-->, and <!DOCTYPE ...>
    private def skipMisc(): Unit =
      var continue = true
      while continue do
        while i < s.length && s.charAt(i).isWhitespace do
          i += 1
        if s.startsWith("<?", i) then
          i = indexOrFail("?>", i) + 2
        else if s.startsWith("<!--", i) then
          i = indexOrFail("-->", i) + 3
        else if s.startsWith("<!", i) && !s.startsWith("<![CDATA[", i) then
          i = indexOrFail(">", i) + 1
        else
          continue = false

    private def indexOrFail(token: String, from: Int): Int =
      val j = s.indexOf(token, from)
      if j < 0 then
        fail(s"missing ${token}")
      j

    private def localName(n: String): String =
      val c = n.indexOf(':')
      if c >= 0 then
        n.substring(c + 1)
      else
        n

    private def parseElement(): Elem =
      // at '<'
      i += 1
      val nameStart = i
      while i < s.length && !s.charAt(i).isWhitespace && s.charAt(i) != '>' && s.charAt(i) != '/' do
        i += 1
      val name  = localName(s.substring(nameStart, i))
      val attrs = parseAttributes(name)
      if s.charAt(i) == '/' then
        i = indexOrFail(">", i) + 1
        Elem(name, Nil, "", attrs)
      else
        i += 1
        val children = mutable.ArrayBuffer.empty[Elem]
        val text     = StringBuilder()
        var done     = false
        while !done do
          if i >= s.length then
            fail(s"unterminated <${name}>")
          if s.startsWith("</", i) then
            i = indexOrFail(">", i) + 1
            done = true
          else if s.startsWith("<![CDATA[", i) then
            val end = indexOrFail("]]>", i)
            text.append(s.substring(i + 9, end))
            i = end + 3
          else if s.startsWith("<!--", i) then
            i = indexOrFail("-->", i) + 3
          else if s.startsWith("<?", i) then
            i = indexOrFail("?>", i) + 2
          else if s.charAt(i) == '<' then
            children += parseElement()
          else
            val end =
              s.indexOf('<', i) match
                case -1 =>
                  s.length
                case j =>
                  j
            text.append(decode(s.substring(i, end)))
            i = end
        // Keep leaf text as is: object keys may start or end with spaces
        val t =
          if children.isEmpty then
            text.toString
          else
            text.toString.trim
        Elem(name, children.toSeq, t, attrs)
      end if

    end parseElement

    // Reads `name="value"` pairs up to '>' or '/>'. Quoted values may contain '>' or '/'.
    private def parseAttributes(elem: String): Map[String, String] =
      val attrs = Map.newBuilder[String, String]
      var done  = false
      while !done do
        while i < s.length && s.charAt(i).isWhitespace do
          i += 1
        if i >= s.length then
          fail(s"unterminated <${elem}>")
        val c = s.charAt(i)
        if c == '>' || c == '/' then
          done = true
        else
          val start = i
          while i < s.length && s.charAt(i) != '=' && s.charAt(i) != '>' && s.charAt(i) != '/' &&
            !s.charAt(i).isWhitespace
          do
            i += 1
          val name = localName(s.substring(start, i))
          while i < s.length && s.charAt(i).isWhitespace do
            i += 1
          if i < s.length && s.charAt(i) == '=' then
            i += 1
            while i < s.length && s.charAt(i).isWhitespace do
              i += 1
            if i >= s.length || (s.charAt(i) != '"' && s.charAt(i) != '\'') then
              fail(s"unquoted attribute ${name} in <${elem}>")
            val q   = s.charAt(i)
            val end = indexOrFail(q.toString, i + 1)
            attrs += name -> decode(s.substring(i + 1, end))
            i = end + 1
          else if name.isEmpty then
            fail(s"malformed <${elem}>")
          else
            // An attribute without a value (not XML, but tolerate it)
            attrs += name -> ""
      end while
      attrs.result()

    end parseAttributes

    private def decode(t: String): String =
      if t.indexOf('&') < 0 then
        t
      else
        val sb = StringBuilder()
        var k  = 0
        while k < t.length do
          val c = t.charAt(k)
          if c == '&' then
            val semi = t.indexOf(';', k)
            if semi < 0 then
              sb.append(c)
              k += 1
            else
              val ent = t.substring(k + 1, semi)
              ent match
                case "amp" =>
                  sb.append('&')
                case "lt" =>
                  sb.append('<')
                case "gt" =>
                  sb.append('>')
                case "quot" =>
                  sb.append('"')
                case "apos" =>
                  sb.append('\'')
                case e if e.startsWith("#x") || e.startsWith("#X") =>
                  sb.appendAll(Character.toChars(Integer.parseInt(e.substring(2), 16)))
                case e if e.startsWith("#") =>
                  sb.appendAll(Character.toChars(Integer.parseInt(e.substring(1))))
                case other =>
                  sb.append(s"&${other};")
              k = semi + 1
          else
            sb.append(c)
            k += 1
        end while
        sb.toString

  end Parser

  /** Escapes text for XML request bodies */
  def escape(t: String): String = t
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

end XmlReader
