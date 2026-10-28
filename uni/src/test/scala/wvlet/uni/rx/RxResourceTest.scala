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
package wvlet.uni.rx

import wvlet.uni.test.UniTest

class RxResourceTest extends UniTest:

  test("use releases the resource after the body succeeds") {
    var released = false
    for key <- RxResource
        .make(Rx.single("key"))(_ =>
          Rx.single {
            released = true
          }
        )
        .use { k =>
          Rx.single(k)
        }
    yield
      key shouldBe "key"
      released shouldBe true
  }

  test("use releases the resource even when the body fails") {
    var released = false
    for error <- RxResource
        .make(Rx.single("key"))(_ =>
          Rx.single {
            released = true
          }
        )
        .use { _ =>
          Rx.single(throw RuntimeException("boom"))
        }
        .recover { case e: RuntimeException =>
          e
        }
    yield
      error.getMessage shouldBe "boom"
      released shouldBe true
  }

  test("a failure in the release is attached to the body error as suppressed") {
    for error <- RxResource
        .make(Rx.single("key"))(_ =>
          Rx.single {
            throw RuntimeException("close failed")
          }
        )
        .use { _ =>
          Rx.single(throw RuntimeException("body failed"))
        }
        .recover { case e: RuntimeException =>
          e
        }
    yield
      error.getMessage shouldBe "body failed"
      error.getSuppressed.map(_.getMessage) shouldContain "close failed"
  }

  test("onFinalize runs in addition to the release") {
    var released  = false
    var finalized = false
    for key <- RxResource
        .make(Rx.single("key"))(_ =>
          Rx.single {
            released = true
          }
        )
        .onFinalize(
          Rx.single {
            finalized = true
          }
        )
        .use { k =>
          Rx.single(k)
        }
    yield
      key shouldBe "key"
      released shouldBe true
      finalized shouldBe true
  }

end RxResourceTest
