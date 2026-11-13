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
package wvlet.uni.http

import wvlet.uni.io.{FileSystem, IOPath}

import java.io.{EOFException, InputStream}

/**
  * File access for [[StaticFiles]] and [[HttpContent.FileContent]], through uni-core `FileSystem`.
  */
private[http] object StaticFilesCompat:
  def hasFileSystem: Boolean = true

  def currentDirectory: IOPath = FileSystem.currentDirectory

  /** The type, size, and mtime of `path` without following a symbolic link (lstat). */
  def stat(path: IOPath): StaticFileStat =
    val info = FileSystem.info(path)
    StaticFileStat(info.fileType, info.size, info.lastModified.map(_.toEpochMilli).getOrElse(0L))

  /** Whether the file can be opened for reading now. */
  def canRead(path: IOPath): Boolean =
    try
      FileSystem.readStream(path).close()
      true
    catch
      case _: Exception =>
        false

  /** A stream of the file positioned at `offset`. EOFException when the file is shorter. */
  def openAt(path: IOPath, offset: Long): InputStream =
    val in = FileSystem.readStream(path)
    try
      var remaining = offset
      while remaining > 0 do
        val n = in.skip(remaining)
        if n > 0 then
          remaining -= n
        else if in.read() < 0 then
          throw EOFException(s"${path} is shorter than ${offset} bytes")
        else
          remaining -= 1
      in
    catch
      case e: Throwable =>
        in.close()
        throw e

  /** `length` bytes of the file from `offset`. EOFException when the file is shorter. */
  def readRange(path: IOPath, offset: Long, length: Long): Array[Byte] =
    require(length <= Int.MaxValue, s"A range of ${length} bytes does not fit in an array")
    val in = openAt(path, offset)
    try
      val buf = new Array[Byte](length.toInt)
      var pos = 0
      while pos < buf.length do
        val n = in.read(buf, pos, buf.length - pos)
        if n < 0 then
          throw EOFException(s"${path} ended before ${offset + length} bytes")
        pos += n
      buf
    finally
      in.close()

end StaticFilesCompat
