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
package wvlet.uni.cloud.storage

import wvlet.uni.cloud.*
import wvlet.uni.util.Sha256

import wvlet.uni.http.HttpAsyncClient
import wvlet.uni.http.HttpClientConfig
import wvlet.uni.http.HttpException
import wvlet.uni.http.HttpMaxRetryException
import wvlet.uni.http.HttpSyncClient
import wvlet.uni.http.Response
import wvlet.uni.rx.Rx

import java.io.ByteArrayOutputStream

/** Pieces shared by the object store implementations */
private[storage] object StorageSupport:

  /** The client throws for 3xx and 4xx; stores map the response to their own errors */
  val unwrapHttpError: PartialFunction[Throwable, Response] =
    wvlet.uni.cloud.CloudHttp.unwrapHttpError

  def utf8(b: Array[Byte]): String = String(b, "UTF-8")

  def bodyOf(resp: Response): Array[Byte] = resp.contentAsBytes.getOrElse(Array.emptyByteArray)

  def concat(parts: Seq[Array[Byte]]): Array[Byte] =
    val out = new Array[Byte](parts.map(_.length).sum)
    var pos = 0
    parts.foreach { p =>
      System.arraycopy(p, 0, out, pos, p.length)
      pos += p.length
    }
    out

  /**
    * The largest range to ask for in one request. The Node.js sync channel caps a response at
    * `maxResponseBytes`, so leave room for headers, and never ask for more than the cap allows.
    */
  def maxRangeChunk(http: HttpClientConfig): Long =
    val cap = http.maxResponseBytes.toLong
    if cap > 128 * 1024 then
      cap - 64 * 1024
    else
      math.max(1L, cap / 2)

  def checkRange(offset: Long, length: Long): Unit =
    if offset < 0 || length < 0 then
      throw IllegalArgumentException(s"Invalid range: offset=${offset}, length=${length}")

  def rangeHeader(offset: Long, length: Long): (String, String) =
    "range" -> s"bytes=${offset}-${offset + length - 1}"

  /**
    * The bytes of a ranged GET. `checked` raises the store's error for a refused request. A 416
    * means the range starts at or past the end; a 200 carries the whole object.
    */
  def rangeBody(
      resp: Response,
      offset: Long,
      length: Long,
      checked: Response => Response
  ): Array[Byte] =
    if resp.statusCode == 416 then
      Array.emptyByteArray
    else
      val b = bodyOf(checked(resp))
      if resp.statusCode == 206 then
        b
      else
        val from = math.min(offset, b.length.toLong).toInt
        val to   = math.min(offset + length, b.length.toLong).toInt
        java.util.Arrays.copyOfRange(b, from, to)

  /** Reads `[offset, offset + length)` in requests of at most `chunk` bytes */
  def readRanges(offset: Long, length: Long, chunk: Long)(
      fetch: (Long, Long) => Array[Byte]
  ): Array[Byte] =
    checkRange(offset, length)
    val out  = ByteArrayOutputStream()
    var pos  = offset
    var left = length
    var eof  = false
    while left > 0 && !eof do
      val n     = math.min(left, chunk)
      val bytes = fetch(pos, n)
      out.write(bytes)
      pos += bytes.length
      left -= bytes.length
      eof = bytes.length < n
    out.toByteArray

  def readRangesAsync(offset: Long, length: Long, chunk: Long)(
      fetch: (Long, Long) => Rx[Array[Byte]]
  ): Rx[Array[Byte]] =
    checkRange(offset, length)
    def loop(pos: Long, left: Long, acc: List[Array[Byte]]): Rx[List[Array[Byte]]] =
      if left <= 0 then
        Rx.single(acc)
      else
        val n = math.min(left, chunk)
        fetch(pos, n).flatMap { bytes =>
          if bytes.length < n then
            Rx.single(bytes :: acc)
          else
            loop(pos + bytes.length, left - bytes.length, bytes :: acc)
        }
    loop(offset, length, Nil).map(parts => concat(parts.reverse))

  /** Lazily created HTTP clients, closed together */
  final class Clients(http: HttpClientConfig):
    private var syncOpt: Option[HttpSyncClient]   = None
    private var asyncOpt: Option[HttpAsyncClient] = None

    def sync: HttpSyncClient = synchronized {
      syncOpt.getOrElse {
        val c = http.newSyncClient
        syncOpt = Some(c)
        c
      }
    }

    def async: HttpAsyncClient = synchronized {
      asyncOpt.getOrElse {
        val c = http.newAsyncClient
        asyncOpt = Some(c)
        c
      }
    }

    def close(): Unit = synchronized {
      syncOpt.foreach(_.close())
      asyncOpt.foreach(_.close())
      syncOpt = None
      asyncOpt = None
    }

  end Clients

end StorageSupport
