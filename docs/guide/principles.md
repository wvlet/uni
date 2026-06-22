# Design Principles

uni is built on a set of core principles that guide its design and implementation.

## Minimal Dependencies

uni avoids heavy external dependencies. The core module has minimal dependencies, making it suitable for:

- Applications with strict dependency requirements
- Libraries that don't want to impose dependencies on users
- Environments where classpath size matters (Scala.js, Native)

## Cross-Platform First

Every feature is designed to work across:

| Platform | Support |
|----------|---------|
| JVM | Full support |
| Scala.js | Browser and Node.js |
| Scala Native | Native binaries |

Platform-specific code is isolated in `.jvm`, `.js`, and `.native` directories.

## Compile-Time Safety

uni leverages Scala 3's type system for compile-time guarantees:

```scala
// Object wiring is type-safe
val design = Design.newDesign
  .bindImpl[UserRepository, PostgresUserRepository]

// Surface provides compile-time type reflection
val surface = Surface.of[User]
```

## Composable Design

Small, focused utilities that combine well:

```scala
// Combine retry with HTTP client
val response = Retry
  .withBackoff(maxRetry = 3)
  .run {
    httpClient.send(request)
  }

// Combine Rx with resource management
Rx.interval(1, TimeUnit.SECONDS)
  .take(10)
  .map(i => fetchData(i))
  .subscribe(println)
```

## Lifecycle Management

Proper resource lifecycle through Design sessions:

```scala
val design = Design.newDesign
  .bindSingleton[DatabasePool]
  .onStart(_.connect())
  .onShutdown(_.close())

design.withSession { session =>
  // DatabasePool is connected
  val pool = session.build[DatabasePool]
  // Use pool...
} // Automatically closed
```

## Functional Patterns

Embrace functional programming patterns:

- Immutable data structures
- Pure functions where possible
- Effect handling with Rx
- Pattern matching over type checks

```scala
// Transform with pattern matching
json.transform {
  case Success(value) => processValue(value)
  case Failure(error) => handleError(error)
}

// Functional error handling
Rx.single(riskyOperation())
  .recover { case e: NetworkError => fallbackValue }
  .subscribe(handleResult)
```

## Configuration Classes

Config classes use a `withXXX` pattern for immutable updates:

```scala
val config = HttpClientConfig()
  .withConnectTimeout(5000)
  .withReadTimeout(30000)
  .withMaxRetries(3)
```

Optional fields support `noXXX` methods:

```scala
val config = HttpClientConfig()
  .noRetry          // Disable retry
  .noLogging        // Disable request logging
```

## Avoid Try[A] Return Types

Instead of returning `Try[A]`, uni uses:

- Direct exceptions for programming errors
- `Either[Error, A]` for expected failures
- `Rx[A]` for async operations with error handling

This makes error handling explicit while avoiding the overhead of wrapping every result.
