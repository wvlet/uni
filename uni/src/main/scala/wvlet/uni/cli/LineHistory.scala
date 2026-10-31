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
package wvlet.uni.cli

import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.log.LogSupport

import scala.collection.mutable.ArrayBuffer

/**
  * Input history of a [[LineReader]], optionally saved to a file
  *
  * The file holds one entry per line, with line breaks in multi-line entries escaped as `\n` and
  * backslashes as `\\`. Lines in JLine's `timestamp:entry` format are also read, so an existing
  * JLine history file keeps working.
  */
class LineHistory(maxSize: Int = 1000, file: Option[IOPath] = None) extends LogSupport:
  private val entries = ArrayBuffer.empty[String]
  // Lines in the history file, which may exceed maxSize until the next rewrite
  private var fileLines = 0
  load()

  def size: Int                 = synchronized(entries.size)
  def apply(index: Int): String = synchronized(entries(index))
  def toSeq: Seq[String]        = synchronized(entries.toSeq)

  /**
    * Add an entry, skipping blank input and an immediate repeat of the last entry
    */
  def add(line: String): Unit = synchronized {
    if line.trim.nonEmpty && !entries.lastOption.contains(line) then
      entries += line
      if entries.size > maxSize then
        entries.remove(0, entries.size - maxSize)
      file.foreach { path =>
        try
          // Append, and rewrite the trimmed history only once the file is twice as long, so that
          // a full history is not rewritten on every input
          if fileLines + 1 > maxSize * 2 then
            save(path)
          else
            FileSystem.appendString(path, s"${LineHistory.escape(line)}\n")
            fileLines += 1
        catch
          case e: Exception =>
            warn(s"Failed to save history to ${path}: ${e.getMessage}")
      }
  }

  def clear(): Unit = synchronized {
    entries.clear()
    file.foreach(save)
  }

  private def save(path: IOPath): Unit =
    FileSystem.writeString(path, entries.map(e => s"${LineHistory.escape(e)}\n").mkString)
    fileLines = entries.size

  private def load(): Unit = file.foreach { path =>
    try
      if FileSystem.exists(path) then
        val lines = FileSystem.readLines(path).filter(_.nonEmpty)
        entries ++= lines.map(LineHistory.unescape)
        fileLines = lines.size
        if entries.size > maxSize then
          entries.remove(0, entries.size - maxSize)
        if fileLines > maxSize * 2 then
          save(path)
      else
        path.parent.foreach(FileSystem.createDirectoryIfNotExists)
    catch
      case e: Exception =>
        warn(s"Failed to load history from ${path}: ${e.getMessage}")
  }

end LineHistory

object LineHistory:
  private val jlineTimestamp = "^\\d{10,}:".r

  private[cli] def escape(line: String): String =
    val sb = StringBuilder()
    // Keep an entry that looks like a JLine timestamp prefix from losing it on reload
    if jlineTimestamp.findPrefixOf(line).isDefined then
      sb.append('\\')
    line.foreach {
      case '\\' =>
        sb.append("\\\\")
      case '\n' =>
        sb.append("\\n")
      case '\r' =>
        sb.append("\\r")
      case c =>
        sb.append(c)
    }
    sb.toString

  private[cli] def unescape(line: String): String =
    val s  = jlineTimestamp.replaceFirstIn(line, "")
    val sb = StringBuilder()
    var i  = 0
    while i < s.length do
      val c = s.charAt(i)
      if c == '\\' && i + 1 < s.length then
        s.charAt(i + 1) match
          case 'n' =>
            sb.append('\n')
          case 'r' =>
            sb.append('\r')
          case other =>
            sb.append(other)
        i += 2
      else
        sb.append(c)
        i += 1
    sb.toString

end LineHistory
