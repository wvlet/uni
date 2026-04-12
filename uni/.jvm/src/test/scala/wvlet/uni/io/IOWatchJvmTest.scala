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

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

class IOWatchJvmTest extends UniTest:
  FileSystemInit.init()

  private def waitForEvents(
      events: CopyOnWriteArrayList[WatchEvent],
      expectedCount: Int,
      timeoutMs: Long = 5000
  ): Unit =
    val deadline = System.currentTimeMillis() + timeoutMs
    while events.size() < expectedCount && System.currentTimeMillis() < deadline do
      Thread.sleep(100)

  test("detect file creation") {
    val dir    = FileSystem.createTempDirectory("watch-create-test")
    val events = CopyOnWriteArrayList[WatchEvent]()

    val watcher =
      IOWatch.watch(dir, WatchOptions.default.withPollingIntervalMs(100)) { event =>
        events.add(event)
      }
    try
      Thread.sleep(200)

      val file = dir / "new-file.txt"
      FileSystem.writeString(file, "hello")

      waitForEvents(events, 1)

      val createdEvents = events.asScala.filter(_.eventType == WatchEventType.Created)
      (createdEvents.size >= 1) shouldBe true
    finally
      watcher.close()
      FileSystem.deleteRecursively(dir)
  }

  test("detect file modification") {
    val dir  = FileSystem.createTempDirectory("watch-modify-test")
    val file = dir / "existing.txt"
    FileSystem.writeString(file, "initial")

    val events = CopyOnWriteArrayList[WatchEvent]()

    val watcher =
      IOWatch.watch(dir, WatchOptions.default.withPollingIntervalMs(100)) { event =>
        events.add(event)
      }
    try
      Thread.sleep(200)

      FileSystem.writeString(file, "modified")

      waitForEvents(events, 1)

      val modifiedEvents = events.asScala.filter(_.eventType == WatchEventType.Modified)
      (modifiedEvents.size >= 1) shouldBe true
    finally
      watcher.close()
      FileSystem.deleteRecursively(dir)
  }

  test("detect file deletion") {
    val dir  = FileSystem.createTempDirectory("watch-delete-test")
    val file = dir / "to-delete.txt"
    FileSystem.writeString(file, "delete me")

    val events = CopyOnWriteArrayList[WatchEvent]()

    val watcher =
      IOWatch.watch(dir, WatchOptions.default.withPollingIntervalMs(100)) { event =>
        events.add(event)
      }
    try
      Thread.sleep(200)

      FileSystem.delete(file)

      waitForEvents(events, 1)

      val deletedEvents = events.asScala.filter(_.eventType == WatchEventType.Deleted)
      (deletedEvents.size >= 1) shouldBe true
    finally
      watcher.close()
      FileSystem.deleteRecursively(dir)
  }

  test("watcher closes cleanly") {
    val dir = FileSystem.createTempDirectory("watch-close-test")

    val watcher =
      IOWatch.watch(dir, WatchOptions.default.withPollingIntervalMs(100)) { _ =>
        ()
      }
    watcher.close()

    // Should not throw when closing again
    watcher.close()

    FileSystem.deleteRecursively(dir)
  }

  test("throw on non-directory path") {
    val dir  = FileSystem.createTempDirectory("watch-nondir-test")
    val file = dir / "a-file.txt"
    FileSystem.writeString(file, "not a dir")

    try
      val ex = intercept[IOOperationException] {
        IOWatch.watch(file) { _ =>
          ()
        }
      }
      ex.getMessage shouldContain "not a directory"
    finally
      FileSystem.deleteRecursively(dir)
  }

  test("IO.watch delegates to IOWatch") {
    val dir    = FileSystem.createTempDirectory("io-watch-test")
    val events = CopyOnWriteArrayList[WatchEvent]()

    val watcher =
      IO.watch(dir, WatchOptions.default.withPollingIntervalMs(100)) { event =>
        events.add(event)
      }
    try
      Thread.sleep(200)

      val file = dir / "via-io.txt"
      FileSystem.writeString(file, "test")

      waitForEvents(events, 1)
      (events.size() >= 1) shouldBe true
    finally
      watcher.close()
      FileSystem.deleteRecursively(dir)
  }

end IOWatchJvmTest
