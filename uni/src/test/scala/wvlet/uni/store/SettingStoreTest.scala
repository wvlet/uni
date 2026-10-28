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
package wvlet.uni.store

import wvlet.uni.io.{FileSystem, IOPath}
import wvlet.uni.json.JSON.{JSONObject, JSONString, JSONValue}
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class AppSettings(theme: String = "light", fontSize: Int = 14) derives Weaver

class SettingStoreTest extends UniTest:
  private def memoryConfig(backend: StoreBackend): SettingStoreConfig = SettingStoreConfig(
    "test-app"
  ).withBackend(backend)

  test("start from the default and save updates") {
    val backend  = InMemoryStoreBackend()
    val settings = SettingStore.of("settings", AppSettings(), memoryConfig(backend))
    settings.get shouldBe AppSettings()
    settings.update(_.copy(theme = "dark"))
    backend.read("settings") shouldBe
      Some("""{"version":0,"state":{"theme":"dark","fontSize":14}}""")

    val reloaded = SettingStore.of("settings", AppSettings(), memoryConfig(backend))
    reloaded.get shouldBe AppSettings(theme = "dark")
  }

  test("notify subscribers on change") {
    val settings = SettingStore.of("settings", AppSettings(), memoryConfig(InMemoryStoreBackend()))
    val themes   = List.newBuilder[String]
    val c        = settings.map(_.theme).subscribe(themes += _)
    settings.update(_.copy(theme = "dark"))
    c.cancel
    themes.result() shouldBe List("light", "dark")
  }

  test("fill missing fields with defaults") {
    val backend = InMemoryStoreBackend()
    backend.write("settings", """{"version":0,"state":{"theme":"dark","unknown":true}}""")
    SettingStore.of("settings", AppSettings(), memoryConfig(backend)).get shouldBe
      AppSettings(theme = "dark")
  }

  test("migrate older versions and save the result") {
    val backend = InMemoryStoreBackend()
    // Version 1 called the field "colorScheme"
    backend.write("settings", """{"version":1,"state":{"colorScheme":"dark"}}""")
    val config = memoryConfig(backend)
      .withVersion(2)
      .withMigrate { (state: JSONValue, fromVersion: Int) =>
        state match
          case JSONObject(fields) if fromVersion < 2 =>
            JSONObject(
              fields.map {
                case ("colorScheme", v) =>
                  "theme" -> v
                case other =>
                  other
              }
            )
          case other =>
            other
      }
    SettingStore.of("settings", AppSettings(), config).get shouldBe AppSettings(theme = "dark")
    backend.read("settings") shouldBe
      Some("""{"version":2,"state":{"theme":"dark","fontSize":14}}""")
  }

  test("move unreadable data aside and start from the default") {
    val backend = InMemoryStoreBackend()
    backend.write("settings", "{not json")
    var settings: AppSettings = null
    val logs                  = captureLogs {
      settings = SettingStore.of("settings", AppSettings(), memoryConfig(backend)).get
    }
    settings shouldBe AppSettings()
    logs.warnings shouldContain "Setting settings cannot be read"
    backend.read("settings") shouldBe None
    backend.snapshot.keys.exists(_.startsWith("settings.corrupt-")) shouldBe true
  }

  test("clear removes the stored data") {
    val backend  = InMemoryStoreBackend()
    val settings = SettingStore.of("settings", AppSettings(), memoryConfig(backend))
    settings.update(_.copy(fontSize = 20))
    settings.clear()
    settings.get shouldBe AppSettings()
    backend.read("settings") shouldBe None
  }

  test("reload when the stored data changes elsewhere") {
    // A backend whose watch callback the test triggers directly
    class WatchableBackend extends InMemoryStoreBackend:
      var fireChange: Option[String] => Unit                            = _ => ()
      override def watch(key: String)(onChange: Option[String] => Unit) =
        fireChange = onChange
        wvlet.uni.rx.Cancelable(() => fireChange = _ => ())

    val backend  = WatchableBackend()
    val settings = SettingStore.of(
      "settings",
      AppSettings(),
      memoryConfig(backend).withWatchChanges(true)
    )
    backend.fireChange(Some("""{"version":0,"state":{"theme":"dark"}}"""))
    settings.get shouldBe AppSettings(theme = "dark")
    backend.fireChange(None)
    settings.get shouldBe AppSettings()
    settings.stop()
  }

  test("reject keys that are not safe file names") {
    intercept[IllegalArgumentException] {
      SettingStore.of("../settings", AppSettings(), memoryConfig(InMemoryStoreBackend()))
    }
  }

  test("save to files") {
    val dir      = FileSystem.createTempDirectory("setting-store-test")
    val config   = SettingStoreConfig("test-app").withLocation(SettingLocation.Directory(dir))
    val settings = SettingStore.of("settings", AppSettings(), config)
    settings.update(_.copy(theme = "dark"))
    FileSystem.readString(dir / "settings.json") shouldContain "\"theme\":\"dark\""
    // No temporary files are left behind
    FileSystem.list(dir).map(_.fileName) shouldBe Seq("settings.json")

    SettingStore.of("settings", AppSettings(), config).get shouldBe AppSettings(theme = "dark")
    FileSystem.deleteRecursively(dir)
  }

  test("resolve setting locations") {
    val home = SettingStoreCompat.homeDir
    SettingLocation.Directory(IOPath("/tmp/x")).resolve("app") shouldBe IOPath("/tmp/x")
    SettingLocation.Project(IOPath("/work/p")).resolve("app") shouldBe IOPath("/work/p/.app")
    SettingStoreCompat.env("XDG_CONFIG_HOME") match
      case Some(xdg) =>
        SettingLocation.UserConfig.resolve("app") shouldBe IOPath(xdg) / "app"
      case None =>
        SettingLocation.UserConfig.resolve("app") shouldBe home / ".config" / "app"
  }

end SettingStoreTest
