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
package wvlet.uni.design

import wvlet.uni.test.UniTest

class Greeter:
  def greet(name: String): String = s"hello ${name}"

/**
  * A suite outside the wvlet.uni.test package, so that the `test` macro is expanded where users
  * write it.
  */
class DesignTestInjectionTest extends UniTest:
  addDesign(_.bindSingleton[Greeter])

  test("injects from another package") { (g: Greeter) =>
    g.greet("uni") shouldBe "hello uni"
  }
