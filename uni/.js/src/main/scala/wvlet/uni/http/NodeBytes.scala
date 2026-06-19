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

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.*

/**
  * Conversions between Node `Buffer`/`Uint8Array` chunks and JVM byte arrays, shared by the Node
  * HTTP server and WebSocket backends.
  */
private[http] object NodeBytes:

  /**
    * Copy a Node `Buffer`/`Uint8Array` chunk into a JVM byte array. The chunk may be a view into a
    * larger pooled buffer, so honor its `byteOffset`/`length`.
    */
  def toBytes(chunk: js.Dynamic): Array[Byte] =
    val u8 = chunk.asInstanceOf[Uint8Array]
    Int8Array(u8.buffer, u8.byteOffset, u8.length).toArray

  /**
    * View a JVM byte array as a Node-compatible `Uint8Array` (Node rejects `Int8Array` payloads).
    * The two share the same underlying bytes; only the signed/unsigned interpretation differs,
    * which Node ignores when writing raw bytes.
    */
  def toUint8Array(bytes: Array[Byte]): Uint8Array =
    val i8 = bytes.toTypedArray
    Uint8Array(i8.buffer, i8.byteOffset, i8.length)

end NodeBytes
