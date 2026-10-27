package example.mcp

import wvlet.uni.mcp.MCPServer
import wvlet.uni.test.*

class NotesMcpServerTest extends UniTest:
  test("the service works as plain Scala") {
    val notes = InMemoryNotesService()
    val n     = notes.addNote("Release", "Tag v1.0 after CI is green", Seq("todo"))
    notes.addNote("Design", "Sketch the API", Seq("design", "todo"))

    notes.searchNotes("ci").map(_.id) shouldBe Seq(n.id)
    notes.listTags() shouldBe Map("todo" -> 2, "design" -> 1)
    notes.deleteNote(n.id) shouldBe true
    notes.deleteNote(n.id) shouldBe false
  }

  // Drive the server the way an MCP client does: one JSON-RPC message per line
  private def call(server: MCPServer, message: String): String = server
    .handleMessage(message)
    .await
    .getOrElse("")

  test("exposes the service methods as MCP tools") {
    val server = NotesMcpServer.server(InMemoryNotesService())
    val tools  = call(server, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
    Seq("addNote", "searchNotes", "deleteNote", "listTags").foreach { name =>
      tools shouldContain s"\"name\":\"${name}\""
    }

    call(
      server,
      """{"jsonrpc":"2.0","id":2,"method":"tools/call",
        |"params":{"name":"addNote","arguments":{"title":"Hi","body":"from MCP"}}}"""
        .stripMargin
        .replace("\n", "")
    ) shouldContain "\\\"title\\\":\\\"Hi\\\""

    val found = call(
      server,
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"searchNotes","arguments":{"query":"mcp"}}}"""
    )
    found shouldContain "from MCP"
    found shouldContain "\"isError\":false"
  }

end NotesMcpServerTest
