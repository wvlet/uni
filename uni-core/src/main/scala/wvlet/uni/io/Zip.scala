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

/**
  * Metadata for a single entry in a zip archive.
  *
  * @param name
  *   The entry name (path within the archive, using forward slashes)
  * @param size
  *   The uncompressed size in bytes
  * @param compressedSize
  *   The compressed size in bytes
  * @param isDirectory
  *   Whether this entry represents a directory
  * @param lastModified
  *   The last modification time in epoch milliseconds
  */
case class ZipEntry(
    name: String,
    size: Long,
    compressedSize: Long,
    isDirectory: Boolean,
    lastModified: Long
)

/**
  * Cross-platform zip archive utilities.
  *
  * Platform implementations:
  *   - JVM: Uses java.util.zip (ZipOutputStream, ZipInputStream, ZipFile)
  *   - Scala.js (Node.js): Uses Node's zlib module with manual ZIP format handling
  *   - Scala Native: Uses java.util.zip (requires linking to zlib)
  */
object Zip extends ZipCompat

/**
  * Base trait for zip operations. Platform-specific implementations extend this trait.
  */
trait ZipApi:
  /**
    * Creates a zip archive from the given source files and directories.
    *
    * If a source is a directory, all files within it are added recursively. Entry names use forward
    * slashes and are relative to each source's parent directory.
    *
    * @param target
    *   The target zip file path
    * @param sources
    *   The source files and directories to include
    */
  def create(target: IOPath, sources: Seq[IOPath]): Unit

  /**
    * Extracts all entries from a zip archive to the given destination directory.
    *
    * Intermediate directories are created as needed.
    *
    * @param archive
    *   The zip archive file path
    * @param destination
    *   The destination directory
    */
  def extract(archive: IOPath, destination: IOPath): Unit

  /**
    * Lists all entries in a zip archive without extracting.
    *
    * @param archive
    *   The zip archive file path
    * @return
    *   The list of zip entries
    */
  def list(archive: IOPath): Seq[ZipEntry]

end ZipApi

/**
  * CRC32 checksum implementation using the standard polynomial. Used by the Scala.js ZIP
  * implementation which cannot rely on java.util.zip.CRC32.
  */
object CRC32:
  private val Polynomial = 0xedb88320

  private val Table: Array[Int] =
    val t = Array.ofDim[Int](256)
    var i = 0
    while i < 256 do
      var crc = i
      var j   = 0
      while j < 8 do
        if (crc & 1) != 0 then
          crc = (crc >>> 1) ^ Polynomial
        else
          crc = crc >>> 1
        j += 1
      t(i) = crc
      i += 1
    t

  def compute(data: Array[Byte]): Int = compute(data, 0, data.length)

  def compute(data: Array[Byte], offset: Int, length: Int): Int =
    var crc = 0xffffffff
    var i   = offset
    val end = offset + length
    while i < end do
      crc = (crc >>> 8) ^ Table((crc ^ (data(i) & 0xff)) & 0xff)
      i += 1
    crc ^ 0xffffffff

end CRC32
