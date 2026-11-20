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

import scala.scalanative.meta.LinktimeInfo

// zlib's CRC-32 through javalib's CRC32, except on Windows, where that binding gave checksums gzip
// readers reject; there, the pure-Scala one
private[http] object GzipCrc:
  def of(bytes: Array[Byte]): Int =
    if LinktimeInfo.isWindows then
      wvlet.uni.util.Crc32.of(bytes)
    else
      val crc = java.util.zip.CRC32()
      crc.update(bytes, 0, bytes.length)
      crc.getValue.toInt
