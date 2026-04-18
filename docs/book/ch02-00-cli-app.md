# 2. A URL Fetcher

This chapter builds one complete program — a small command-line tool
that fetches a URL, prints the status line, and optionally dumps the
body. It is the first real Uni app we write, and the point is to meet
four pieces together, in context:

- **Launcher** — turn command-line arguments into a typed object.
- **HttpClient** — make a request and inspect the response.
- **Design** — wire the two together so they can be replaced
  independently.
- **UniTest** — write a fast, dependency-free test.

You will not learn everything about any of these in this chapter;
Parts III–V take each one apart. What you will get is a working mental
shape: *what goes where, and why*.

## What we are building

A tool called `fetch` that behaves like this:

```bash
$ fetch https://httpbin.org/get
200 OK  (https://httpbin.org/get)

$ fetch --show-body https://httpbin.org/get
200 OK  (https://httpbin.org/get)
{
  "args": {},
  "headers": { ... },
  ...
}

$ fetch
Missing URL. Usage: fetch [--show-body] <url>
```

Small, but it has the three pieces every real CLI has: it parses args,
does I/O, and reports success or failure.

## Step 1: Parse arguments with Launcher

Create `src/main/scala/Fetch.scala` and start with the argument shape:

```scala
import wvlet.uni.cli.launcher.{Launcher, argument, option}

case class FetchArgs(
    @option(prefix = "--show-body", description = "Print response body")
    showBody: Boolean = false,
    @argument(name = "url", description = "URL to fetch")
    url: String = ""
)

@main def fetch(args: String*): Unit =
  val parsed = Launcher.execute[FetchArgs](args.toArray)
  if parsed == null then return // user requested --help; Launcher already printed it
  println(s"url='${parsed.url}', showBody=${parsed.showBody}")
```

Try it:

```bash
$ sbt "runMain fetch --show-body https://example.com"
url='https://example.com', showBody=true
```

Three things to notice.

**`FetchArgs` is a plain case class.** The `@option` and `@argument`
annotations are metadata; the class itself is data. This is a
recurring Uni shape: configuration and input are ordinary Scala types,
and a separate tool (`Launcher`) populates them.

**Defaults live with the field.** `showBody = false` is how you say
"this flag is optional". `url = ""` is a sentinel we will check below.

**`Launcher.execute[FetchArgs](args)` does two things.** It parses, and
it constructs — returning a `FetchArgs` ready to use. If you passed
malformed flags, it prints help and exits; if the user passed `--help`
(or invoked the program with no arguments, which is the default help
trigger) `Launcher` prints the annotation-derived usage text and returns
`null`. The `if parsed == null then return` line is how you let that
help-message path out of your `main`.

> **Why annotate the fields instead of calling a builder?**
> The annotation form keeps the case class *the* source of truth for
> what the CLI accepts. There is no second list to keep in sync, no
> place for the "declared flag" and "consumed flag" to drift apart.

## Step 2: Fetch the URL

Add a service that does the actual HTTP call. The service takes its
`HttpSyncClient` as a constructor parameter instead of creating one
inside — so whoever builds `UrlFetcher` decides how long the client
lives:

```scala
import wvlet.uni.http.{HttpSyncClient, Request}
import wvlet.uni.log.LogSupport

class UrlFetcher(client: HttpSyncClient) extends LogSupport:
  def fetch(url: String, showBody: Boolean): Unit =
    info(s"GET ${url}")
    val response = client.send(Request.get(url))
    println(s"${response.status}  (${url})")
    if showBody then
      println(response.contentAsString.getOrElse(""))
```

A few things to notice.

`Http.client.newSyncClient` (which we will call in the next step)
produces a client with sensible defaults: connection pooling, JSON
content negotiation, and — importantly — a retry policy. If
`httpbin.org` returns a 5xx or the TCP connection flakes, the client
retries with exponential backoff before giving up. We lean on that in
Chapter 7.

`response.status` is a typed `HttpStatus` (not a number), so you can
pattern-match on it without digging through strings.
`response.contentAsString` returns `Option[String]` — some responses
genuinely have no body (a `204 No Content`, for instance), and the
type reflects that. We unwrap with `.getOrElse("")` at the print site.

Because `UrlFetcher` no longer owns its client, we can tell `Design` to
own it instead — and to close it cleanly when the session ends.

## Step 3: Wire it with Design

Now connect the pieces in `main`:

```scala
import wvlet.uni.cli.launcher.{Launcher, argument, option}
import wvlet.uni.design.Design
import wvlet.uni.http.{Http, HttpSyncClient, Request}
import wvlet.uni.log.LogSupport

case class FetchArgs(
    @option(prefix = "--show-body", description = "Print response body")
    showBody: Boolean = false,
    @argument(name = "url", description = "URL to fetch")
    url: String = ""
)

class UrlFetcher(client: HttpSyncClient) extends LogSupport:
  def fetch(url: String, showBody: Boolean): Unit =
    info(s"GET ${url}")
    val response = client.send(Request.get(url))
    println(s"${response.status}  (${url})")
    if showBody then
      println(response.contentAsString.getOrElse(""))

@main def fetch(args: String*): Unit =
  val parsed = Launcher.execute[FetchArgs](args.toArray)
  if parsed == null then return // --help was handled by Launcher
  if parsed.url.isEmpty then
    println("Missing URL. Usage: fetch [--show-body] <url>")
    sys.exit(1)

  val design = Design.newDesign
    .bindInstance[HttpSyncClient](Http.client.newSyncClient)
    .onShutdown(_.close())
    .bindSingleton[UrlFetcher]

  design.build[UrlFetcher] { fetcher =>
    fetcher.fetch(parsed.url, parsed.showBody)
  }
```

Run the real thing:

```bash
$ sbt "runMain fetch https://httpbin.org/get"
info [UrlFetcher] GET https://httpbin.org/get  - (Fetch.scala:15)
200 OK  (https://httpbin.org/get)
```

You now have the spine of every Uni application you will write:

1. **Parse** (`Launcher.execute`).
2. **Wire** (`Design.newDesign...`).
3. **Do** (`design.build[EntryPoint] { entry => ... }`).

Everything else — more services, more wiring, more complex flows —
slots into that spine.

## Why the extra ceremony?

Right now you could inline `UrlFetcher` into `main` and save six lines.
So what does the indirection buy?

**Replaceability.** In the next step we will test this without
actually hitting the network. That means swapping the *real*
`UrlFetcher` for a stand-in. If `main` constructed `UrlFetcher` directly,
the test would have to call `main` with monkey-patched globals. With
`Design`, the swap is one line.

**Lifecycle.** You saw a small example of this already:
`.bindInstance[HttpSyncClient](...).onShutdown(_.close())` attaches the
client's cleanup to the session `design.build` opens. When the block
returns — whether normally or by throwing — the session shuts down
and the client's `close()` runs. A real app might add a database
connection, a server, and a scheduler, each with its own hook. One
block controls when they all wind down.

**Locality of wiring.** The entire dependency graph of your program is
visible in one `Design.newDesign...` block. Growing from one service to
ten does not scatter construction logic across the codebase.

## Step 4: A test, without the network

Add `src/test/scala/FetchTest.scala`:

```scala
import wvlet.uni.cli.launcher.Launcher
import wvlet.uni.test.UniTest

class FetchTest extends UniTest:
  test("parses --show-body and URL") {
    val args = Launcher.execute[FetchArgs](Array("--show-body", "https://example.com"))
    args.showBody shouldBe true
    args.url shouldBe "https://example.com"
  }

  test("defaults showBody to false") {
    val args = Launcher.execute[FetchArgs](Array("https://example.com"))
    args.showBody shouldBe false
  }

  test("treats missing URL as empty string") {
    val args = Launcher.execute[FetchArgs](Array.empty)
    args.url shouldBe ""
  }
```

Run it:

```bash
$ sbt test
[info] FetchTest:
[info]  - parses --show-body and URL
[info]  - defaults showBody to false
[info]  - treats missing URL as empty string
```

No network, no mocks, no fixtures. The arg-parsing layer is a pure
function from `Array[String]` to `FetchArgs`, and Uni keeps it that
way.

For testing `UrlFetcher` itself *without* the network, we would
replace its HTTP client with a stand-in via a test-only `Design`. We
will do exactly that in Chapter 12 once we have met `Design`'s
override mechanism.

## What you have, what comes next

You have written a Scala 3 CLI tool end to end:

- Typed CLI arguments with `@option` and `@argument`.
- Real HTTP calls through `Http.client.newSyncClient`.
- Object wiring and lifecycle through `Design.build`.
- A test suite that runs in milliseconds and has no external
  dependencies.

You have also met the shape every Uni program shares: **parse, wire,
do, clean up**. The rest of the book takes each of those layers apart
in depth.

Next, [Part III](./ch03-00-design) goes back to the beginning and
explains **Design** properly — why constructor wiring beats the
alternatives, how singletons and sessions interact, and how to
override bindings for tests and environments.

[← 1.2 Hello, Uni!](./ch01-02-hello-uni) | [Next → 3. Wiring with Design](./ch03-00-design)
