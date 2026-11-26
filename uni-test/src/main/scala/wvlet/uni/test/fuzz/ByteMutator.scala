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

import scala.util.Random

/**
  * Byte-level mutations in the style of libFuzzer and AFL, written in plain Scala so they run on
  * the JVM, Scala.js, and Scala Native.
  *
  * Each call applies 1 to 4 stacked mutations to a copy of `base`. Mutations that edit a few bytes
  * keep most of a valid input intact, so a parser gets past its header checks and reaches deeper
  * code; splicing with another corpus input and inserting dictionary tokens add structure that
  * random bytes rarely produce.
  */
object ByteMutator:

  /** Values at integer and length boundaries, where off-by-one and overflow bugs live. */
  private val interestingLongs: Array[Long] = Array(
    0L,
    1L,
    -1L,
    16L,
    32L,
    64L,
    100L,
    127L,
    128L,
    255L,
    256L,
    1024L,
    4096L,
    32767L,
    32768L,
    65535L,
    65536L,
    Int.MaxValue.toLong,
    Int.MinValue.toLong,
    0xffffffffL,
    Long.MaxValue,
    Long.MinValue
  )

  private val NumMutations = 12

  def mutate(
      base: Array[Byte],
      other: Array[Byte],
      dictionary: IndexedSeq[Array[Byte]],
      rng: Random,
      maxLength: Int
  ): Array[Byte] =
    var cur   = base
    val count = 1 + rng.nextInt(4)
    var i     = 0
    while i < count do
      cur = mutateOnce(cur, other, dictionary, rng)
      i += 1
    if cur.length > maxLength then
      cur.take(maxLength)
    else
      cur

  private def mutateOnce(
      in: Array[Byte],
      other: Array[Byte],
      dictionary: IndexedSeq[Array[Byte]],
      rng: Random
  ): Array[Byte] =
    if in.isEmpty then
      // Nothing to edit in place: grow the input first.
      if dictionary.nonEmpty && rng.nextBoolean() then
        dictionary(rng.nextInt(dictionary.length)).clone()
      else
        randomBytes(rng, 1 + rng.nextInt(8))
    else
      rng.nextInt(NumMutations) match
        case 0 =>
          flipBit(in, rng)
        case 1 =>
          setRandomByte(in, rng)
        case 2 =>
          addToByte(in, rng)
        case 3 =>
          writeInteresting(in, rng)
        case 4 =>
          insert(in, rng.nextInt(in.length + 1), randomBytes(rng, 1 + rng.nextInt(4)))
        case 5 =>
          deleteRange(in, rng)
        case 6 =>
          duplicateRange(in, rng)
        case 7 =>
          if other.nonEmpty then
            crossOver(in, other, rng)
          else
            flipBit(in, rng)
        case 8 =>
          if other.nonEmpty then
            overwriteFrom(in, other, rng)
          else
            setRandomByte(in, rng)
        case 9 =>
          if dictionary.nonEmpty then
            insert(in, rng.nextInt(in.length + 1), dictionary(rng.nextInt(dictionary.length)))
          else
            writeInteresting(in, rng)
        case 10 =>
          if dictionary.nonEmpty then
            overwriteFrom(in, dictionary(rng.nextInt(dictionary.length)), rng)
          else
            addToByte(in, rng)
        case _ =>
          in.take(rng.nextInt(in.length))

  private def randomBytes(rng: Random, n: Int): Array[Byte] =
    val b = new Array[Byte](n)
    rng.nextBytes(b)
    b

  private def flipBit(in: Array[Byte], rng: Random): Array[Byte] =
    val out = in.clone()
    val pos = rng.nextInt(out.length)
    out(pos) = (out(pos) ^ (1 << rng.nextInt(8))).toByte
    out

  private def setRandomByte(in: Array[Byte], rng: Random): Array[Byte] =
    val out = in.clone()
    out(rng.nextInt(out.length)) = rng.nextInt(256).toByte
    out

  private def addToByte(in: Array[Byte], rng: Random): Array[Byte] =
    val out   = in.clone()
    val pos   = rng.nextInt(out.length)
    val delta = 1 + rng.nextInt(35)
    out(pos) =
      (
        if rng.nextBoolean() then
          out(pos) + delta
        else
          out(pos) - delta
      ).toByte
    out

  /** Write a boundary value as 1, 2, 4, or 8 bytes, big- or little-endian. */
  private def writeInteresting(in: Array[Byte], rng: Random): Array[Byte] =
    val width = Array(1, 2, 4, 8)(rng.nextInt(4))
    if in.length < width then
      setRandomByte(in, rng)
    else
      val out    = in.clone()
      val v      = interestingLongs(rng.nextInt(interestingLongs.length))
      val pos    = rng.nextInt(out.length - width + 1)
      val bigEnd = rng.nextBoolean()
      var i      = 0
      while i < width do
        val shift =
          if bigEnd then
            (width - 1 - i) * 8
          else
            i * 8
        out(pos + i) = (v >>> shift).toByte
        i += 1
      out

  private def insert(in: Array[Byte], pos: Int, bytes: Array[Byte]): Array[Byte] =
    val out = new Array[Byte](in.length + bytes.length)
    System.arraycopy(in, 0, out, 0, pos)
    System.arraycopy(bytes, 0, out, pos, bytes.length)
    System.arraycopy(in, pos, out, pos + bytes.length, in.length - pos)
    out

  private def deleteRange(in: Array[Byte], rng: Random): Array[Byte] =
    val start = rng.nextInt(in.length)
    val len   = 1 + rng.nextInt(math.min(16, in.length - start))
    val out   = new Array[Byte](in.length - len)
    System.arraycopy(in, 0, out, 0, start)
    System.arraycopy(in, start + len, out, start, in.length - start - len)
    out

  private def duplicateRange(in: Array[Byte], rng: Random): Array[Byte] =
    val start = rng.nextInt(in.length)
    val len   = 1 + rng.nextInt(math.min(16, in.length - start))
    insert(in, rng.nextInt(in.length + 1), in.slice(start, start + len))

  /** A prefix of `in` followed by a suffix of `other`. */
  private def crossOver(in: Array[Byte], other: Array[Byte], rng: Random): Array[Byte] =
    in.take(rng.nextInt(in.length + 1)) ++ other.drop(rng.nextInt(other.length))

  /** Overwrite part of `in` with a slice of `src`, keeping the length of `in`. */
  private def overwriteFrom(in: Array[Byte], src: Array[Byte], rng: Random): Array[Byte] =
    val len    = 1 + rng.nextInt(math.min(in.length, src.length))
    val srcPos = rng.nextInt(src.length - len + 1)
    val dstPos = rng.nextInt(in.length - len + 1)
    val out    = in.clone()
    System.arraycopy(src, srcPos, out, dstPos, len)
    out

end ByteMutator
