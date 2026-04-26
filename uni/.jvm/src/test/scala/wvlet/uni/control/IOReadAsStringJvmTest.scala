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
package wvlet.uni.control

import wvlet.uni.test.UniTest

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class IOReadAsStringJvmTest extends UniTest:

  // Deliberately do NOT call FileSystemInit.init() in this test. Touching IO must be enough
  // for IO.readAsString(String) to work; otherwise callers migrating from airframe would have
  // to learn about an initialization step that the existing File/URL overloads never required.
  test("IO.readAsString(String) works without explicit FileSystemInit.init()") {
    val tmp = Files.createTempFile("uni-io-read-jvm", ".txt")
    try
      Files.write(tmp, "auto-init works".getBytes(StandardCharsets.UTF_8))
      IO.readAsString(tmp.toString) shouldBe "auto-init works"
    finally
      Files.deleteIfExists(tmp)
  }

end IOReadAsStringJvmTest
