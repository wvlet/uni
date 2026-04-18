# Plan: Add Filesystem Watcher to wvlet.uni.io

## Context

The `wvlet.uni.io` package provides cross-platform file system operations (JVM, Scala.js, Scala Native). It currently lacks a file-watching capability — the `os.watch` equivalent from os-lib. This feature enables detecting file creation, modification, and deletion events in a directory, which is essential for dev tools, build systems, and reactive file processing.

## API Design

New standalone file `IOWatch.scala` in the shared source directory. Follows existing patterns: enum for event types, options case class with builder methods, `AutoCloseable` for resource cleanup, object-level factory.

```scala
// Shared API
enum WatchEventType:
  case Created, Modified, Deleted, Overflow

case class WatchEvent(eventType: WatchEventType, path: IOPath)

case class WatchOptions(
    recursive: Boolean = false,
    pollingIntervalMs: Long = 500
):
  def withRecursive(value: Boolean): WatchOptions = ...
  def withPollingIntervalMs(ms: Long): WatchOptions = ...

trait IOWatcher extends AutoCloseable:
  def close(): Unit

// Platform-delegating factory
object IOWatch:
  def watch(path: IOPath, options: WatchOptions = WatchOptions.default)(
      handler: WatchEvent => Unit
  ): IOWatcher
```

## Files to Create/Modify

### New shared file
- `uni-core/src/main/scala/wvlet/uni/io/IOWatch.scala` — `WatchEventType`, `WatchEvent`, `WatchOptions`, `IOWatcher` trait, `IOWatch` object (delegates to platform impl)

### Platform implementations
- `uni-core/.jvm/src/main/scala/wvlet/uni/io/IOWatchImpl.scala` — JVM impl using `java.nio.file.WatchService`, daemon thread for polling
- `uni-core/.js/src/main/scala/wvlet/uni/io/IOWatchImpl.scala` — Node.js impl using `fs.watch`; browser throws `UnsupportedOperationException`
- `uni-core/.native/src/main/scala/wvlet/uni/io/IOWatchImpl.scala` — Polling-based impl: periodically scan directory, compare modification times

### Tests
- `uni/src/test/scala/wvlet/uni/io/IOWatchTest.scala` — Cross-platform tests (create/modify/delete detection)
- `uni/.jvm/src/test/scala/wvlet/uni/io/IOWatchTest.scala` — JVM-specific tests if needed

### Facade export
- `uni-core/src/main/scala/wvlet/uni/io/IO.scala` — Add `IOWatch.watch` to the `IO` object exports

## Platform Implementation Details

### JVM (`java.nio.file.WatchService`)
- Create `WatchService` from `FileSystems.getDefault.newWatchService()`
- Register directory for `ENTRY_CREATE`, `ENTRY_MODIFY`, `ENTRY_DELETE` events
- For recursive: walk directory tree with `Files.walkFileTree`, register each subdir; also register newly created directories
- Daemon thread polls `watchService.poll(pollingIntervalMs)` in a loop
- `close()` stops the thread and closes the watch service

### Node.js (`fs.watch`)
- Use `NodeFSModule.watch(path, options)` which returns an `FSWatcher`
- `recursive` option is natively supported on macOS/Windows
- Convert `rename`/`change` events to Created/Modified/Deleted by checking file existence
- `close()` calls `watcher.close()`
- Browser: throw `UnsupportedOperationException`

### Scala Native (polling)
- Background thread periodically scans directory tree
- Maintain `Map[IOPath, Long]` of last-modified timestamps
- Compare snapshots to detect create/modify/delete
- For recursive: include subdirectories in scan
- `close()` stops the polling thread

## Verification

```bash
./sbt "uniJVM/testOnly *IOWatchTest"     # JVM tests
./sbt "uniJS/testOnly *IOWatchTest"      # JS tests
./sbt "uniNative/testOnly *IOWatchTest"  # Native tests
./sbt scalafmtAll                        # Format check
./sbt compile                            # Full compile
```
