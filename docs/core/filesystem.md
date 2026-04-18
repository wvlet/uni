# FileSystem

Cross-platform file I/O abstraction for JVM, Scala.js (Node.js and browser), and Scala Native.

Uni's `IO` object is the single entry point for file operations — it exposes path
construction, reads, writes, directory listings, metadata, and async variants
uniformly across platforms.

```scala
import wvlet.uni.io.IO

// Read and write files
val content = IO.readString(IO.path("config.json"))
IO.writeString(IO.path("output.txt"), "hello")

// Path operations
val path = IO.path("/home/user") / "documents" / "file.txt"
println(path.fileName)   // "file.txt"
println(path.extension)  // "txt"
```

## Paths

`IOPath` is uni's cross-platform path abstraction. Construct one through
`IO.path(...)` and use it as the path type everywhere in the API.

### Creating Paths

```scala
import wvlet.uni.io.IO

// From string
val path = IO.path("/home/user/file.txt")

// Join segments
val joined = IO.path("home", "user", "file.txt")

// Using / operator
val composed = IO.path("/home") / "user" / "file.txt"

// Well-known directories
val cwd  = IO.currentDirectory
val home = IO.homeDirectory
val tmp  = IO.tempDirectory
```

### Path Properties

```scala
val path = IO.path("/home/user/project/README.md")

path.fileName    // "README.md"
path.baseName    // "README"
path.extension   // "md"
path.isAbsolute  // true
path.segments    // Seq("home", "user", "project", "README.md")
path.parent      // Some(IO.path("/home/user/project"))
```

### Path Operations

```scala
// Normalize . and ..
IO.path("/home/user/../admin/./config").normalize
// IO.path("/home/admin/config")

// Relative path
val base = IO.path("/home/user")
val file = IO.path("/home/user/project/src/Main.scala")
file.relativeTo(base)  // IO.path("project/src/Main.scala")

// POSIX format (always forward slashes)
path.posixPath  // "/home/user/project/README.md"

// Checks
path.startsWith(IO.path("/home"))  // true
path.endsWith(IO.path("README.md"))  // true
```

## Reading and Writing Files

### Reading Files

```scala
import wvlet.uni.io.IO

val path = IO.path("data.txt")

// Read as string (UTF-8)
val text: String = IO.readString(path)

// Read as bytes
val bytes: Array[Byte] = IO.readBytes(path)

// Read line by line
val lines: Seq[String] = IO.readLines(path)
```

### Writing Files

```scala
import wvlet.uni.io.{IO, WriteMode}

val path = IO.path("output.txt")

// Create or overwrite (default)
IO.writeString(path, "hello world")

// Create new file, fail if exists
IO.writeString(path, "content", WriteMode.CreateNew)

// Append to file
IO.writeString(path, "more content", WriteMode.Append)

// Write bytes
IO.writeBytes(path, Array[Byte](1, 2, 3))

// Append shorthand
IO.appendString(path, "\nnew line")
```

### WriteMode

| Mode | Description |
|------|-------------|
| `CreateNew` | Create a new file, fail if it already exists |
| `Create` | Create or truncate the file (default) |
| `Append` | Append to the file, create if it doesn't exist |

## Directory Operations

### Listing Files

```scala
import wvlet.uni.io.{IO, ListOptions}

val dir = IO.path("src")

// List immediate children
val files = IO.list(dir)

// Recursive listing
val allFiles = IO.list(dir, ListOptions().withRecursive(true))

// Filter by extension
val scalaFiles = IO.list(dir,
  ListOptions()
    .withRecursive(true)
    .withExtensions("scala")
)

// Filter by glob pattern
val testFiles = IO.list(dir,
  ListOptions()
    .withRecursive(true)
    .withGlob("**/*Test.scala")
)

// Limit depth
val shallow = IO.list(dir,
  ListOptions()
    .withRecursive(true)
    .withMaxDepth(2)
)

// Include hidden files
val withHidden = IO.list(dir, ListOptions().withIncludeHidden(true))
```

### Creating, Deleting, Copying, Moving

```scala
import wvlet.uni.io.{IO, CopyOptions}

// Create directory (including parents)
IO.createDirectory(IO.path("a/b/c"))

// Create only if it doesn't exist
IO.createDirectoryIfNotExists(IO.path("output"))

// Delete file or empty directory
IO.delete(IO.path("temp.txt"))

// Delete recursively
IO.deleteRecursively(IO.path("build"))

// Delete if exists (no error if missing)
IO.deleteIfExists(IO.path("maybe.txt"))

// Copy
IO.copy(IO.path("src.txt"), IO.path("dst.txt"))
IO.copy(IO.path("srcDir"), IO.path("dstDir"),
  CopyOptions().withOverwrite(true).withPreserveAttributes(true)
)

// Move / rename
IO.move(IO.path("old.txt"), IO.path("new.txt"))
IO.move(IO.path("old.txt"), IO.path("new.txt"), overwrite = true)
```

### Temporary Files

```scala
// Temporary file
val tmpFile = IO.createTempFile(prefix = "data", suffix = ".json")

// Temporary directory
val tmpDir = IO.createTempDirectory(prefix = "work")

// In a specific directory
val custom = IO.createTempFile(
  prefix = "report",
  suffix = ".csv",
  directory = Some(IO.path("output"))
)
```

## File Metadata

```scala
import wvlet.uni.io.{IO, FileType}

val info = IO.info(IO.path("README.md"))

info.fileType      // FileType.File
info.size          // file size in bytes
info.lastModified  // Option[Instant]
info.createdAt     // Option[Instant]
info.isFile        // true
info.isDirectory   // false
info.isReadable    // true
info.isWritable    // true
info.isExecutable  // false
info.isHidden      // false

// Existence checks
IO.exists(IO.path("README.md"))       // true
IO.isFile(IO.path("README.md"))       // true
IO.isDirectory(IO.path("src"))        // true
```

### FileType

| Type | Description |
|------|-------------|
| `File` | Regular file |
| `Directory` | Directory |
| `SymbolicLink` | Symbolic link |
| `Other` | Other file type |
| `NotFound` | Path does not exist |

## Async Operations

All async operations return `Future[...]` and work on every platform, including browsers.

```scala
import wvlet.uni.io.{IO, IOPath, FileInfo}
import scala.concurrent.Future

// Async read/write
val content: Future[String] = IO.readStringAsync(IO.path("data.txt"))
val bytes: Future[Array[Byte]] = IO.readBytesAsync(IO.path("image.png"))

val written: Future[Unit] = IO.writeStringAsync(
  IO.path("output.txt"), "hello"
)

// Async directory listing
val files: Future[Seq[IOPath]] = IO.listAsync(IO.path("src"))

// Async metadata
val info: Future[FileInfo] = IO.infoAsync(IO.path("file.txt"))
val exists: Future[Boolean] = IO.existsAsync(IO.path("file.txt"))
```

`IOPath` and `FileInfo` are the underlying types — import them when you need
explicit type annotations.

## Gzip Compression

Cross-platform gzip utilities for compressing and decompressing data.

```scala
import wvlet.uni.io.{IO, Gzip}

// In-memory compression
val data = "hello world".getBytes("UTF-8")
val compressed = Gzip.compress(data)
val decompressed = Gzip.decompress(compressed)

// File-based compression (streaming)
Gzip.compressFile(IO.path("large.txt"), IO.path("large.txt.gz"))
Gzip.decompressFile(IO.path("large.txt.gz"), IO.path("large.txt"))
```

## Cross-Platform Support

| Operation | JVM | JS (Node) | JS (Browser) | Native |
|-----------|-----|-----------|-------------|--------|
| Sync read/write | Yes | Yes | No | Yes |
| Async read/write | Yes | Yes | Yes | Yes |
| Directory operations | Yes | Yes | No | Yes |
| File metadata | Yes | Yes | No | Yes |
| Temporary files | Yes | Yes | No | Yes |
| Gzip | Yes | Yes | No | Yes |

Sync operations throw `UnsupportedOperationException` in browser environments. Use async operations for cross-platform code that needs to run in browsers.

## Best Practices

1. **Use `IO` as the single entry point** — `IO.path(...)`, `IO.readString(...)`, `IO.list(...)`, etc.
2. **Prefer async operations** for code that must run on all platforms
3. **Use `ListOptions`** with glob or extension filters instead of filtering results manually
4. **Use `WriteMode.CreateNew`** when you want to avoid overwriting existing files
5. **Use `deleteIfExists`** to avoid error handling for missing files
