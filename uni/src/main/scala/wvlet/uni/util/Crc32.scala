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

/**
  * CRC-32 (IEEE 802.3, as gzip and zip use) in pure Scala, table-driven. For platforms whose
  * `java.util.zip.CRC32` can't be trusted: on Scala Native for Windows it returned checksums that
  * didn't match gzip's (its zlib binding uses the wrong width for zlib's 32-bit `uLong` there).
  */
private[uni] object Crc32:
  private val Table: Array[Int] =
    Array.tabulate(256) { n =>
      var c = n
      var k = 0
      while k < 8 do
        c =
          if (c & 1) != 0 then
            0xedb88320 ^ (c >>> 1)
          else
            c >>> 1
        k += 1
      c
    }

  /** The CRC-32 of `bytes[offset, offset + length)` */
  def of(bytes: Array[Byte], offset: Int, length: Int): Int =
    var c   = 0xffffffff
    var i   = offset
    val end = offset + length
    while i < end do
      c = Table((c ^ bytes(i)) & 0xff) ^ (c >>> 8)
      i += 1
    ~c

  def of(bytes: Array[Byte]): Int = of(bytes, 0, bytes.length)

end Crc32
