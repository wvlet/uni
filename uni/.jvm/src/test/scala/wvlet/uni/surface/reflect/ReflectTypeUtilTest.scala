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
package wvlet.uni.surface.reflect

import wvlet.uni.test.UniTest

object ReflectTypeUtilTestFixtures:
  class HasCompanion
  object HasCompanion:
    val tag: String = "ok"

  class NoCompanion

class ReflectTypeUtilTest extends UniTest:

  test("companionObject returns the singleton companion when present") {
    import ReflectTypeUtilTestFixtures.*
    val companion = ReflectTypeUtil.companionObject(classOf[HasCompanion])
    companion.isDefined shouldBe true
    companion.get shouldBe HasCompanion
    companion.get.asInstanceOf[HasCompanion.type].tag shouldBe "ok"
  }

  test("companionObject returns None when no companion exists") {
    import ReflectTypeUtilTestFixtures.*
    ReflectTypeUtil.companionObject(classOf[NoCompanion]) shouldBe None
  }

  test("companionObject is idempotent on companion classes") {
    import ReflectTypeUtilTestFixtures.*
    val companionCls = HasCompanion.getClass
    val companion    = ReflectTypeUtil.companionObject(companionCls)
    companion.isDefined shouldBe true
    companion.get shouldBe HasCompanion
  }

end ReflectTypeUtilTest
