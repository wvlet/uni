# FileSystem

Cross-platform file I/O abstraction for JVM, Scala.js (Node.js and browser), and Scala Native.

```scala
import wvlet.uni.io.*

// Read and write files
val content = FileSystem.readString(IOPath("config.json"))
FileSystem.writeString(IOPath("output.txt"), "hello")

// Path operations
val path = IOPath("/home/user") / "documents" / "file.txt"
println(path.fileName)   // "file.txt"
println(path.extension)  // "txt"
```

## IOPath

`IOPath` is a cross-platform path abstraction that works consistently across all platforms.

### Creating Paths

```scala
import wvlet.uni.io.IOPath

// From string
val path = IOPath("/home/user/file.txt")

// Join segments
val joined = IOPath.of("home", "user", "file.txt")

// Using / operator
val composed = IOPath("/home") / "user" / "file.txt"

// Well-known directories
val cwd  = IOPath.currentDir
val home = IOPath.homeDir
val tmp  = IOPath.tempDir
```

### Path Properties

```scala
val path = IOPath("/home/user/project/README.md")

path.fileName    // "README.md"
path.baseName    // "README"
path.extension   // "md"
path.isAbsolute  // true
path.segments    // Seq("home", "user", "project", "README.md")
path.parent      // Some(IOPath("/home/user/project"))
```

### Path Operations

```scala
// Normalize . and ..
IOPath("/home/user/../admin/./config").normalize
// IOPath("/home/admin/config")

// Relative path
val base = IOPath("/home/user")
val file = IOPath("/home/user/project/src/Main.scala")
file.relativeTo(base)  // IOPath("project/src/Main.scala")

// POSIX format (always forward slashes)
path.posixPath  // "/home/user/project/README.md"

// Checks
path.startsWith(IOPath("/home"))  // true
path.endsWith(IOPath("README.md"))  // true
```

## Reading and Writing Files

### Reading Files

```scala
import wvlet.uni.io.{FileSystem, IOPath}

val path = IOPath("data.txt")

// Read as string (UTF-8)
val text: String = FileSystem.readString(path)

// Read as bytes
val bytes: Array[Byte] = FileSystem.readBytes(path)

// Read line by line
val lines: Seq[String] = FileSystem.readLines(path)
```

### Writing Files

```scala
import wvlet.uni.io.{FileSystem, IOPath, WriteMode}

val path = IOPath("output.txt")

// Create or overwrite (default)
FileSystem.writeString(path, "hello world")

// Create new file, fail if exists
FileSystem.writeString(path, "content", WriteMode.CreateNew)

// Append to file
FileSystem.writeString(path, "more content", WriteMode.Append)

// Write bytes
FileSystem.writeBytes(path, Array[Byte](1, 2, 3))

// Append shorthand
FileSystem.appendString(path, "\nnew line")
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
import wvlet.uni.io.{FileSystem, IOPath, ListOptions}

val dir = IOPath("src")

// List immediate children
val files = FileSystem.list(dir)

// Recursive listing
val allFiles = FileSystem.list(dir, ListOptions().withRecursive(true))

// Filter by extension
val scalaFiles = FileSystem.list(dir,
  ListOptions()
    .withRecursive(true)
    .withExtensions("scala")
)

// Filter by glob pattern
val testFiles = FileSystem.list(dir,
  ListOptions()
    .withRecursive(true)
    .withGlob("**/*Test.scala")
)

// Limit depth
val shallow = FileSystem.list(dir,
  ListOptions()
    .withRecursive(true)
    .withMaxDepth(2)
)

// Include hidden files
val withHidden = FileSystem.list(dir, ListOptions().withIncludeHidden(true))
```

### Creating, Deleting, Copying, Moving

```scala
// Create directory (including parents)
FileSystem.createDirectory(IOPath("a/b/c"))

// Create only if it doesn't exist
FileSystem.createDirectoryIfNotExists(IOPath("output"))

// Delete file or empty directory
FileSystem.delete(IOPath("temp.txt"))

// Delete recursively
FileSystem.deleteRecursively(IOPath("build"))

// Delete if exists (no error if missing)
FileSystem.deleteIfExists(IOPath("maybe.txt"))

// Copy
FileSystem.copy(IOPath("src.txt"), IOPath("dst.txt"))
FileSystem.copy(IOPath("srcDir"), IOPath("dstDir"),
  CopyOptions().withOverwrite(true).withPreserveAttributes(true)
)

// Move / rename
FileSystem.move(IOPath("old.txt"), IOPath("new.txt"))
FileSystem.move(IOPath("old.txt"), IOPath("new.txt"), overwrite = true)
```

### Temporary Files

```scala
// Temporary file
val tmpFile = FileSystem.createTempFile(prefix = "data", suffix = ".json")

// Temporary directory
val tmpDir = FileSystem.createTempDirectory(prefix = "work")

// In a specific directory
val custom = FileSystem.createTempFile(
  prefix = "report",
  suffix = ".csv",
  directory = Some(IOPath("output"))
)
```

## File Metadata

```scala
import wvlet.uni.io.{FileSystem, IOPath, FileType}

val info = FileSystem.info(IOPath("README.md"))

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
FileSystem.exists(IOPath("README.md"))       // true
FileSystem.isFile(IOPath("README.md"))       // true
FileSystem.isDirectory(IOPath("src"))        // true
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
import wvlet.uni.io.{FileSystem, IOPath}
import scala.concurrent.Future

// Async read/write
val content: Future[String] = FileSystem.readStringAsync(IOPath("data.txt"))
val bytes: Future[Array[Byte]] = FileSystem.readBytesAsync(IOPath("image.png"))

val written: Future[Unit] = FileSystem.writeStringAsync(
  IOPath("output.txt"), "hello"
)

// Async directory listing
val files: Future[Seq[IOPath]] = FileSystem.listAsync(IOPath("src"))

// Async metadata
val info: Future[FileInfo] = FileSystem.infoAsync(IOPath("file.txt"))
val exists: Future[Boolean] = FileSystem.existsAsync(IOPath("file.txt"))
```

## Gzip Compression

Cross-platform gzip utilities for compressing and decompressing data.

```scala
import wvlet.uni.io.{Gzip, IOPath}

// In-memory compression
val data = "hello world".getBytes("UTF-8")
val compressed = Gzip.compress(data)
val decompressed = Gzip.decompress(compressed)

// File-based compression (streaming)
Gzip.compressFile(IOPath("large.txt"), IOPath("large.txt.gz"))
Gzip.decompressFile(IOPath("large.txt.gz"), IOPath("large.txt"))
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

1. **Use `IOPath`** instead of raw strings for file paths
2. **Prefer async operations** for code that must run on all platforms
3. **Use `ListOptions`** with glob or extension filters instead of filtering results manually
4. **Use `WriteMode.CreateNew`** when you want to avoid overwriting existing files
5. **Use `deleteIfExists`** to avoid error handling for missing files
