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
package wvlet.uni.storage

import scala.collection.mutable

/**
  * A minimal XML reader for S3 responses (listings, multipart upload ids, errors). It keeps
  * elements and text, drops attributes, namespace prefixes, comments, and processing instructions,
  * and decodes the predefined and numeric entities. It is not a general XML parser.
  */
private[storage] object S3Xml:
  case class Elem(name: String, children: Seq[Elem], text: String):
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
      throw StorageException(StorageErrorCode.InvalidResponse, s"Malformed XML response: ${msg}")

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
      val name = localName(s.substring(nameStart, i))
      // Skip attributes, honoring quoted values that may contain '>'
      var quote: Char = 0
      while i < s.length && (quote != 0 || (s.charAt(i) != '>' && s.charAt(i) != '/')) do
        val c = s.charAt(i)
        if quote != 0 then
          if c == quote then
            quote = 0
        else if c == '"' || c == '\'' then
          quote = c
        i += 1
      if i >= s.length then
        fail(s"unterminated <${name}>")
      if s.charAt(i) == '/' then
        i = indexOrFail(">", i) + 1
        Elem(name, Nil, "")
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
        Elem(name, children.toSeq, t)
      end if

    end parseElement

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

end S3Xml
