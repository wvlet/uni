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
package wvlet.uni.cli.launcher

/**
  * Represents a key=value pair from command line options. Used with prefixed options like
  * -Lkey=value, -Dkey=value, etc.
  *
  * @param key
  *   The key part before '='
  * @param value
  *   The value part after '='
  */
case class KeyValue(key: String, value: String):
  override def toString: String = s"${key}=${value}"

object KeyValue:
  /** Surface fullName used by the launcher to recognize this scalar-parsed type. */
  val SurfaceName: String = "wvlet.uni.cli.launcher.KeyValue"

  /**
    * Parse "key=value" string into KeyValue. If no '=' is present, value defaults to empty string.
    */
  def parse(s: String): KeyValue =
    s.indexOf('=') match
      case -1 =>
        KeyValue(s, "")
      case idx =>
        KeyValue(s.substring(0, idx), s.substring(idx + 1))
