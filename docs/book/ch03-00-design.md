# 3. Wiring with Design

Your app has a `UserService`, an `OrderService`, and a `ReportService`.
All three need the same database connection. How do you get the
connection to them — without making it a global, and without threading
it by hand through every constructor at every call site?

This is the question `Design` answers. You met it in Chapter 2 as the
*wire* step; this chapter explains it properly. By the end you should be
able to read an unfamiliar `Design.newDesign…` block and know exactly
what it says.

## A first wiring

Two services, one shared dependency:

```scala
import wvlet.uni.design.Design

class Database:
  def query(sql: String): Seq[String] = Seq("alice", "bob")

class UserService(db: Database):
  def listUsers(): Seq[String] = db.query("select name from users")

@main def main =
  val design = Design.newDesign
    .bindSingleton[Database]
    .bindSingleton[UserService]

  design.build[UserService] { users =>
    println(users.listUsers())
  }
```

```bash
$ sbt run
List(alice, bob)
```

`UserService` takes its `Database` as a constructor parameter. It never
constructs one, never looks one up — it just declares that it needs one.
The wiring lives somewhere else entirely.

## Walking the code

**Each class names its dependencies in its constructor.**
`UserService(db: Database)` is a complete, honest statement of what the
class needs to do its job. There is no hidden `Database.getInstance()`
call buried three methods deep. If you can construct a `UserService`, you
have everything it needs; if you can't, the compiler tells you which
type is missing.

**A `Design` is a plan, not a container.** `Design.newDesign` starts an
empty plan. `bindSingleton[Database]` adds a line to it: "when something
needs a `Database`, build one and share it." `bindSingleton[UserService]`
adds another. A `Design` is an immutable value — every `bind…` call
returns a new `Design`, so you can build them up, pass them around, and
combine them without anyone mutating yours.

**`build` turns the plan into objects.** `design.build[UserService] { … }`
reads the plan, constructs the graph from the bottom up (the `Database`
first, then the `UserService` that needs it), and hands you the root.
When the block returns, the graph is torn down.

### Why not just pass it as a constructor argument yourself?

You could. `UserService(Database())` works for two classes. The reason
to reach for `Design` shows up at scale and under change:

- **No globals.** A `Database` reachable through a global is reachable
  from anywhere, which means *anything* can depend on it without saying
  so. Dependencies you can't see are dependencies you can't test or
  replace. Constructor parameters keep the dependency graph honest.
- **No service locator.** A `locator.get[Database]` hides the dependency
  from the type system — you discover a missing binding when the call
  runs, not when it compiles. `Design` resolves the graph from
  constructor *types*, so a missing piece is a wiring error you can find
  before shipping.
- **One place to look.** The entire dependency graph of your program is
  one `Design.newDesign…` block. Growing from two services to twenty
  does not scatter `new` calls across the codebase.

## The four ways to bind

`bindSingleton` is the common case, but you reach for different bindings
depending on how the value is produced.

```scala
import wvlet.uni.design.Design

// 1. Build it for me, once, and share it.
Design.newDesign.bindSingleton[Database]

// 2. Use this exact object I already have.
Design.newDesign.bindInstance[Database](Database())

// 3. This type is a trait — use this implementation for it.
trait UserRepo
class InMemoryUserRepo extends UserRepo
Design.newDesign.bindImpl[UserRepo, InMemoryUserRepo]

// 4. Build it with a function that itself needs dependencies.
case class Config(url: String)
Design.newDesign
  .bindInstance[Config](Config("jdbc:db"))
  .bindProvider[Config, Database] { config => Database() }
```

| Binding | Use it when |
|---------|-------------|
| `bindSingleton[A]` | `A` is a class Design can construct; you want one shared instance |
| `bindInstance[A](v)` | you already hold the value (config, a pre-built client) |
| `bindImpl[A, B]` | `A` is an interface and `B` is the implementation to use |
| `bindProvider[D, A](d => a)` | building `A` needs other bound values |

`bindImpl` is the seam that makes a program configurable: bind `UserRepo`
to `InMemoryUserRepo` in development and to `PostgresUserRepo` in
production, and nothing that *uses* `UserRepo` changes. See the full
[binding reference](/core/design#binding-summary) for the complete set.

## Sessions and lifecycle

`design.build[A] { … }` is shorthand for opening a **session**, building
the graph inside it, and closing it when the block ends. A session is the
lifetime of a set of wired objects.

That lifetime is where cleanup hooks attach. Bind a value with an
`onStart`/`onShutdown` hook and the session runs them at the edges:

```scala
import wvlet.uni.design.Design

class Server:
  def start(): Unit   = println("listening")
  def stop(): Unit    = println("stopped")

val design = Design.newDesign
  .bindSingleton[Server]
  .onStart(_.start())     // when the session starts
  .onShutdown(_.stop())   // when the session ends — even on exception

design.build[Server] { server =>
  // server.start() has already run here
  println("serving requests")
}
// server.stop() has run by here
```

```bash
$ sbt run
listening
serving requests
stopped
```

The shutdown hook runs whether the block returns normally or throws, so
resources don't leak on the error path. Chapter 2 used the compact form
of this — `.onShutdown(_.close())` on the HTTP client — and it is the
same mechanism. For a type that is already `AutoCloseable`, Design can
call `close()` for you; the [lifecycle reference](/core/design#lifecycle-management)
lists every hook and the order they fire in.

When you need the session itself — to open short-lived **child
sessions**, say, one per web request, while singletons live for the whole
process — use `design.withSession { session => … }` instead of `build`.
[Session management](/core/design#session-management) covers child
sessions in depth.

## Overriding a design

A `Design` is a value, and two designs combine with `+`. When both bind
the same type, the one on the right wins. That single rule is the
foundation of testing and per-environment configuration:

```scala
// Production wiring, defined once.
val appDesign = Design.newDesign
  .bindSingleton[Database]
  .bindSingleton[UserService]

// A test swaps in a fake Database — everything else is unchanged.
val testDesign = appDesign +
  Design.newDesign.bindInstance[Database](FakeDatabase())

testDesign.build[UserService] { users =>
  // users is the real UserService, wired to FakeDatabase
}
```

`UserService` does not know or care that its `Database` was replaced —
it asked for a `Database`, and it got one. This is the override
mechanism Chapter 11 builds on to test services without their real
dependencies. See [Testing with Design](/core/design#testing) for the
patterns.

## What you have, what comes next

You can now read and write a `Design`:

- Classes declare dependencies as **constructor parameters**; `Design`
  supplies them.
- **`bindSingleton` / `bindInstance` / `bindImpl` / `bindProvider`** cover
  how a value is produced.
- **`build`** opens a session, wires the graph, and tears it down;
  **`onStart` / `onShutdown`** hook the edges of that lifetime.
- **`+`** overrides a design, which is how tests swap real dependencies
  for fakes.

Next, [Chapter 4](./ch04-00-logging) adds the first thing every one of
these services wants — logging that tells you not just *what* happened
but *where*.

[← 2. A URL Fetcher](./ch02-00-cli-app) | [Next → 4. Logging That Finds You](./ch04-00-logging)
