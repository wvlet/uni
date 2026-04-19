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
package wvlet.uni.test.check

/**
  * Configuration knobs for a property run. Mirrors the commonly used subset of ScalaCheck's
  * `Test.Parameters`.
  */
final case class PropertyConfig(
    minSuccessfulTests: Int = 100,
    maxDiscarded: Int = 500,
    minSize: Int = 0,
    maxSize: Int = 100,
    maxShrinks: Int = 1000,
    seed: Option[Long] = None
):
  def withMinSuccessful(n: Int): PropertyConfig    = copy(minSuccessfulTests = n)
  def withMaxDiscarded(n: Int): PropertyConfig     = copy(maxDiscarded = n)
  def withSize(min: Int, max: Int): PropertyConfig =
    val newMin = math.max(0, min)
    val newMax = math.max(newMin, max)
    copy(minSize = newMin, maxSize = newMax)

  def withMaxShrinks(n: Int): PropertyConfig = copy(maxShrinks = n)
  def withSeed(s: Long): PropertyConfig      = copy(seed = Some(s))
  def noSeed: PropertyConfig                 = copy(seed = None)

object PropertyConfig:
  val default: PropertyConfig = PropertyConfig()

/**
  * Thrown by [[wvlet.uni.test.PropertyCheck.==>]] when a property's precondition is false. The
  * runner catches this and increments the discard counter instead of reporting a failure.
  */
final class DiscardException extends RuntimeException with scala.util.control.NoStackTrace
