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
package wvlet.uni.weaver

import wvlet.uni.surface.Surface
import wvlet.uni.test.UniTest

case class SimpleUser(id: Long, name: String) derives Weaver
case class UserWithOption(id: Long, name: String, email: Option[String]) derives Weaver
case class UserWithList(id: Long, tags: List[String]) derives Weaver
case class NestedUser(id: Long, friend: SimpleUser) derives Weaver

class WeaverFromSurfaceTest extends UniTest:

  test("fromSurface for primitive Int") {
    val surface = Surface.of[Int]
    val weaver  = Weaver.fromSurface(surface)
    val json    = weaver.asInstanceOf[Weaver[Any]].toJson(42)
    json shouldBe "42"
    val result = weaver.asInstanceOf[Weaver[Any]].fromJson("42")
    result shouldBe 42
  }

  test("fromSurface for primitive Long") {
    val surface = Surface.of[Long]
    val weaver  = Weaver.fromSurface(surface)
    val json    = weaver.asInstanceOf[Weaver[Any]].toJson(123456789L)
    json shouldBe "123456789"
    val result = weaver.asInstanceOf[Weaver[Any]].fromJson("123456789")
    result shouldBe 123456789L
  }

  test("fromSurface for primitive String") {
    val surface = Surface.of[String]
    val weaver  = Weaver.fromSurface(surface)
    val json    = weaver.asInstanceOf[Weaver[Any]].toJson("hello")
    json shouldBe "\"hello\""
    val result = weaver.asInstanceOf[Weaver[Any]].fromJson("\"hello\"")
    result shouldBe "hello"
  }

  test("fromSurface for primitive Boolean") {
    val surface = Surface.of[Boolean]
    val weaver  = Weaver.fromSurface(surface)
    val json    = weaver.asInstanceOf[Weaver[Any]].toJson(true)
    json shouldBe "true"
    val result = weaver.asInstanceOf[Weaver[Any]].fromJson("true")
    result shouldBe true
  }

  test("fromSurface for Option[String]") {
    val surface = Surface.of[Option[String]]
    val weaver  = Weaver.fromSurface(surface)

    val json1 = weaver.asInstanceOf[Weaver[Any]].toJson(Some("hello"))
    json1 shouldBe "\"hello\""

    val json2 = weaver.asInstanceOf[Weaver[Any]].toJson(None)
    json2 shouldBe "null"

    val result1 = weaver.asInstanceOf[Weaver[Any]].fromJson("\"hello\"")
    result1 shouldBe Some("hello")

    val result2 = weaver.asInstanceOf[Weaver[Any]].fromJson("null")
    result2 shouldBe None
  }

  test("fromSurface for Seq[Int]") {
    val surface = Surface.of[Seq[Int]]
    val weaver  = Weaver.fromSurface(surface)

    val json = weaver.asInstanceOf[Weaver[Any]].toJson(Seq(1, 2, 3))
    json shouldBe "[1,2,3]"

    val result = weaver.asInstanceOf[Weaver[Any]].fromJson("[1,2,3]")
    result shouldBe Seq(1, 2, 3)
  }

  test("fromSurface for List[String]") {
    val surface = Surface.of[List[String]]
    val weaver  = Weaver.fromSurface(surface)

    val json = weaver.asInstanceOf[Weaver[Any]].toJson(List("a", "b", "c"))
    json shouldBe "[\"a\",\"b\",\"c\"]"

    val result = weaver.asInstanceOf[Weaver[Any]].fromJson("[\"a\",\"b\",\"c\"]")
    result shouldBe List("a", "b", "c")
  }

  test("fromSurface for Map[String, Int]") {
    val surface = Surface.of[Map[String, Int]]
    val weaver  = Weaver.fromSurface(surface)

    val json   = weaver.asInstanceOf[Weaver[Any]].toJson(Map("a" -> 1, "b" -> 2))
    val result = weaver.asInstanceOf[Weaver[Any]].fromJson(json)
    result shouldBe Map("a" -> 1, "b" -> 2)
  }

  test("fromSurface for case class") {
    val surface = Surface.of[SimpleUser]
    val weaver  = Weaver.fromSurface(surface)

    val user = SimpleUser(1, "Alice")
    val json = weaver.asInstanceOf[Weaver[Any]].toJson(user)
    json shouldContain "\"id\":1"
    json shouldContain "\"name\":\"Alice\""

    val result = weaver.asInstanceOf[Weaver[Any]].fromJson(json)
    result shouldBe user
  }

  test("fromSurface for case class with Option") {
    val surface = Surface.of[UserWithOption]
    val weaver  = Weaver.fromSurface(surface)

    val user1   = UserWithOption(1, "Alice", Some("alice@example.com"))
    val json1   = weaver.asInstanceOf[Weaver[Any]].toJson(user1)
    val result1 = weaver.asInstanceOf[Weaver[Any]].fromJson(json1)
    result1 shouldBe user1

    val user2   = UserWithOption(2, "Bob", None)
    val json2   = weaver.asInstanceOf[Weaver[Any]].toJson(user2)
    val result2 = weaver.asInstanceOf[Weaver[Any]].fromJson(json2)
    result2 shouldBe user2
  }

  test("fromSurface for case class with List") {
    val surface = Surface.of[UserWithList]
    val weaver  = Weaver.fromSurface(surface)

    val user   = UserWithList(1, List("scala", "java"))
    val json   = weaver.asInstanceOf[Weaver[Any]].toJson(user)
    val result = weaver.asInstanceOf[Weaver[Any]].fromJson(json)
    result shouldBe user
  }

  test("fromSurface for nested case class") {
    val surface = Surface.of[NestedUser]
    val weaver  = Weaver.fromSurface(surface)

    val user   = NestedUser(1, SimpleUser(2, "Friend"))
    val json   = weaver.asInstanceOf[Weaver[Any]].toJson(user)
    val result = weaver.asInstanceOf[Weaver[Any]].fromJson(json)
    result shouldBe user
  }

  test("fromSurface caches weavers") {
    val surface1 = Surface.of[Int]
    val surface2 = Surface.of[Int]

    val weaver1 = Weaver.fromSurface(surface1)
    val weaver2 = Weaver.fromSurface(surface2)

    // Same surface should return same (cached) weaver
    (weaver1 eq weaver2) shouldBe true
  }

  test("fromSurface for Unit") {
    val surface = Surface.of[Unit]
    val weaver  = Weaver.fromSurface(surface)
    val json    = weaver.asInstanceOf[Weaver[Any]].toJson(())
    json shouldBe "null"
    val result = weaver.asInstanceOf[Weaver[Any]].fromJson("null")
    result shouldBe ()
  }

  test("fromSurface for java.util.List") {
    val surface = Surface.of[java.util.List[String]]
    val weaver  = Weaver.fromSurface(surface)

    val list = java.util.Arrays.asList("a", "b", "c")
    val json = weaver.asInstanceOf[Weaver[Any]].toJson(list)
    json shouldBe "[\"a\",\"b\",\"c\"]"

    val result = weaver.asInstanceOf[Weaver[Any]].fromJson("[\"a\",\"b\",\"c\"]")
    result shouldMatch { case l: java.util.List[?] =>
      l.size() shouldBe 3
      l.get(0) shouldBe "a"
    }
  }

  test("fromSurface for java.util.Map") {
    val surface = Surface.of[java.util.Map[String, Int]]
    val weaver  = Weaver.fromSurface(surface)

    val map = new java.util.HashMap[String, Int]()
    map.put("a", 1)
    map.put("b", 2)
    val json   = weaver.asInstanceOf[Weaver[Any]].toJson(map)
    val result = weaver.asInstanceOf[Weaver[Any]].fromJson(json)
    result shouldMatch { case m: java.util.Map[?, ?] =>
      m.size() shouldBe 2
      m.get("a") shouldBe 1
      m.get("b") shouldBe 2
    }
  }

  test("fromSurface for java.util.Set") {
    val surface = Surface.of[java.util.Set[String]]
    val weaver  = Weaver.fromSurface(surface)

    val set = new java.util.HashSet[String]()
    set.add("a")
    set.add("b")
    val json = weaver.asInstanceOf[Weaver[Any]].toJson(set)
    json shouldContain "\"a\""
    json shouldContain "\"b\""

    val result = weaver.asInstanceOf[Weaver[Any]].fromJson("[\"a\",\"b\"]")
    result shouldMatch { case s: java.util.Set[?] =>
      s.size() shouldBe 2
      s.contains("a") shouldBe true
      s.contains("b") shouldBe true
    }
  }

end WeaverFromSurfaceTest
