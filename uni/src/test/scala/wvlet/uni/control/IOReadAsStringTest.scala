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

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.test.UniTest

class IOReadAsStringTest extends UniTest:
  // The test creates temp files via FileSystem before invoking IO, so initialize explicitly.
  // (IO.readAsString itself does not require this — IO triggers FileSystemInit during class load.)
  FileSystemInit.init()

  test("IO.readAsString accepts a path string") {
    val tmp     = FileSystem.createTempFile(prefix = "uni-io-read", suffix = ".txt")
    val content = "Hello, uni!\nSecond line."
    try
      FileSystem.writeString(tmp, content)
      IO.readAsString(tmp.toString) shouldBe content
    finally
      FileSystem.deleteIfExists(tmp)
  }

  test("IO.readAsString agrees with FileSystem.readString") {
    val tmp     = FileSystem.createTempFile(prefix = "uni-io-read", suffix = ".txt")
    val content = "shared bytes"
    try
      FileSystem.writeString(tmp, content)
      IO.readAsString(tmp.toString) shouldBe FileSystem.readString(tmp)
    finally
      FileSystem.deleteIfExists(tmp)
  }

end IOReadAsStringTest
