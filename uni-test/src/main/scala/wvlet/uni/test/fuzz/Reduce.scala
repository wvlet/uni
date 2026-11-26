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
    * Reduce a failing byte input: [[ddmin]] to drop bytes, then set each remaining byte to 0 where
    * the input still fails, so the reported input shows only the bytes that matter.
    */
  def bytes(input: Array[Byte], fails: Array[Byte] => Boolean, maxSteps: Int): Array[Byte] =
    var steps   = 0
    val shorter =
      ddmin[Byte](
        input.toIndexedSeq,
        c =>
          steps += 1
          fails(c.toArray)
        ,
        maxSteps
      ).toArray
    var i = 0
    while i < shorter.length && steps < maxSteps do
      if shorter(i) != 0 then
        val candidate = shorter.clone()
        candidate(i) = 0
        steps += 1
        if fails(candidate) then
          shorter(i) = 0
      i += 1
    shorter

end Reduce
