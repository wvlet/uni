# Build Your Own MCP Server (stdio)

`wvlet.uni.mcp` turns a plain Scala service trait into an [MCP (Model Context
Protocol)](https://modelcontextprotocol.io/) server: every public method becomes
a **tool** that AI agents (Claude Code, Claude Desktop, and other MCP clients)
can discover and call. The server speaks JSON-RPC 2.0 over **stdio** and runs on
all three platforms:

- **JVM** — run with `sbt run` or package a jar
- **Scala Native** — ship a single self-contained binary (no JVM, instant startup)
- **Scala.js** — run on Node.js

No extra dependency is needed beyond `uni` itself.

## 1. Create a project

```scala
// build.sbt
scalaVersion := "3.8.4"

libraryDependencies += "org.wvlet.uni" %% "uni" % "__UNI_VERSION__"
```

## 2. Define a service trait

Any trait works. Method parameters and return values are (de)serialized with
uni's [Weaver](/core/weaver), so primitives, `Option`, `Seq`, `Map`, and case
classes are all supported. Add `@description` annotations — Surface does not
capture Scaladoc, so this is how tool and parameter descriptions reach the
client:

```scala
import wvlet.uni.mcp.description

case class Forecast(city: String, temperature: Double, condition: String)

trait WeatherService:
  @description("Return the current weather forecast for a city")
  def forecast(
      @description("City name, e.g. Tokyo") city: String,
      @description("Temperature unit") unit: String = "celsius"
  ): Forecast

class WeatherServiceImpl extends WeatherService:
  def forecast(city: String, unit: String): Forecast = Forecast(city, 22.5, "sunny")
```

## 3. Start the server

```scala
import wvlet.uni.mcp.MCPServer

@main def start(): Unit = MCPServer()
  .withName("weather")
  .withVersion("0.1.0")
  .withTools[WeatherService](WeatherServiceImpl())
  .serveStdio()
```

That's the whole server. `serveStdio()` reads newline-delimited JSON-RPC
messages from stdin and writes responses to stdout. On JVM and Scala Native it
blocks until the client closes stdin; on Scala.js (Node.js) it returns
immediately and the server runs on the event loop.

::: tip Logs go to stderr
MCP reserves stdout for protocol messages. `serveStdio()` routes uni's logging
to stderr (on Scala.js it replaces the default `console.log` handler), so
`info(...)` / `debug(...)` calls in your tools are safe.
:::

## How methods become tools

- Every **public method** of the registered trait is exposed as a tool named
  after the method. Tool names must match `[a-zA-Z0-9_-]{1,128}` and be unique
  across all registered services — violations fail at `withTools` time.
- The tool's **input schema** is derived from the method signature:

  | Scala type | JSON Schema |
  |------------|-------------|
  | `Int`, `Long`, `Short`, `Byte`, `BigInt` | `integer` |
  | `Float`, `Double` | `number` |
  | `Boolean` | `boolean` |
  | `String`, `Char`, enums, `Instant`, `UUID`, `ULID` | `string` |
  | `Seq[A]`, `Set[A]`, `Array[A]` | `array` with `items` |
  | `Map[String, A]` | `object` with `additionalProperties` |
  | case classes | nested `object` schemas |

- Parameters with a **default value** or an **`Option` type** are optional;
  everything else is listed in `required`.
- A method returning `Rx[A]` is awaited and serialized like `A` — use it for
  async tools.
- Results are returned to the client as text content: `String` results as plain
  text, everything else as JSON. Exceptions thrown by a tool become
  `isError: true` results; malformed arguments are reported as JSON-RPC `-32602`
  errors.

Multiple services can be registered on one server with repeated
`withTools[...]` calls.

## 4. Package it

**JVM** — point the client at sbt (simplest during development):

```json
{ "command": "sbt", "args": ["--error", "run"] }
```

**Scala Native** — build a single binary. With `sbt-scala-native` enabled
(`enablePlugins(ScalaNativePlugin)`):

```
sbt> nativeLink
```

The linked binary (path is printed by sbt) is a self-contained executable —
copy it anywhere and use it as the `command` directly. This is the best
distribution story: no JVM, no Node, millisecond startup.

**Scala.js (Node.js)** — with `sbt-scalajs` enabled and
`scalaJSUseMainModuleInitializer := true`:

```
sbt> fullLinkJS
```

Run the emitted script (path printed by sbt) with `node`.

## 5. Register with an MCP client

Any MCP client can launch the server; each has its own registration flow.

**Claude Code** — register the command with the `claude mcp add` CLI:

```
claude mcp add weather -- /path/to/weather-server
```

To share the server with everyone working on a repository, use the project
scope (`claude mcp add --scope project ...`), which records it in a `.mcp.json`
file at the project root:

```json
{
  "mcpServers": {
    "weather": {
      "type": "stdio",
      "command": "/path/to/weather-server"
    }
  }
}
```

**Claude Desktop** — add the same `mcpServers` entry to
`claude_desktop_config.json` (Settings → Developer → Edit Config).

Other clients (VS Code, Cursor, ...) have equivalent settings; consult their
documentation for the file location and exact schema.

## 6. Try it out

The MCP Inspector gives you an interactive UI to list and call your tools:

```
npx @modelcontextprotocol/inspector /path/to/weather-server
```

Or drive a session by hand — the protocol is just JSON lines:

```
$ ./weather-server
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"weather","version":"0.1.0"}}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"forecast","arguments":{"city":"Tokyo"}}}
{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"{\"city\":\"Tokyo\",\"temperature\":22.5,\"condition\":\"sunny\"}"}],"isError":false}}
```

## HTTP transport

The same server can speak MCP's **Streamable HTTP** transport: one endpoint
that answers each POSTed JSON-RPC message with `application/json` (202 for
notifications). `httpHandler` exposes it as a standard uni
[`RxHttpHandler`](/http/server), so it mounts on the HTTP server of every
platform:

```scala
val mcp = MCPServer()
  .withName("weather")
  .withTools[WeatherService](WeatherServiceImpl())

// JVM (uni-netty)
NettyServer.withPort(8080).withRxHandler(mcp.httpHandler).start()
// Scala.js (Node.js)
NodeServer.withPort(8080).withRxHandler(mcp.httpHandler).start()
// Scala Native
NativeServer.withPort(8080).withRxHandler(mcp.httpHandler).start()
```

Register an HTTP server by its URL instead of a command — in Claude Code:

```
claude mcp add --transport http weather http://localhost:8080/mcp
```

or in a project-scope `.mcp.json`:

```json
{
  "mcpServers": {
    "weather": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

::: warning Origin checking
As required by the MCP spec (DNS-rebinding protection), when a request carries
an `Origin` header only localhost origins are accepted by default. Serving
browser-based clients from another origin requires registering it explicitly:

```scala
MCPServer().withAllowedOrigins("https://app.example.com")
```
:::

The server is stateless: no `Mcp-Session-Id` is issued, and since a tools-only
server never initiates messages, the optional SSE streaming and GET event
stream are not offered (GET returns 405).

## Scope and roadmap

The current implementation covers the MCP **tools** capability over the
**stdio** and **Streamable HTTP** transports (protocol version `2025-06-18`;
`2025-03-26` clients are also accepted). Resources, prompts, and SSE streaming
for server-initiated notifications are planned as follow-ups. Under the hood,
dispatch reuses uni's transport-neutral [RPC](/http/rpc) layer, so behavior
(parameter matching, default values, error statuses) is identical to uni RPC.
