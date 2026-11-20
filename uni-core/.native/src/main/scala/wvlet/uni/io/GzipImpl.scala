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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
  * Scala Native implementation of gzip compression using java.util.zip. Requires linking to zlib
  * (-lz).
  */
trait GzipCompat extends GzipApi:
  /**
    * The most bytes passed to one zlib call. Keep it under 64 KiB: Scala Native's javalib (0.5.12)
    * declares zlib's `avail_in` and `avail_out` as 16-bit on Windows, where they are 32-bit, so a
    * larger `GZIPOutputStream.write` there never returns.
    */
  private val BufferSize = 8192

  override def compress(data: Array[Byte]): Array[Byte] =
    val baos = ByteArrayOutputStream()
    val gzip = GZIPOutputStream(baos)
    try
      var pos = 0
      while pos < data.length do
        val n = math.min(BufferSize, data.length - pos)
        gzip.write(data, pos, n)
        pos += n
      gzip.finish()
      baos.toByteArray
    finally
      gzip.close()

  override def decompress(data: Array[Byte]): Array[Byte] =
    val bais = ByteArrayInputStream(data)
    val gzip = GzipStreams.decoder(bais, BufferSize)
    try
      val baos   = ByteArrayOutputStream()
      val buffer = Array.ofDim[Byte](BufferSize)
      var len    = gzip.read(buffer)
      while len > 0 do
        baos.write(buffer, 0, len)
        len = gzip.read(buffer)
      baos.toByteArray
    finally
      gzip.close()

  override def compressFile(source: IOPath, target: IOPath): Unit =
    val fis  = FileInputStream(source.toString)
    val fos  = FileOutputStream(target.toString)
    val gzip = GZIPOutputStream(fos)
    try
      val buffer = Array.ofDim[Byte](BufferSize)
      var len    = fis.read(buffer)
      while len > 0 do
        gzip.write(buffer, 0, len)
        len = fis.read(buffer)
      gzip.finish()
    finally
      gzip.close()
      fis.close()

  override def decompressFile(source: IOPath, target: IOPath): Unit =
    val fis  = FileInputStream(source.toString)
    val gzip = GzipStreams.decoder(fis, BufferSize)
    val fos  = FileOutputStream(target.toString)
    try
      val buffer = Array.ofDim[Byte](BufferSize)
      var len    = gzip.read(buffer)
      while len > 0 do
        fos.write(buffer, 0, len)
        len = gzip.read(buffer)
    finally
      fos.close()
      gzip.close()

end GzipCompat
