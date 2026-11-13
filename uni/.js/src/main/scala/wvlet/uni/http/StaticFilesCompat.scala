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

import wvlet.uni.io.{FileType, IOPath}

import scala.scalajs.js

/**
  * File access for [[StaticFiles]] and [[HttpContent.FileContent]] on Node.js. Node's `fs` is
  * loaded at run time, not through uni-core `FileSystem`, whose static `import "fs"` would break a
  * browser bundle that merely links StaticFiles (e.g. a renderer whose tests link the app's server
  * code).
  */
private[http] object StaticFilesCompat:
  def hasFileSystem: Boolean = NodeModules.isNode

  private def fs: js.Dynamic = NodeModules.builtin("fs")

  def currentDirectory: IOPath = IOPath.parse(js.Dynamic.global.process.cwd().asInstanceOf[String])

  /** The type, size, and mtime of `path` without following a symbolic link (lstat). */
  def stat(path: IOPath): StaticFileStat =
    val stats = fs.lstatSync(path.posixPath, js.Dynamic.literal(throwIfNoEntry = false))
    if js.isUndefined(stats) then
      StaticFileStat(FileType.NotFound, 0L, 0L)
    else
      val fileType =
        if stats.isSymbolicLink().asInstanceOf[Boolean] then
          FileType.SymbolicLink
        else if stats.isFile().asInstanceOf[Boolean] then
          FileType.File
        else if stats.isDirectory().asInstanceOf[Boolean] then
          FileType.Directory
        else
          FileType.Other
      StaticFileStat(
        fileType,
        stats.size.asInstanceOf[Double].toLong,
        stats.mtimeMs.asInstanceOf[Double].toLong
      )

  /** Whether the file can be opened for reading now. */
  def canRead(path: IOPath): Boolean =
    try
      fs.closeSync(fs.openSync(path.posixPath, "r"))
      true
    catch
      case _: Throwable =>
        false

  /** `length` bytes of the file from `offset`. Fails when the file is shorter. */
  def readRange(path: IOPath, offset: Long, length: Long): Array[Byte] =
    require(length <= Int.MaxValue, s"A range of ${length} bytes does not fit in an array")
    val buf = js.Dynamic.global.Buffer.alloc(length.toDouble)
    val fd  = fs.openSync(path.posixPath, "r")
    try
      var pos = 0.0
      while pos < length.toDouble do
        val n = fs
          .readSync(fd, buf, pos, length.toDouble - pos, offset.toDouble + pos)
          .asInstanceOf[Double]
        if n <= 0 then
          throw java.io.EOFException(s"${path} ended before ${offset + length} bytes")
        pos += n
    finally
      fs.closeSync(fd)
    NodeBytes.toBytes(buf)

end StaticFilesCompat
