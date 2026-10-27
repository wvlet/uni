# Uni snippets

Each snippet compiles against the current `uni` / `uni-test` release. For HTTP
servers (`uni-netty`) and typed RPC, follow https://wvlet.org/uni/http/server.md
and https://wvlet.org/uni/http/rpc.md.

## Dependency injection with lifecycle (Design)

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

## HTTP client (all platforms)

```scala
import wvlet.uni.http.{Http, Request}

val client = Http.client.newSyncClient
val resp   = client.send(Request.get("https://example.com/"))
if resp.isSuccessful then println(resp.contentAsString.getOrElse(""))
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
import wvlet.uni.mcp.{MCPServer, description}

case class Forecast(city: String, temperature: Double)

trait WeatherService:
  @description("Return the current forecast for a city")
  def forecast(@description("City name") city: String): Forecast

class WeatherServiceImpl extends WeatherService:
  def forecast(city: String): Forecast = Forecast(city, 22.5)

@main def weatherMcp(): Unit = MCPServer()
  .withName("weather")
  .withVersion("0.1.0")
  .withTools[WeatherService](WeatherServiceImpl())
  .serveStdio()
```

## Unit test (UniTest)

```scala
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

class PersonTest extends UniTest:
  private val p = Person(1, "leo")

  test("round-trip JSON") {
    Weaver.fromJson[Person](Weaver.toJson(p)) shouldBe p
  }

  test("assertions") {
    (p.id >= 1) shouldBe true          // not: p.id should be >= 1
    Seq(1, 2) shouldContain 2
    Some(p) shouldMatch { case Some(_: Person) => }
    intercept[IllegalArgumentException] { require(false) }
  }
```
