# Uni core snippets

Object wiring, logging, JSON, retry, and MCP servers. Each snippet compiles against the
current `uni` release.

## Object wiring with lifecycle (Design)

Dependencies are constructor parameters. `build` starts a session, runs the
body, then calls shutdown hooks.

```scala
import wvlet.uni.design.Design
import wvlet.uni.log.LogSupport

case class DbConfig(url: String)

class Db(config: DbConfig) extends LogSupport:
  def query(sql: String): Seq[String] =
    debug(s"query ${config.url}: ${sql}")
    Seq.empty
  def close(): Unit = info("closed")

class UserRepo(db: Db):
  def names: Seq[String] = db.query("select name from users")

val design = Design.newDesign
  .bindInstance[DbConfig](DbConfig("jdbc:duckdb:"))
  .bindSingleton[Db]
  .onShutdown(_.close())
  .bindSingleton[UserRepo]

design.build[UserRepo] { repo =>
  repo.names
}
```

## JSON / MessagePack (Weaver)

```scala
import wvlet.uni.weaver.Weaver
import wvlet.uni.json.JSON

case class Person(id: Int, name: String, tags: Seq[String] = Seq.empty) derives Weaver

val json: String = Weaver.toJson(Person(1, "leo"))
val p: Person    = Weaver.fromJson[Person](json)
val tree         = JSON.parse(json) // untyped JSONValue
```

## Persisted settings (SettingStore)

An `RxVar` saved as `{"version", "state"}` JSON on every change: `~/.config/<app>/<key>.json` on
the JVM, Native, and Node.js, and `localStorage` in a browser. Missing fields take case-class
defaults; raise `withVersion` and convert old JSON in `withMigrate` when renaming fields.

```scala
import wvlet.uni.store.{InMemoryStoreBackend, SettingLocation, SettingStore, SettingStoreConfig}

case class AppSettings(theme: String = "light", fontSize: Int = 14) derives Weaver

val settings = SettingStore.of("settings", AppSettings(), SettingStoreConfig("my-app"))
settings.update(_.copy(theme = "dark"))
val fontSize = settings.map(_.fontSize) // derived Rx, like a zustand selector

// Other locations: OSConfig, Project(dir), Directory(path); in tests, use an in-memory backend
val osConfig = SettingStoreConfig("my-app").withLocation(SettingLocation.OSConfig)
val inTest   = SettingStoreConfig("my-app").withBackend(InMemoryStoreBackend())
```

## Retry

```scala
import wvlet.uni.control.Retry
import wvlet.uni.http.{Http, Request}

val body: String = Retry.withBackOff(maxRetry = 5).run {
  Http.client.newSyncClient.send(Request.get("https://example.com/")).contentAsString.getOrElse("")
}
```

## MCP server over stdio

Public methods of the trait become MCP tools. Parameters and results go through
Weaver, so case classes work.

```scala
import wvlet.uni.mcp.{MCP, description}

case class Forecast(city: String, temperature: Double)

trait WeatherService:
  @description("Return the current forecast for a city")
  def forecast(@description("City name") city: String): Forecast

class WeatherServiceImpl extends WeatherService:
  def forecast(city: String): Forecast = Forecast(city, 22.5)

@main def weatherMcp(): Unit = MCP.server
  .withName("weather")
  .withVersion("0.1.0")
  .withTools[WeatherService](WeatherServiceImpl())
  .serveStdio()
```
