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

import scala.concurrent.Promise

/**
  * The JVM counterpart of DesignTestTest's async check, which is deterministic only on
  * single-threaded Scala.js: here the Future completes on another thread well after the body
  * returns, so a shutdown on the body's return would close the dir before it is checked.
  */
class AsyncSessionJVMTest extends TempDirFixture:
  addDesign(_.bindInstance[EventLog](EventLog()))

  @volatile
  private var lastDir: Option[TempDir] = None

  test("an async test keeps its objects open until the Future completes") { (dir: TempDir) =>
    lastDir = Some(dir)
    val openWhenChecked = Promise[Boolean]()
    val t               = Thread { () =>
      Thread.sleep(100)
      openWhenChecked.success(dir.isOpen): Unit
    }
    t.setDaemon(true)
    t.start()
    openWhenChecked.future.map(_ shouldBe true)(using compat.executionContext)
  }

  test("its objects were closed after the Future completed") {
    lastDir.get.isOpen shouldBe false
  }

end AsyncSessionJVMTest
