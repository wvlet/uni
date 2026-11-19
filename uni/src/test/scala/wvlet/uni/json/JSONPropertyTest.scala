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
package wvlet.uni.json

import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest
import wvlet.uni.test.check.Arbitrary
import wvlet.uni.test.check.Gen

class JSONPropertyTest extends UniTest:

  private val genString: Gen[String] = Arbitrary.arbitrary[String]

  // JSON has no NaN or Infinity, and whole-number doubles print as integers on Scala.js
  // ("1.0".toString == "1"), which parse back as JSONLong. Both are limits of the text format,
  // so the generator emits only finite doubles with a fractional part.
  private val genDouble: Gen[Double] = Arbitrary
    .arbitrary[Double]
    .filter(d => !d.isInfinite && !d.isNaN && d != d.floor)

  private val genScalar: Gen[JSONValue] = Gen.frequency(
    1 -> Gen.const(JSONNull()),
    1 -> Gen.oneOf(JSONTrue, JSONFalse),
    3 -> Arbitrary.arbitrary[Long].map(JSONLong(_)),
    2 -> genDouble.map(JSONDouble(_)),
    3 -> genString.map(JSONString(_))
  )

  private def genValue(depth: Int): Gen[JSONValue] =
    if depth <= 0 then
      genScalar
    else
      Gen.frequency(
        3 -> genScalar,
        1 -> Gen.resize(5, Gen.listOf(genValue(depth - 1))).map(xs => JSONArray(xs.toIndexedSeq)),
        1 ->
          Gen.resize(5, Gen.listOf(genString.zip(genValue(depth - 1)))).map(kvs => JSONObject(kvs))
      )

  private val genJSON: Gen[JSONValue] = genValue(3)

  test("parseAny(v.toJSON) restores the value") {
    forAll(genJSON) { v =>
      JSON.parseAny(v.toJSON) shouldBe v
    }
  }

  test("parseAny(format(v)) restores the value") {
    forAll(genJSON) { v =>
      JSON.parseAny(JSON.format(v)) shouldBe v
    }
  }

  test("quoted strings survive arbitrary Unicode and control characters") {
    forAll { (s: String) =>
      JSON.parseAny(JSONString(s).toJSON) shouldBe JSONString(s)
      quoteJSONString(s).exists(c => c < 0x20) shouldBe false
    }
  }

  test("parse(v.toJSON) == v for objects and arrays") {
    forAll(genJSON) { v =>
      v match
        case _: JSONObject | _: JSONArray =>
          JSON.parse(v.toJSON) shouldBe v
        case _ =>
    }
  }

end JSONPropertyTest
