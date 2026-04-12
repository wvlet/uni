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

class IOWatchTest extends UniTest:
  FileSystemInit.init()

  test("WatchOptions default values") {
    val opts = WatchOptions.default
    opts.recursive shouldBe false
    opts.pollingIntervalMs shouldBe 500L
  }

  test("WatchOptions builder") {
    val opts = WatchOptions.default.withRecursive(true).withPollingIntervalMs(1000)
    opts.recursive shouldBe true
    opts.pollingIntervalMs shouldBe 1000L
  }

  test("WatchEventType values") {
    val types = WatchEventType.values
    types.length shouldBe 4
    types shouldContain WatchEventType.Created
    types shouldContain WatchEventType.Modified
    types shouldContain WatchEventType.Deleted
    types shouldContain WatchEventType.Overflow
  }

  test("WatchEvent construction") {
    val event = WatchEvent(WatchEventType.Created, IOPath.parse("/tmp/test.txt"))
    event.eventType shouldBe WatchEventType.Created
    event.path.fileName shouldBe "test.txt"
  }

end IOWatchTest
