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
  * A dependency-free SHA-256 (FIPS 180-4) and HMAC-SHA256 (RFC 2104) for request signing.
  * `java.security.MessageDigest` and `javax.crypto.Mac` are not available on Scala Native, so this
  * provides the same digests on JVM, Scala.js, and Scala Native.
  */
private[uni] final class Sha256:
  private val h     = Sha256.initialHash.clone()
  private val block = new Array[Byte](64)
  private val w     = new Array[Int](64)
  private var used  = 0
  private var total = 0L

  def update(bytes: Array[Byte]): Sha256 = update(bytes, 0, bytes.length)

  def update(bytes: Array[Byte], offset: Int, length: Int): Sha256 =
    var i   = offset
    val end = offset + length
    total += length
    // Fill a partial block first
    while used > 0 && i < end do
      block(used) = bytes(i)
      used += 1
      i += 1
      if used == 64 then
        compress(block, 0)
        used = 0
    // Whole blocks straight from the input
    while end - i >= 64 do
      compress(bytes, i)
      i += 64
    while i < end do
      block(used) = bytes(i)
      used += 1
      i += 1
    this

  def digest(): Array[Byte] =
    val bitLen = total * 8
    val pad    =
      new Array[Byte](
        if used < 56 then
          64 - used
        else
          128 - used
      )
    pad(0) = 0x80.toByte
    var s = 0
    while s < 8 do
      pad(pad.length - 1 - s) = ((bitLen >>> (s * 8)) & 0xff).toByte
      s += 1
    // The padding must not count toward the length
    val saved = total
    update(pad)
    total = saved
    val out = new Array[Byte](32)
    var k   = 0
    while k < 8 do
      out(k * 4) = (h(k) >>> 24).toByte
      out(k * 4 + 1) = (h(k) >>> 16).toByte
      out(k * 4 + 2) = (h(k) >>> 8).toByte
      out(k * 4 + 3) = h(k).toByte
      k += 1
    out

  private def compress(data: Array[Byte], offset: Int): Unit =
    var t = 0
    while t < 16 do
      val p = offset + t * 4
      w(t) =
        ((data(p) & 0xff) << 24) |
          ((data(p + 1) & 0xff) << 16) |
          ((data(p + 2) & 0xff) << 8) |
          (data(p + 3) & 0xff)
      t += 1
    while t < 64 do
      val x  = w(t - 15)
      val y  = w(t - 2)
      val s0 = Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ (x >>> 3)
      val s1 = Integer.rotateRight(y, 17) ^ Integer.rotateRight(y, 19) ^ (y >>> 10)
      w(t) = w(t - 16) + s0 + w(t - 7) + s1
      t += 1

    var a  = h(0)
    var b  = h(1)
    var c  = h(2)
    var d  = h(3)
    var e  = h(4)
    var f  = h(5)
    var g  = h(6)
    var hh = h(7)
    t = 0
    while t < 64 do
      val s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)
      val ch = (e & f) ^ (~e & g)
      val temp1 = hh + s1 + ch + Sha256.k(t) + w(t)
      val s0  = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)
      val maj = (a & b) ^ (a & c) ^ (b & c)
      val temp2 = s0 + maj
      hh = g
      g = f
      f = e
      e = d + temp1
      d = c
      c = b
      b = a
      a = temp1 + temp2
      t += 1
    h(0) += a
    h(1) += b
    h(2) += c
    h(3) += d
    h(4) += e
    h(5) += f
    h(6) += g
    h(7) += hh

  end compress

end Sha256

private[uni] object Sha256:
  def digest(message: Array[Byte]): Array[Byte] = Sha256().update(message).digest()

  def digest(message: String): Array[Byte] = digest(message.getBytes("UTF-8"))

  def hex(message: Array[Byte]): String = toHex(digest(message))

  def hex(message: String): String = toHex(digest(message))

  /** The SHA-256 of an empty payload, used by requests without a body */
  lazy val emptyHex: String = hex(Array.emptyByteArray)

  def hmac(key: Array[Byte], message: Array[Byte]): Array[Byte] =
    val k =
      if key.length > 64 then
        digest(key)
      else
        key
    val ipad = new Array[Byte](64)
    val opad = new Array[Byte](64)
    var i    = 0
    while i < 64 do
      val b =
        if i < k.length then
          k(i)
        else
          0.toByte
      ipad(i) = (b ^ 0x36).toByte
      opad(i) = (b ^ 0x5c).toByte
      i += 1
    val inner = Sha256().update(ipad).update(message).digest()
    Sha256().update(opad).update(inner).digest()

  def hmac(key: Array[Byte], message: String): Array[Byte] = hmac(key, message.getBytes("UTF-8"))

  private val hexChars = "0123456789abcdef".toCharArray

  def toHex(bytes: Array[Byte]): String =
    val sb = StringBuilder(bytes.length * 2)
    bytes.foreach { b =>
      sb.append(hexChars((b >> 4) & 0x0f))
      sb.append(hexChars(b & 0x0f))
    }
    sb.toString

  private val initialHash: Array[Int] = Array(
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
  )

  private val k: Array[Int] = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  )

end Sha256
