# Persisted Settings

`SettingStore` keeps user settings, such as a theme, recent files, or window size, across restarts.
It is an `RxVar`: read it with `get`, change it with `set` or `update`, and subscribe to it like
any other Rx value. Every change is saved as JSON through [Weaver](./weaver).

If you have used zustand's `persist` middleware in a JavaScript app, `SettingStore` covers the
same ground: a reactive store, a storage backend, a version number, and migrations.

```scala
import wvlet.uni.config.{SettingStore, SettingStoreConfig}
import wvlet.uni.weaver.Weaver

case class AppSettings(theme: String = "light", fontSize: Int = 14) derives Weaver

val settings = SettingStore.of("settings", AppSettings(), SettingStoreConfig("my-app"))

settings.get.theme                      // "light" on the first run
settings.update(_.copy(theme = "dark")) // saved to ~/.config/my-app/settings.json

// Subscribe to one field, as with a zustand selector
val cancelable = settings.map(_.fontSize).subscribe { size => println(s"font size: ${size}") }
```

## Where settings are stored

| Platform | Default storage |
|----------|-----------------|
| JVM, Scala Native, Node.js | `~/.config/<app>/<key>.json`, or `$XDG_CONFIG_HOME/<app>/<key>.json` when that variable is set |
| Browser (Scala.js) | `localStorage`, under the item key `<app>/<key>` |

Use `withLocation` to pick another directory:

```scala
import wvlet.uni.io.IOPath
import wvlet.uni.config.SettingLocation

// ~/Library/Application Support/my-app on macOS, %APPDATA%\my-app on Windows
val osConfig = SettingStoreConfig("my-app").withLocation(SettingLocation.OSConfig)

// <project>/.my-app/, for settings that belong to one project
val project = SettingStoreConfig("my-app").withLocation(
  SettingLocation.Project(IOPath("/work/my-project"))
)

// Any explicit directory
val custom = SettingStoreConfig("my-app").withLocation(
  SettingLocation.Directory(IOPath("/etc/my-app"))
)
```

Each write goes to a temporary file first and then replaces the target file, so a crash during a
write never leaves a half-written settings file.

## Schema changes

The stored file records a schema version:

```json
{"version": 2, "state": {"theme": "dark", "fontSize": 14}}
```

- **Adding a field**: give it a default value in the case class. Older files load, and the new
  field takes its default.
- **Removing a field**: unknown fields in the stored file are ignored.
- **Renaming or reshaping a field**: raise the version and convert the old JSON in `withMigrate`.
  The migrated value is written back, so each migration runs once.

```scala
import wvlet.uni.json.JSON.{JSONObject, JSONValue}

val migrating = SettingStoreConfig("my-app")
  .withVersion(2)
  .withMigrate { (state: JSONValue, fromVersion: Int) =>
    state match
      // Version 1 called the field "colorScheme"
      case JSONObject(fields) if fromVersion < 2 =>
        JSONObject(fields.map {
          case ("colorScheme", v) =>
            "theme" -> v
          case other =>
            other
        })
      case other =>
        other
  }
```

If the stored data cannot be read, `SettingStore` logs a warning, keeps the data for inspection
(the file is renamed to `<key>.json.corrupt-<timestamp>`), and starts from the default value.

## Changes from other processes

By default, a store reads its value once, when it is created. With `withWatchChanges(true)` it
also reloads when another process changes the file, or when another browser tab changes
`localStorage`:

```scala
val shared = SettingStore.of(
  "settings",
  AppSettings(),
  SettingStoreConfig("my-app").withWatchChanges(true)
)
// Call shared.stop() to stop watching
```

## Tests

Use `InMemoryStoreBackend` so tests do not touch the real settings directory:

```scala
import wvlet.uni.config.InMemoryStoreBackend

val inMemory = SettingStore.of(
  "settings",
  AppSettings(),
  SettingStoreConfig("my-app").withBackend(InMemoryStoreBackend())
)
```

`withBackend` also accepts your own `StoreBackend`, for example one that stores settings in a
database.
