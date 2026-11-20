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
package wvlet.uni.util

import java.nio.charset.StandardCharsets

// The JDK's own conversions: HotSpot intrinsifies both
private[util] object Utf8Compat:
  inline def manualAscii: Boolean = false

  def decode(bytes: Array[Byte], offset: Int, length: Int): String = String(
    bytes,
    offset,
    length,
    StandardCharsets.UTF_8
  )

  def encode(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)
