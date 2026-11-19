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

import wvlet.uni.test.check.Gen

object UnitGens:
  // Log-uniform non-negative Longs, so samples spread across every unit instead of
  // clustering near Long.MaxValue
  def logUniform(maxBits: Int): Gen[Long] = Gen
    .chooseNum(0, maxBits)
    .flatMap(bits => Gen.chooseNum(0L, (1L << bits) - 1))

  // `%.2f` rounds to the nearest 0.005 of the display unit; the relative slack absorbs the
  // floating-point noise on exact ties such as 1.125 -> "1.13"
  def withinDisplayPrecision(actual: Double, expected: Double, unitFactor: Double): Boolean =
    math.abs(actual - expected) <= 0.005 * unitFactor * (1 + 1e-9) + 1e-9
