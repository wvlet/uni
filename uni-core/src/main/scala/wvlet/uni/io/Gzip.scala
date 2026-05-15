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
  * Cross-platform gzip compression utilities.
  *
  * Platform implementations:
  *   - JVM: Uses java.util.zip.GZIPOutputStream/GZIPInputStream
  *   - Scala.js (Node.js): Uses Node's zlib module
  *   - Scala Native: Uses java.util.zip (requires linking to zlib)
  */
object Gzip extends GzipCompat

/**
  * Base trait for gzip operations. Platform-specific implementations extend this trait.
  */
trait GzipApi:
  /**
    * Compresses the given data using gzip.
    *
    * @param data
    *   The data to compress
    * @return
    *   The compressed data
    */
  def compress(data: Array[Byte]): Array[Byte]

  /**
    * Decompresses the given gzip-compressed data.
    *
    * @param data
    *   The compressed data
    * @return
    *   The decompressed data
    */
  def decompress(data: Array[Byte]): Array[Byte]

  /**
    * Compresses a file using gzip.
    *
    * Platform implementations use streaming where possible to avoid loading entire files into
    * memory.
    *
    * @param source
    *   The source file path
    * @param target
    *   The target file path (typically with .gz extension)
    */
  def compressFile(source: IOPath, target: IOPath): Unit

  /** String-path overload of [[compressFile]]. */
  def compressFile(source: String, target: String): Unit = compressFile(
    IOPath.parse(source),
    IOPath.parse(target)
  )

  /**
    * Decompresses a gzip file.
    *
    * Platform implementations use streaming where possible to avoid loading entire files into
    * memory.
    *
    * @param source
    *   The compressed file path
    * @param target
    *   The target file path for decompressed content
    */
  def decompressFile(source: IOPath, target: IOPath): Unit

  /** String-path overload of [[decompressFile]]. */
  def decompressFile(source: String, target: String): Unit = decompressFile(
    IOPath.parse(source),
    IOPath.parse(target)
  )

end GzipApi
