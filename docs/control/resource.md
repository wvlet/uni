# Resource Management

Safely acquire and release resources with automatic cleanup.

## Basic Usage

```scala
import wvlet.uni.control.Control

Control.withResource(openFile("data.txt")) { file =>
  processFile(file)
} // File is automatically closed
```

## Multiple Resources

```scala
Control.withResources(
  openDatabase(),
  openFile("config.txt")
) { (db, file) =>
  processWithResources(db, file)
} // Both resources are closed in reverse order
```

## AutoCloseable Support

Works with any `AutoCloseable`:

```scala
import java.io.{BufferedReader, FileReader}

Control.withResource(BufferedReader(FileReader("data.txt"))) { reader =>
  reader.lines().forEach(println)
}
```

## Nested Resources

```scala
Control.withResource(openConnection()) { conn =>
  Control.withResource(conn.createStatement()) { stmt =>
    Control.withResource(stmt.executeQuery(sql)) { rs =>
      processResultSet(rs)
    }
  }
}
```

## Error Handling

Resources are closed even if an exception occurs:

```scala
try
  Control.withResource(openFile("data.txt")) { file =>
    if someCondition then
      throw RuntimeException("Processing failed")
    processFile(file)
  }
catch
  case e: RuntimeException =>
    // Handle error, file is already closed
    logger.error("Error", e)
```

## Custom Resource Types

Implement `AutoCloseable` for custom types:

```scala
class DatabasePool extends AutoCloseable:
  private val connections = scala.collection.mutable.ListBuffer[Connection]()

  def getConnection(): Connection = ???

  def close(): Unit =
    // Release all connections
    connections.foreach(_.close())

Control.withResource(DatabasePool()) { pool =>
  val conn = pool.getConnection()
  // Use connection
}
```

## Resource in Rx

Combine with reactive streams:

```scala
import wvlet.uni.rx.Rx

def readLines(path: String): Rx[String] =
  Rx.single {
    Control.withResource(scala.io.Source.fromFile(path)) { source =>
      source.getLines().toList
    }
  }.flatMap(lines => Rx.fromSeq(lines))
```

## Loan Pattern

The `withResource` method implements the loan pattern:

```scala
def withDatabase[T](f: Database => T): T =
  Control.withResource(Database.connect()) { db =>
    f(db)
  }

// Usage
val users = withDatabase { db =>
  db.query("SELECT * FROM users")
}
```

## Resource with Design

Integrate with Design for lifecycle management:

```scala
import wvlet.uni.design.Design

val design = Design.newDesign
  .bindSingleton[DatabasePool]
  .onStart(_.initialize())
  .onShutdown(_.close())

design.withSession { session =>
  val pool = session.build[DatabasePool]
  // Pool is initialized and will be closed
}
```

## Best Practices

1. **Always use withResource** for closeable resources
2. **Handle exceptions** appropriately
3. **Close in reverse order** for nested resources
4. **Prefer Design lifecycle** for application-scoped resources
5. **Log cleanup failures** but don't mask original errors

## Common Resources

| Resource | Usage |
|----------|-------|
| Files | `BufferedReader`, `BufferedWriter` |
| Database | `Connection`, `Statement`, `ResultSet` |
| Network | `Socket`, `HttpClient` |
| Streams | `InputStream`, `OutputStream` |
