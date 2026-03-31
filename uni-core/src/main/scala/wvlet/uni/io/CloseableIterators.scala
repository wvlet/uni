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

import java.io.BufferedReader
import java.io.InputStream

/**
  * An Iterator over lines from a BufferedReader that implements AutoCloseable. The underlying
  * reader is automatically closed when the iterator is exhausted.
  */
private[io] class CloseableLineIterator(reader: BufferedReader)
    extends Iterator[String]
    with AutoCloseable:
  private var nextLine: String | Null = reader.readLine()

  override def hasNext: Boolean =
    val has = nextLine != null
    if !has then
      close()
    has

  override def next(): String =
    val line = nextLine
    if line == null then
      throw java.util.NoSuchElementException("No more lines")
    nextLine = reader.readLine()
    line

  override def close(): Unit = reader.close()

end CloseableLineIterator

/**
  * An Iterator over fixed-size byte chunks from an InputStream that implements AutoCloseable. The
  * underlying stream is automatically closed when the iterator is exhausted.
  */
private[io] class CloseableChunkIterator(in: InputStream, chunkSize: Int)
    extends Iterator[Array[Byte]]
    with AutoCloseable:
  private val buffer                        = new Array[Byte](chunkSize)
  private var bytesRead: Int                = in.read(buffer)
  private var nextChunk: Array[Byte] | Null =
    if bytesRead == -1 then
      null
    else if bytesRead == chunkSize then
      buffer.clone()
    else
      java.util.Arrays.copyOf(buffer, bytesRead)

  override def hasNext: Boolean =
    val has = nextChunk != null
    if !has then
      close()
    has

  override def next(): Array[Byte] =
    val chunk = nextChunk
    if chunk == null then
      throw java.util.NoSuchElementException("No more chunks")
    bytesRead = in.read(buffer)
    nextChunk =
      if bytesRead == -1 then
        null
      else if bytesRead == chunkSize then
        buffer.clone()
      else
        java.util.Arrays.copyOf(buffer, bytesRead)
    chunk

  override def close(): Unit = in.close()

end CloseableChunkIterator
