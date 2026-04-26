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
package wvlet.uni.reflect

import wvlet.uni.test.UniTest

object ReflectJvmTestFixtures:
  // Intentionally NOT annotated — companionOfUnchecked must still find the companion on JVM.
  class UnannotatedClass
  object UnannotatedClass:
    val tag: String = "found"

  class UnannotatedClassWithoutCompanion

end ReflectJvmTestFixtures

class ReflectJvmTest extends UniTest:
  import ReflectJvmTestFixtures.*

  test("companionOfUnchecked finds companion without @EnableReflectiveInstantiation") {
    Reflect.companionOfUnchecked(classOf[UnannotatedClass]) shouldBe Some(UnannotatedClass)
  }

  test("companionOfUnchecked returns None when no companion exists") {
    Reflect.companionOfUnchecked(classOf[UnannotatedClassWithoutCompanion]) shouldBe None
  }

  test("companionOf returns None for unannotated companions") {
    // Strict portable companionOf needs the annotation; unannotated companions are not visible.
    Reflect.companionOf(classOf[UnannotatedClass]) shouldBe None
  }

end ReflectJvmTest
