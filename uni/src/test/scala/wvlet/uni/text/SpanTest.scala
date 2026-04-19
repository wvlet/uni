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
package wvlet.uni.text

import wvlet.uni.test.UniTest

class SpanTest extends UniTest:

  test("encode and decode start/end/point") {
    val s = Span(10, 25, 3)
    s.start shouldBe 10
    s.end shouldBe 25
    s.pointOffset shouldBe 3
    s.point shouldBe 13
    s.size shouldBe 15
  }

  test("within creates a span with point at start") {
    val s = Span.within(4, 9)
    s.start shouldBe 4
    s.end shouldBe 9
    s.point shouldBe 4
    s.exists shouldBe true
  }

  test("at creates an empty span at a single offset") {
    val s = Span.at(7)
    s.start shouldBe 7
    s.end shouldBe 7
    s.size shouldBe 0
  }

  test("NoSpan reports itself as missing") {
    Span.NoSpan.exists shouldBe false
    Span.NoSpan.isEmpty shouldBe true
    Span.NoSpan.nonEmpty shouldBe false
    Span.NoSpan.toString shouldBe "[NoSpan]"
  }

  test("contains checks") {
    val s = Span.within(5, 15)
    s.contains(5) shouldBe true
    s.contains(14) shouldBe true
    s.contains(15) shouldBe false
    s.containsInclusive(15) shouldBe true
    s.contains(Span.within(7, 12)) shouldBe true
    s.contains(Span.within(4, 12)) shouldBe false
  }

  test("precedes and follows") {
    val s = Span.within(10, 20)
    s.precedes(20) shouldBe true
    s.precedes(19) shouldBe false
    s.follows(10) shouldBe true
    s.follows(11) shouldBe false
  }

  test("withStart adjusts the start and recomputes point") {
    val s  = Span(10, 25, 5) // point at 15
    val s2 = s.withStart(12)
    s2.start shouldBe 12
    s2.end shouldBe 25
    s2.point shouldBe 15
  }

  test("withEnd preserves the pointOffset") {
    val s  = Span(10, 25, 5)
    val s2 = s.withEnd(40)
    s2.start shouldBe 10
    s2.end shouldBe 40
    s2.point shouldBe 15
  }

  test("extendTo grows to cover the later end") {
    val a = Span.within(5, 10)
    val b = Span.within(8, 20)
    a.extendTo(b).end shouldBe 20
    b.extendTo(a).end shouldBe 20
  }

  test("extendTo falls back to the non-empty span") {
    val a = Span.within(3, 7)
    Span.NoSpan.extendTo(a) shouldBe a
    a.extendTo(Span.NoSpan) shouldBe a
  }

  test("map applies f when the span exists") {
    Span.within(2, 6).map(_.size) shouldBe Some(4)
    Span.NoSpan.map(_.size) shouldBe None
  }

  test("toString formats with point marker when point != start") {
    Span(10, 20, 3).toString shouldBe "[10..13..20)"
    Span(10, 20, 0).toString shouldBe "[10..20)"
  }

end SpanTest
