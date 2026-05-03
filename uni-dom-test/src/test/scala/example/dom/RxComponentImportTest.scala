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
package example.dom

import wvlet.uni.test.UniTest
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import scala.language.implicitConversions

/**
  * Pins the airframe-rx-html migration shape for `RxComponent`: the migration code uses
  * `import wvlet.uni.dom.all.*` and writes `object MyFrame extends RxComponent` directly. This test
  * must compile *and* run from a sibling package — a regression where `RxComponent` is no longer
  * re-exported from `all` would surface as a compile error here, not a missing-symbol error
  * reachable only from inside `package wvlet.uni.dom` via package visibility. See #527.
  */
class RxComponentImportTest extends UniTest:

  test("RxComponent reachable through `import wvlet.uni.dom.all.*`"):
    object Frame extends RxComponent:
      override def render(content: RxElement): RxElement = div(cls -> "frame", content)

    val wrapped = Frame(span("inner"))
    wrapped shouldMatch { case _: RxElement =>
    }

  test("standalone Frame() reachable through `import wvlet.uni.dom.all.*`"):
    object Frame extends RxComponent:
      override def render(content: RxElement): RxElement = div(cls -> "frame", content)

    Frame() shouldMatch { case _: RxElement =>
    }

end RxComponentImportTest
