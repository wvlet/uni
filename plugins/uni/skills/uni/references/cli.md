# CLI: launcher, colors, progress

`wvlet.uni.cli` (colors, progress bars, spinners) and `wvlet.uni.cli.launcher`
(argument parsing) are in `uni` and compile on JVM, Scala.js, and Scala Native.

Full reference: https://wvlet.org/uni/cli/launcher.md, https://wvlet.org/uni/cli/tint.md,
https://wvlet.org/uni/cli/progress.md

## Options from a case class

```scala
import wvlet.uni.cli.launcher.*

case class MyApp(
    @option(prefix = "-n,--name", description = "User name")
    name: String = "world",
    @option(prefix = "-c,--count", description = "Repeat count")
    count: Int = 1,
    @option(prefix = "-v,--verbose", description = "Verbose output")
    verbose: Boolean = false,
    @option(prefix = "-D", description = "Properties")
    props: Seq[KeyValue] = Seq.empty,         // -Dk=v -Dk2=v2
    @argument(name = "files", description = "Input files")
    files: Seq[String] = Seq.empty            // positional
)

@main def hello(args: String*): Unit =
  val app = Launcher.execute[MyApp](args.toArray) // null when help was shown
  if app != null then
    for _ <- 1 to app.count do println(s"Hello, ${app.name}!")
```

- Accepted forms: `--count 3`, `--count=3`, `-c 3` (not `-c3`); a `Boolean`
  option is a flag. `Option[A]` and `Seq[A]` (repeat the option) are supported.
- `-h`/`--help` prints help for the command it follows; `Launcher.execute[A]`
  then returns `null`. `.withHelpPrefixes(Seq("-h", "--help", "-?"))` changes
  the flags.
- `Launcher.of[A].execute(args)` returns a `LauncherResult(instance,
  executedMethod, showedHelp)`; `execute("--name x")` also takes one string.

## Sub-commands

```scala
import wvlet.uni.cli.launcher.*

class GitCli(@option(prefix = "-v,--verbose", description = "Verbose") verbose: Boolean = false):
  @command(description = "Initialize a repository")
  def init(@argument(name = "dir", description = "Directory") dir: String = "."): String =
    s"init ${dir}"

  @command(description = "Clone a repository")
  def clone(
      @argument(name = "url", description = "Repository URL") url: String,
      @option(prefix = "--depth", description = "Clone depth") depth: Option[Int] = None
  ): String = s"clone ${url} depth=${depth.getOrElse(0)}"

@main def git(args: String*): Unit =
  val result = Launcher.of[GitCli].execute(args)
  result.executedMethod.foreach { (method, value) => println(s"${method.name} -> ${value}") }
```

`executedMethod` holds the `MethodSurface` and the return value.
`@command(isDefault = true)` marks the method run when no sub-command is given;
without one, running with no arguments prints help
(`Launcher.of[A].withShowHelpOnNoArgs(false)` turns that off).
`Launcher.of[Main].addModule[Sub]("name", "description")` nests another
command class.

## Colors and progress

```scala
import wvlet.uni.cli.{ProgressBar, Spinner, Tint}

println(Tint.green("ok"))
println(Tint.red.bold("error"))
println(Tint.hex("#ff8800").bgBlack("custom"))
println(Tint.yellow.noColor("plain"))     // disable styling (e.g. when not a TTY)

val bar = ProgressBar(total = 3).withPrefix("Copying ").withShowCount(true).start()
(1 to 3).foreach(_ => bar.increment())
bar.finish()                               // or bar.fail()

val spinner = Spinner().withText("Loading...").start()
spinner.succeed("Loaded")                  // also fail, warn, info, stop
```

Progress bars and spinners write to `System.err`; use `.withStream(out)` to
redirect and `.withEnabled(false)` in non-interactive runs.
