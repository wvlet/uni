# stdio MCP server: notes for an AI agent

An [MCP](https://modelcontextprotocol.io/) server that gives Claude Code (or any MCP client) four
tools: `addNote`, `searchNotes`, `deleteNote`, and `listTags`. It is a plain Scala trait handed to
`MCPServer().withTools[...]`. Uni derives the tool list and input schemas, and speaks JSON-RPC
over stdin/stdout.

## What it shows

- **Tools from a trait**: every public method of `NotesService` becomes a tool. Parameters with a
  default value (`tags`, `limit`) are optional in the schema; `Seq`, `Map`, `Instant`, and case
  classes are encoded with Weaver.
- **`@description`** on methods and parameters, which is how descriptions reach the client.
- **Logging to stderr**: `info(...)` in a tool is safe, because `serveStdio()` keeps stdout for
  protocol messages.
- **UniTest**: [`NotesMcpServerTest`](src/test/scala/example/mcp/NotesMcpServerTest.scala) tests
  the service as plain Scala, then sends `tools/list` and `tools/call` messages to
  `MCPServer.handleMessage`, exactly as a client would, without starting a process.

Source: [`NotesMcpServer.scala`](src/main/scala/example/mcp/NotesMcpServer.scala). Notes are kept
in memory for the life of the server process.

## Run

```bash
./sbt exampleMcpServer/test

# Talk to it by hand: one JSON-RPC message per line
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | ./sbt --error exampleMcpServer/run
```

The build forks `run` with stdin connected, so `./sbt --error exampleMcpServer/run` works as an MCP
command (`--error` keeps sbt's own output off stdout).

## Add it to Claude Code

The uni `sbt` script must run from the repository root, so register a command that changes there
first (replace `/path/to/uni` with your checkout):

```bash
claude mcp add notes -- sh -c 'cd /path/to/uni && exec ./sbt --error exampleMcpServer/run'
```

Or add it to a project-scoped `.mcp.json`, which `claude mcp add --scope project` writes:

```json
{
  "mcpServers": {
    "notes": {
      "type": "stdio",
      "command": "sh",
      "args": ["-c", "cd /path/to/uni && exec ./sbt --error exampleMcpServer/run"]
    }
  }
}
```

Then ask Claude "save a note that the release is blocked on CI" or run `/mcp` to see the tools.
Starting through sbt takes a few seconds. For instant startup, build the server as a Scala Native
binary and register the binary as the command (see [Build an MCP Server](https://wvlet.org/uni/mcp/)).

To try it with a UI instead, run the MCP Inspector:

```bash
npx @modelcontextprotocol/inspector ./sbt --error exampleMcpServer/run   # from the repository root
```

## Use it in your own build

MCP support is part of `uni` itself; no extra module is needed.

```scala
libraryDependencies ++= Seq(
  "org.wvlet.uni" %% "uni"      % "<version>",
  "org.wvlet.uni" %% "uni-test" % "<version>" % Test
)
testFrameworks += new TestFramework("wvlet.uni.test.Framework")

// Let `sbt --error run` serve stdio
run / fork         := true
run / connectInput := true
outputStrategy     := Some(StdoutOutput)
```
