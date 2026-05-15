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

// Deliberately does NOT call FileSystemInit.init(). Touching the shared FileSystem object
// must register the platform implementation on its own so cross-platform callers (e.g.
// FileLogHandler) don't have to learn about a platform-specific bootstrap step.
class FileSystemAutoInitTest extends UniTest:

  test("FileSystem.tempDirectory works without explicit FileSystemInit.init()") {
    val tmp = FileSystem.tempDirectory
    FileSystem.exists(tmp) shouldBe true
    FileSystem.isDirectory(tmp) shouldBe true
  }

end FileSystemAutoInitTest
