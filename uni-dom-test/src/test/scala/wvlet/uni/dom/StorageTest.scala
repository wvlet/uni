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
package wvlet.uni.dom

import org.scalajs.dom
import wvlet.uni.test.UniTest
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given

class StorageTest extends UniTest:

  private def cleanupStorage(): Unit =
    dom.window.localStorage.clear()
    dom.window.sessionStorage.clear()

  test("Storage.local creates reactive variable backed by localStorage"):
    cleanupStorage()
    val counter = Storage.local("test-counter", 0)
    counter.get shouldBe 0

    counter := 5
    counter.get shouldBe 5

    // Verify localStorage was updated
    dom.window.localStorage.getItem("test-counter") shouldBe "5"

  test("Storage.local reads existing value from localStorage"):
    cleanupStorage()
    dom.window.localStorage.setItem("existing-key", "42")
    val value = Storage.local("existing-key", 0)
    value.get shouldBe 42

  test("Storage.local uses default when key doesn't exist"):
    cleanupStorage()
    val value = Storage.local("non-existent-key", "default-value")
    value.get shouldBe "default-value"

  test("Storage.session creates reactive variable backed by sessionStorage"):
    cleanupStorage()
    val token = Storage.session("session-token", "")
    token.get shouldBe ""

    token := "abc123"
    token.get shouldBe "abc123"

    // Verify sessionStorage was updated
    dom.window.sessionStorage.getItem("session-token") shouldBe "abc123"

  test("StorageCodec works for Int"):
    cleanupStorage()
    val intVal = Storage.local("int-val", 0)
    intVal := 123
    dom.window.localStorage.getItem("int-val") shouldBe "123"

  test("StorageCodec works for Long"):
    cleanupStorage()
    val longVal = Storage.local("long-val", 0L)
    longVal := 9876543210L
    dom.window.localStorage.getItem("long-val") shouldBe "9876543210"

  test("StorageCodec works for Double"):
    cleanupStorage()
    val doubleVal = Storage.local("double-val", 0.0)
    doubleVal := 3.14159
    dom.window.localStorage.getItem("double-val") shouldBe "3.14159"

  test("StorageCodec works for Boolean"):
    cleanupStorage()
    val boolVal = Storage.local("bool-val", false)
    boolVal := true
    dom.window.localStorage.getItem("bool-val") shouldBe "true"

  test("StorageCodec works for String"):
    cleanupStorage()
    val strVal = Storage.local("str-val", "")
    strVal := "hello world"
    dom.window.localStorage.getItem("str-val") shouldBe "hello world"

  test("Storage.removeLocal removes key from localStorage"):
    cleanupStorage()
    val value = Storage.local("to-remove", "initial")
    // Set a value to ensure it's written to storage
    value := "to-be-removed"
    dom.window.localStorage.getItem("to-remove") shouldBe "to-be-removed"

    Storage.removeLocal("to-remove")
    dom.window.localStorage.getItem("to-remove") shouldBe null

  test("Storage.removeSession removes key from sessionStorage"):
    cleanupStorage()
    val value = Storage.session("to-remove-session", "initial")
    value.get shouldBe "initial"

    Storage.removeSession("to-remove-session")
    dom.window.sessionStorage.getItem("to-remove-session") shouldBe null

  test("StorageVar.clear removes key and resets to default"):
    cleanupStorage()
    val counter = Storage.local("clear-test", 100)
    counter := 200
    counter.get shouldBe 200

    counter.clear()
    counter.get shouldBe 100
    dom.window.localStorage.getItem("clear-test") shouldBe null

  test("Storage.clearLocal clears all localStorage"):
    cleanupStorage()
    val val1 = Storage.local("key1", "a")
    val val2 = Storage.local("key2", "b")
    val1 := "x"
    val2 := "y"

    Storage.clearLocal()
    dom.window.localStorage.getItem("key1") shouldBe null
    dom.window.localStorage.getItem("key2") shouldBe null

  test("Reactive updates propagate correctly"):
    cleanupStorage()
    val theme    = Storage.local("theme", "light")
    var observed = ""

    val cancelable = theme.foreach { t =>
      observed = t
    }

    observed shouldBe "light"

    theme := "dark"
    observed shouldBe "dark"

    cancelable.cancel

  test("StorageVar follows changes made in another tab"):
    cleanupStorage()
    val theme = Storage.local("theme", "light")

    // Simulate another tab: change the storage directly, then dispatch the storage event it sends
    def changedInAnotherTab(key: String | Null): Unit =
      val init = new dom.StorageEventInit {}
      init.key = key
      init.storageArea = dom.window.localStorage
      dom.window.dispatchEvent(dom.StorageEvent("storage", init))

    dom.window.localStorage.setItem("theme", "dark")
    changedInAnotherTab("theme")
    theme.get shouldBe "dark"

    dom.window.localStorage.clear()
    changedInAnotherTab(null)
    theme.get shouldBe "light"

    theme.stop()
    dom.window.localStorage.setItem("theme", "dark")
    changedInAnotherTab("theme")
    theme.get shouldBe "light"

end StorageTest
