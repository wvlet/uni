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
package wvlet.uni.cli

/**
  * Tint provides fluent API for terminal text styling.
  *
  * Usage:
  * {{{
  * import wvlet.uni.cli.Tint
  *
  * // Method chaining
  * Tint.red.bold("Error!")
  * Tint.green("Success")
  * Tint.yellow.bgBlue.underline("Warning")
  *
  * // Reusable styles
  * val errorStyle = Tint.red.bold
  * val successStyle = Tint.green
  * println(errorStyle("Failed!"))
  * println(successStyle("Passed!"))
  *
  * // Extended colors
  * Tint.hex("#FF5733")("Custom color")
  * Tint.rgb(255, 87, 51)("RGB color")
  * Tint.ansi256(208)("256-color")
  * }}}
  */
case class Tint(
    foreground: Option[Color] = None,
    background: Option[Color] = None,
    modifiers: Set[Modifier] = Set.empty,
    enabled: Boolean = true
):
  private val RESET = "\u001b[0m"

  /**
    * Apply the style to the given text.
    */
  def apply(text: String): String =
    if !enabled || (foreground.isEmpty && background.isEmpty && modifiers.isEmpty) then
      text
    else
      val sb = StringBuilder()
      foreground.foreach(c => sb.append(Color.foregroundCode(c)))
      background.foreach(c => sb.append(Color.backgroundCode(c)))
      modifiers.foreach(m => sb.append(Modifier.code(m)))
      sb.append(text)
      sb.append(RESET)
      sb.toString

  // Foreground colors
  def black: Tint   = withForeground(Color.Black)
  def red: Tint     = withForeground(Color.Red)
  def green: Tint   = withForeground(Color.Green)
  def yellow: Tint  = withForeground(Color.Yellow)
  def blue: Tint    = withForeground(Color.Blue)
  def magenta: Tint = withForeground(Color.Magenta)
  def cyan: Tint    = withForeground(Color.Cyan)
  def white: Tint   = withForeground(Color.White)
  def gray: Tint    = withForeground(Color.Gray)

  // Bright foreground colors
  def brightBlack: Tint   = withForeground(Color.BrightBlack)
  def brightRed: Tint     = withForeground(Color.BrightRed)
  def brightGreen: Tint   = withForeground(Color.BrightGreen)
  def brightYellow: Tint  = withForeground(Color.BrightYellow)
  def brightBlue: Tint    = withForeground(Color.BrightBlue)
  def brightMagenta: Tint = withForeground(Color.BrightMagenta)
  def brightCyan: Tint    = withForeground(Color.BrightCyan)
  def brightWhite: Tint   = withForeground(Color.BrightWhite)

  // Background colors
  def bgBlack: Tint   = withBackground(Color.Black)
  def bgRed: Tint     = withBackground(Color.Red)
  def bgGreen: Tint   = withBackground(Color.Green)
  def bgYellow: Tint  = withBackground(Color.Yellow)
  def bgBlue: Tint    = withBackground(Color.Blue)
  def bgMagenta: Tint = withBackground(Color.Magenta)
  def bgCyan: Tint    = withBackground(Color.Cyan)
  def bgWhite: Tint   = withBackground(Color.White)
  def bgGray: Tint    = withBackground(Color.Gray)

  // Bright background colors
  def bgBrightBlack: Tint   = withBackground(Color.BrightBlack)
  def bgBrightRed: Tint     = withBackground(Color.BrightRed)
  def bgBrightGreen: Tint   = withBackground(Color.BrightGreen)
  def bgBrightYellow: Tint  = withBackground(Color.BrightYellow)
  def bgBrightBlue: Tint    = withBackground(Color.BrightBlue)
  def bgBrightMagenta: Tint = withBackground(Color.BrightMagenta)
  def bgBrightCyan: Tint    = withBackground(Color.BrightCyan)
  def bgBrightWhite: Tint   = withBackground(Color.BrightWhite)

  // Text modifiers
  def bold: Tint          = withModifier(Modifier.Bold)
  def dim: Tint           = withModifier(Modifier.Dim)
  def italic: Tint        = withModifier(Modifier.Italic)
  def underline: Tint     = withModifier(Modifier.Underline)
  def strikethrough: Tint = withModifier(Modifier.Strikethrough)
  def inverse: Tint       = withModifier(Modifier.Inverse)
  def hidden: Tint        = withModifier(Modifier.Hidden)

  // Extended colors - foreground
  def rgb(r: Int, g: Int, b: Int): Tint = withForeground(Color.Rgb(r, g, b))
  def hex(hexColor: String): Tint       = withForeground(Color.fromHex(hexColor))
  def ansi256(code: Int): Tint          = withForeground(Color.Ansi256(code))

  // Extended colors - background
  def bgRgb(r: Int, g: Int, b: Int): Tint = withBackground(Color.Rgb(r, g, b))
  def bgHex(hexColor: String): Tint       = withBackground(Color.fromHex(hexColor))
  def bgAnsi256(code: Int): Tint          = withBackground(Color.Ansi256(code))

  // Builder methods
  def withForeground(color: Color): Tint     = this.copy(foreground = Some(color))
  def withBackground(color: Color): Tint     = this.copy(background = Some(color))
  def withModifier(modifier: Modifier): Tint = this.copy(modifiers = modifiers + modifier)
  def withEnabled(enabled: Boolean): Tint    = this.copy(enabled = enabled)

  /**
    * Disable color output. Returns plain text without ANSI codes.
    */
  def noColor: Tint = this.copy(enabled = false)

  /**
    * Reset this tint instance to default (no styling).
    */
  def reset: Tint = Tint()

end Tint

/**
  * Companion object providing static shortcuts for common colors.
  */
object Tint:
  private val default = Tint(None, None, Set.empty, true)

  /**
    * Create a new Tint instance with default settings.
    */
  def apply(): Tint = default

  // Fluent API methods that return a styled Tint instance
  // Use as Tint.red("text") which calls Tint.red.apply("text")
  def black: Tint   = default.black
  def red: Tint     = default.red
  def green: Tint   = default.green
  def yellow: Tint  = default.yellow
  def blue: Tint    = default.blue
  def magenta: Tint = default.magenta
  def cyan: Tint    = default.cyan
  def white: Tint   = default.white
  def gray: Tint    = default.gray

  def brightBlack: Tint   = default.brightBlack
  def brightRed: Tint     = default.brightRed
  def brightGreen: Tint   = default.brightGreen
  def brightYellow: Tint  = default.brightYellow
  def brightBlue: Tint    = default.brightBlue
  def brightMagenta: Tint = default.brightMagenta
  def brightCyan: Tint    = default.brightCyan
  def brightWhite: Tint   = default.brightWhite

  def bold: Tint          = default.bold
  def dim: Tint           = default.dim
  def italic: Tint        = default.italic
  def underline: Tint     = default.underline
  def strikethrough: Tint = default.strikethrough
  def inverse: Tint       = default.inverse
  def hidden: Tint        = default.hidden

  // Extended colors
  def rgb(r: Int, g: Int, b: Int): Tint = default.rgb(r, g, b)
  def hex(hexColor: String): Tint       = default.hex(hexColor)
  def ansi256(code: Int): Tint          = default.ansi256(code)

  // Background colors
  def bgBlack: Tint   = default.bgBlack
  def bgRed: Tint     = default.bgRed
  def bgGreen: Tint   = default.bgGreen
  def bgYellow: Tint  = default.bgYellow
  def bgBlue: Tint    = default.bgBlue
  def bgMagenta: Tint = default.bgMagenta
  def bgCyan: Tint    = default.bgCyan
  def bgWhite: Tint   = default.bgWhite
  def bgGray: Tint    = default.bgGray

  def bgBrightBlack: Tint   = default.bgBrightBlack
  def bgBrightRed: Tint     = default.bgBrightRed
  def bgBrightGreen: Tint   = default.bgBrightGreen
  def bgBrightYellow: Tint  = default.bgBrightYellow
  def bgBrightBlue: Tint    = default.bgBrightBlue
  def bgBrightMagenta: Tint = default.bgBrightMagenta
  def bgBrightCyan: Tint    = default.bgBrightCyan
  def bgBrightWhite: Tint   = default.bgBrightWhite

  // Extended background colors
  def bgRgb(r: Int, g: Int, b: Int): Tint = default.bgRgb(r, g, b)
  def bgHex(hexColor: String): Tint       = default.bgHex(hexColor)
  def bgAnsi256(code: Int): Tint          = default.bgAnsi256(code)

end Tint
