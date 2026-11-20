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
package wvlet.uni.jsenv.playwright

import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import javax.imageio.ImageIO

/**
  * Pixel comparison of two PNG images using only the JDK (`ImageIO`), for `expectScreenshot`.
  *
  * A pixel differs when any ARGB channel differs by more than `threshold * 255`. This is simpler
  * than `@playwright/test`'s perceptual (YIQ) metric, but deterministic and dependency-free.
  */
private[playwright] object ImageDiff:

  /**
    * Result of a comparison.
    *
    * @param diffPixels
    *   number of differing pixels (all pixels when the sizes differ)
    * @param diffImage
    *   PNG bytes highlighting differing pixels in red over a faded copy of the expected image, or
    *   None when the sizes differ or no pixel differs
    */
  case class Result(
      width: Int,
      height: Int,
      sizeMismatch: Boolean,
      diffPixels: Long,
      diffImage: Option[Array[Byte]]
  ):
    def totalPixels: Long = width.toLong * height
    def diffRatio: Double =
      if totalPixels == 0 then
        0.0
      else
        diffPixels.toDouble / totalPixels

    def isMatch(maxDiffPixelRatio: Double): Boolean =
      !sizeMismatch && diffRatio <= maxDiffPixelRatio

  def compare(expectedPng: Array[Byte], actualPng: Array[Byte], threshold: Double): Result =
    val expected = decode(expectedPng)
    val actual   = decode(actualPng)
    val w        = expected.getWidth
    val h        = expected.getHeight
    if w != actual.getWidth || h != actual.getHeight then
      Result(w, h, sizeMismatch = true, diffPixels = w.toLong * h, diffImage = None)
    else
      val limit = (threshold.max(0.0).min(1.0) * 255).toInt
      val count =
        (
          for
            y <- 0 until h
            x <- 0 until w
            if differs(expected.getRGB(x, y), actual.getRGB(x, y), limit)
          yield 1L
        ).sum
      // Build the diff image only on a difference, so a passing comparison stays cheap.
      val diffImage =
        if count == 0 then
          None
        else
          val diff = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
          for
            y <- 0 until h
            x <- 0 until w
          do
            val e = expected.getRGB(x, y)
            diff.setRGB(
              x,
              y,
              if differs(e, actual.getRGB(x, y), limit) then
                0xffff0000
              else
                faded(e)
            )
          Some(encode(diff))
      Result(w, h, sizeMismatch = false, diffPixels = count, diffImage = diffImage)
    end if

  end compare

  private def differs(a: Int, b: Int, limit: Int): Boolean = (0 until 32 by 8).exists { shift =>
    math.abs(((a >>> shift) & 0xff) - ((b >>> shift) & 0xff)) > limit
  }

  // A light grayscale version of the pixel, so red highlights stand out.
  private def faded(argb: Int): Int =
    val r    = (argb >>> 16) & 0xff
    val g    = (argb >>> 8) & 0xff
    val b    = argb & 0xff
    val gray = (r * 299 + g * 587 + b * 114) / 1000
    val v    = 255 - (255 - gray) / 4
    0xff000000 | (v << 16) | (v << 8) | v

  private def decode(png: Array[Byte]): BufferedImage =
    val img = ImageIO.read(ByteArrayInputStream(png))
    if img == null then
      throw IOException("Not a decodable image")
    img

  private def encode(img: BufferedImage): Array[Byte] =
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    out.toByteArray

end ImageDiff
