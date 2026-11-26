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
package wvlet.uni.test

import java.nio.charset.StandardCharsets
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import scala.util.control.NonFatal
import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.test.check.Gen
import wvlet.uni.test.check.Params
import wvlet.uni.test.fuzz.ByteMutator
import wvlet.uni.test.fuzz.ByteRandom
import wvlet.uni.test.fuzz.FuzzConfig
import wvlet.uni.test.fuzz.Reduce
import wvlet.uni.test.fuzz.SlowInput

/**
  * Mutation-based fuzz testing, mixed into [[UniTest]].
  *
  * A fuzz target is a function over bytes that must not fail on any input: it may throw only the
  * exceptions [[wvlet.uni.test.fuzz.FuzzConfig.expected]] accepts as rejections of invalid input.
  * Assertions in the body (for example, a round-trip check) count as failures too.
  *
  * {{{
  *   class UnpackerFuzzTest extends UniTest:
  *     test("unpacker rejects bad input cleanly") {
  *       fuzz(
  *         "msgpack-unpack",
  *         FuzzConfig().withSeedBytes(Array(0x93.toByte, 1, 2, 3)).withExpectedCases {
  *           case _: MessageException =>
  *         }
  *       ) { bytes =>
  *         MessagePack.newUnpacker(bytes).unpackValue
  *       }
  *     }
  * }}}
  *
  * A run replays the seeds and the saved corpus files, then mutates them (see
  * [[wvlet.uni.test.fuzz.ByteMutator]]). On a failure the input is reduced (see
  * [[wvlet.uni.test.fuzz.Reduce]]) while it fails with the same exception class, written under
  * `crashDir/<target>/`, and reported with the seed. Copy the crash file into the corpus directory
  * to keep it as a regression case.
  */
trait FuzzCheck:

  /** Default settings for [[fuzz]] in this suite. */
  protected def fuzzConfig: FuzzConfig = FuzzConfig.default

  /** Fuzz a target that reads bytes. */
  def fuzz(target: String, config: FuzzConfig = fuzzConfig)(body: Array[Byte] => Any)(using
      source: TestSource
  ): Unit = FuzzCheck.run(target, config.applyEnv(FuzzCheck.env), Some(_), body, source)

  /** Fuzz a target that reads text. Inputs are mutated as bytes and decoded as UTF-8. */
  def fuzzString(target: String, config: FuzzConfig = fuzzConfig)(body: String => Any)(using
      source: TestSource
  ): Unit = fuzz(target, config)(bytes => body(String(bytes, StandardCharsets.UTF_8)))

  /**
    * Fuzz a target over values of a generator, such as a `Gen` built for property tests:
    *
    * {{{
    *   fuzzGen("round-trip", Gen.nonEmptyListOf(Gen.alphaNumStr)) { names =>
    *     CSV.parse(CSV.writeRecords(Seq(names))) shouldBe Seq(names)
    *   }
    * }}}
    *
    * The generator draws its random choices from the input bytes, so mutating the bytes changes the
    * value, and reducing them shrinks it: once the bytes run out every draw is 0, which generators
    * map to their first or smallest choice. A failure reports the generated value, and a saved
    * crash file replays as long as the generator stays the same. An input for which the generator
    * throws (for example, a `filter` that never matches) is skipped.
    */
  def fuzzGen[A](target: String, gen: Gen[A], config: FuzzConfig = fuzzConfig)(body: A => Any)(using
      source: TestSource
  ): Unit =
    val c = config.applyEnv(FuzzCheck.env)
    FuzzCheck.run(
      target,
      c,
      bytes => FuzzCheck.generate(gen, bytes, c.genSize),
      body,
      source,
      bytes => FuzzCheck.generate(gen, bytes, c.genSize).map(FuzzCheck.show)
    )

end FuzzCheck

object FuzzCheck:

  private def env(name: String): Option[String] =
    try
      Option(System.getenv(name))
    catch
      case NonFatal(_) =>
        None

  /** Generate a value from the bytes, or None when the generator itself fails. */
  private[test] def generate[A](gen: Gen[A], bytes: Array[Byte], size: Int): Option[A] =
    try
      Some(gen(Params(Random(ByteRandom(bytes)), size)))
    catch
      case NonFatal(_) =>
        None

  /**
    * The value as text, with control and surrogate characters escaped so that values differing only
    * in invisible characters look different
    */
  private[test] def show(value: Any): String =
    val s     = String.valueOf(value)
    val shown = s
      .take(500)
      .flatMap { c =>
        if c < ' ' || c == '\u007f' || Character.isSurrogate(c) then
          f"\\u${c.toInt}%04x"
        else
          c.toString
      }
    if s.length > 500 then
      s"${shown} …(+${s.length - 500} chars)"
    else
      shown

  /** At most this many candidates are tried when reducing a slow input, since each one is slow. */
  private val maxSlowReduceSteps = 30

  /**
    * @param decode
    *   turns an input into the target's argument, or None to skip it; its time does not count
    *   against the slow-input limit
    */
  private[test] def run[A](
      target: String,
      config: FuzzConfig,
      decode: Array[Byte] => Option[A],
      body: A => Any,
      source: TestSource,
      describe: Array[Byte] => Option[String] = _ => None
  ): Unit =
    val seed = config.seed.getOrElse(System.nanoTime())
    val rng  = Random(seed)

    def runOnce(input: Array[Byte]): Option[Throwable] =
      val arg    = decode(input)
      val start  = System.nanoTime()
      val result =
        try
          arg.foreach(body)
          None
        catch
          // Skip/pending/cancel from the body are test control, not crashes.
          case e: TestException if !e.isInstanceOf[AssertionFailure] =>
            throw e
          case e: Throwable if config.expected(e) =>
            None
          case e: Throwable =>
            Some(e)
      result.orElse {
        config
          .slowInputMillis
          .flatMap { limit =>
            val millis = (System.nanoTime() - start) / 1000000
            Option.when(millis > limit)(SlowInput(millis, limit))
          }
      }

    def check(input: Array[Byte]): Option[Throwable] =
      runOnce(input) match
        case Some(_: SlowInput) =>
          // Run a slow input again: JIT compilation and GC pauses can slow down a single run
          runOnce(input)
        case other =>
          other

    def fail(input: Array[Byte], cause: Throwable, executed: Long): Nothing =
      val crashClass = cause.getClass
      val reduced    = Reduce.bytes(
        input,
        candidate => check(candidate).exists(_.getClass == crashClass),
        cause match
          case _: SlowInput =>
            math.min(config.maxReduceSteps, maxSlowReduceSteps)
          case _ =>
            config.maxReduceSteps
      )
      val reducedCause = check(reduced).getOrElse(cause)
      val saved        = saveCrash(config, target, reduced, reducedCause, seed)
      val msg          = failureMessage(
        target,
        input,
        reduced,
        describe(reduced),
        reducedCause,
        seed,
        executed,
        saved,
        config.corpusDir
      )
      throw AssertionFailure(msg, source)

    // 1. Replay seeds and the saved corpus. These run on every test run, so a fixed crash
    //    stays fixed.
    val corpus   = config.seeds ++ loadCorpus(config.corpusDir, target)
    var executed = 0L
    corpus.foreach { input =>
      executed += 1
      check(input).foreach(e => fail(input, e, executed))
    }

    // 2. Mutate.
    val pool       = ArrayBuffer.from(corpus.filter(_.length <= config.maxLength))
    val dictionary = config.dictionary.toIndexedSeq
    if pool.isEmpty then
      pool += Array.emptyByteArray
    val deadline = config.timeBudgetMillis.map(System.currentTimeMillis() + _)
    var i        = 0
    while i < config.iterations && deadline.forall(System.currentTimeMillis() < _) do
      val input =
        if rng.nextInt(16) == 0 then
          // Fresh random bytes now and then, so a target without seeds still gets varied inputs
          val b = new Array[Byte](rng.nextInt(math.min(config.maxLength, 256) + 1))
          rng.nextBytes(b)
          b
        else
          val base  = pool(rng.nextInt(pool.length))
          val other = pool(rng.nextInt(pool.length))
          ByteMutator.mutate(base, other, dictionary, rng, config.maxLength)
      executed += 1
      check(input).foreach(e => fail(input, e, executed))
      i += 1

  end run

  private def loadCorpus(dir: Option[String], target: String): Seq[Array[Byte]] =
    dir match
      case None =>
        Seq.empty
      case Some(d) =>
        try
          val path = IOPath.parse(d) / target
          if FileSystem.isBrowser || !FileSystem.isDirectory(path) then
            Seq.empty
          else
            FileSystem
              .list(path)
              .filter(p => FileSystem.isFile(p) && !p.fileName.endsWith(".report"))
              .sortBy(_.fileName)
              .map(p => FileSystem.readBytes(p))
        catch
          case NonFatal(_) =>
            Seq.empty

  /** Write the input and a report next to it; returns the input's path, if written. */
  private def saveCrash(
      config: FuzzConfig,
      target: String,
      input: Array[Byte],
      cause: Throwable,
      seed: Long
  ): Option[String] =
    try
      if FileSystem.isBrowser then
        None
      else
        val dir = IOPath.parse(config.crashDir) / target
        FileSystem.createDirectoryIfNotExists(dir)
        val file = dir / s"crash-${hash(input)}"
        FileSystem.writeBytes(file, input)
        FileSystem.writeString(
          IOPath.parse(s"${file.path}.report"),
          s"""target: ${target}
             |seed: ${seed}
             |input_size: ${input.length}
             |crash_id: ${crashId(cause)}
             |reason: ${cause}
             |stacktrace:
             |${cause.getStackTrace.take(30).map(f => s"  at ${f}").mkString("\n")}
             |""".stripMargin
        )
        Some(file.path)
    catch
      case NonFatal(_) =>
        None

  private val libraryPrefixes = Seq("wvlet.uni.test.", "scala.", "java.", "jdk.", "sun.")

  /** Exception class plus the first frame outside the test framework and Scala library. */
  private[test] def crashId(e: Throwable): String =
    val frame = e
      .getStackTrace
      .find { f =>
        val c = f.getClassName
        !FuzzCheck.libraryPrefixes.exists(c.startsWith)
      }
    s"${e.getClass.getName}${frame
        .map(f => s"@${f.getClassName}.${f.getMethodName}:${f.getLineNumber}")
        .getOrElse("")}"

  /** FNV-1a 64-bit hash as hex: a stable file name on every platform. */
  private[test] def hash(bytes: Array[Byte]): String =
    var h = 0xcbf29ce484222325L
    var i = 0
    while i < bytes.length do
      h = (h ^ (bytes(i) & 0xff)) * 0x100000001b3L
      i += 1
    f"${h}%016x"

  private[test] def hex(bytes: Array[Byte], limit: Int = 64): String =
    val shown = bytes.take(limit).map(b => f"${b & 0xff}%02x").mkString(" ")
    if bytes.length > limit then
      s"${shown} …(+${bytes.length - limit} bytes)"
    else
      shown

  private def failureMessage(
      target: String,
      original: Array[Byte],
      reduced: Array[Byte],
      value: Option[String],
      cause: Throwable,
      seed: Long,
      executed: Long,
      saved: Option[String],
      corpusDir: Option[String]
  ): String =
    val text = String(reduced.take(64), StandardCharsets.ISO_8859_1).map { c =>
      if c >= 0x20 && c < 0x7f then
        c
      else
        '.'
    }
    val lines =
      Seq(
        s"Fuzz target ${target} failed on input ${executed} with ${cause}",
        s"  input (${reduced.length} bytes, reduced from ${original.length}): ${hex(reduced)}"
      ) ++ value.map(v => s"  value: ${v}") ++
        Seq(
          s"  as text: \"${text}\"",
          s"  seed=${seed} (replay with UNI_FUZZ_SEED=${seed})",
          s"  crash_id: ${crashId(cause)}"
        ) ++ saved.map(p => s"  saved to ${p}") ++ (
          for
            p <- saved
            d <- corpusDir
          yield s"  keep it as a regression case: mkdir -p ${d}/${target} && cp ${p} ${d}/${target}/"
        )
    lines.mkString("\n")

  end failureMessage

end FuzzCheck
