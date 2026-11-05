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
package wvlet.uni.cli.term

/**
  * A cell color packed into an Int: the terminal's default color, a 256-color palette index, or a
  * 24-bit RGB value.
  */
object CellColor:
  val Default: Int = 0

  private val PaletteTag = 0x1000000
  private val RgbTag     = 0x2000000

  def palette(index: Int): Int         = PaletteTag | (index & 0xff)
  def rgb(r: Int, g: Int, b: Int): Int =
    RgbTag | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff)

  def isDefault(c: Int): Boolean = c == Default
  def isPalette(c: Int): Boolean = (c & 0x3000000) == PaletteTag
  def isRgb(c: Int): Boolean     = (c & 0x3000000) == RgbTag

  /**
    * The palette index of a palette color
    */
  def index(c: Int): Int = c & 0xff

  /**
    * The 24-bit `0xRRGGBB` value of an RGB color
    */
  def rgbValue(c: Int): Int = c & 0xffffff

end CellColor

/**
  * Cell attribute bits
  */
object CellFlags:
  val Bold: Int          = 1
  val Dim: Int           = 1 << 1
  val Italic: Int        = 1 << 2
  val Underline: Int     = 1 << 3
  val Blink: Int         = 1 << 4
  val Inverse: Int       = 1 << 5
  val Invisible: Int     = 1 << 6
  val Strikethrough: Int = 1 << 7
  val Overline: Int      = 1 << 8

  /**
    * The first (left) half of a two-column character
    */
  val Wide: Int = 1 << 9

  /**
    * The second (right) half of a two-column character. It holds no text of its own.
    */
  val WideTail: Int = 1 << 10

  /**
    * The bits that describe text style, as opposed to cell layout
    */
  val StyleMask: Int = Wide - 1

end CellFlags

/**
  * One row of terminal cells. Cells are stored in parallel arrays so that a screen of thousands of
  * cells stays cheap to scroll and keep in scrollback.
  */
final class Line private[term] (initialCols: Int, bg: Int):
  private[term] var codePoints: Array[Int] = Array.fill(initialCols)(0)
  private[term] var fgs: Array[Int]        = Array.fill(initialCols)(CellColor.Default)
  private[term] var bgs: Array[Int]        = Array.fill(initialCols)(bg)
  private[term] var attrs: Array[Int]      = Array.fill(initialCols)(0)
  // Allocated on first use: most lines have no links or combining marks
  private[term] var links: Array[Int]        = null
  private[term] var combining: Array[String] = null

  /**
    * True when this line continues the previous line because the text wrapped at the right margin
    */
  var isWrapped: Boolean = false

  /**
    * The number of cells
    */
  def length: Int = codePoints.length

  /**
    * The code point of a cell, or 0 for an empty cell or the right half of a wide character
    */
  def codePoint(col: Int): Int = codePoints(col)
  def fg(col: Int): Int        = fgs(col)
  def bg(col: Int): Int        = bgs(col)
  def flags(col: Int): Int     = attrs(col)

  /**
    * The hyperlink id of a cell (see [[TerminalEmulator.hyperlink]]), or 0 when there is none
    */
  def link(col: Int): Int =
    if links == null then
      0
    else
      links(col)

  /**
    * The text a cell shows: its character with any combining marks, a space for an empty cell, or
    * "" for the right half of a wide character
    */
  def cellText(col: Int): String =
    val cp = codePoints(col)
    if (attrs(col) & CellFlags.WideTail) != 0 then
      ""
    else if cp == 0 then
      " "
    else
      val base = String(Character.toChars(cp))
      if combining != null && combining(col) != null then
        base + combining(col)
      else
        base

  /**
    * The text of this line with trailing spaces removed
    */
  def text: String =
    val sb  = StringBuilder()
    val end = contentLength
    var i   = 0
    while i < end do
      sb.append(cellText(i))
      i += 1
    sb.toString

  override def toString: String = text

  /**
    * The number of cells up to the last one that holds a character
    */
  private[term] def contentLength: Int =
    var end = length
    while end > 0 && codePoints(end - 1) == 0 && (attrs(end - 1) & CellFlags.WideTail) == 0 do
      end -= 1
    end

  /**
    * Copy a cell, with its combining marks and link, into another line
    */
  private[term] def copyCellTo(col: Int, dst: Line, dstCol: Int): Unit =
    dst.setCell(dstCol, codePoints(col), fgs(col), bgs(col), attrs(col), link(col))
    if combining != null && combining(col) != null then
      if dst.combining == null then
        dst.combining = Array.fill[String](dst.length)(null)
      dst.combining(dstCol) = combining(col)

  private[term] def setCell(col: Int, cp: Int, fg: Int, bg: Int, flags: Int, link: Int): Unit =
    codePoints(col) = cp
    fgs(col) = fg
    bgs(col) = bg
    attrs(col) = flags
    if link != 0 then
      if links == null then
        links = Array.fill(length)(0)
      links(col) = link
    else if links != null then
      links(col) = 0
    if combining != null then
      combining(col) = null

  private[term] def blank(col: Int, bg: Int): Unit = setCell(col, 0, CellColor.Default, bg, 0, 0)

  private[term] def blank(from: Int, until: Int, bg: Int): Unit =
    var i = math.max(0, from)
    val e = math.min(length, until)
    while i < e do
      blank(i, bg)
      i += 1

  private[term] def appendCombining(col: Int, cp: Int): Unit =
    if combining == null then
      combining = Array.fill[String](length)(null)
    val mark = String(Character.toChars(cp))
    combining(col) =
      if combining(col) == null then
        mark
      else
        combining(col) + mark

  private[term] def copyCell(from: Int, to: Int): Unit =
    codePoints(to) = codePoints(from)
    fgs(to) = fgs(from)
    bgs(to) = bgs(from)
    attrs(to) = attrs(from)
    if links != null then
      links(to) = links(from)
    if combining != null then
      combining(to) = combining(from)

  /**
    * Blank a wide character that the boundary between `col - 1` and `col` cuts in half
    */
  private[term] def splitAt(col: Int, bg: Int): Unit =
    if col > 0 && col < length && (attrs(col) & CellFlags.WideTail) != 0 then
      blank(col - 1, bg)
      blank(col, bg)

  /**
    * Insert n blank cells at `col`, shifting the rest right; cells pushed past the end are lost
    */
  private[term] def insertCells(col: Int, n: Int, bg: Int): Unit =
    splitAt(col, bg)
    var i = length - 1
    while i >= col + n do
      copyCell(i - n, i)
      i -= 1
    blank(col, math.min(length, col + n), bg)
    fixRightEdge(bg)

  /**
    * Delete n cells at `col`, shifting the rest left and filling the end with blanks
    */
  private[term] def deleteCells(col: Int, n: Int, bg: Int): Unit =
    splitAt(col, bg)
    splitAt(col + n, bg)
    var i = col
    while i + n < length do
      copyCell(i + n, i)
      i += 1
    blank(math.max(col, length - n), length, bg)

  // A wide character cut in half at the right edge becomes a blank
  private def fixRightEdge(bg: Int): Unit =
    if length > 0 && (attrs(length - 1) & CellFlags.Wide) != 0 then
      blank(length - 1, bg)

  private[term] def resize(newCols: Int, bg: Int): Unit =
    val old = length
    if newCols != old then
      def grow(a: Array[Int], fill: Int): Array[Int] =
        val b = Array.fill(newCols)(fill)
        System.arraycopy(a, 0, b, 0, math.min(old, newCols))
        b
      codePoints = grow(codePoints, 0)
      fgs = grow(fgs, CellColor.Default)
      bgs = grow(bgs, bg)
      attrs = grow(attrs, 0)
      if links != null then
        links = grow(links, 0)
      if combining != null then
        val b = Array.fill[String](newCols)(null)
        System.arraycopy(combining, 0, b, 0, math.min(old, newCols))
        combining = b
      fixRightEdge(bg)

end Line
