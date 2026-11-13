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

/** File access for [[StaticFiles]], through uni-core `FileSystem`. */
private[http] object StaticFilesCompat:
  def hasFileSystem: Boolean = true

  def currentDirectory: IOPath = FileSystem.currentDirectory

  /** The type, size, and mtime of `path` without following a symbolic link (lstat). */
  def stat(path: IOPath): StaticFileStat =
    val info = FileSystem.info(path)
    StaticFileStat(info.fileType, info.size, info.lastModified.map(_.toEpochMilli).getOrElse(0L))

  def readBytes(path: IOPath): Array[Byte] = FileSystem.readBytes(path)
