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
package wvlet.uni.config

import org.scalajs.dom
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class BrowserSettings(theme: String = "light") derives Weaver

class LocalStorageBackendTest extends UniTest:

  test("use localStorage by default in a browser"):
    dom.window.localStorage.clear()
    val settings = SettingStore.of[BrowserSettings].withAppName("my-app").open("settings")
    settings.update(_.copy(theme = "dark"))
    dom.window.localStorage.getItem("my-app/settings") shouldBe
      """{"version":0,"state":{"theme":"dark"}}"""
    SettingStore.of[BrowserSettings].withAppName("my-app").open("settings").get shouldBe
      BrowserSettings(theme = "dark")

  test("move unreadable data aside"):
    dom.window.localStorage.clear()
    val backend = LocalStorageBackend("my-app")
    backend.write("settings", "{not json")
    backend.moveAside("settings")
    backend.read("settings") shouldBe None
    val keys = (0 until dom.window.localStorage.length).map(dom.window.localStorage.key)
    keys.exists(_.startsWith("my-app/settings.corrupt-")) shouldBe true

end LocalStorageBackendTest
