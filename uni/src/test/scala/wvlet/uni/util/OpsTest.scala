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

import wvlet.uni.test.UniTest
import wvlet.uni.util.ops.*

class OpsTest extends UniTest:

  test("pipe applies a function and returns its result") {
    1.pipe(_ + 1) shouldBe 2
    "abc".pipe(_.length) shouldBe 3
  }

  test("ifDefined applies the function only when the option is defined") {
    "v".ifDefined(Some(1))((s, n) => s * n) shouldBe "v"
    "v".ifDefined(Some(3))((s, n) => s * n) shouldBe "vvv"
    "v".ifDefined(None: Option[Int])((s, n) => s * n) shouldBe "v"
  }

  test("when runs the partial function only on a match") {
    var matched = ""
    (42: Any).when { case i: Int =>
      matched = s"int:${i}"
    }
    matched shouldBe "int:42"

    matched = ""
    ("str": Any).when { case i: Int =>
      matched = s"int:${i}"
    }
    matched shouldBe ""
  }

  test("Seq.when runs the partial function on each matching element") {
    val collected = Seq.newBuilder[Int]
    Seq(1, "a", 2, "b", 3).when { case i: Int =>
      collected += i
    }
    collected.result() shouldBe Seq(1, 2, 3)
  }

end OpsTest
