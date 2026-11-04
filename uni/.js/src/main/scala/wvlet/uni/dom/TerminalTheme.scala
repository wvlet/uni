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

import scala.collection.immutable.ListMap

/**
  * Colors for [[TerminalView]], as CSS color strings. `ansi` holds the 16 base colors: black, red,
  * green, yellow, blue, magenta, cyan, white, then their bright variants.
  */
case class TerminalTheme(
    foreground: String,
    background: String,
    cursor: String,
    selection: String,
    ansi: Seq[String]
):
  require(ansi.length == 16, s"A terminal theme needs 16 ANSI colors, but got ${ansi.length}")

  def withForeground(color: String): TerminalTheme = copy(foreground = color)
  def withBackground(color: String): TerminalTheme = copy(background = color)
  def withCursor(color: String): TerminalTheme     = copy(cursor = color)
  def withSelection(color: String): TerminalTheme  = copy(selection = color)
  def withAnsi(colors: Seq[String]): TerminalTheme = copy(ansi = colors)

  private lazy val palette: Array[String] = TerminalTheme.palette256(ansi)

  /**
    * The CSS color of a 256-color palette index
    */
  def paletteColor(index: Int): String = palette(index & 0xff)

  // The theme as CSS custom properties, which TerminalView's cells refer to
  private[dom] def cssVariables: Seq[(String, String)] =
    Seq(
      "--uni-term-fg"        -> foreground,
      "--uni-term-bg"        -> background,
      "--uni-term-cursor"    -> cursor,
      "--uni-term-selection" -> selection
    ) ++ ansi.zipWithIndex.map((c, i) => s"--uni-term-${i}" -> c)

end TerminalTheme

/**
  * Preset themes. [[uniDark]] is the default of [[TerminalViewConfig]]; [[presets]] lists them all
  * by name, for a theme picker.
  */
object TerminalTheme:

  /**
    * Uni's dark theme: a teal-black surface, Uni's cyan glow for the cursor, and ANSI colors tuned
    * to sit with the teal brand colors
    */
  val uniDark: TerminalTheme = TerminalTheme(
    foreground = "#d5e4e7",
    background = "#0a1a1e",
    cursor = "#08bdcb",
    selection = "rgba(37, 167, 193, 0.32)",
    ansi = Seq(
      "#16313a",
      "#ef6f78",
      "#5cc98f",
      "#e8c46c",
      "#5d9eec",
      "#b78ae8",
      "#08bdcb",
      "#b7b9b4",
      "#5f7c82",
      "#ff9198",
      "#86e0ad",
      "#f6d993",
      "#8bbaf5",
      "#d2adf4",
      "#6fdbe8",
      "#eef5f6"
    )
  )

  /**
    * Uni's light theme: a near-white surface with Uni's teal brand color for the cursor
    */
  val uniLight: TerminalTheme = TerminalTheme(
    foreground = "#10272c",
    background = "#fbfdfd",
    cursor = "#2e7685",
    selection = "rgba(46, 118, 133, 0.22)",
    ansi = Seq(
      "#10272c",
      "#c4374a",
      "#23824a",
      "#946500",
      "#2c5fc0",
      "#8742b5",
      "#1f7f91",
      "#8a9ca0",
      "#5b7176",
      "#de4f5e",
      "#2f9c5c",
      "#b07d0a",
      "#4679dc",
      "#a35fd0",
      "#3c99ad",
      "#c4d0d3"
    )
  )

  /**
    * Neutral dark colors, like VS Code's integrated terminal
    */
  val dark: TerminalTheme = TerminalTheme(
    foreground = "#d4d4d4",
    background = "#1e1e1e",
    cursor = "#d4d4d4",
    selection = "rgba(120, 160, 255, 0.35)",
    ansi = Seq(
      "#000000",
      "#cd3131",
      "#0dbc79",
      "#e5e510",
      "#2472c8",
      "#bc3fbc",
      "#11a8cd",
      "#e5e5e5",
      "#666666",
      "#f14c4c",
      "#23d18b",
      "#f5f543",
      "#3b8eea",
      "#d670d6",
      "#29b8db",
      "#ffffff"
    )
  )

  /**
    * Neutral light colors, like VS Code's integrated terminal
    */
  val light: TerminalTheme = TerminalTheme(
    foreground = "#333333",
    background = "#ffffff",
    cursor = "#333333",
    selection = "rgba(0, 100, 255, 0.25)",
    ansi = Seq(
      "#000000",
      "#cd3131",
      "#00bc00",
      "#949800",
      "#0451a5",
      "#bc05bc",
      "#0598bc",
      "#555555",
      "#666666",
      "#cd3131",
      "#14ce14",
      "#b5ba00",
      "#0451a5",
      "#bc05bc",
      "#0598bc",
      "#a5a5a5"
    )
  )

  // Solarized by Ethan Schoonover (MIT License), with its official terminal mapping
  private val solarizedAnsi = Seq(
    "#073642",
    "#dc322f",
    "#859900",
    "#b58900",
    "#268bd2",
    "#d33682",
    "#2aa198",
    "#eee8d5",
    "#002b36",
    "#cb4b16",
    "#586e75",
    "#657b83",
    "#839496",
    "#6c71c4",
    "#93a1a1",
    "#fdf6e3"
  )

  /**
    * Solarized Dark, by Ethan Schoonover (MIT License)
    */
  val solarizedDark: TerminalTheme = TerminalTheme(
    foreground = "#839496",
    background = "#002b36",
    cursor = "#93a1a1",
    selection = "rgba(147, 161, 161, 0.25)",
    ansi = solarizedAnsi
  )

  /**
    * Solarized Light, by Ethan Schoonover (MIT License)
    */
  val solarizedLight: TerminalTheme = TerminalTheme(
    foreground = "#657b83",
    background = "#fdf6e3",
    cursor = "#586e75",
    selection = "rgba(88, 110, 117, 0.2)",
    ansi = solarizedAnsi
  )

  /**
    * Nord, by Arctic Ice Studio (MIT License)
    */
  val nord: TerminalTheme = TerminalTheme(
    foreground = "#d8dee9",
    background = "#2e3440",
    cursor = "#d8dee9",
    selection = "rgba(136, 192, 208, 0.25)",
    ansi = Seq(
      "#3b4252",
      "#bf616a",
      "#a3be8c",
      "#ebcb8b",
      "#81a1c1",
      "#b48ead",
      "#88c0d0",
      "#e5e9f0",
      "#4c566a",
      "#bf616a",
      "#a3be8c",
      "#ebcb8b",
      "#81a1c1",
      "#b48ead",
      "#8fbcbb",
      "#eceff4"
    )
  )

  /**
    * Dracula, by the Dracula Theme team (MIT License)
    */
  val dracula: TerminalTheme = TerminalTheme(
    foreground = "#f8f8f2",
    background = "#282a36",
    cursor = "#f8f8f2",
    selection = "rgba(68, 71, 90, 0.9)",
    ansi = Seq(
      "#21222c",
      "#ff5555",
      "#50fa7b",
      "#f1fa8c",
      "#bd93f9",
      "#ff79c6",
      "#8be9fd",
      "#f8f8f2",
      "#6272a4",
      "#ff6e6e",
      "#69ff94",
      "#ffffa5",
      "#d6acff",
      "#ff92df",
      "#a4ffff",
      "#ffffff"
    )
  )

  /**
    * Gruvbox Dark, by Pavel Pertsev (MIT License)
    */
  val gruvboxDark: TerminalTheme = TerminalTheme(
    foreground = "#ebdbb2",
    background = "#282828",
    cursor = "#ebdbb2",
    selection = "rgba(80, 73, 69, 0.9)",
    ansi = Seq(
      "#282828",
      "#cc241d",
      "#98971a",
      "#d79921",
      "#458588",
      "#b16286",
      "#689d6a",
      "#a89984",
      "#928374",
      "#fb4934",
      "#b8bb26",
      "#fabd2f",
      "#83a598",
      "#d3869b",
      "#8ec07c",
      "#ebdbb2"
    )
  )

  /**
    * One Dark, from the Atom editor (MIT License)
    */
  val oneDark: TerminalTheme = TerminalTheme(
    foreground = "#abb2bf",
    background = "#282c34",
    cursor = "#528bff",
    selection = "rgba(62, 68, 81, 0.9)",
    ansi = Seq(
      "#282c34",
      "#e06c75",
      "#98c379",
      "#e5c07b",
      "#61afef",
      "#c678dd",
      "#56b6c2",
      "#abb2bf",
      "#5c6370",
      "#e06c75",
      "#98c379",
      "#e5c07b",
      "#61afef",
      "#c678dd",
      "#56b6c2",
      "#ffffff"
    )
  )

  /**
    * Every preset by name, Uni's themes first
    */
  val presets: ListMap[String, TerminalTheme] = ListMap(
    "uniDark"        -> uniDark,
    "uniLight"       -> uniLight,
    "dark"           -> dark,
    "light"          -> light,
    "solarizedDark"  -> solarizedDark,
    "solarizedLight" -> solarizedLight,
    "nord"           -> nord,
    "dracula"        -> dracula,
    "gruvboxDark"    -> gruvboxDark,
    "oneDark"        -> oneDark
  )

  // The 16 base colors, a 6x6x6 color cube, and a 24-step gray ramp, as xterm defines them
  private def palette256(ansi: Seq[String]): Array[String] =
    val levels = Array(0, 95, 135, 175, 215, 255)
    Array.tabulate(256) { i =>
      if i < 16 then
        ansi(i)
      else if i < 232 then
        val c = i - 16
        s"rgb(${levels(c / 36)}, ${levels((c / 6) % 6)}, ${levels(c % 6)})"
      else
        val v = 8 + (i - 232) * 10
        s"rgb(${v}, ${v}, ${v})"
    }

end TerminalTheme
