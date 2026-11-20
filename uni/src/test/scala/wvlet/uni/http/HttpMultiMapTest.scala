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

import wvlet.uni.test.UniTest

class HttpMultiMapTest extends UniTest:

  test("should create empty map") {
    val map = HttpMultiMap.empty
    map.isEmpty shouldBe true
    map.size shouldBe 0
  }

  test("should create from varargs") {
    val map = HttpMultiMap("Content-Type" -> "application/json", "Accept" -> "text/html")
    map.size shouldBe 2
    map.get("Content-Type") shouldBe Some("application/json")
  }

  test("should be case-insensitive for get") {
    val map = HttpMultiMap("Content-Type" -> "application/json")
    map.get("content-type") shouldBe Some("application/json")
    map.get("CONTENT-TYPE") shouldBe Some("application/json")
    map.get("Content-Type") shouldBe Some("application/json")
  }

  test("should be case-insensitive for contains") {
    val map = HttpMultiMap("Content-Type" -> "application/json")
    map.contains("content-type") shouldBe true
    map.contains("CONTENT-TYPE") shouldBe true
    map.contains("Content-Type") shouldBe true
  }

  test("should add multiple values for same key") {
    val map = HttpMultiMap
      .empty
      .add("Set-Cookie", "cookie1=value1")
      .add("Set-Cookie", "cookie2=value2")
    map.get("Set-Cookie") shouldBe Some("cookie1=value1")
    map.getAll("Set-Cookie") shouldBe Seq("cookie1=value1", "cookie2=value2")
  }

  test("should set replaces existing values") {
    val map = HttpMultiMap
      .empty
      .add("Accept", "text/html")
      .add("Accept", "application/json")
      .set("Accept", "text/plain")
    map.getAll("Accept") shouldBe Seq("text/plain")
  }

  test("should remove key case-insensitively") {
    val map = HttpMultiMap("Content-Type" -> "application/json", "Accept" -> "text/html").remove(
      "content-type"
    )
    map.contains("Content-Type") shouldBe false
    map.contains("Accept") shouldBe true
  }

  test("should preserve original key casing in entries") {
    val map = HttpMultiMap
      .empty
      .add("Content-Type", "application/json")
      .add("X-Custom-Header", "value")
    val entries = map.entries
    entries.exists(_._1 == "Content-Type") shouldBe true
    entries.exists(_._1 == "X-Custom-Header") shouldBe true
  }

  test("should support + operator") {
    val map = HttpMultiMap.empty + ("Content-Type" -> "application/json")
    map.get("Content-Type") shouldBe Some("application/json")
  }

  test("should support ++ operator") {
    val map1     = HttpMultiMap("Content-Type" -> "application/json")
    val map2     = HttpMultiMap("Accept" -> "text/html")
    val combined = map1 ++ map2
    combined.get("Content-Type") shouldBe Some("application/json")
    combined.get("Accept") shouldBe Some("text/html")
  }

  test("should return default with getOrElse") {
    val map = HttpMultiMap.empty
    map.getOrElse("Missing", "default") shouldBe "default"
  }

  test("should create from seq") {
    val map = HttpMultiMap.fromSeq(Seq("A" -> "1", "B" -> "2", "A" -> "3"))
    map.getAll("A") shouldBe Seq("1", "3")
    map.get("B") shouldBe Some("2")
  }

  test("should create from map") {
    val map = HttpMultiMap.fromMap(Map("A" -> "1", "B" -> "2"))
    map.get("A") shouldBe Some("1")
    map.get("B") shouldBe Some("2")
  }

  test("should create from multi-map") {
    val map = HttpMultiMap.fromMultiMap(Map("A" -> Seq("1", "2"), "B" -> Seq("3")))
    map.getAll("A") shouldBe Seq("1", "2")
    map.getAll("B") shouldBe Seq("3")
  }

  test("should use builder pattern") {
    val map = HttpMultiMap
      .newBuilder
      .add("Content-Type", "application/json")
      .add("Accept", "text/html")
      .result()
    map.size shouldBe 2
    map.get("Content-Type") shouldBe Some("application/json")
  }

  test("should return empty seq for missing key in getAll") {
    val map = HttpMultiMap.empty
    map.getAll("Missing") shouldBe Seq.empty
  }

  test("should convert to raw map") {
    val map = HttpMultiMap("Content-Type" -> "application/json")
    val raw = map.toMap
    raw.get("content-type") shouldBe Some(Seq("application/json"))
  }

  test("should provide keys in original casing") {
    val map  = HttpMultiMap("Content-Type" -> "application/json", "X-Custom" -> "value")
    val keys = map.keys.toSet
    keys.contains("Content-Type") shouldBe true
    keys.contains("X-Custom") shouldBe true
  }

  test("a builder gives the same map as repeated add") {
    val pairs = Seq(
      "Host"           -> "a",
      "content-type"   -> "text/plain",
      "X-Multi"        -> "1",
      "Accept"         -> "*/*",
      "x-multi"        -> "2",
      "User-Agent"     -> "t",
      "X-MULTI"        -> "3",
      "Content-Length" -> "0"
    )
    val b = HttpMultiMap.newBuilder
    pairs.foreach((k, v) => b.add(k, v))
    val built = b.result()
    val added = pairs.foldLeft(HttpMultiMap.empty)((m, kv) => m.add(kv._1, kv._2))
    built.entries shouldBe added.entries
    built.toMap shouldBe added.toMap
    built.keys.toSeq shouldBe added.keys.toSeq
    // The first spelling of a name is kept, and values keep their order
    built.getAll("x-multi") shouldBe Seq("1", "2", "3")
    built.entries.filter(_._1.equalsIgnoreCase("x-multi")).map(_._1).distinct shouldBe
      Seq("X-Multi")
  }

  test("a builder keeps the insertion order of a few keys") {
    val b = HttpMultiMap.newBuilder
    b.add("B", "1").add("A", "2").add("C", "3")
    b.result().entries shouldBe Seq("B" -> "1", "A" -> "2", "C" -> "3")
  }

  test("entries and keys keep insertion order past a few keys") {
    val names = Seq("Zeta", "Alpha", "Mu", "Beta", "Omega", "Gamma", "Kappa")
    val map   = HttpMultiMap.fromSeq(names.map(n => n -> n.toLowerCase))
    map.keys.toSeq shouldBe names
    map.entries.map(_._1) shouldBe names
    map.add("Delta", "d").keys.toSeq shouldBe names :+ "Delta"
    map.remove("mu").keys.toSeq shouldBe names.filterNot(_ == "Mu")
    map.set("ALPHA", "x").entries.map(_._2) shouldBe names.map(_.toLowerCase).updated(1, "x")
  }

  test("maps with many keys look up, add, set, and remove through their index") {
    val n     = HttpMultiMap.IndexThreshold * 4
    val pairs = (0 until n).map(i => s"X-Key-${i}" -> s"v${i}")
    val b     = HttpMultiMap.newBuilder
    pairs.foreach((k, v) => b.add(k, v))
    b.add("x-key-3", "again")
    val built = b.result()
    built.size shouldBe n
    built.getAll("X-KEY-3") shouldBe Seq("v3", "again")
    built.get(s"x-key-${n - 1}") shouldBe Some(s"v${n - 1}")
    built.contains("x-key-missing") shouldBe false
    built.keys.toSeq shouldBe pairs.map(_._1)
    val added = pairs.foldLeft(HttpMultiMap.empty)((m, kv) => m.add(kv._1, kv._2))
    added.get("x-key-40") shouldBe Some("v40")
    added.remove("X-Key-40").contains("x-key-40") shouldBe false
    added.remove("X-Key-40").get("x-key-41") shouldBe Some("v41")
    added.set("x-key-0", "zero").getAll("X-Key-0") shouldBe Seq("zero")
  }

  test("adding to a builder after result leaves the built map unchanged") {
    val b = HttpMultiMap.newBuilder
    b.add("A", "1")
    val first = b.result()
    b.add("A", "2").add("B", "3")
    val second = b.result()
    first.entries shouldBe Seq("A" -> "1")
    second.entries shouldBe Seq("A" -> "1", "A" -> "2", "B" -> "3")
  }

  test("++ adds the other map's entries in order") {
    val a = HttpMultiMap("A" -> "1", "B" -> "2")
    val b = HttpMultiMap("b" -> "3", "C" -> "4")
    (a ++ b).entries shouldBe Seq("A" -> "1", "B" -> "2", "B" -> "3", "C" -> "4")
    (HttpMultiMap.empty ++ b).entries shouldBe b.entries
    (a ++ HttpMultiMap.empty).entries shouldBe a.entries
  }

  test("lookups lower-case ASCII and non-ASCII names") {
    HttpMultiMap.lower("content-type") shouldBe "content-type"
    HttpMultiMap.lower(HttpHeader.ContentType) shouldBe "content-type"
    HttpMultiMap.lower("X-Custom-Header") shouldBe "x-custom-header"
    HttpMultiMap.lower("X-Ünïcode") shouldBe "x-ünïcode"
    val map = HttpMultiMap("X-Ünïcode" -> "v", "X-Custom-Header" -> "w")
    map.get("x-ÜNÏCODE") shouldBe Some("v")
    map.get("x-custom-HEADER") shouldBe Some("w")
  }

end HttpMultiMapTest
