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

## YAML and frontmatter

`YAML` parses YAML 1.2 into the same `JSONValue` tree, so any `derives Weaver` type decodes
from YAML too. Missing fields take their defaults.

```scala
import wvlet.uni.text.yaml.{Frontmatter, YAML}

val fromYaml: Person = YAML.fromYaml[Person]("id: 2\nname: yui\ntags: [admin]")
val docs             = YAML.parseAll("a: 1\n---\nb: 2") // Seq[JSONValue], one per document

case class SkillHeader(name: String, description: String = "") derives Weaver

val md     = "---\nname: review\n---\n# Body\n"
val header = Frontmatter.parse(md).as[SkillHeader] // SkillHeader(review, "")
val mdBody = Frontmatter.parse(md).body            // "# Body\n"
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
