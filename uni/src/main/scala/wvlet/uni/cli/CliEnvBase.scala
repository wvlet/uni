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
  * Color support level for the terminal.
  */
enum ColorLevel:
  case None      // No color support
  case Basic     // 16 colors (ANSI)
  case Ansi256   // 256 colors
  case TrueColor // 16 million colors (24-bit RGB)

/**
  * Environment detection for CLI utilities. Platform-specific implementations provide actual
  * detection logic.
  */
trait CliEnvBase:
  /**
    * Returns the detected color support level of the terminal.
    */
  def colorLevel: ColorLevel

  /**
    * Returns true if the terminal supports any color output.
    */
  def supportsColor: Boolean = colorLevel != ColorLevel.None

  /**
    * Returns true if the terminal supports 256 colors.
    */
  def supports256Color: Boolean =
    colorLevel == ColorLevel.Ansi256 || colorLevel == ColorLevel.TrueColor

  /**
    * Returns true if the terminal supports true color (24-bit RGB).
    */
  def supportsTrueColor: Boolean = colorLevel == ColorLevel.TrueColor

  /**
    * Returns true if the output is connected to an interactive terminal.
    */
  def isInteractive: Boolean

  /**
    * Returns true if running on Windows.
    */
  def isWindows: Boolean

  /**
    * Returns the terminal width in columns, or a default value if not available.
    */
  def terminalWidth: Int

  /**
    * Returns the terminal height in rows, or a default value if not available.
    */
  def terminalHeight: Int

end CliEnvBase
