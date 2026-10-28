package example.mcp

import wvlet.uni.log.LogSupport
import wvlet.uni.mcp.{description, MCP, MCPServer}
import wvlet.uni.weaver.Weaver

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap

case class Note(id: Int, title: String, body: String, tags: Seq[String], createdAt: Instant)
    derives Weaver

/**
  * The MCP tools. Every public method becomes a tool named after the method, its parameters become
  * the tool's input schema, and the @description annotations reach the client as tool and parameter
  * descriptions.
  */
trait NotesService:
  @description("Save a note for later. Returns the saved note with its id.")
  def addNote(
      @description("Short title")
      title: String,
      @description("Note text")
      body: String,
      @description("Optional tags, e.g. [\"todo\", \"design\"]")
      tags: Seq[String] = Seq.empty
  ): Note

  @description("Find notes whose title, body, or tags contain the query (case-insensitive)")
  def searchNotes(
      @description("Text to search for")
      query: String,
      @description("Maximum number of notes to return")
      limit: Int = 10
  ): Seq[Note]

  @description("Delete a note by id. Returns true if the note existed.")
  def deleteNote(id: Int): Boolean

  @description("List all tags with the number of notes using each")
  def listTags(): Map[String, Int]

end NotesService

/** An in-memory store. Notes live as long as the server process (one MCP client session). */
class InMemoryNotesService extends NotesService with LogSupport:
  private val notes  = TrieMap.empty[Int, Note]
  private val nextId = AtomicInteger(0)

  def addNote(title: String, body: String, tags: Seq[String]): Note =
    val note = Note(nextId.incrementAndGet(), title, body, tags, Instant.now())
    notes.put(note.id, note)
    // Logs go to stderr; stdout is reserved for MCP protocol messages
    info(s"Saved note ${note.id}: ${title}")
    note

  def searchNotes(query: String, limit: Int): Seq[Note] =
    val q = query.toLowerCase
    notes
      .values
      .filter { n =>
        n.title.toLowerCase.contains(q) || n.body.toLowerCase.contains(q) ||
        n.tags.exists(_.toLowerCase.contains(q))
      }
      .toSeq
      .sortBy(_.id)
      .take(limit)

  def deleteNote(id: Int): Boolean = notes.remove(id).isDefined

  def listTags(): Map[String, Int] =
    notes.values.flatMap(_.tags).groupMapReduce(identity)(_ => 1)(_ + _)

end InMemoryNotesService

object NotesMcpServer:
  def server(service: NotesService): MCPServer = MCP
    .server
    .withName("notes")
    .withVersion("0.1.0")
    .withTools[NotesService](service)

  // Reads newline-delimited JSON-RPC from stdin until the client closes it
  def main(args: Array[String]): Unit = server(InMemoryNotesService()).serveStdio()
