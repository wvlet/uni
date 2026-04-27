/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.cli.launcher

import wvlet.uni.test.UniTest

// Test case classes
case class SimpleApp(
    @option(prefix = "-v,--verbose", description = "Enable verbose output")
    verbose: Boolean = false,
    @argument(name = "file", description = "Input file")
    file: String = ""
)

case class TypedOptions(
    @option(prefix = "-n,--count", description = "Count")
    count: Int = 0,
    @option(prefix = "-r,--rate", description = "Rate")
    rate: Double = 0.0,
    @option(prefix = "-e,--enabled", description = "Enabled")
    enabled: Boolean = false,
    @option(prefix = "-m,--message", description = "Message")
    message: String = ""
)

case class OptionalOptions(
    @option(prefix = "-c,--config", description = "Config file")
    config: Option[String] = None,
    @option(prefix = "-p,--port", description = "Port")
    port: Option[Int] = None
)

case class MultiValueOptions(
    @option(prefix = "-f,--file", description = "Input files")
    files: Seq[String] = Seq.empty,
    @option(prefix = "-t,--tag", description = "Tags")
    tags: Seq[String] = Seq.empty
)

case class KeyValueOptions(
    @option(prefix = "-D", description = "System property")
    props: Seq[KeyValue] = Seq.empty,
    @option(prefix = "-L", description = "Log level")
    logLevels: Seq[KeyValue] = Seq.empty
)

case class NestedGlobal(
    @option(prefix = "-v,--verbose", description = "Verbose")
    verbose: Boolean = false,
    @option(prefix = "--config", description = "Config")
    config: Option[String] = None
)

case class NestedApp(
    global: NestedGlobal,
    @argument(name = "target", description = "Target")
    target: String = ""
)

// Command with sub-commands
@command(description = "Git-like CLI")
class GitLikeCommand(
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
  ): String =
    depth match
      case Some(d) =>
        s"clone ${url} --depth ${d}"
      case None =>
        s"clone ${url}"

  @command(isDefault = true)
  def help(): String = "show help"

// Methods that take nested config-class parameters (#509)
case class ServerConfig(
    @option(prefix = "--port", description = "Server port")
    port: Int = 8080,
    @option(prefix = "--host", description = "Server host")
    host: String = "localhost"
)

case class CompilerOption(
    @option(prefix = "--target", description = "Target directory")
    target: String = "out",
    @argument(name = "source", description = "Source file")
    source: String = ""
)

@command(description = "App with nested-config methods")
class NestedConfigApp:
  @command(description = "Start the server")
  def start(config: ServerConfig): String = s"start ${config.host}:${config.port}"

  @command(description = "Compile a source file")
  def compile(opt: CompilerOption): String = s"compile ${opt.source} -> ${opt.target}"

  // Nested config-class with a method-level default
  @command(description = "Start the server with a custom default")
  def startCustom(config: ServerConfig = ServerConfig(port = 9090, host = "default-host")): String =
    s"start ${config.host}:${config.port}"

@command(description = "App with conflicting nested configs")
class ConflictingNestedApp:
  @command(description = "Two nested configs that share field names")
  def collide(a: ServerConfig, b: ServerConfig): String = s"${a.port}-${b.port}"

class LauncherTest extends UniTest:

  test("parse simple options") {
    val app = Launcher.execute[SimpleApp]("--verbose input.txt")
    app.verbose shouldBe true
    app.file shouldBe "input.txt"
  }

  test("parse short options") {
    val app = Launcher.execute[SimpleApp]("-v test.txt")
    app.verbose shouldBe true
    app.file shouldBe "test.txt"
  }

  test("parse typed options") {
    val app = Launcher.execute[TypedOptions]("-n 42 -r 3.14 -e -m hello")
    app.count shouldBe 42
    app.rate shouldBe 3.14
    app.enabled shouldBe true
    app.message shouldBe "hello"
  }

  test("parse long option with equals") {
    val app = Launcher.execute[TypedOptions]("--count=100 --message=world")
    app.count shouldBe 100
    app.message shouldBe "world"
  }

  test("parse optional options when present") {
    val app = Launcher.execute[OptionalOptions]("--config app.conf --port 8080")
    app.config shouldBe Some("app.conf")
    app.port shouldBe Some(8080)
  }

  test("parse optional options when absent") {
    val app = Launcher.execute[OptionalOptions]("")
    app.config shouldBe None
    app.port shouldBe None
  }

  test("parse multi-value options") {
    val app = Launcher.execute[MultiValueOptions]("-f a.txt -f b.txt -t foo -t bar")
    app.files shouldBe Seq("a.txt", "b.txt")
    app.tags shouldBe Seq("foo", "bar")
  }

  test("parse key-value options") {
    val app = Launcher.execute[KeyValueOptions]("-Dfoo=bar -Dbaz=qux -Lwvlet=debug")
    app.props shouldBe Seq(KeyValue("foo", "bar"), KeyValue("baz", "qux"))
    app.logLevels shouldBe Seq(KeyValue("wvlet", "debug"))
  }

  test("parse nested options") {
    val app = Launcher.execute[NestedApp]("--verbose --config app.conf target.txt")
    app.global.verbose shouldBe true
    app.global.config shouldBe Some("app.conf")
    app.target shouldBe "target.txt"
  }

  test("execute sub-command") {
    val launcher = Launcher.of[GitLikeCommand]
    val result   = launcher.execute(Array("init", "myrepo"))
    result.executedMethod.isDefined shouldBe true
    result.executedMethod.get._2 shouldBe "init myrepo"
  }

  test("execute sub-command with options") {
    val launcher = Launcher.of[GitLikeCommand]
    val result   = launcher.execute(Array("clone", "https://example.com/repo", "--depth", "1"))
    result.executedMethod.isDefined shouldBe true
    result.executedMethod.get._2 shouldBe "clone https://example.com/repo --depth 1"
  }

  test("default values are used when options not provided") {
    val app = Launcher.execute[SimpleApp]("")
    app.verbose shouldBe false
    app.file shouldBe ""
  }

  test("Launcher.of creates a launcher") {
    val launcher = Launcher.of[SimpleApp]
    launcher shouldNotBe null
  }

  test("execute method with nested config-class parameter") {
    val launcher = Launcher.of[NestedConfigApp]
    val result   = launcher.execute(Array("start", "--port", "9090", "--host", "example.com"))
    result.executedMethod.isDefined shouldBe true
    result.executedMethod.get._2 shouldBe "start example.com:9090"
  }

  test("nested config-class method falls back to defaults when flags omitted") {
    val launcher = Launcher.of[NestedConfigApp]
    val result   = launcher.execute(Array("start"))
    result.executedMethod.isDefined shouldBe true
    result.executedMethod.get._2 shouldBe "start localhost:8080"
  }

  test("nested config-class method accepts positional arguments and options") {
    val launcher = Launcher.of[NestedConfigApp]
    val result   = launcher.execute(Array("compile", "--target", "build", "Main.scala"))
    result.executedMethod.isDefined shouldBe true
    result.executedMethod.get._2 shouldBe "compile Main.scala -> build"
  }

  test("nested config-class method honors method-level default when no flags parsed") {
    val launcher = Launcher.of[NestedConfigApp]
    val result   = launcher.execute(Array("startCustom"))
    result.executedMethod.isDefined shouldBe true
    // Should preserve the method-level default, not fall back to inner field defaults
    result.executedMethod.get._2 shouldBe "start default-host:9090"
  }

  test("nested config-class method overrides method default when flags provided") {
    val launcher = Launcher.of[NestedConfigApp]
    val result   = launcher.execute(Array("startCustom", "--port", "1234"))
    result.executedMethod.isDefined shouldBe true
    // When any inner flag is parsed, the method default is replaced by an instance built
    // from the nested surface (so unspecified fields fall back to inner defaults).
    result.executedMethod.get._2 shouldBe "start localhost:1234"
  }

  test("colliding nested config-class params produce a clear error") {
    intercept[IllegalArgumentException] {
      Launcher.of[ConflictingNestedApp].execute(Array("collide"))
    }
  }

  test("launcher with config") {
    val launcher = Launcher
      .of[SimpleApp]
      .withShowHelpOnNoArgs(false)
      .withHelpPrefixes(Seq("--help"))
    launcher.config.showHelpOnNoArgs shouldBe false
    launcher.config.helpPrefixes shouldBe Seq("--help")
  }

end LauncherTest
