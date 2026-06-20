# Appendix A: Scala 3 Syntax Notes for This Book

This book uses a handful of Scala 3 features in nearly every example. If
you are coming from Scala 2, Java, or another typed language, this
appendix is the short reference for the syntax you'll see — just enough to
read the chapters comfortably.

## Indentation-based syntax

Scala 3 lets you replace braces with significant indentation. A class
body, method body, or block opens with `:` (for definitions) or is simply
indented:

```scala
class Greeter:
  def greet(name: String): String =
    val message = s"Hello, ${name}"
    message
```

Braces still work and are still useful — for a short lambda passed to a
method, the book often keeps them: `design.build[App] { app => … }`. Use
whichever reads more clearly; they mean the same thing.

## `@main` entry points

A program's entry point is a method annotated with `@main`, not a magic
signature on an object:

```scala
@main def run(): Unit =
  println("started")
```

Arguments become method parameters. `@main def fetch(args: String*)` gives
you the command-line arguments directly, which is how the CLI app in
[Chapter 2](./ch02-00-cli-app) starts.

## `given` / `using` — contextual parameters

A `using` parameter is supplied by the compiler from a matching `given`
in scope, instead of being passed explicitly at the call site:

```scala
given Weaver[User] = Weaver.of[User]

def toJson[A](a: A)(using weaver: Weaver[A]): String = weaver.toJson(a)

toJson(user)   // the Weaver[User] given is found and passed for you
```

This is how [Weaver](./ch05-00-data) codecs and other typeclass-style
values travel through the code without cluttering every signature. A
`derives` clause (`case class User(...) derives Weaver`) generates the
`given` for you.

## Enums and pattern matching

Scala 3 enums declare a closed set of cases, which you then match on:

```scala
enum Color:
  case Red, Green, Blue

def hex(c: Color): String =
  c match
    case Color.Red   => "#f00"
    case Color.Green => "#0f0"
    case Color.Blue  => "#00f"
```

The compiler warns if a `match` misses a case, so adding `Color.Black`
later points you at every place that needs updating. Uni uses enums for
closed sets like log levels and RPC status codes.

## Extension methods

An `extension` adds methods to a type you don't own:

```scala
extension (s: String)
  def shout: String = s.toUpperCase + "!"

"hello".shout   // "HELLO!"
```

Several Uni APIs read fluently because of extensions — the `shouldBe` in
[Chapter 11](./ch11-00-testing)'s tests is an extension method on any
value.

## Why `new` is almost never needed

Scala 3 lets you construct a class by calling it like a function, so the
book writes `StringBuilder()` and `FakeDatabase()`, not `new
StringBuilder()`. You'll only see `new` in this book for the rare
anonymous-class case (overriding methods inline, as with a
`WebSocketHandler`). If you wrote Scala 2, dropping `new` is the habit to
unlearn first.

[← 13. Bundling with Vite](./ch13-00-vite) | [Next → Appendix B: Uni and Airframe](./appendix-b-airframe)
