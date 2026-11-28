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

import scala.collection.mutable.ArrayBuffer
import wvlet.uni.test.check.Shrink

/**
  * Test-case reduction: shrink a failing input while it still fails.
  */
object Reduce:

  /**
    * Delta debugging (ddmin) over a sequence: remove chunks, halving the chunk size whenever no
    * chunk can be removed, until no single element can go. `fails` must be true for `input`.
    *
    * @param maxSteps
    *   upper bound on calls to `fails`
    */
  def ddmin[A](
      input: IndexedSeq[A],
      fails: IndexedSeq[A] => Boolean,
      maxSteps: Int
  ): IndexedSeq[A] =
    var cur                                     = input
    var steps                                   = 0
    def tryCandidate(c: IndexedSeq[A]): Boolean =
      steps += 1
      fails(c)

    if cur.nonEmpty && steps < maxSteps && tryCandidate(IndexedSeq.empty) then
      IndexedSeq.empty
    else
      var n    = 2
      var done = false
      while !done && cur.length >= 1 && steps < maxSteps do
        val chunk   = math.max(1, (cur.length + n - 1) / n)
        var start   = 0
        var reduced = false
        while !reduced && start < cur.length && steps < maxSteps do
          val candidate = cur.take(start) ++ cur.drop(start + chunk)
          if tryCandidate(candidate) then
            cur = candidate
            n = math.max(n - 1, 2)
            reduced = true
          else
            start += chunk
        if !reduced then
          if chunk == 1 then
            done = true
          else
            n = math.min(n * 2, cur.length)
      cur

  end ddmin

  /**
    * Reduce a failing byte input: [[ddmin]] to drop bytes, then lower each remaining byte to the
    * smallest value (as unsigned, found by binary search) where the input still fails, then drop
    * bytes again. Lowering shows only the bytes that matter, and for
    * [[wvlet.uni.test.FuzzCheck .fuzzGen]] it shrinks the generated value: smaller bytes mean
    * shorter lists and smaller numbers, after which the bytes past the value are no longer read and
    * can go.
    */
  def bytes(input: Array[Byte], fails: Array[Byte] => Boolean, maxSteps: Int): Array[Byte] =
    var steps                                 = 0
    def tryCandidate(c: Array[Byte]): Boolean =
      steps += 1
      fails(c)

    def drop(in: Array[Byte]): Array[Byte] =
      ddmin[Byte](in.toIndexedSeq, c => tryCandidate(c.toArray), maxSteps - steps).toArray

    val shorter = drop(input)
    var lowered = false
    var i       = 0
    while i < shorter.length && steps < maxSteps do
      var lo    = 0
      var hi    = shorter(i) & 0xff
      var first = true
      while lo < hi && steps < maxSteps do
        // Try 0 first: most bytes do not matter, and then one candidate settles them
        val mid =
          if first then
            0
          else
            (lo + hi) >>> 1
        first = false
        val candidate = shorter.clone()
        candidate(i) = mid.toByte
        if tryCandidate(candidate) then
          hi = mid
          shorter(i) = mid.toByte
          lowered = true
        else
          lo = mid + 1
      i += 1
    if lowered && steps < maxSteps then
      drop(shorter)
    else
      shorter

  end bytes

  /**
    * Reduce a failing text input: [[ddmin]] over lines, then over tokens, then over bytes. Units of
    * whole lines and tokens keep candidates lexically valid, so a parser fails on them the same way
    * more often than on byte cuts. Bytes are only dropped, never lowered, so the identifiers and
    * literals that remain stay readable. All passes work on bytes, so input that is not valid UTF-8
    * reduces byte for byte.
    */
  def text(input: Array[Byte], fails: Array[Byte] => Boolean, maxSteps: Int): Array[Byte] =
    var steps                                 = 0
    def tryCandidate(c: Array[Byte]): Boolean =
      steps += 1
      fails(c)

    val byLine  = lines(input, tryCandidate, maxSteps)
    val byToken = tokens(byLine, tryCandidate, maxSteps - steps)
    ddmin[Byte](byToken.toIndexedSeq, c => tryCandidate(c.toArray), maxSteps - steps).toArray

  /** [[ddmin]] over lines, each with its trailing `\n`. */
  def lines(input: Array[Byte], fails: Array[Byte] => Boolean, maxSteps: Int): Array[Byte] =
    ddminUnits(splitLines(input), fails, maxSteps)

  /**
    * [[ddmin]] over tokens: a run of ASCII letters, digits, and `_` or of non-ASCII bytes (so a
    * UTF-8 character stays whole), a run of whitespace, or any other single byte. The tokens join
    * back to the input.
    */
  def tokens(input: Array[Byte], fails: Array[Byte] => Boolean, maxSteps: Int): Array[Byte] =
    ddminUnits(splitTokens(input), fails, maxSteps)

  /**
    * Greedy shrinking with a [[wvlet.uni.test.check.Shrink]]: replace the value with its first
    * candidate for which `fails` is true, until no candidate fails or `maxSteps` calls to `fails`
    * are spent. `fails` must be true for `input`.
    */
  def shrink[A](input: A, fails: A => Boolean, maxSteps: Int)(using s: Shrink[A]): A =
    var cur   = input
    var steps = 0
    var done  = false
    while !done && steps < maxSteps do
      val next = s
        .shrink(cur)
        .find { c =>
          steps < maxSteps && {
            steps += 1
            fails(c)
          }
        }
      next match
        case Some(c) =>
          cur = c
        case None =>
          done = true
    cur

  private def ddminUnits(
      units: IndexedSeq[Array[Byte]],
      fails: Array[Byte] => Boolean,
      maxSteps: Int
  ): Array[Byte] = concat(ddmin[Array[Byte]](units, us => fails(concat(us)), maxSteps))

  private def concat(units: IndexedSeq[Array[Byte]]): Array[Byte] =
    val out = new Array[Byte](units.map(_.length).sum)
    var pos = 0
    units.foreach { u =>
      System.arraycopy(u, 0, out, pos, u.length)
      pos += u.length
    }
    out

  private def splitLines(input: Array[Byte]): IndexedSeq[Array[Byte]] =
    val units = ArrayBuffer.empty[Array[Byte]]
    var start = 0
    var i     = 0
    while i < input.length do
      if input(i) == '\n' then
        units += input.slice(start, i + 1)
        start = i + 1
      i += 1
    if start < input.length then
      units += input.slice(start, input.length)
    units.toIndexedSeq

  // 0: word bytes, 1: whitespace, 2: any other byte, which is a token by itself
  private def tokenClass(b: Byte): Int =
    if b < 0 ||
      (b >= 'a' && b <= 'z') ||
      (b >= 'A' && b <= 'Z') ||
      (b >= '0' && b <= '9') || b == '_'
    then
      0
    else if b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == '\f' || b == 0x0b then
      1
    else
      2

  private def splitTokens(input: Array[Byte]): IndexedSeq[Array[Byte]] =
    val units = ArrayBuffer.empty[Array[Byte]]
    var start = 0
    var i     = 1
    while i <= input.length do
      if i == input.length || tokenClass(input(i - 1)) == 2 ||
        tokenClass(input(i)) != tokenClass(input(i - 1))
      then
        units += input.slice(start, i)
        start = i
      i += 1
    units.toIndexedSeq

end Reduce
