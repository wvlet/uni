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
package wvlet.uni.dom

/**
  * Block elements and box-drawing lines that [[TerminalView]] draws itself, so they fill the whole
  * cell. A font draws them only as tall as its glyph box, which leaves gaps between rows when the
  * line height is above 1 (as in xterm.js, which draws them itself for the same reason).
  */
private[dom] object TerminalGlyphs:

  // A filled rectangle in cell fractions, drawn at `alpha` opacity of the foreground color
  private case class Rect(x0: Double, y0: Double, x1: Double, y1: Double, alpha: Int = 100)

  private val Full = Rect(0, 0, 1, 1)

  private def quadrants(bits: Int): Seq[Rect] = Seq(
    1 -> Rect(0, 0, 0.5, 0.5),
    2 -> Rect(0.5, 0, 1, 0.5),
    4 -> Rect(0, 0.5, 0.5, 1),
    8 -> Rect(0.5, 0.5, 1, 1)
  ).collect {
    case (bit, r) if (bits & bit) != 0 =>
      r
  }

  // U+2580 to U+259F
  private def block(cp: Int): Seq[Rect] =
    cp match
      case 0x2580 =>
        Seq(Rect(0, 0, 1, 0.5))
      case c if c >= 0x2581 && c <= 0x2588 =>
        // Lower one eighth to the full block
        Seq(Rect(0, 1 - (c - 0x2580) / 8.0, 1, 1))
      case c if c >= 0x2589 && c <= 0x258f =>
        // Left seven eighths to left one eighth
        Seq(Rect(0, 0, (0x2590 - c) / 8.0, 1))
      case 0x2590 =>
        Seq(Rect(0.5, 0, 1, 1))
      case 0x2591 =>
        Seq(Full.copy(alpha = 25))
      case 0x2592 =>
        Seq(Full.copy(alpha = 50))
      case 0x2593 =>
        Seq(Full.copy(alpha = 75))
      case 0x2594 =>
        Seq(Rect(0, 0, 1, 1 / 8.0))
      case 0x2595 =>
        Seq(Rect(7 / 8.0, 0, 1, 1))
      case 0x2596 =>
        quadrants(4)
      case 0x2597 =>
        quadrants(8)
      case 0x2598 =>
        quadrants(1)
      case 0x2599 =>
        quadrants(1 | 4 | 8)
      case 0x259a =>
        quadrants(1 | 8)
      case 0x259b =>
        quadrants(1 | 2 | 4)
      case 0x259c =>
        quadrants(1 | 2 | 8)
      case 0x259d =>
        quadrants(2)
      case 0x259e =>
        quadrants(2 | 4)
      case 0x259f =>
        quadrants(2 | 4 | 8)
      case _ =>
        Seq.empty

  // Box-drawing lines as the weights (0 none, 1 light, 2 heavy) of the up, right, down, and left
  // segments from the cell center. Dashed, double, and rounded lines keep the font's glyph.
  private val lines: Map[Int, (Int, Int, Int, Int)] = Map(
    0x2500 -> (0, 1, 0, 1),
    0x2501 -> (0, 2, 0, 2),
    0x2502 -> (1, 0, 1, 0),
    0x2503 -> (2, 0, 2, 0),
    0x250c -> (0, 1, 1, 0),
    0x250d -> (0, 2, 1, 0),
    0x250e -> (0, 1, 2, 0),
    0x250f -> (0, 2, 2, 0),
    0x2510 -> (0, 0, 1, 1),
    0x2511 -> (0, 0, 1, 2),
    0x2512 -> (0, 0, 2, 1),
    0x2513 -> (0, 0, 2, 2),
    0x2514 -> (1, 1, 0, 0),
    0x2515 -> (1, 2, 0, 0),
    0x2516 -> (2, 1, 0, 0),
    0x2517 -> (2, 2, 0, 0),
    0x2518 -> (1, 0, 0, 1),
    0x2519 -> (1, 0, 0, 2),
    0x251a -> (2, 0, 0, 1),
    0x251b -> (2, 0, 0, 2),
    0x251c -> (1, 1, 1, 0),
    0x2523 -> (2, 2, 2, 0),
    0x2524 -> (1, 0, 1, 1),
    0x252b -> (2, 0, 2, 2),
    0x252c -> (0, 1, 1, 1),
    0x2533 -> (0, 2, 2, 2),
    0x2534 -> (1, 1, 0, 1),
    0x253b -> (2, 2, 0, 2),
    0x253c -> (1, 1, 1, 1),
    0x254b -> (2, 2, 2, 2),
    0x2574 -> (0, 0, 0, 1),
    0x2575 -> (1, 0, 0, 0),
    0x2576 -> (0, 1, 0, 0),
    0x2577 -> (0, 0, 1, 0),
    0x2578 -> (0, 0, 0, 2),
    0x2579 -> (2, 0, 0, 0),
    0x257a -> (0, 2, 0, 0),
    0x257b -> (0, 0, 2, 0)
  )

  /**
    * True when the view draws this code point itself
    */
  def isCustom(cp: Int): Boolean =
    cp >= 0x2500 && cp <= 0x259f && (cp >= 0x2580 || lines.contains(cp))

  /**
    * CSS for a cell of `width` x `height` px that draws the code point in the current text color,
    * or an empty string when the font should draw it. The character stays in the DOM, with a
    * transparent fill, so selection and copy still return it.
    */
  def css(cp: Int, width: Double, height: Double): String =
    val rects =
      if cp >= 0x2580 && cp <= 0x259f then
        block(cp).map(r => (r.x0 * width, r.y0 * height, r.x1 * width, r.y1 * height, r.alpha))
      else
        lines.get(cp).map(lineRects(_, width, height)).getOrElse(Seq.empty)
    if rects.isEmpty then
      ""
    else
      def px(v: Double): String = f"${v}%.2fpx"
      val images                = rects.map { (_, _, _, _, alpha) =>
        val c =
          if alpha == 100 then
            "currentColor"
          else
            s"color-mix(in srgb, currentColor ${alpha}%, transparent)"
        s"linear-gradient(${c}, ${c})"
      }
      val sizes     = rects.map((x0, y0, x1, y1, _) => s"${px(x1 - x0)} ${px(y1 - y0)}")
      val positions = rects.map((x0, y0, _, _, _) => s"${px(x0)} ${px(y0)}")
      s"display: inline-block; width: ${px(width)}; height: ${px(height)}; vertical-align: top; " +
        "-webkit-text-fill-color: transparent; background-repeat: no-repeat; " +
        s"background-image: ${images.mkString(", ")}; background-size: ${sizes.mkString(", ")}; " +
        s"background-position: ${positions.mkString(", ")};"

  end css

  private def lineRects(
      weights: (Int, Int, Int, Int),
      width: Double,
      height: Double
  ): Seq[(Double, Double, Double, Double, Int)] =
    val (up, right, down, left) = weights
    val light                   = math.max(1.0, math.round(width / 8).toDouble)
    def thickness(w: Int)       = w * light
    val cx                      = math.floor(width / 2)
    val cy                      = math.floor(height / 2)
    // The bands the vertical and horizontal lines occupy; segments reach across the other band so
    // corners and tees close without notches or spurs
    val tv                                         = thickness(math.max(up, down))
    val th                                         = thickness(math.max(left, right))
    val vx0                                        = cx - math.floor(tv / 2)
    val hy0                                        = cy - math.floor(th / 2)
    def horizontal(w: Int, x0: Double, x1: Double) =
      val t = thickness(w)
      (x0, cy - math.floor(t / 2), x1, cy - math.floor(t / 2) + t, 100)
    def vertical(w: Int, y0: Double, y1: Double) =
      val t = thickness(w)
      (cx - math.floor(t / 2), y0, cx - math.floor(t / 2) + t, y1, 100)
    Seq(
      Option.when(left > 0)(horizontal(left, 0, vx0 + tv)),
      Option.when(right > 0)(horizontal(right, vx0, width)),
      Option.when(up > 0)(vertical(up, 0, hy0 + th)),
      Option.when(down > 0)(vertical(down, hy0, height))
    ).flatten

end TerminalGlyphs
