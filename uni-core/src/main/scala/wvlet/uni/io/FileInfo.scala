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
package wvlet.uni.io

import java.time.Instant

/**
  * File type enumeration.
  */
enum FileType:
  case File
  case Directory
  case SymbolicLink
  case Other
  case NotFound

/**
  * File information/metadata. Provides a cross-platform representation of file attributes.
  *
  * @param path
  *   The path to the file
  * @param fileType
  *   The type of the file (file, directory, symbolic link, etc.)
  * @param size
  *   The file size in bytes (0 for directories)
  * @param lastModified
  *   The last modification time
  * @param lastAccessed
  *   The last access time (if available)
  * @param createdAt
  *   The creation time (if available)
  * @param isReadable
  *   Whether the file is readable
  * @param isWritable
  *   Whether the file is writable
  * @param isExecutable
  *   Whether the file is executable
  * @param isHidden
  *   Whether the file is hidden
  * @param permissions
  *   POSIX file permissions (if available on the platform)
  * @param owner
  *   File owner name (if available on the platform)
  * @param group
  *   File group name (if available on the platform)
  */
case class FileInfo(
    path: IOPath,
    fileType: FileType,
    size: Long,
    lastModified: Option[Instant],
    lastAccessed: Option[Instant] = None,
    createdAt: Option[Instant] = None,
    isReadable: Boolean = true,
    isWritable: Boolean = true,
    isExecutable: Boolean = false,
    isHidden: Boolean = false,
    permissions: Option[PermSet] = None,
    owner: Option[String] = None,
    group: Option[String] = None
):
  /**
    * Returns true if this is a regular file.
    */
  def isFile: Boolean = fileType == FileType.File

  /**
    * Returns true if this is a directory.
    */
  def isDirectory: Boolean = fileType == FileType.Directory

  /**
    * Returns true if this is a symbolic link.
    */
  def isSymbolicLink: Boolean = fileType == FileType.SymbolicLink

  /**
    * Returns true if the file exists.
    */
  def exists: Boolean = fileType != FileType.NotFound

  /**
    * Returns the file name.
    */
  def fileName: String = path.fileName

  /**
    * Returns the file extension.
    */
  def extension: String = path.extension

  /**
    * Returns the parent path.
    */
  def parent: Option[IOPath] = path.parent

end FileInfo

object FileInfo:
  /**
    * Creates a FileInfo for a non-existent file.
    */
  def notFound(path: IOPath): FileInfo = FileInfo(
    path = path,
    fileType = FileType.NotFound,
    size = 0,
    lastModified = None,
    isReadable = false,
    isWritable = false,
    isExecutable = false
  )

end FileInfo
