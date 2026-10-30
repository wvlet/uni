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
package wvlet.uni.util

import scala.scalanative.libc.errno.errno
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno.EINTR
import scala.scalanative.posix.unistd
import scala.scalanative.runtime.ByteArray
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/**
  * A java.util.Random backed by the OS CSPRNG: getentropy(3) on POSIX (macOS, Linux glibc/musl) and
  * BCryptGenRandom on Windows. Both come from libraries every binary already links (libc /
  * libSystem, and the Windows system bcrypt), so using it adds no OpenSSL dependency.
  */
class NativeSecureRandom() extends java.util.Random(0L):

  override def setSeed(x: Long): Unit = ()

  override def nextBytes(bytes: Array[Byte]): Unit =
    val len = bytes.length
    if len > 0 then
      NativeSecureRandom.fill(bytes.asInstanceOf[ByteArray].at(0), len)

  // Adapted from Scala.js
  // https://github.com/scala-js/scala-js-java-securerandom/blob/d6cf1c8651006047bb8c8bfc754ef10500d3bd06/src/main/scala/java/security/SecureRandom.scala#L47
  override protected final def next(numBits: Int): Int =
    if numBits <= 0 then
      0 // special case because the formula on the last line is incorrect for numBits == 0
    else
      val bytes = stackalloc[Int]()
      NativeSecureRandom.fill(bytes.asInstanceOf[Ptr[Byte]], 4)
      val rand32 = !bytes
      rand32 & (-1 >>> (32 - numBits)) // Clear the (32 - numBits) higher order bits

end NativeSecureRandom

private object NativeSecureRandom:
  // getentropy(3) rejects requests larger than 256 bytes
  private val maxEntropyChunk = 256

  private val BCRYPT_USE_SYSTEM_PREFERRED_RNG: CUnsignedInt = 0x00000002.toUInt

  // Resolved at link time, so a POSIX binary never references bcrypt and a Windows binary never
  // references getentropy
  private def fill(buf: Ptr[Byte], len: Int): Unit =
    if LinktimeInfo.isWindows then
      fillWindows(buf, len)
    else
      fillPosix(buf, len)

  private def fillPosix(buf: Ptr[Byte], len: Int): Unit =
    var offset = 0
    while offset < len do
      val n = math.min(maxEntropyChunk, len - offset)
      // getentropy fills all n bytes or fails; it never returns a partial read
      if unistd.getentropy(buf + offset, n.toCSize) == 0 then
        offset += n
      else if errno != EINTR then
        throw IllegalStateException(s"getentropy failed (errno=${errno})")

  private def fillWindows(buf: Ptr[Byte], len: Int): Unit =
    val status = bcrypt.BCryptGenRandom(null, buf, len.toUInt, BCRYPT_USE_SYSTEM_PREFERRED_RNG)
    if status != 0 then
      throw IllegalStateException(s"BCryptGenRandom failed (NTSTATUS=0x${status.toHexString})")

end NativeSecureRandom

@link("bcrypt")
@extern
private object bcrypt:
  // NTSTATUS BCryptGenRandom(BCRYPT_ALG_HANDLE, PUCHAR, ULONG, ULONG); STATUS_SUCCESS is 0
  def BCryptGenRandom(
      hAlgorithm: Ptr[Byte],
      pbBuffer: Ptr[Byte],
      cbBuffer: CUnsignedInt,
      dwFlags: CUnsignedInt
  ): CInt = extern
