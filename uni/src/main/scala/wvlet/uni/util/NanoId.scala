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

import scala.util.Random

/**
  * NanoId generator — compact, URL-safe, unique string IDs.
  *
  * Default: 21 characters from the alphabet `A-Za-z0-9_-` (64 symbols). Uses SecureRandom with
  * rejection sampling (bitmask) to avoid modulo bias.
  */
object NanoId:

  val DefaultAlphabet: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
  val DefaultSize: Int        = 21

  private val defaultRandom: Random = SecureRandom.getInstance

  /**
    * Generate a NanoId with default settings (21 chars, A-Za-z0-9_-).
    */
  def generate(): String = generate(DefaultAlphabet, DefaultSize)

  /**
    * Generate a NanoId with a custom size using the default alphabet.
    */
  def generate(size: Int): String = generate(DefaultAlphabet, size)

  /**
    * Generate a NanoId with a custom alphabet and size.
    *
    * @param alphabet
    *   The set of characters to use (must have 2-255 characters, no duplicates).
    * @param size
    *   The length of the generated ID (must be > 0).
    */
  def generate(alphabet: String, size: Int): String = generate(alphabet, size, defaultRandom)

  /**
    * Generate a NanoId with a custom alphabet, size, and random source.
    */
  def generate(alphabet: String, size: Int, random: Random): String =
    if alphabet.isEmpty || alphabet.length > 255 then
      throw IllegalArgumentException(
        s"Alphabet must have 1 to 255 characters, but has ${alphabet.length}"
      )
    if size <= 0 then
      throw IllegalArgumentException(s"Size must be positive: ${size}")

    // Bitmask: smallest power-of-2 minus 1 that covers all alphabet indices
    val mask =
      if alphabet.length == 1 then
        0
      else
        (Integer.highestOneBit(alphabet.length - 1) << 1) - 1
    // How many random bytes to request per step.
    // Factor of 1.6 reduces the expected number of iterations.
    val step = math.max(1, math.ceil(1.6 * mask * size / alphabet.length).toInt)

    val id    = new Array[Char](size)
    val bytes = new Array[Byte](step)
    var count = 0
    while count < size do
      random.nextBytes(bytes)
      var i = 0
      while i < step && count < size do
        val idx = bytes(i) & mask
        if idx < alphabet.length then
          id(count) = alphabet.charAt(idx)
          count += 1
        i += 1
    String(id)

  end generate

end NanoId
