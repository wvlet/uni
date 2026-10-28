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

import wvlet.uni.io.{FileSystem, IOPath}
import wvlet.uni.json.JSON.{JSONObject, JSONString, JSONValue}
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class AppSettings(theme: String = "light", fontSize: Int = 14) derives Weaver
case class Token(value: String) derives Weaver
case class Window(width: Int = 800, title: Option[String]) derives Weaver

class SettingStoreTest extends UniTest:
  private def memoryConfig(backend: StoreBackend): SettingStoreConfig[AppSettings] = SettingStore
    .of[AppSettings]
    .withAppName("test-app")
    .withBackend(backend)

  test("start from the default and save updates") {
    val backend  = InMemoryStoreBackend()
    val settings = memoryConfig(backend).open("settings")
    settings.get shouldBe AppSettings()
    settings.update(_.copy(theme = "dark"))
    backend.read("settings") shouldBe
      Some("""{"version":0,"state":{"theme":"dark","fontSize":14}}""")

    val reloaded = memoryConfig(backend).open("settings")
    reloaded.get shouldBe AppSettings(theme = "dark")
  }

  test("skip writes when an update leaves the value unchanged") {
    var writes  = 0
    val backend =
      new InMemoryStoreBackend:
        override def write(key: String, value: String): Unit =
          writes += 1
          super.write(key, value)
    val settings = memoryConfig(backend).open("settings")
    settings.update(_.copy(theme = "dark"))
    settings.update(_.copy(theme = "dark"))
    settings := AppSettings(theme = "dark")
    writes shouldBe 1
  }

  test("notify subscribers on change") {
    val settings = memoryConfig(InMemoryStoreBackend()).open("settings")
    val themes   = List.newBuilder[String]
    val c        = settings.map(_.theme).subscribe(themes += _)
    settings.update(_.copy(theme = "dark"))
    c.cancel
    themes.result() shouldBe List("light", "dark")
  }

  test("fill missing fields with defaults") {
    val backend = InMemoryStoreBackend()
    backend.write("settings", """{"version":0,"state":{"theme":"dark","unknown":true}}""")
    memoryConfig(backend).open("settings").get shouldBe AppSettings(theme = "dark")
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
    config.open("settings").get shouldBe AppSettings(theme = "dark")
    backend.read("settings") shouldBe
      Some("""{"version":2,"state":{"theme":"dark","fontSize":14}}""")
  }

  test("move unreadable data aside and start from the default") {
    val backend = InMemoryStoreBackend()
    backend.write("settings", "{not json")
    var settings: AppSettings = null
    val logs                  = captureLogs {
      settings = memoryConfig(backend).open("settings").get
    }
    settings shouldBe AppSettings()
    logs.warnings shouldContain "Setting settings cannot be read"
    backend.read("settings") shouldBe None
    backend.snapshot.keys.exists(_.startsWith("settings.corrupt-")) shouldBe true
  }

  test("clear removes the stored data") {
    val backend  = InMemoryStoreBackend()
    val settings = memoryConfig(backend).open("settings")
    settings.update(_.copy(fontSize = 20))
    settings.clear()
    settings.get shouldBe AppSettings()
    backend.read("settings") shouldBe None
  }

  test("reload when the stored data changes elsewhere") {
    // A backend whose change events the test fires directly
    class WatchableBackend extends InMemoryStoreBackend:
      var fireChange: () => Unit                            = () => ()
      override def watch(key: String)(onChange: () => Unit) =
        fireChange = onChange
        wvlet.uni.rx.Cancelable(() => fireChange = () => ())

      def changeElsewhere(value: Option[String]): Unit =
        value match
          case Some(v) =>
            write("settings", v)
          case None =>
            remove("settings")
        fireChange()

    val backend  = WatchableBackend()
    val settings = memoryConfig(backend).withWatchChanges(true).open("settings")
    backend.changeElsewhere(Some("""{"version":0,"state":{"theme":"dark"}}"""))
    settings.get shouldBe AppSettings(theme = "dark")
    // Unreadable data, e.g., a file in the middle of a non-atomic save, keeps the current value
    backend.changeElsewhere(Some("{not json"))
    settings.get shouldBe AppSettings(theme = "dark")
    backend.changeElsewhere(None)
    settings.get shouldBe AppSettings()
    settings.stop()
  }

  test("start from withDefault when set") {
    memoryConfig(InMemoryStoreBackend())
      .withDefault(AppSettings(fontSize = 20))
      .open("settings")
      .get shouldBe AppSettings(fontSize = 20)
  }

  test("use None for Option parameters without a default") {
    SettingStore.of[Window].withBackend(InMemoryStoreBackend()).open("window").get shouldBe
      Window(800, None)
  }

  test("require withDefault for types without default parameters") {
    val e = intercept[IllegalArgumentException] {
      SettingStore.of[Token].withBackend(InMemoryStoreBackend()).open("token")
    }
    e.getMessage shouldContain "value has no default value"
    e.getMessage shouldContain "withDefault"
    SettingStore
      .of[Token]
      .withBackend(InMemoryStoreBackend())
      .withDefault(Token("t"))
      .open("token")
      .get shouldBe Token("t")
  }

  test("require an application name for the default location") {
    intercept[IllegalArgumentException] {
      SettingStore.of[AppSettings].open("settings")
    }
  }

  test("reject keys that are not safe file names") {
    intercept[IllegalArgumentException] {
      memoryConfig(InMemoryStoreBackend()).open("../settings")
    }
  }

  test("save to files") {
    val dir    = FileSystem.createTempDirectory("setting-store-test")
    val config = SettingStore
      .of[AppSettings]
      .withAppName("test-app")
      .withLocation(SettingLocation.Directory(dir))
    val settings = config.open("settings")
    settings.update(_.copy(theme = "dark"))
    FileSystem.readString(dir / "settings.json") shouldContain "\"theme\":\"dark\""
    // No temporary files are left behind
    FileSystem.list(dir).map(_.fileName) shouldBe Seq("settings.json")

    config.open("settings").get shouldBe AppSettings(theme = "dark")
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
