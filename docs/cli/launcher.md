# Command Launcher

Build type-safe CLI applications with automatic argument parsing using annotations.

## Basic Usage

```scala
import wvlet.uni.cli.launcher.*

case class MyApp(
  @option(prefix = "-n,--name", description = "User name")
  name: String = "world",
  @option(prefix = "-c,--count", description = "Repeat count")
  count: Int = 1,
  @option(prefix = "-v,--verbose", description = "Enable verbose output")
  verbose: Boolean = false
)

// Parse command-line arguments
val app = Launcher.execute[MyApp](args)
println(s"Hello, ${app.name}! (${app.count} times)")
```

Command line:
```bash
$ myapp --name Alice --count 3
Hello, Alice! (3 times)

$ myapp -n Bob -c 2 -v
Hello, Bob! (2 times)
```

## Annotations

### @option

Define command-line options:

```scala
case class App(
  @option(prefix = "-v,--verbose", description = "Enable verbose output")
  verbose: Boolean = false,
  @option(prefix = "-n,--count", description = "Count")
  count: Int = 0,
  @option(prefix = "-m,--message", description = "Message")
  message: String = ""
)
```

### @argument

Define positional arguments:

```scala
case class App(
  @option(prefix = "-v,--verbose", description = "Verbose")
  verbose: Boolean = false,
  @argument(name = "file", description = "Input file")
  file: String = ""
)
```

```bash
$ app --verbose input.txt
```

### @command

Define commands and sub-commands:

```scala
@command(description = "Git-like CLI")
class GitCommand(
  @option(prefix = "-v,--verbose", description = "Verbose")
  verbose: Boolean = false
):
  @command(description = "Initialize repository")
  def init(
    @argument(name = "dir", description = "Directory")
    dir: String = "."
  ): String = s"init ${dir}"

  @command(description = "Clone repository")
  def clone(
    @argument(name = "url", description = "Repository URL")
    url: String,
    @option(prefix = "--depth", description = "Clone depth")
    depth: Option[Int] = None
  ): String = s"clone ${url}"

  @command(isDefault = true)
  def help(): String = "show help"
```

```bash
$ git init myrepo
$ git clone https://example.com/repo --depth 1
```

On the class, `description` appears under the usage line of the top-level help, and `usage` replaces the generated usage line:

```scala
@command(description = "Git-like CLI", usage = "git [options] <command>")
```

## Argument Types

### String Options

```scala
case class App(
  @option(prefix = "-m,--message", description = "Message")
  message: String = ""
)
```

### Numeric Options

```scala
case class App(
  @option(prefix = "-n,--count", description = "Count")
  count: Int = 0,
  @option(prefix = "-r,--rate", description = "Rate")
  rate: Double = 0.0
)
```

### Boolean Flags

```scala
case class App(
  @option(prefix = "-v,--verbose", description = "Verbose")
  verbose: Boolean = false
)
```
```bash
$ app -v           # verbose = true
$ app --verbose    # verbose = true
```

### Optional Values

```scala
case class App(
  @option(prefix = "-c,--config", description = "Config file")
  config: Option[String] = None,
  @option(prefix = "-p,--port", description = "Port")
  port: Option[Int] = None
)
```

### Multi-Value Options

```scala
case class App(
  @option(prefix = "-f,--file", description = "Input files")
  files: Seq[String] = Seq.empty,
  @option(prefix = "-t,--tag", description = "Tags")
  tags: Seq[String] = Seq.empty
)
```
```bash
$ app -f a.txt -f b.txt -t foo -t bar
```

### Key-Value Options

```scala
case class App(
  @option(prefix = "-D", description = "System property")
  props: Seq[KeyValue] = Seq.empty
)
```
```bash
$ app -Dfoo=bar -Dbaz=qux
# props = Seq(KeyValue("foo", "bar"), KeyValue("baz", "qux"))
```

## Nested Options

Group related options:

```scala
case class GlobalOptions(
  @option(prefix = "-v,--verbose", description = "Verbose")
  verbose: Boolean = false,
  @option(prefix = "--config", description = "Config")
  config: Option[String] = None
)

case class App(
  global: GlobalOptions,
  @argument(name = "target", description = "Target")
  target: String = ""
)
```

```bash
$ app --verbose --config app.conf target.txt
```

## Option Formats

The launcher supports multiple option formats:

```bash
$ app --count 100           # Long option with space
$ app --count=100           # Long option with equals
$ app -n 100                # Short option with space
$ app -n100                 # Short option without space
```

## Help Generation

Help is automatically generated:

```scala
val launcher = Launcher.of[MyApp]
launcher.printHelp
```

A help flag applies to the command it follows: `app --help` prints the top-level help, while
`app compile --help` prints the options of the `compile` sub-command.

## Configuration

```scala
val launcher = Launcher.of[MyApp]
  .withHelpPrefixes(Seq("-h", "--help", "-?"))
  .withShowHelpOnNoArgs(true)
```

## Executing Commands

```scala
// Simple execution - returns parsed instance
val app = Launcher.execute[MyApp](args)

// Full control with result
val launcher = Launcher.of[MyApp]
val result = launcher.execute(args)

if result.showedHelp then
  System.exit(0)

// For commands with methods
val result = Launcher.of[GitCommand].execute(Array("init", "myrepo"))
result.executedMethod match
  case Some((name, returnValue)) =>
    println(s"Executed ${name}: ${returnValue}")
  case None =>
    println("No method executed")
```

## Example: Complete CLI

```scala
import wvlet.uni.cli.launcher.*
import wvlet.uni.cli.Tint

@command(description = "File management tool")
class FileTool(
  @option(prefix = "-v,--verbose", description = "Verbose output")
  verbose: Boolean = false
):
  @command(description = "Copy files")
  def copy(
    @argument(name = "source", description = "Source file")
    source: String,
    @argument(name = "dest", description = "Destination")
    dest: String
  ): Unit =
    if verbose then println(s"Copying ${source} to ${dest}")
    // Implementation...
    println(Tint.green("Done!"))

  @command(description = "Move files")
  def move(
    @argument(name = "source", description = "Source file")
    source: String,
    @argument(name = "dest", description = "Destination")
    dest: String
  ): Unit =
    if verbose then println(s"Moving ${source} to ${dest}")
    // Implementation...
    println(Tint.green("Done!"))

  @command(description = "Delete files")
  def delete(
    @argument(name = "path", description = "File path")
    path: String,
    @option(prefix = "-f,--force", description = "Force delete")
    force: Boolean = false
  ): Unit =
    if verbose then println(s"Deleting ${path}")
    // Implementation...
    println(Tint.green("Done!"))

object Main:
  def main(args: Array[String]): Unit =
    Launcher.of[FileTool].execute(args)
```

Usage:
```bash
$ filetool copy source.txt dest.txt --verbose
$ filetool move old.txt new.txt
$ filetool delete temp.txt --force
$ filetool --help
$ filetool move --help
```

## Best Practices

1. **Use descriptive prefixes** - Include both short and long forms
2. **Provide defaults** for optional arguments
3. **Add descriptions** to all options and arguments
4. **Group related options** using nested case classes
5. **Use `@command(isDefault = true)`** for default sub-command
