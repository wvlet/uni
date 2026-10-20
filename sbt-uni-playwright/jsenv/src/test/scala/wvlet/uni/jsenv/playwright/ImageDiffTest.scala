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
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageDiffTest extends munit.FunSuite:

  private def png(w: Int, h: Int)(color: (Int, Int) => Int): Array[Byte] =
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    for
      y <- 0 until h
      x <- 0 until w
    do
      img.setRGB(x, y, color(x, y))
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    out.toByteArray

  private val blue = png(10, 10)((_, _) => 0xff0000ff)

  test("identical images match"):
    val r = ImageDiff.compare(blue, blue, threshold = 0.1)
    assertEquals(r.diffPixels, 0L)
    assert(r.isMatch(0.0))

  test("counts differing pixels and honors maxDiffPixelRatio"):
    val oneRed =
      png(10, 10)((x, y) =>
        if x == 0 && y == 0 then
          0xffff0000
        else
          0xff0000ff
      )
    val r = ImageDiff.compare(blue, oneRed, threshold = 0.1)
    assertEquals(r.diffPixels, 1L)
    assert(!r.isMatch(0.0))
    assert(r.isMatch(0.01))
    assert(r.diffImage.isDefined)

  test("small channel differences within threshold match"):
    val nearBlue = png(10, 10)((_, _) => 0xff0000f0)
    assert(ImageDiff.compare(blue, nearBlue, threshold = 0.1).isMatch(0.0))
    assert(!ImageDiff.compare(blue, nearBlue, threshold = 0.0).isMatch(0.0))

  test("size mismatch never matches"):
    val r = ImageDiff.compare(blue, png(10, 11)((_, _) => 0xff0000ff), threshold = 0.1)
    assert(r.sizeMismatch)
    assert(!r.isMatch(1.0))

end ImageDiffTest
