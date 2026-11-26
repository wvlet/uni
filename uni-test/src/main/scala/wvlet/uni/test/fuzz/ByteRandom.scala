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
package wvlet.uni.test.fuzz

/**
  * A random source that reads its bits from fuzz input bytes, so a generator becomes a decoder of
  * bytes: [[wvlet.uni.test.FuzzCheck.fuzzGen]] mutates and reduces the bytes, and the generator
  * maps them to values. Past the end of the bytes, every draw is 0.
  */
private[test] final class ByteRandom(bytes: Array[Byte]) extends java.util.Random(0L):
  private var pos = 0

  override protected def next(bits: Int): Int =
    var v    = 0
    var read = 0
    while read < bits do
      val b =
        if pos < bytes.length then
          bytes(pos) & 0xff
        else
          0
      pos += 1
      v = (v << 8) | b
      read += 8
    v >>> (read - bits)

  // The constructor calls setSeed; the bytes are the only source of randomness
  override def setSeed(seed: Long): Unit = ()
