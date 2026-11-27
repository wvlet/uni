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
import scala.collection.mutable
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

  /**
    * Report that the current fuzz input reached a state worth exploring further, such as a branch
    * of a parser. An input that reports a label no earlier input reported is kept and mutated
    * again, which guides the search toward deeper code without coverage instrumentation:
    *
    * {{{
    *   fuzz("header") { bytes =>
    *     if bytes.startsWith("PK".getBytes) then
    *       fuzzFeature("zip-magic")
    *       ...
    *   }
    * }}}
    *
    * Rejections also count as features: each expected exception class and message (with digits
    * removed) is one, so a parser that fails differently along different paths guides the search
    * without any calls. Outside a fuzz target, this does nothing.
    */
  def fuzzFeature(label: String): Unit = FuzzCheck.feature(label)

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

  /** The most inputs kept for mutation; past it, a new input replaces a random one. */
  private val maxPoolSize = 1024

  /** How many of the latest new-feature inputs are favored as mutation bases */
  private val maxRecent = 8

  // Features reported by the input running on this thread, or null outside a fuzz target
  private val currentFeatures = ThreadLocal[mutable.Set[String] | Null]()

  private[test] def feature(label: String): Unit =
    val features = currentFeatures.get()
    if features != null then
      features += label

  // Quoted text, hex, and numbers in a rejection message usually echo the input or a position,
  // which would make one parser state look like many
  private val quotedText = java.util.regex.Pattern.compile("'[^']*'|\"[^\"]*\"")
  private val numbers    = java.util.regex.Pattern.compile("0[xX][0-9a-fA-F]+|[0-9]+")

  /** A rejection as a feature: the exception class and its message without input echoes */
  private def rejectionFeature(e: Throwable): String =
    val msg        = String.valueOf(e.getMessage).take(200)
    val normalized = numbers.matcher(quotedText.matcher(msg).replaceAll("_")).replaceAll("#")
    s"${rejectPrefix}${e.getClass.getName}:${normalized}"

  private val rejectPrefix = "reject:"

  /**
    * At most this many rejection features per run, so messages that still vary with the input
    * cannot fill the pool with near-copies
    */
  private val maxRejectionFeatures = 256

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

    // Features of the last input run, every feature seen so far, and the inputs to mutate
    val features   = mutable.Set.empty[String]
    val seen       = mutable.Set.empty[String]
    var rejections = 0
    val pool       = ArrayBuffer.empty[Array[Byte]]
    // The inputs that most recently showed a new feature
    val recent = mutable.ArrayDeque.empty[Array[Byte]]

    def runOnce(input: Array[Byte]): Option[Throwable] =
      features.clear()
      val arg    = decode(input)
      val start  = System.nanoTime()
      val guided = config.featureGuidance
      val prev   = currentFeatures.get()
      val result =
        if guided then
          currentFeatures.set(features)
        try
          arg match
            case Some(a) =>
              body(a)
              if guided then
                features += "ok"
            case None =>
              if guided then
                features += "undecodable"
          None
        catch
          // Skip/pending/cancel from the body are test control, not crashes.
          case e: TestException if !e.isInstanceOf[AssertionFailure] =>
            throw e
          case e: Throwable if config.expected(e) =>
            if guided then
              features += rejectionFeature(e)
            None
          case e: Throwable =>
            Some(e)
        finally
          if guided then
            currentFeatures.set(prev)
      result.orElse {
        config
          .slowInputMillis
          .flatMap { limit =>
            val millis = (System.nanoTime() - start) / 1000000
            Option.when(millis > limit)(SlowInput(millis, limit))
          }
      }
    end runOnce

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
      val msg          =
        failureMessage(
          target,
          input,
          reduced,
          describe(reduced),
          reducedCause,
          seed,
          executed,
          saved,
          config.corpusDir
        ) + (
          if config.featureGuidance then
            s"\n  explored: ${seen.size} features, ${pool.size} inputs kept"
          else
            ""
        )
      throw AssertionFailure(msg, source)
    end fail

    // 1. Replay seeds and the saved corpus. These run on every test run, so a fixed crash
    //    stays fixed.
    val corpus = config.seeds ++ loadCorpus(config.corpusDir, target)
    pool ++= corpus.filter(_.length <= config.maxLength)
    var executed = 0L
    corpus.foreach { input =>
      executed += 1
      check(input).foreach(e => fail(input, e, executed))
      seen ++= features
    }

    // 2. Mutate. With feature guidance, an input that shows a new feature joins the pool.
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
          // Half the time, build on a recent find: the inputs that reached the newest states
          val base =
            if recent.nonEmpty && rng.nextBoolean() then
              recent(rng.nextInt(recent.size))
            else
              pool(rng.nextInt(pool.length))
          val other = pool(rng.nextInt(pool.length))
          ByteMutator.mutate(base, other, dictionary, rng, config.maxLength)
      executed += 1
      check(input).foreach(e => fail(input, e, executed))
      val fresh = features.filterNot(seen.contains)
      if fresh.nonEmpty then
        rejections += fresh.count(_.startsWith(rejectPrefix))
      // Past the cap, only features other than rejections count as new
      if fresh.exists(f => rejections <= maxRejectionFeatures || !f.startsWith(rejectPrefix)) then
        seen ++= features
        if pool.size < maxPoolSize then
          pool += input
        else
          pool(rng.nextInt(pool.size)) = input
        recent += input
        if recent.size > maxRecent then
          recent.removeHead()
      i += 1
    end while

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
