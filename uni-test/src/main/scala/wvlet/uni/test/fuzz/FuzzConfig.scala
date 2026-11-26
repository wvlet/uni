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

import java.nio.charset.StandardCharsets

/**
  * Settings for one fuzz target run by [[wvlet.uni.test.FuzzCheck.fuzz]].
  *
  * A run first replays every seed and every file in the corpus directory, then mutates them for
  * `iterations` inputs (or until `timeBudgetMillis` passes, whichever comes first).
  *
  * Environment variables override the budget for long local or nightly runs without code changes:
  *   - `UNI_FUZZ_ITERATIONS`: number of mutated inputs
  *   - `UNI_FUZZ_SECONDS`: time budget; with it set, `iterations` no longer limits the run
  *   - `UNI_FUZZ_SEED`: RNG seed, to replay a reported failure
  *
  * @param iterations
  *   number of mutated inputs to run after the replay
  * @param timeBudgetMillis
  *   stop mutating after this many milliseconds
  * @param maxLength
  *   upper bound on the length of a mutated input
  * @param seed
  *   RNG seed; a fresh one is chosen and reported when absent
  * @param seeds
  *   inputs to replay and mutate, kept in code so they run on every platform
  * @param dictionary
  *   tokens (keywords, magic bytes) that mutations insert or write over the input
  * @param corpusDir
  *   directory whose `<target>/` subdirectory holds saved inputs, one file each; replayed like
  *   seeds where a file system is available
  * @param crashDir
  *   directory where a failing input is written as `<target>/crash-<hash>` with a `.report` file
  * @param expected
  *   exceptions the target may throw for invalid input; any other exception is a bug
  * @param maxReduceSteps
  *   how many candidate inputs the reducer may try when shrinking a failure
  */
final case class FuzzConfig(
    iterations: Int = 1000,
    timeBudgetMillis: Option[Long] = None,
    maxLength: Int = 4096,
    seed: Option[Long] = None,
    seeds: Seq[Array[Byte]] = Seq.empty,
    dictionary: Seq[Array[Byte]] = Seq.empty,
    corpusDir: Option[String] = None,
    crashDir: String = "target/fuzz",
    expected: Throwable => Boolean = _ => false,
    maxReduceSteps: Int = 10000
):
  def withIterations(n: Int): FuzzConfig                    = copy(iterations = math.max(0, n))
  def withTimeBudgetMillis(millis: Long): FuzzConfig        = copy(timeBudgetMillis = Some(millis))
  def noTimeBudget: FuzzConfig                              = copy(timeBudgetMillis = None)
  def withMaxLength(n: Int): FuzzConfig                     = copy(maxLength = math.max(1, n))
  def withSeed(s: Long): FuzzConfig                         = copy(seed = Some(s))
  def noSeed: FuzzConfig                                    = copy(seed = None)
  def withSeeds(inputs: String*): FuzzConfig                = withSeedBytes(inputs.map(utf8)*)
  def withSeedBytes(inputs: Array[Byte]*): FuzzConfig       = copy(seeds = seeds ++ inputs)
  def noSeeds: FuzzConfig                                   = copy(seeds = Seq.empty)
  def withDictionary(tokens: String*): FuzzConfig           = withDictionaryBytes(tokens.map(utf8)*)
  def withDictionaryBytes(tokens: Array[Byte]*): FuzzConfig = copy(dictionary =
    dictionary ++ tokens.filter(_.nonEmpty)
  )

  def noDictionary: FuzzConfig                          = copy(dictionary = Seq.empty)
  def withCorpusDir(dir: String): FuzzConfig            = copy(corpusDir = Some(dir))
  def noCorpusDir: FuzzConfig                           = copy(corpusDir = None)
  def withCrashDir(dir: String): FuzzConfig             = copy(crashDir = dir)
  def withMaxReduceSteps(n: Int): FuzzConfig            = copy(maxReduceSteps = math.max(0, n))
  def withExpected(p: Throwable => Boolean): FuzzConfig = copy(expected = p)

  /**
    * Accept exceptions matched by `pf` as rejections of invalid input, in addition to those already
    * accepted:
    * {{{
    *   FuzzConfig().withExpectedCases { case _: MessageException => }
    * }}}
    */
  def withExpectedCases(pf: PartialFunction[Throwable, Any]): FuzzConfig =
    val prev = expected
    copy(expected = e => prev(e) || pf.isDefinedAt(e))

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  /** Apply the `UNI_FUZZ_*` environment overrides. */
  private[test] def withEnvOverrides(env: String => Option[String]): FuzzConfig =
    var c = this
    env("UNI_FUZZ_ITERATIONS").flatMap(_.trim.toIntOption).foreach(n => c = c.withIterations(n))
    env("UNI_FUZZ_SECONDS")
      .flatMap(_.trim.toLongOption)
      .foreach { s =>
        c = c.withTimeBudgetMillis(s * 1000).withIterations(Int.MaxValue)
      }
    env("UNI_FUZZ_SEED").flatMap(_.trim.toLongOption).foreach(s => c = c.withSeed(s))
    c

end FuzzConfig

object FuzzConfig:
  val default: FuzzConfig = FuzzConfig()
