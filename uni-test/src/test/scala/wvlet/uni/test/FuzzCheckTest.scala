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
import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.test.check.Gen
import wvlet.uni.test.fuzz.ByteMutator
import wvlet.uni.test.fuzz.FuzzConfig
import wvlet.uni.test.fuzz.Reduce

class FuzzCheckTest extends UniTest:

  private val base = FuzzConfig().withSeed(42L).withCrashDir("target/fuzz-test").noEnvOverrides

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  test("replays every seed before mutating") {
    val seen = ArrayBuffer.empty[String]
    fuzz("replay", base.withIterations(0).withSeeds("a", "bc")) { bytes =>
      seen += String(bytes, StandardCharsets.UTF_8)
    }
    seen.toSeq shouldBe Seq("a", "bc")
  }

  test("runs the configured number of mutated inputs") {
    var count = 0
    fuzz("count", base.withIterations(200).withSeeds("seed")) { _ =>
      count += 1
    }
    count shouldBe 201
  }

  test("accepts expected exceptions as rejections") {
    fuzz(
      "expected",
      base
        .withIterations(300)
        .withSeeds("{}")
        .withExpectedCases { case _: IllegalArgumentException =>
        }
    ) { bytes =>
      if bytes.length != 2 then
        throw IllegalArgumentException("bad length")
    }
  }

  test("finds a planted bug and reduces the input") {
    val ex = intercept[AssertionFailure] {
      fuzz("planted", base.withIterations(5000).withSeeds("hello world")) { bytes =>
        if bytes.contains(0xff.toByte) then
          throw IllegalStateException("found 0xff")
      }
    }
    val msg = ex.getMessage
    msg shouldContain "Fuzz target planted failed"
    msg shouldContain "IllegalStateException"
    msg shouldContain "input (1 bytes"
    msg shouldContain ": ff"
    msg shouldContain "UNI_FUZZ_SEED=42"
  }

  test("reports an assertion failure in the body") {
    val ex = intercept[AssertionFailure] {
      fuzz("assertion", base.withIterations(0).withSeeds("x")) { bytes =>
        bytes.length shouldBe 2
      }
    }
    ex.getMessage shouldContain "Fuzz target assertion failed on input 1"
  }

  test("saves the failing input under the crash directory") {
    if FileSystem.isBrowser then
      skip("no file system in browsers")
    val ex = intercept[AssertionFailure] {
      fuzz("saved", base.withIterations(0).withSeeds("boom")) { _ =>
        throw IllegalStateException("boom")
      }
    }
    // Every input fails with the same exception, so the reducer drops all bytes.
    val file =
      IOPath.parse("target/fuzz-test/saved") / s"crash-${FuzzCheck.hash(Array.emptyByteArray)}"
    ex.getMessage shouldContain s"saved to ${file.path}"
    FileSystem.exists(file) shouldBe true
    FileSystem.readString(IOPath.parse(s"${file.path}.report")) shouldContain "crash_id: "
  }

  test("replays files in the corpus directory") {
    if FileSystem.isBrowser then
      skip("no file system in browsers")
    val dir = IOPath.parse("target/fuzz-test-corpus/files")
    FileSystem.createDirectoryIfNotExists(dir)
    FileSystem.writeBytes(dir / "case-1", utf8("from-file"))
    val seen = ArrayBuffer.empty[String]
    fuzz("files", base.withIterations(0).withCorpusDir("target/fuzz-test-corpus")) { bytes =>
      seen += String(bytes, StandardCharsets.UTF_8)
    }
    seen.toSeq shouldBe Seq("from-file")
  }

  test("fuzzString decodes inputs as UTF-8") {
    val seen = ArrayBuffer.empty[String]
    fuzzString("string", base.withIterations(0).withSeeds("こんにちは")) { s =>
      seen += s
    }
    seen.toSeq shouldBe Seq("こんにちは")
  }

  test("environment variables override the budget and seed") {
    val env = Map("UNI_FUZZ_ITERATIONS" -> "7", "UNI_FUZZ_SEED" -> "9")
    val c   = FuzzConfig().applyEnv(env.get)
    c.iterations shouldBe 7
    c.seed shouldBe Some(9L)
    val timed = FuzzConfig().applyEnv(Map("UNI_FUZZ_SECONDS" -> "3").get)
    timed.timeBudgetMillis shouldBe Some(3000L)
    timed.iterations shouldBe Int.MaxValue
    FuzzConfig().noEnvOverrides.applyEnv(env.get).iterations shouldBe 1000
  }

  test("mutations are deterministic for a seed and respect maxLength") {
    def run(seed: Long): Seq[Seq[Byte]] =
      val rng = Random(seed)
      (0 until 500).map { _ =>
        ByteMutator
          .mutate(utf8("SELECT 1"), utf8("FROM t"), IndexedSeq(utf8("WHERE")), rng, 16)
          .toSeq
      }
    val a = run(1L)
    a shouldBe run(1L)
    a.forall(_.length <= 16) shouldBe true
    (a.distinct.size > 100) shouldBe true
  }

  test("ddmin keeps only the elements the failure needs") {
    val input = (0 until 100).toIndexedSeq
    Reduce.ddmin[Int](input, c => c.contains(3) && c.contains(71), 10000) shouldBe IndexedSeq(3, 71)
  }

  test("ddmin returns an empty input when it still fails") {
    Reduce.ddmin[Int](IndexedSeq(1, 2, 3), _ => true, 100) shouldBe IndexedSeq.empty
  }

  test("Reduce.bytes zeroes bytes that do not matter") {
    val reduced = Reduce.bytes(utf8("abXcd"), b => b.length >= 2 && b(1) == 'X'.toByte, 10000)
    reduced.toSeq shouldBe Seq[Byte](0, 'X'.toByte)
  }

  test("fuzzGen finds a failing value and reports it") {
    val ex = intercept[AssertionFailure] {
      fuzzGen("gen-sum", Gen.listOf(Gen.chooseNum(0, 1000)), base.withIterations(5000)) { xs =>
        if xs.sum > 1500 then
          throw IllegalStateException("sum too large")
      }
    }
    ex.getMessage shouldContain "Fuzz target gen-sum failed"
    ex.getMessage shouldContain "value: List("
    ex.getMessage shouldContain "IllegalStateException"
  }

  test("fuzzGen explores values without seeds") {
    val seen = scala.collection.mutable.Set.empty[Int]
    fuzzGen("gen-explore", Gen.chooseNum(0, 1000000), base.withIterations(500)) { x =>
      seen += x
    }
    (seen.size > 100) shouldBe true
  }

  test("fuzzGen skips inputs the generator cannot decode") {
    var count = 0
    fuzzGen("gen-filter", Gen.chooseNum(0, 10).filter(_ > 100), base.withIterations(5)) { _ =>
      count += 1
    }
    count shouldBe 0
  }

  test("generators read 0 once the bytes run out") {
    val gen = Gen.listOf(Gen.chooseNum(0, 1000))
    FuzzCheck.generate(gen, Array.emptyByteArray, 32) shouldBe Some(List.empty)
    FuzzCheck.generate(Gen.chooseNum(5, 10), Array.emptyByteArray, 32) shouldBe Some(5)
    val bytes = Array.tabulate[Byte](64)(i => (i * 37).toByte)
    FuzzCheck.generate(gen, bytes, 32) shouldBe FuzzCheck.generate(gen, bytes, 32)
  }

  test("fails on an input that is slow twice in a row") {
    val ex = intercept[AssertionFailure] {
      fuzz("slow", base.withIterations(0).withSeeds("slow!").withSlowInputMillis(5)) { bytes =>
        if bytes.length >= 4 then
          val start = System.nanoTime()
          while System.nanoTime() - start < 15000000L do
            (
          )
      }
    }
    ex.getMessage shouldContain "SlowInput"
    ex.getMessage shouldContain "input (4 bytes, reduced from 5)"
  }

end FuzzCheckTest
