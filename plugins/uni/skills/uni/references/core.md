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
from YAML too. Missing fields take their defaults. To edit a YAML file in place, use
`YAML.parseDocument`: `set`/`remove` by path (`String` keys, `Int` indexes) and `toYaml` keep
comments, quoting, and layout, rewriting only changed values. For a `---`-separated stream, use
`YAML.parseDocuments`, edit one `YAMLDocument`, and write all of them back with
`YAML.writeStream`. `Frontmatter` edits the same way with `update(json).toText`.

```scala
import wvlet.uni.text.yaml.{Frontmatter, YAML}

val fromYaml: Person = YAML.fromYaml[Person]("id: 2\nname: yui\ntags: [admin]")
val docs             = YAML.parseAll("a: 1\n---\nb: 2") // Seq[JSONValue], one per document

case class SkillHeader(name: String, description: String = "") derives Weaver

val md     = "---\nname: review\n---\n# Body\n"
val header = Frontmatter.parse(md).as[SkillHeader] // SkillHeader(review, "")
val mdBody = Frontmatter.parse(md).body            // "# Body\n"

val edited = YAML
  .parseDocument("# Server\nport: 8080  # default\nold: x\n")
  .set(Seq("port"), 9090)
  .remove(Seq("old"))
  .toYaml // "# Server\nport: 9090  # default\n"
val stream  = YAML.parseDocuments("a: 1\n---\n# b\nb: 2\n")
val newStream = YAML.writeStream(stream.updated(1, stream(1).set(Seq("b"), 3)))
// "a: 1\n---\n# b\nb: 3\n"
val newMd = Frontmatter.parse(md).update(JSON.parse("""{"name":"review-pr"}""")).toText
// "---\nname: review-pr\n---\n# Body\n"
```

## Markdown

`Markdown.toHtml` renders CommonMark with GitHub Flavored Markdown extensions (tables, task
lists, strikethrough, autolinks). Raw HTML is escaped and `javascript:` links are dropped unless
you pass `MarkdownConfig().withRawHtml(true)` for trusted input. A leading `---` YAML header is
read in the same pass into `frontmatter` and not rendered (`withFrontmatter(false)` for strict
CommonMark). `doc.toMarkdown` writes the document back: unchanged nodes print their source
byte for byte (whitespace, `\r\n`, frontmatter), and nodes changed with `copy` are rewritten in
their original style. A changed `frontmatter` is written back too, keeping the header's comments.

```scala
import wvlet.uni.text.markdown.{Block, Inline, Markdown, MarkdownConfig}

val html    = Markdown.toHtml("# Notes\n\n- [x] done\n- [ ] todo")
val trusted = Markdown.toHtml("<kbd>Ctrl</kbd>", MarkdownConfig().withRawHtml(true))
val titles  = Markdown.parse("# A\n## B").blocks.collect { case Block.Heading(_, inlines, _) =>
  Inline.plainText(inlines)
}

case class Post(title: String, tags: Seq[String] = Nil) derives Weaver
val post = Markdown.parse("---\ntitle: Hello\n---\nThe *body*\n")
val meta = post.frontmatterAs[Post] // Post(Hello, List())
val postHtml = post.toHtml()        // <p>The <em>body</em></p>
val tasks = Markdown.parse("- [ ] write docs\n")
val done = tasks.copy(blocks = tasks.blocks.map {
  case l: Block.ListBlock => l.copy(items = l.items.map(_.copy(checked = Some(true))))
  case b                  => b
})
val written = done.toMarkdown       // "- [x] write docs\n"
```

## Cron schedules

`Cron.parse` reads 5-field Unix expressions (or 6 with leading seconds), names (`JAN`, `MON`),
steps, `?`, and macros such as `@daily`. When both day-of-month and day-of-week are restricted,
either one matches (Vixie cron). `next` reads the fields in UTC or a fixed `UtcOffset`; Scala.js
has no `ZoneId`, so resolve a named zone to its current offset first. `toString` returns the
source text, and invalid input throws `TextParseException` with the column.

```scala
import wvlet.uni.text.cron.{Cron, UtcOffset}
import java.time.Instant

val cron  = Cron.parse("30 9 * * MON-FRI")
val after = Instant.parse("2026-10-30T12:00:00Z")
val next  = cron.next(after)                       // 2026-11-02T09:30:00Z
val week  = cron.nextN(after, 5)
val local = cron.next(after, UtcOffset.ofHours(9)) // 09:30 in +09:00
val text  = cron.describe                          // At 09:30, Monday through Friday
```

## Persisted settings (SettingStore)

An `RxVar` saved as `{"version", "state"}` JSON on every change: `~/.config/<app>/<fileName>` on
the JVM, Native, and Node.js, and `localStorage` in a browser. Missing fields take case-class
defaults (and the starting value is built from them; `withDefault(v)` overrides it); raise `withVersion` and convert old JSON in `withMigrate` when renaming fields.

```scala
import wvlet.uni.config.{InMemoryStoreBackend, SettingLocation, SettingStore}

case class AppSettings(theme: String = "light", fontSize: Int = 14) derives Weaver

val settings = SettingStore.of[AppSettings].withAppName("my-app").load()
settings.update(_.copy(theme = "dark"))
val fontSize = settings.map(_.fontSize) // derived Rx, like a zustand selector

// Other locations: OSConfig, Project(dir), Directory(path); in tests, use an in-memory backend
val osConfig = SettingStore
  .of[AppSettings]
  .withAppName("my-app")
  .withLocation(SettingLocation.OSConfig)
  .load()
val inTest = SettingStore
  .of[AppSettings]
  .withAppName("my-app")
  .withBackend(InMemoryStoreBackend())
  .load()
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
