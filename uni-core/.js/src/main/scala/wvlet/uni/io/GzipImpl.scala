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

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

/**
  * Node.js zlib module facade
  */
@js.native
@JSImport("zlib", JSImport.Namespace)
private[io] object NodeZlibModule extends js.Object:
  def gzipSync(buffer: Uint8Array): Uint8Array   = js.native
  def gunzipSync(buffer: Uint8Array): Uint8Array = js.native

/**
  * Scala.js (Node.js) implementation of gzip compression using zlib module.
  *
  * Note: Node.js sync zlib APIs load files into memory. For streaming compression, async APIs would
  * be required. The default 100MB max log size should be acceptable for typical Node.js servers.
  */
trait GzipCompat extends GzipApi:

  override def compress(data: Array[Byte]): Array[Byte] =
    if FileSystem.isBrowser then
      throw UnsupportedOperationException(
        "Gzip compression is not supported in browser environments"
      )
    val input      = byteArrayToUint8Array(data)
    val compressed = NodeZlibModule.gzipSync(input)
    uint8ArrayToByteArray(compressed)

  override def decompress(data: Array[Byte]): Array[Byte] =
    if FileSystem.isBrowser then
      throw UnsupportedOperationException(
        "Gzip decompression is not supported in browser environments"
      )
    val input        = byteArrayToUint8Array(data)
    val decompressed =
      try
        NodeZlibModule.gunzipSync(input)
      catch
        // zlib throws a JavaScript Error on corrupt data; the JVM and Native throw ZipException
        case e: js.JavaScriptException =>
          throw java.io.IOException(s"Corrupt gzip data: ${e.getMessage}", e)
    uint8ArrayToByteArray(decompressed)

  override def compressFile(source: IOPath, target: IOPath): Unit =
    if FileSystem.isBrowser then
      throw UnsupportedOperationException(
        "Gzip file compression is not supported in browser environments"
      )
    val data       = FileSystem.readBytes(source)
    val compressed = compress(data)
    FileSystem.writeBytes(target, compressed)

  override def decompressFile(source: IOPath, target: IOPath): Unit =
    if FileSystem.isBrowser then
      throw UnsupportedOperationException(
        "Gzip file decompression is not supported in browser environments"
      )
    val compressed   = FileSystem.readBytes(source)
    val decompressed = decompress(compressed)
    FileSystem.writeBytes(target, decompressed)

  private def byteArrayToUint8Array(bytes: Array[Byte]): Uint8Array =
    val uint8 = Uint8Array(bytes.length)
    var i     = 0
    while i < bytes.length do
      uint8(i) = (bytes(i) & 0xff).toShort
      i += 1
    uint8

  private def uint8ArrayToByteArray(uint8: Uint8Array): Array[Byte] =
    import scala.scalajs.js.typedarray.Int8Array
    Int8Array(uint8.buffer, uint8.byteOffset, uint8.length).toArray

end GzipCompat
