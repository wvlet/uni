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

import wvlet.uni.reflect.annotation.EnableReflectiveInstantiation
import wvlet.uni.test.UniTest

object ReflectTestFixtures:

  @EnableReflectiveInstantiation
  trait Plugin:
    def name: String

  @EnableReflectiveInstantiation
  object EnabledObject extends Plugin:
    def name: String           = "enabled"
    val tag: String            = "ok"
    def greet(s: String): Unit = ()

  // Inherits annotation from Plugin
  object EnabledViaTrait extends Plugin:
    def name: String = "via-trait"

  // Not annotated; portable Reflect must reject this
  object DisabledObject:
    def name: String = "disabled"

  @EnableReflectiveInstantiation
  class EnabledClass:
    def hello: String = "hello"

  @EnableReflectiveInstantiation
  class EnabledClassWithArgs(val n: Int)

  // Not annotated
  class DisabledClass

  // Class with an explicit companion (used by companionOf tests)
  @EnableReflectiveInstantiation
  class ClassWithCompanion

  @EnableReflectiveInstantiation
  object ClassWithCompanion:
    val tag: String = "companion"

  // Class without a companion
  class ClassWithoutCompanion

end ReflectTestFixtures

class ReflectTest extends UniTest:
  import ReflectTestFixtures.*

  private val Prefix = "wvlet.uni.reflect.ReflectTestFixtures$"

  test("lookupLoadableModuleClass finds an annotated object") {
    val opt = Reflect.lookupLoadableModuleClass(s"${Prefix}EnabledObject$$")
    opt.isDefined shouldBe true
    opt.get.loadModule() shouldBe EnabledObject
  }

  test("lookupLoadableModuleClass finds objects that inherit the annotation") {
    val opt = Reflect.lookupLoadableModuleClass(s"${Prefix}EnabledViaTrait$$")
    opt.isDefined shouldBe true
    opt.get.loadModule() shouldBe EnabledViaTrait
  }

  test("lookupLoadableModuleClass returns None for non-annotated objects") {
    Reflect.lookupLoadableModuleClass(s"${Prefix}DisabledObject$$") shouldBe None
  }

  test("lookupLoadableModuleClass returns None for missing classes") {
    Reflect.lookupLoadableModuleClass(s"${Prefix}DoesNotExist$$") shouldBe None
  }

  test("lookupInstantiatableClass finds an annotated class") {
    val opt = Reflect.lookupInstantiatableClass(s"${Prefix}EnabledClass")
    opt.isDefined shouldBe true
    val instance = opt.get.newInstance().asInstanceOf[EnabledClass]
    instance.hello shouldBe "hello"
  }

  test("lookupInstantiatableClass returns None for non-annotated classes") {
    Reflect.lookupInstantiatableClass(s"${Prefix}DisabledClass") shouldBe None
  }

  test("lookupInstantiatableClass exposes a constructor with arguments") {
    val opt = Reflect.lookupInstantiatableClass(s"${Prefix}EnabledClassWithArgs")
    opt.isDefined shouldBe true
    val ctor = opt.get.getConstructor(classOf[Int])
    ctor.isDefined shouldBe true
    val instance = ctor.get.newInstance(Int.box(42)).asInstanceOf[EnabledClassWithArgs]
    instance.n shouldBe 42
  }

  test("companionOf returns the singleton when the class has a companion object") {
    Reflect.companionOf(classOf[ClassWithCompanion]) shouldBe Some(ClassWithCompanion)
  }

  test("companionOf returns Some(self) when called on a module's $ class") {
    val cls = EnabledObject.getClass
    Reflect.companionOf(cls) shouldBe Some(EnabledObject)
  }

  test("companionOf returns None when no companion exists") {
    Reflect.companionOf(classOf[ClassWithoutCompanion]) shouldBe None
  }

end ReflectTest
