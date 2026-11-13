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

class HttpStatusTest extends UniTest:

  test("should have correct status codes") {
    HttpStatus.Ok_200.code shouldBe 200
    HttpStatus.Created_201.code shouldBe 201
    HttpStatus.BadRequest_400.code shouldBe 400
    HttpStatus.NotFound_404.code shouldBe 404
    HttpStatus.InternalServerError_500.code shouldBe 500
  }

  test("should have correct reason phrases") {
    HttpStatus.Ok_200.reason shouldBe "OK"
    HttpStatus.NotFound_404.reason shouldBe "Not Found"
    HttpStatus.InternalServerError_500.reason shouldBe "Internal Server Error"
  }

  test("should classify informational status codes") {
    HttpStatus.Continue_100.isInformational shouldBe true
    HttpStatus.SwitchingProtocols_101.isInformational shouldBe true
    HttpStatus.Ok_200.isInformational shouldBe false
  }

  test("should classify successful status codes") {
    HttpStatus.Ok_200.isSuccessful shouldBe true
    HttpStatus.Created_201.isSuccessful shouldBe true
    HttpStatus.NoContent_204.isSuccessful shouldBe true
    HttpStatus.MovedPermanently_301.isSuccessful shouldBe false
  }

  test("should classify redirection status codes") {
    HttpStatus.MovedPermanently_301.isRedirection shouldBe true
    HttpStatus.Found_302.isRedirection shouldBe true
    HttpStatus.TemporaryRedirect_307.isRedirection shouldBe true
    HttpStatus.Ok_200.isRedirection shouldBe false
  }

  test("should classify client error status codes") {
    HttpStatus.BadRequest_400.isClientError shouldBe true
    HttpStatus.Unauthorized_401.isClientError shouldBe true
    HttpStatus.NotFound_404.isClientError shouldBe true
    HttpStatus.Ok_200.isClientError shouldBe false
  }

  test("should classify server error status codes") {
    HttpStatus.InternalServerError_500.isServerError shouldBe true
    HttpStatus.BadGateway_502.isServerError shouldBe true
    HttpStatus.ServiceUnavailable_503.isServerError shouldBe true
    HttpStatus.NotFound_404.isServerError shouldBe false
  }

  test("should identify retryable status codes") {
    HttpStatus.ServiceUnavailable_503.isRetryable shouldBe true
    HttpStatus.GatewayTimeout_504.isRetryable shouldBe true
    HttpStatus.TooManyRequests_429.isRetryable shouldBe true
    HttpStatus.RequestTimeout_408.isRetryable shouldBe true
    HttpStatus.Ok_200.isRetryable shouldBe false
    HttpStatus.NotFound_404.isRetryable shouldBe false
  }

  test("should lookup status by code") {
    HttpStatus.ofCode(200) shouldBe HttpStatus.Ok_200
    HttpStatus.ofCode(404) shouldBe HttpStatus.NotFound_404
    HttpStatus.ofCode(500) shouldBe HttpStatus.InternalServerError_500
  }

  test("should handle unknown status codes") {
    val other = HttpStatus.ofCode(999)
    other shouldMatch { case HttpStatus.Other(999) =>
    }
    other.code shouldBe 999
    other.reason shouldBe "Unknown (999)"
  }

  test("should provide category context for Other status codes") {
    HttpStatus.ofCode(50).reason shouldBe "Unknown (50)"
    HttpStatus.ofCode(150).reason shouldBe "Unknown 1xx (150)"
    HttpStatus.ofCode(250).reason shouldBe "Unknown 2xx (250)"
    HttpStatus.ofCode(350).reason shouldBe "Unknown 3xx (350)"
    HttpStatus.ofCode(450).reason shouldBe "Unknown 4xx (450)"
    HttpStatus.ofCode(550).reason shouldBe "Unknown 5xx (550)"
    HttpStatus.ofCode(650).reason shouldBe "Unknown (650)"
  }

  test("should detect unknown state codes") {
    HttpStatus.ofCode(50).isUnknownState shouldBe true
    HttpStatus.ofCode(99).isUnknownState shouldBe true
    HttpStatus.Continue_100.isUnknownState shouldBe false
    HttpStatus.Ok_200.isUnknownState shouldBe false
    HttpStatus.InternalServerError_500.isUnknownState shouldBe false
    HttpStatus.ofCode(599).isUnknownState shouldBe false
    HttpStatus.ofCode(600).isUnknownState shouldBe true
    HttpStatus.ofCode(999).isUnknownState shouldBe true
  }

  test("should classify codes using static methods") {
    // isUnknownState
    HttpStatus.isUnknownState(50) shouldBe true
    HttpStatus.isUnknownState(100) shouldBe false
    HttpStatus.isUnknownState(599) shouldBe false
    HttpStatus.isUnknownState(600) shouldBe true

    // isInformational
    HttpStatus.isInformational(100) shouldBe true
    HttpStatus.isInformational(199) shouldBe true
    HttpStatus.isInformational(200) shouldBe false

    // isSuccessful
    HttpStatus.isSuccessful(200) shouldBe true
    HttpStatus.isSuccessful(299) shouldBe true
    HttpStatus.isSuccessful(300) shouldBe false

    // isRedirection
    HttpStatus.isRedirection(300) shouldBe true
    HttpStatus.isRedirection(399) shouldBe true
    HttpStatus.isRedirection(400) shouldBe false

    // isClientError
    HttpStatus.isClientError(400) shouldBe true
    HttpStatus.isClientError(499) shouldBe true
    HttpStatus.isClientError(500) shouldBe false

    // isServerError
    HttpStatus.isServerError(500) shouldBe true
    HttpStatus.isServerError(599) shouldBe true
    HttpStatus.isServerError(600) shouldBe false
  }

  test("should compare status codes by code value") {
    (HttpStatus.Ok_200 == HttpStatus.Ok_200) shouldBe true
    (HttpStatus.Ok_200 == HttpStatus.ofCode(200)) shouldBe true
    (HttpStatus.Ok_200 == HttpStatus.Created_201) shouldBe false
  }

  test("should format status as string") {
    HttpStatus.Ok_200.toString shouldBe "200 OK"
    HttpStatus.NotFound_404.toString shouldBe "404 Not Found"
    HttpStatus.InternalServerError_500.toString shouldBe "500 Internal Server Error"
  }

  test("clients follow only the redirects that carry a Location") {
    val followed = Seq(301, 302, 303, 307, 308).map(HttpStatus.ofCode)
    followed.forall(_.isFollowableRedirect) shouldBe true
    // A 304 answers a conditional request; the caller gets it back
    HttpStatus.NotModified_304.isFollowableRedirect shouldBe false
    HttpStatus.MultipleChoices_300.isFollowableRedirect shouldBe false
  }

end HttpStatusTest
