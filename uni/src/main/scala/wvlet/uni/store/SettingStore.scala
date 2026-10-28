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

import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.{JSONLong, JSONObject, JSONValue}
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.{Cancelable, RxVar}
import wvlet.uni.weaver.Weaver

import scala.util.control.NonFatal

/**
  * Where a file-backed [[SettingStore]] keeps its `<key>.json` files.
  */
enum SettingLocation:
  /**
    * `$XDG_CONFIG_HOME/<app>/` when the variable is set, otherwise `~/.config/<app>/` on every OS
    */
  case UserConfig

  /**
    * The platform-native settings directory: `~/Library/Application Support/<app>` on macOS,
    * `%APPDATA%\<app>` on Windows, and the same path as [[UserConfig]] elsewhere
    */
  case OSConfig

  /**
    * `<dir>/.<app>/`, for settings that belong to one project
    */
  case Project(dir: IOPath)

  /**
    * An explicit directory
    */
  case Directory(path: IOPath)

  /**
    * Resolve this location to a directory for the given application name
    */
  def resolve(appName: String): IOPath =
    this match
      case UserConfig =>
        val base = SettingStoreCompat
          .env("XDG_CONFIG_HOME")
          .map(IOPath.parse)
          .getOrElse(SettingStoreCompat.homeDir / ".config")
        base / appName
      case OSConfig =>
        SettingStoreCompat.osName match
          case "mac" =>
            SettingStoreCompat.homeDir / "Library" / "Application Support" / appName
          case "windows" =>
            val base = SettingStoreCompat
              .env("APPDATA")
              .map(IOPath.parse)
              .getOrElse(SettingStoreCompat.homeDir / "AppData" / "Roaming")
            base / appName
          case _ =>
            UserConfig.resolve(appName)
      case Project(dir) =>
        dir / s".${appName}"
      case Directory(path) =>
        path

end SettingLocation

/**
  * Configuration of a [[SettingStore]].
  *
  * @param appName
  *   the application name, used as the directory name under the setting location
  * @param location
  *   where the file backend stores settings (default: `~/.config/<app>/`)
  * @param version
  *   the schema version of the stored state. Stored data with a lower version goes through
  *   `migrate` before it is decoded.
  * @param migrate
  *   converts stored state JSON of an older version (the second argument) into the current schema
  * @param backend
  *   a storage backend to use instead of the platform default
  * @param watchChanges
  *   reload the value when another process or browser tab changes the stored data
  */
case class SettingStoreConfig(
    appName: String,
    location: SettingLocation = SettingLocation.UserConfig,
    version: Int = 0,
    migrate: (JSONValue, Int) => JSONValue = (state, _) => state,
    backend: Option[StoreBackend] = None,
    watchChanges: Boolean = false
):
  def withAppName(name: String): SettingStoreConfig                     = copy(appName = name)
  def withLocation(location: SettingLocation): SettingStoreConfig       = copy(location = location)
  def withVersion(version: Int): SettingStoreConfig                     = copy(version = version)
  def withMigrate(f: (JSONValue, Int) => JSONValue): SettingStoreConfig = copy(migrate = f)
  def withBackend(backend: StoreBackend): SettingStoreConfig = copy(backend = Some(backend))
  def noBackend(): SettingStoreConfig                        = copy(backend = None)
  def withWatchChanges(enabled: Boolean): SettingStoreConfig = copy(watchChanges = enabled)

  /**
    * The directory the file backend uses for this configuration
    */
  def directory: IOPath = location.resolve(appName)

  /**
    * The backend to use: the explicit one if set, otherwise the platform default (files on the JVM,
    * Scala Native, and Node.js; localStorage in a browser)
    */
  def resolveBackend: StoreBackend = backend.getOrElse(SettingStoreCompat.defaultBackend(this))

end SettingStoreConfig

/**
  * A reactive variable whose value is saved as JSON and restored on the next start, in the spirit
  * of zustand's `persist` middleware.
  *
  * Every `set` or `update` writes the value through to the backend. Subscribers and derived values
  * (`map`, `subscribe`) work as with any [[RxVar]].
  *
  * {{{
  *   case class AppSettings(theme: String = "light", fontSize: Int = 14) derives Weaver
  *
  *   val settings = SettingStore.of("settings", AppSettings(), SettingStoreConfig("my-app"))
  *   settings.update(_.copy(theme = "dark")) // saved to ~/.config/my-app/settings.json
  * }}}
  *
  * The stored data is `{"version": <n>, "state": <value>}`. Fields missing from the stored state
  * take their case-class defaults, and unknown fields are ignored. Stored data that cannot be read
  * is moved aside (for files, renamed to `<key>.json.corrupt-<timestamp>`), and the store starts
  * from the default value.
  */
class SettingStore[A] private (
    val key: String,
    default: A,
    initial: A,
    config: SettingStoreConfig,
    backend: StoreBackend,
    weaver: Weaver[A]
) extends RxVar[A](initial)
    with LogSupport:

  private val watcher: Cancelable =
    if config.watchChanges then
      backend.watch(key) { raw =>
        val newValue = raw.flatMap(r => SettingStore.decode(r, config, weaver)).getOrElse(default)
        super.update(_ => newValue)
      }
    else
      Cancelable.empty

  override def update(updater: A => A, force: Boolean = false): Unit =
    super.update(updater, force)
    save()

  private def save(): Unit =
    try
      backend.write(key, SettingStore.encode(get, config, weaver))
    catch
      case NonFatal(e) =>
        warn(s"Failed to save setting ${key}: ${e.getMessage}", e)

  /**
    * Remove the stored data and reset the value to the default
    */
  def clear(): Unit =
    backend.remove(key)
    super.update(_ => default, force = true)

  override def stop(): Unit =
    watcher.cancel
    super.stop()

end SettingStore

object SettingStore extends LogSupport:
  private val validKey = "[A-Za-z0-9_\\-][A-Za-z0-9_.\\-]*".r

  /**
    * Create a store for `key`, loading its saved value or starting from `default`
    */
  def of[A](key: String, default: A, config: SettingStoreConfig)(using
      weaver: Weaver[A]
  ): SettingStore[A] =
    require(validKey.matches(key), s"Invalid setting key: ${key}")
    val backend = config.resolveBackend
    val initial = load(key, default, config, backend, weaver)
    SettingStore(key, default, initial, config, backend, weaver)

  private def load[A](
      key: String,
      default: A,
      config: SettingStoreConfig,
      backend: StoreBackend,
      weaver: Weaver[A]
  ): A =
    backend.read(key) match
      case None =>
        default
      case Some(raw) =>
        decode(raw, config, weaver) match
          case Some(v) =>
            if storedVersion(raw).exists(_ < config.version) then
              // Save the migrated value so the migration runs only once
              try
                backend.write(key, encode(v, config, weaver))
              catch
                case NonFatal(e) =>
                  warn(s"Failed to save migrated setting ${key}: ${e.getMessage}", e)
            v
          case None =>
            warn(s"Setting ${key} cannot be read. Moving it aside and starting from the default")
            try
              backend.moveAside(key)
            catch
              case NonFatal(e) =>
                warn(s"Failed to move aside setting ${key}: ${e.getMessage}", e)
            default

  private def encode[A](v: A, config: SettingStoreConfig, weaver: Weaver[A]): String =
    s"""{"version":${config.version},"state":${weaver.toJson(v)}}"""

  private def storedVersion(raw: String): Option[Int] =
    try
      JSON.parse(raw) match
        case obj: JSONObject =>
          obj
            .get("version")
            .collect { case JSONLong(n) =>
              n.toInt
            }
        case _ =>
          None
    catch
      case NonFatal(e) =>
        None

  private def decode[A](raw: String, config: SettingStoreConfig, weaver: Weaver[A]): Option[A] =
    try
      JSON.parse(raw) match
        case obj: JSONObject =>
          (obj.get("version"), obj.get("state")) match
            case (Some(JSONLong(version)), Some(state)) =>
              val current =
                if version < config.version then
                  config.migrate(state, version.toInt)
                else
                  state
              Some(weaver.fromJSONValue(current))
            case _ =>
              None
        case _ =>
          None
    catch
      case NonFatal(e) =>
        debug(s"Failed to decode a setting: ${e.getMessage}")
        None

end SettingStore
