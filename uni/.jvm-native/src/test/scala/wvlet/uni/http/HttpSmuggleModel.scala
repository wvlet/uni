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
package wvlet.uni.http

/**
  * The family of lenient HTTP head parsers from `spec/lean/UniSpec/HttpSmuggle.lean`, transcribed
  * for checking the reader on fuzz inputs. Each member is one combination of behaviours real
  * proxies and servers have shipped; see the Lean file for the list and for the rule, proven there
  * as `no_smuggling`: when the reader accepts a request and keeps the connection open after it,
  * every member rejects it or finds the same head length and the same framing.
  *
  * The helpers here are written apart from the reader's, so a bug in a shared helper cannot make
  * both sides agree by accident. Bytes are held as ISO-8859-1 strings, one char per byte.
  */
object HttpSmuggleModel:

  case class Opts(
      lfLines: Boolean,
      dupLast: Boolean,
      teWins: Boolean,
      stripColonWs: Boolean,
      unfold: Boolean,
      trimValue: Boolean
  )

  /** All 64 members */
  val allOpts: Seq[Opts] =
    val bs = Seq(false, true)
    for
      a <- bs
      b <- bs
      c <- bs
      d <- bs
      e <- bs
      f <- bs
    yield Opts(a, b, c, d, e, f)

  enum Framing:
    case Length(n: BigInt)
    case Chunked

  private def isWs(c: Char): Boolean = c == ' ' || c == '\t'

  private def lowerAscii(c: Char): Char =
    if 'A' <= c && c <= 'Z' then
      (c + 32).toChar
    else
      c

  private def named(n: String, s: String): Boolean = n.map(lowerAscii) == s

  private def dropTrailingWs(s: String): String = s.reverse.dropWhile(isWs).reverse

  /** One line from `from`: its content and the index after its end, or None if no end arrived */
  private def nextLine(lf: Boolean, s: String, from: Int): Option[(String, Int)] =
    val line = StringBuilder()
    var i    = from
    while i < s.length do
      if s(i) == '\r' && i + 1 < s.length && s(i + 1) == '\n' then
        return Some((line.result(), i + 2))
      else if lf && s(i) == '\n' then
        return Some((line.result(), i + 1))
      line += s(i)
      i += 1
    None

  /** The lines up to the empty line, and the index after it */
  private def headLines(lf: Boolean, s: String): Option[(List[String], Int)] =
    val lines = List.newBuilder[String]
    var pos   = 0
    while true do
      nextLine(lf, s, pos) match
        case None =>
          return None
        case Some(("", next)) =>
          return Some((lines.result(), next))
        case Some((line, next)) =>
          lines += line
          pos = next
    None

  /** Decimal digits without a limit; None for anything else, or nothing */
  private def decimal(v: String): Option[BigInt] =
    if v.isEmpty || !v.forall(c => '0' <= c && c <= '9') then
      None
    else
      Some(BigInt(v))

  /** The fields a member keeps, newest first */
  private def addLine(o: Opts)(acc: List[(String, String)], line: String): List[(String, String)] =
    def value(v: String): String =
      val trimmed = v.dropWhile(isWs)
      if o.trimValue then
        dropTrailingWs(trimmed)
      else
        trimmed
    if line.isEmpty then
      acc
    else if isWs(line.head) then
      if o.unfold then
        acc match
          case (n, v) :: rest =>
            (n, s"${v} ${value(line.tail)}") :: rest
          case Nil =>
            acc
      else
        acc
    else
      val colon = line.indexOf(':')
      if colon < 0 then
        acc
      else
        val name = line.substring(0, colon)
        val n    =
          if o.stripColonWs then
            dropTrailingWs(name)
          else
            name
        (n, value(line.substring(colon + 1))) :: acc

  end addLine

  /**
    * Member `o`: the length of the head at the front of `s0` (with the CR and LF before it) and the
    * body's framing, or None if it rejects
    */
  def interp(o: Opts, s0: String): Option[(Int, Framing)] =
    val skipped = s0.takeWhile(c => c == '\r' || c == '\n').length
    val s       = s0.substring(skipped)
    headLines(o.lfLines, s).flatMap { (lines, after) =>
      val fields  = lines.drop(1).foldLeft(List.empty[(String, String)])(addLine(o)).reverse
      val te      = fields.filter((n, _) => named(n, "transfer-encoding")).map(_._2)
      val cl      = fields.filter((n, _) => named(n, "content-length")).map(_._2)
      val chunked = te.lastOption.exists(named(_, "chunked"))
      val length  =
        (
          if o.dupLast then
            cl.lastOption
          else
            cl.headOption
        ) match
          case None =>
            Some(BigInt(0))
          case Some(v) =>
            decimal(v)
      val framing =
        if te.isEmpty then
          length.map(Framing.Length(_))
        else if cl.isEmpty || o.teWins then
          Option.when(chunked)(Framing.Chunked)
        else
          length.map(Framing.Length(_))
      framing.map(fr => (skipped + after, fr))
    }

  /** The members that accept `s` but frame it differently from `reader` */
  def disagreeing(reader: (Int, Framing), s: String): Seq[(Opts, (Int, Framing))] = allOpts
    .flatMap { o =>
      interp(o, s).filter(_ != reader).map(o -> _)
    }

end HttpSmuggleModel
