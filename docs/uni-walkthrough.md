# Uni Walkthrough

This walkthrough provides a comprehensive guide to using `uni`, the foundational module of the wvlet/uni library. Uni provides essential utilities including dependency injection, logging, JSON/MessagePack serialization, reactive streams, and control flow utilities.

## Table of Contents

- [Getting Started](#getting-started)
- [Dependency Injection with Design](#dependency-injection-with-design)
- [Logging](#logging)
- [JSON Processing](#json-processing)
- [MessagePack Serialization](#messagepack-serialization)
- [FileSystem](#filesystem)
- [Reactive Streams (Rx)](#reactive-streams-rx)
- [Control Flow Utilities](#control-flow-utilities)
- [Surface (Type Reflection)](#surface-type-reflection)
- [Object Weaving](#object-weaving)
- [Best Practices](#best-practices)

## Getting Started

Add the uni dependency to your `build.sbt`:

```scala
libraryDependencies += "org.wvlet.uni" %% "uni" % "__UNI_VERSION__"
```

## Dependency Injection with Design

Uni-Core provides a powerful dependency injection framework based on `Design`. This enables clean separation of concerns and testable code.

### Basic Usage

```scala
import wvlet.uni.design.Design

// Define case classes
case class User(id: String, name: String, email: String)

// Define your interfaces
trait DatabaseService:
  def findUser(id: String): Option[User]

trait UserService:
  def getUser(id: String): User

// Define implementations
class PostgresDatabase extends DatabaseService:
  def findUser(id: String): Option[User] =
    // Database implementation
    ???

class UserServiceImpl(db: DatabaseService) extends UserService:
  def getUser(id: String): User =
    db.findUser(id).getOrElse(throw new RuntimeException("User not found"))

// Create a design
val design = Design
  .newDesign
  .bindImpl[DatabaseService, PostgresDatabase]
  .bindImpl[UserService, UserServiceImpl]

// Use the design
design.build[UserService] { userService =>
  val user = userService.getUser("123")
  println(s"Found user: $user")
}
```

### Advanced Design Features

```scala
// Bind instances for configuration
case class Config(host: String, port: Int)

val design = Design
  .newDesign
  .bindInstance[Config](Config("localhost", 8080))
  .bindImpl[DatabaseService, PostgresDatabase]

// Lifecycle management
class ServiceWithLifecycle extends LogSupport:
  def start(): Unit = info("Service started")
  def stop(): Unit = info("Service stopped")

val designWithLifecycle = Design
  .newDesign
  .bindSingleton[ServiceWithLifecycle]
  .onStart(_.start())
  .onShutdown(_.stop())

// Sessions manage object lifecycles
designWithLifecycle.withSession { session =>
  val service = session.build[ServiceWithLifecycle]
  // Service automatically started and will be stopped when session ends
}
```

## Logging

Uni-Core provides a comprehensive logging framework with support for structured logging and various output formats.

### Basic Logging

```scala
import wvlet.uni.log.LogSupport

class MyService extends LogSupport:
  def processData(data: String): Unit =
    info(s"Processing data: $data")
    debug("Detailed processing information")
    try
      // Process data
      warn("Something unusual happened")
    catch
      case e: Exception => error("Processing failed", e)
```

### Logger Configuration

```scala
import wvlet.uni.log.{Logger, LogLevel}

// Configure log levels
Logger.setDefaultLogLevel(LogLevel.INFO)

// Create custom loggers
val logger = Logger("MyApp")
logger.info("Application started")

// Structured logging
logger.info("User action", Map(
  "userId" -> "123",
  "action" -> "login",
  "timestamp" -> System.currentTimeMillis()
))
```

## JSON Processing

Uni-Core includes a pure Scala JSON parser with DSL support for easy JSON manipulation.

### Parsing JSON

```scala
import wvlet.uni.json.JSON

val jsonString = """
{
  "user": {
    "id": 123,
    "name": "John Doe",
    "email": "john@example.com",
    "preferences": {
      "theme": "dark",
      "notifications": true
    }
  },
  "posts": [
    {"id": 1, "title": "First Post", "likes": 42},
    {"id": 2, "title": "Second Post", "likes": 17}
  ]
}
"""

val json = JSON.parse(jsonString)
```

### JSON DSL

```scala
// Extract values using DSL
val userId = json("user")("id").toLongValue  // 123
val userName = json("user")("name").toStringValue  // "John Doe"
val theme = (json / "user" / "preferences" / "theme").toStringValue  // "dark"

// Extract arrays
val postTitles = (json / "posts" / "title").values  // Seq("First Post", "Second Post")
val firstPostLikes = json("posts")(0)("likes").toLongValue  // 42

// Safe navigation with Option
val email = for {
  user <- json.get("user")
  email <- user.get("email")
} yield email.toStringValue
```

### Creating JSON

```scala
import wvlet.uni.json.*

// Create JSON objects programmatically
val userJson = JSONObject(Map(
  "id" -> JSONLong(456),
  "name" -> JSONString("Jane Smith"),
  "active" -> JSONBoolean(true)
))

val arrayJson = JSONArray(Seq(
  JSONString("item1"),
  JSONString("item2"),
  JSONLong(123)
))
```

## MessagePack Serialization

Uni-Core provides efficient binary serialization using MessagePack format.

### Basic MessagePack Usage

```scala
import wvlet.uni.msgpack.spi.{MessagePack, Packer, Unpacker}

// Serialize primitive data
val packer = MessagePack.newPacker()
packer.packString("Alice")
packer.packInt(30)
val bytes = packer.toByteArray

// Deserialize data
val unpacker = MessagePack.newUnpacker(bytes)
val name = unpacker.unpackString()
val age = unpacker.unpackInt()

// For complex objects, use Weaver (see Object Weaving section)
```

## FileSystem

Scala historically lacked a cross-platform file I/O abstraction that works
uniformly on JVM, Scala.js (Node and browser), and Scala Native. Uni's
`wvlet.uni.io` package fills that gap with `IOPath` and `FileSystem`, so
the same code handles paths, reads, writes, and directory listings on
every supported platform.

### Paths with IOPath

```scala
import wvlet.uni.io.IOPath

val path = IOPath("/home/user") / "project" / "README.md"
path.fileName    // "README.md"
path.extension   // "md"
path.parent      // Some(IOPath("/home/user/project"))

// Well-known directories
val cwd  = IOPath.currentDir
val home = IOPath.homeDir
val tmp  = IOPath.tempDir
```

### Reading and Writing

```scala
import wvlet.uni.io.{FileSystem, IOPath, WriteMode}

val path = IOPath("data.txt")

// Synchronous read/write (JVM, Node.js, Native)
val text: String = FileSystem.readString(path)
FileSystem.writeString(path, "hello")

// Create-new and append modes
FileSystem.writeString(path, "content", WriteMode.CreateNew)
FileSystem.writeString(path, "more", WriteMode.Append)
```

### Directory Listing

```scala
import wvlet.uni.io.{FileSystem, IOPath, ListOptions}

val scalaSources = FileSystem.list(
  IOPath("src"),
  ListOptions().withRecursive(true).withExtensions("scala")
)
```

### Async for Every Platform

Browser Scala.js cannot run the synchronous APIs; use the `Async` variants
for code that must work everywhere, including in a browser.

```scala
import wvlet.uni.io.{FileSystem, IOPath}
import scala.concurrent.Future

val content: Future[String] = FileSystem.readStringAsync(IOPath("data.txt"))
```

See [FileSystem](/core/filesystem) for the full reference, including
copy/move, metadata, temporary files, and gzip utilities.

## Reactive Streams (Rx)

Uni-Core includes a reactive streams implementation for handling asynchronous data flows.

### Basic Rx Operations

```scala
import wvlet.uni.rx.Rx

// Create reactive sources
val numbers = Rx.fromSeq(1 to 10)
val evenNumbers = numbers.filter(_ % 2 == 0)
val doubled = evenNumbers.map(_ * 2)

// Subscribe to changes
val cancelable = doubled.subscribe { value =>
  println(s"Received: $value")
}


// Create reactive variables
val counter = Rx.variable(0)
counter.map(_ * 10).subscribe { value =>
  println(s"Counter * 10: $value")
}

// Update reactive variables
counter := 5  // Triggers subscriber with value 50

cancelable.cancel() // Cancel the subscription
```

### Advanced Rx Patterns

```scala
// Combine multiple streams
val stream1 = Rx.fromSeq(1 to 5)
val stream2 = Rx.fromSeq(6 to 10)
val combined = stream1.concat(stream2)

// Throttling and debouncing
val throttled = numbers.throttle(100) // milliseconds
val debounced = numbers.debounce(50)

// Error handling
val withErrorHandling = numbers
  .map(x => if (x == 5) throw new RuntimeException("Error") else x)
  .recover {
    case _: RuntimeException => -1
  }

// Async operations
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val asyncResults = numbers.flatMap { n =>
  Rx.future(Future {
    Thread.sleep(100)
    n * n
  })
}
```

## Control Flow Utilities

Uni-Core provides utilities for handling errors, retries, and resource management.

### Retry Logic

```scala
import wvlet.uni.control.Retry

// Simple retry
val result = Retry.retryOn[IllegalArgumentException](maxRetry = 3) {
  // Operation that might fail
  riskyOperation()
}

// Advanced retry with backoff
val advancedResult = Retry
  .withBackoff(maxRetry = 5)
  .withJitter(0.1)
  .withWaitTime(1000) // milliseconds
  .run {
    networkCall()
  }
```

### Circuit Breaker

```scala
import wvlet.uni.control.CircuitBreaker
import scala.concurrent.duration.*

val circuitBreaker = CircuitBreaker(
  maxFailures = 5,
  callTimeout = 10.seconds,
  resetTimeout = 30.seconds
)

val protectedCall = circuitBreaker.protect {
  externalServiceCall()
}
```

### Resource Management

```scala
import wvlet.uni.control.Resource

// Automatic resource cleanup
Resource.withResource(openFile("data.txt")) { file =>
  processFile(file)
} // File automatically closed

// Multiple resources
Resource.withResources(
  openDatabase(),
  openFile("config.txt")
) { (db, config) =>
  processWithResources(db, config)
}
```

## Surface (Type Reflection)

Surface provides compile-time type reflection for Scala 3.

### Basic Surface Usage

```scala
import wvlet.uni.surface.Surface

case class User(id: Long, name: String, email: Option[String])

val userSurface = Surface.of[User]
println(userSurface.name)  // "User"
println(userSurface.params)  // Parameter information

// Generic types
val listSurface = Surface.of[List[String]]
val mapSurface = Surface.of[Map[String, Int]]
```

## Object Weaving

Object weaving provides automatic serialization/deserialization capabilities.

### Basic Weaving

```scala
import wvlet.uni.weaver.Weaver

case class Config(host: String, port: Int, ssl: Boolean) derives Weaver

// Convert case classes to/from various formats
val config = Config("localhost", 8080, true)

// JSON integration
val jsonString = Weaver.toJson(config)
val fromJson = Weaver.fromJson[Config](jsonString)
```

## Best Practices

### 1. Design Patterns

```scala
// Prefer trait-based designs for testability
trait UserRepository:
  def findById(id: String): Option[User]
  def save(user: User): User

class DatabaseUserRepository extends UserRepository:
  // Implementation

class InMemoryUserRepository extends UserRepository:
  // Test implementation

// Use Design for dependency management
val productionDesign = Design.newDesign
  .bindImpl[UserRepository, DatabaseUserRepository]

val testDesign = Design.newDesign
  .bindImpl[UserRepository, InMemoryUserRepository]
```

### 2. Error Handling

```scala
// Define result and exception types
case class ProcessingResult(value: String, timestamp: Long)
class ValidationException(message: String) extends Exception(message)

// Combine logging with error handling
class ServiceImpl extends LogSupport:
  def processData(data: String): Either[String, ProcessingResult] =
    try
      info(s"Processing: $data")
      val result = heavyComputation(data)
      Right(result)
    catch
      case e: ValidationException =>
        warn(s"Validation failed: ${e.getMessage}")
        Left("Invalid data")
      case e: Exception =>
        error("Unexpected error", e)
        Left("Internal error")
        
  private def heavyComputation(data: String): ProcessingResult =
    if (data.trim.isEmpty) throw new ValidationException("Data cannot be empty")
    ProcessingResult(data.toUpperCase, System.currentTimeMillis())
```

### 3. Testing

```scala
import wvlet.airspec.AirSpec

class MyServiceTest extends AirSpec:

  test("service should process data correctly") {
    val testDesign = Design.newDesign
      .bindImpl[DatabaseService, MockDatabase]
      .bindImpl[UserService, UserServiceImpl]

    testDesign.build[UserService] { userService =>
      val result = userService.getUser("123")
      result.name shouldBe "Test User"
    }
  }

  test("should handle errors gracefully") {
    val service = new ServiceImpl()
    val result = service.processData("")
    result.isLeft shouldBe true
  }
  
class MockDatabase extends DatabaseService:
  def findUser(id: String): Option[User] =
    if (id == "123") Some(User("123", "Test User", "test@example.com"))
    else None
```

### 4. Configuration Management

```scala
case class AppConfig(
  database: DatabaseConfig,
  server: ServerConfig,
  logging: LoggingConfig
)

case class DatabaseConfig(url: String, maxConnections: Int)
case class ServerConfig(host: String, port: Int)
case class LoggingConfig(level: String, file: Option[String])

// Create configuration programmatically or from external sources
// Note: For file-based configuration loading, consider using external libraries
// like typesafe-config or airframe-config
val config = AppConfig(
  database = DatabaseConfig("jdbc:postgresql://localhost/mydb", 10),
  server = ServerConfig("localhost", 8080),
  logging = LoggingConfig("INFO", Some("/var/log/app.log"))
)

val design = Design.newDesign
  .bindInstance[AppConfig](config)
  .bindInstance[DatabaseConfig](config.database)
  .bindInstance[ServerConfig](config.server)
```

This walkthrough covers the essential features of uni. For more advanced usage and specific use cases, refer to the test files in the `uni/src/test` directory and the API documentation.