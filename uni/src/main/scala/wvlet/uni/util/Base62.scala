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

import java.math.BigInteger

/**
  * Base62 encoding using the alphabet `0-9A-Za-z`. This ordering is sort-order preserving:
  * lexicographic order of encoded strings matches the numeric order of the original values.
  *
  * 128-bit values encode to exactly 22 characters (ceil(128 * log(2) / log(62)) = 22).
  */
object Base62:

  private val Base = BigInteger.valueOf(62)

  // Sort-order preserving alphabet: 0-9 < A-Z < a-z
  private val Alphabet: Array[Char] = (('0' to '9') ++ ('A' to 'Z') ++ ('a' to 'z')).toArray

  // Pre-allocated BigInteger values for each alphabet index (0-61) to avoid repeated allocations
  private val BigIntValues: Array[BigInteger] = (0 until 62)
    .map(i => BigInteger.valueOf(i.toLong))
    .toArray

  // Decoding table: char -> value (0-61), -1 for invalid
  private val DecodeTable: Array[Byte] =
    val table = Array.fill[Byte](128)(-1)
    var i     = 0
    while i < Alphabet.length do
      table(Alphabet(i).toInt) = i.toByte
      i += 1
    table

  // Fixed length for 128-bit values
  private val EncodedLength128 = 22

  // Pre-allocated 64-bit mask for splitting 128-bit values
  private val Mask64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

  /**
    * Encode a 128-bit value (represented as two Longs) to a 22-character Base62 string.
    */
  def encode128bits(hi: Long, low: Long): String =
    // Convert to unsigned 128-bit BigInteger
    val hiBig  = toUnsignedBigInteger(hi)
    val lowBig = toUnsignedBigInteger(low)
    val value  = hiBig.shiftLeft(64).or(lowBig)
    encodeBigInteger(value, EncodedLength128)

  /**
    * Decode a 22-character Base62 string to a 128-bit value (hi, low).
    */
  def decode128bits(s: String): (Long, Long) =
    if s.length != EncodedLength128 then
      throw IllegalArgumentException(
        s"Base62 string must be ${EncodedLength128} characters: ${s} (length: ${s.length})"
      )
    val value = decodeBigInteger(s)
    if value.bitLength() > 128 then
      throw IllegalArgumentException(s"Base62 value exceeds 128-bit range: ${s}")
    val low = value.and(Mask64).longValue()
    val hi  = value.shiftRight(64).and(Mask64).longValue()
    (hi, low)

  /**
    * Encode an arbitrary BigInteger to a Base62 string with the given fixed length. The result is
    * left-padded with '0' characters.
    */
  private def encodeBigInteger(value: BigInteger, length: Int): String =
    val buf = new Array[Char](length)
    var v   = value
    var i   = length - 1
    while i >= 0 do
      val Array(quotient, remainder) = v.divideAndRemainder(Base)
      buf(i) = Alphabet(remainder.intValue())
      v = quotient
      i -= 1
    String(buf)

  /**
    * Decode a Base62 string to a BigInteger.
    */
  private def decodeBigInteger(s: String): BigInteger =
    var result = BigInteger.ZERO
    var i      = 0
    while i < s.length do
      val ch = s.charAt(i)
      if ch >= 128 then
        throw IllegalArgumentException(s"Invalid Base62 character: ${ch}")
      val v = DecodeTable(ch)
      if v < 0 then
        throw IllegalArgumentException(s"Invalid Base62 character: ${ch}")
      result = result.multiply(Base).add(BigIntValues(v.toInt))
      i += 1
    result

  /**
    * Check if a string contains only valid Base62 characters.
    */
  def isValid(s: String): Boolean = s.forall { ch =>
    ch < 128 && DecodeTable(ch) >= 0
  }

  private def toUnsignedBigInteger(v: Long): BigInteger =
    if v >= 0 then
      BigInteger.valueOf(v)
    else
      // Treat as unsigned: add 2^64
      BigInteger.valueOf(v & Long.MaxValue).add(BigInteger.ONE.shiftLeft(63))

end Base62
