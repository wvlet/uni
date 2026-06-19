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

import scala.collection.mutable

/**
  * A small, dependency-free SHA-1 (RFC 3174) used for the WebSocket handshake accept key.
  * `java.security.MessageDigest` is not available on Scala Native, so this provides the digest; the
  * result is base64-encoded with `java.util.Base64` (which is available on Native).
  */
private[http] object Sha1:

  def digest(message: Array[Byte]): Array[Byte] =
    var h0 = 0x67452301
    var h1 = 0xefcdab89
    var h2 = 0x98badcfe
    var h3 = 0x10325476
    var h4 = 0xc3d2e1f0

    val bitLen = message.length.toLong * 8
    val padded = mutable.ArrayBuffer.from(message)
    padded += 0x80.toByte
    while padded.length % 64 != 56 do
      padded += 0.toByte
    var s = 7
    while s >= 0 do
      padded += ((bitLen >>> (s * 8)) & 0xff).toByte
      s -= 1

    val w     = new Array[Int](80)
    var chunk = 0
    while chunk < padded.length do
      var t = 0
      while t < 16 do
        val off = chunk + t * 4
        w(t) =
          ((padded(off) & 0xff) << 24) |
            ((padded(off + 1) & 0xff) << 16) |
            ((padded(off + 2) & 0xff) << 8) |
            (padded(off + 3) & 0xff)
        t += 1
      while t < 80 do
        w(t) = Integer.rotateLeft(w(t - 3) ^ w(t - 8) ^ w(t - 14) ^ w(t - 16), 1)
        t += 1

      var a = h0
      var b = h1
      var c = h2
      var d = h3
      var e = h4
      var j = 0
      while j < 80 do
        var f = 0
        var k = 0
        if j < 20 then
          f = (b & c) | (~b & d);
          k = 0x5a827999
        else if j < 40 then
          f = b ^ c ^ d;
          k = 0x6ed9eba1
        else if j < 60 then
          f = (b & c) | (b & d) | (c & d);
          k = 0x8f1bbcdc
        else
          f = b ^ c ^ d;
          k = 0xca62c1d6
        val tmp = Integer.rotateLeft(a, 5) + f + e + k + w(j)
        e = d
        d = c
        c = Integer.rotateLeft(b, 30)
        b = a
        a = tmp
        j += 1

      h0 += a
      h1 += b
      h2 += c
      h3 += d
      h4 += e
      chunk += 64
    end while

    val hs  = Array(h0, h1, h2, h3, h4)
    val out = new Array[Byte](20)
    var x   = 0
    while x < 5 do
      out(x * 4) = ((hs(x) >>> 24) & 0xff).toByte
      out(x * 4 + 1) = ((hs(x) >>> 16) & 0xff).toByte
      out(x * 4 + 2) = ((hs(x) >>> 8) & 0xff).toByte
      out(x * 4 + 3) = (hs(x) & 0xff).toByte
      x += 1
    out

  end digest

end Sha1
