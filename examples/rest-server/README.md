# REST + RPC server: a todo service

A todo service on one Netty server (JVM): REST endpoints under `/api/todos`, the same data as a
typed RPC service, a typed client, and a test that starts the server and calls it both ways.

## What it shows

- **REST with `Router`**: `@Endpoint` methods on `TodoController`, path parameters
  (`/api/todos/:id`), a JSON body bound to a parameter (`POST /api/todos {"title": ...}`), case
  class results encoded as JSON, and an explicit `Response.notFound` for a missing id.
- **RPC with `RPCRouter`**: the `TodoApi` trait becomes `POST /example.rest.TodoApi/<method>`
  endpoints. `Option` and `Seq` results are encoded with Weaver.
- **Typed RPC client**: `TodoApiClient extends TodoApi` delegates to `RPCClient.of[TodoApi].newSyncClient(http)`, which
  is what sbt-uni's `uniHttpClients` code generator produces. Callers get compile-checked calls.
- **One server, two handlers**: `Http.server.withRxHandler` sends RPC paths to `RPCHandler` and
  everything else to `RouterHandler`.
- **Design wiring**: `TodoServer.design` binds the config, a singleton `TodoStore` shared by both
  APIs, `bindImpl[TodoApi, TodoApiImpl]`, and `onStart` / `onShutdown` hooks that start and stop
  the server with the session.
- **UniTest**: [`TodoServerTest`](src/test/scala/example/rest/TodoServerTest.scala) adds the
  same design with port 0 (a free port) and an HTTP client with `addTestDesign`, so each test gets
  its own server and takes the client as an argument. It calls the server through the RPC client
  and the REST API, and checks that a 404 surfaces as `HttpException`.

Source: [`TodoServer.scala`](src/main/scala/example/rest/TodoServer.scala).

## Run

```bash
./sbt exampleRestServer/run        # listens on http://localhost:8080 (pass a port as the argument)
./sbt exampleRestServer/test
```

Then, from another terminal:

```bash
curl -X POST localhost:8080/api/todos -H 'Content-Type: application/json' -d '{"title":"write docs"}'
curl localhost:8080/api/todos
curl localhost:8080/api/todos/1
curl -X POST localhost:8080/example.rest.TodoApi/complete -H 'Content-Type: application/json' \
  -d '{"request":{"id":1}}'
```

## Use it in your own build

```scala
libraryDependencies ++= Seq(
  "org.wvlet.uni" %% "uni"       % "<version>",
  "org.wvlet.uni" %% "uni-netty" % "<version>",
  "org.wvlet.uni" %% "uni-test"  % "<version>" % Test
)
testFrameworks += new TestFramework("wvlet.uni.test.Framework")
```

To generate the RPC client instead of writing it, add the `sbt-uni` plugin and set
`uniHttpClients := Seq("example.rest.TodoApi:rpc")` (see [RPC](https://wvlet.org/uni/http/rpc)).
`Router`, `RouterHandler`, and `RPCHandler` live in `uni-netty` and are JVM-only. For Scala.js and
Scala Native servers, see [Cross-Platform Servers](https://wvlet.org/uni/http/server#cross-platform-servers).
