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
package wvlet.uni.io

import wvlet.uni.test.UniTest

/** `IO.run` and `IO.call` on Node.js, through `child_process.spawnSync` */
class NodeIOProcessTest extends UniTest:
  FileSystemInit.init()

  test("run captures stdout, stderr, and the exit code") {
    val result = IO.run("sh", "-c", "echo out; echo err >&2; exit 3")
    result.exitCode shouldBe 3
    result.stdout.trim shouldBe "out"
    result.stderr.trim shouldBe "err"
  }

  test("run passes extra environment variables and the working directory") {
    val dir    = FileSystem.createTempDirectory("node-io-process")
    val result = IO.run(
      Seq("sh", "-c", "echo ${UNI_TEST_VALUE}; pwd"),
      ProcessConfig.default.withEnv("UNI_TEST_VALUE", "hello").withWorkingDirectory(dir)
    )
    val lines = result.stdout.linesIterator.toSeq
    lines.head shouldBe "hello"
    // macOS temp dirs resolve through /private
    lines(1).endsWith(dir.fileName) shouldBe true
  }

  test("run can append stderr to stdout") {
    val result = IO.run(
      Seq("sh", "-c", "echo a; echo b >&2"),
      ProcessConfig.default.withRedirectErrorToOutput(true)
    )
    result.stdout.linesIterator.toSeq shouldBe Seq("a", "b")
  }

  test("call throws on a non-zero exit code") {
    val e = intercept[NonZeroExitCodeException] {
      IO.call("sh", "-c", "exit 1")
    }
    e.exitCode shouldBe 1
  }

  test("a missing executable fails with an IOException") {
    intercept[java.io.IOException] {
      IO.run("uni-no-such-command-for-test")
    }
  }

end NodeIOProcessTest
