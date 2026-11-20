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

import java.util.zip.Deflater

/**
  * The deflate stream behind a pooled response compressor. On the JVM, a `Deflater`: the JDK picks
  * zlib's window and memory sizes, so every body uses the one size class.
  */
private[http] final class DeflateEngine(raw: Boolean, level: Int, windowBits: Int):
  private val deflater = Deflater(level, raw)

  /** Always false: the JVM goes through Deflater */
  def direct: Boolean                                    = false
  def reset(): Unit                                      = deflater.reset()
  def needsInput: Boolean                                = deflater.needsInput()
  def setInput(b: Array[Byte], off: Int, len: Int): Unit = deflater.setInput(b, off, len)
  def finish(): Unit                                     = deflater.finish()
  def finished: Boolean                                  = deflater.finished()
  def deflate(out: Array[Byte], off: Int, len: Int): Int = deflater.deflate(out, off, len)
  def end(): Unit                                        = deflater.end()

private[http] object DeflateEngine:
  final val MinWindowBits = 15
  final val MaxWindowBits = 15

  def windowBitsFor(length: Int): Int = MaxWindowBits
