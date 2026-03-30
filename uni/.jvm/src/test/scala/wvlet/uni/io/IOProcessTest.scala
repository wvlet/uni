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

class IOProcessTest extends UniTest:
  FileSystemInit.init()

  test("run should execute a command and capture output") {
    val result = IO.run("echo", "hello world")
    result.exitCode shouldBe 0
    result.stdout.trim shouldBe "hello world"
    result.isSuccess shouldBe true
  }

  test("run should capture stderr") {
    val result = IO.run("sh", "-c", "echo error >&2")
    result.exitCode shouldBe 0
    result.stderr.trim shouldBe "error"
  }

  test("run should return non-zero exit code without throwing") {
    val result = IO.run("sh", "-c", "exit 42")
    result.exitCode shouldBe 42
    result.isSuccess shouldBe false
  }

  test("call should succeed on zero exit code") {
    val result = IO.call("echo", "success")
    result.exitCode shouldBe 0
    result.stdout.trim shouldBe "success"
  }

  test("call should throw on non-zero exit code") {
    val e = intercept[NonZeroExitCodeException] {
      IO.call("sh", "-c", "exit 1")
    }
    e.exitCode shouldBe 1
  }

  test("run with working directory override") {
    val tmpDir = FileSystem.createTempDirectory("io-process-test")
    try
      val result = IO.run(Seq("pwd"), ProcessConfig.default.withWorkingDirectory(tmpDir))
      // Resolve symlinks for macOS /tmp -> /private/tmp
      val expected = java.io.File(tmpDir.path).getCanonicalPath
      result.stdout.trim shouldBe expected
    finally
      FileSystem.deleteRecursively(tmpDir)
  }

  test("run with environment variable override") {
    val result = IO.run(
      Seq("sh", "-c", "echo ${TEST_VAR}"),
      ProcessConfig.default.withEnv("TEST_VAR", "hello_from_env")
    )
    result.stdout.trim shouldBe "hello_from_env"
  }

  test("run with redirectErrorToOutput") {
    val result = IO.run(
      Seq("sh", "-c", "echo out; echo err >&2"),
      ProcessConfig.default.withRedirectErrorToOutput(true)
    )
    result.stdout shouldContain "out"
    result.stdout shouldContain "err"
    result.stderr.trim shouldBe ""
  }

  test("spawn should return a process handle") {
    val proc = IO.spawn("sh", "-c", "echo spawned; sleep 0.1")
    try
      val output = String(proc.stdout.readAllBytes(), "UTF-8")
      output.trim shouldBe "spawned"
      val exitCode = proc.waitFor()
      exitCode shouldBe 0
      proc.isAlive shouldBe false
    finally
      proc.destroy()
  }

  test("spawn should allow writing to stdin") {
    val proc = IO.spawn("cat")
    try
      proc.stdin.write("hello from stdin".getBytes("UTF-8"))
      proc.stdin.close()
      val output = String(proc.stdout.readAllBytes(), "UTF-8")
      output.trim shouldBe "hello from stdin"
      proc.waitFor() shouldBe 0
    finally
      proc.destroy()
  }

  test("CommandResult isSuccess") {
    CommandResult(0, "", "").isSuccess shouldBe true
    CommandResult(1, "", "").isSuccess shouldBe false
    CommandResult(-1, "", "").isSuccess shouldBe false
  }

end IOProcessTest
