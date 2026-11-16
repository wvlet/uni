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

class ServiceStatusTest extends UniTest:

  // ============ ServiceStatusType Tests ============

  test("ServiceStatusType should have correct prefixes") {
    ServiceStatusType.SUCCESS.prefix shouldBe "S"
    ServiceStatusType.USER_ERROR.prefix shouldBe "U"
    ServiceStatusType.INTERNAL_ERROR.prefix shouldBe "I"
    ServiceStatusType.RESOURCE_EXHAUSTED.prefix shouldBe "R"
  }

  test("ServiceStatusType should have correct code ranges") {
    ServiceStatusType.SUCCESS.codeRange shouldBe (0, 1000)
    ServiceStatusType.USER_ERROR.codeRange shouldBe (1000, 2000)
    ServiceStatusType.INTERNAL_ERROR.codeRange shouldBe (2000, 3000)
    ServiceStatusType.RESOURCE_EXHAUSTED.codeRange shouldBe (3000, 4000)
  }

  test("ServiceStatusType.isValidCode should validate codes") {
    ServiceStatusType.SUCCESS.isValidCode(0) shouldBe true
    ServiceStatusType.SUCCESS.isValidCode(999) shouldBe true
    ServiceStatusType.SUCCESS.isValidCode(1000) shouldBe false

    ServiceStatusType.USER_ERROR.isValidCode(1000) shouldBe true
    ServiceStatusType.USER_ERROR.isValidCode(1999) shouldBe true
    ServiceStatusType.USER_ERROR.isValidCode(2000) shouldBe false
  }

  test("ServiceStatusType.isValidHttpStatus should validate HTTP status") {
    ServiceStatusType.SUCCESS.isValidHttpStatus(HttpStatus.Ok_200) shouldBe true
    ServiceStatusType.SUCCESS.isValidHttpStatus(HttpStatus.BadRequest_400) shouldBe false

    ServiceStatusType.USER_ERROR.isValidHttpStatus(HttpStatus.BadRequest_400) shouldBe true
    ServiceStatusType.USER_ERROR.isValidHttpStatus(HttpStatus.Ok_200) shouldBe false

    ServiceStatusType.INTERNAL_ERROR.isValidHttpStatus(HttpStatus.InternalServerError_500) shouldBe
      true
    ServiceStatusType.RESOURCE_EXHAUSTED.isValidHttpStatus(HttpStatus.TooManyRequests_429) shouldBe
      true
  }

  test("ServiceStatusType.ofPrefix should return correct type") {
    ServiceStatusType.ofPrefix('S') shouldBe ServiceStatusType.SUCCESS
    ServiceStatusType.ofPrefix('U') shouldBe ServiceStatusType.USER_ERROR
    ServiceStatusType.ofPrefix('I') shouldBe ServiceStatusType.INTERNAL_ERROR
    ServiceStatusType.ofPrefix('R') shouldBe ServiceStatusType.RESOURCE_EXHAUSTED
  }

  test("ServiceStatusType.ofPrefix should throw on invalid prefix") {
    intercept[IllegalArgumentException] {
      ServiceStatusType.ofPrefix('X')
    }
  }

  test("ServiceStatusType.values should return all types") {
    ServiceStatusType.values.length shouldBe 4
  }

  test("ServiceStatusType.unapply should extract from string") {
    ServiceStatusType.unapply("SUCCESS") shouldBe Some(ServiceStatusType.SUCCESS)
    ServiceStatusType.unapply("user_error") shouldBe Some(ServiceStatusType.USER_ERROR)
    ServiceStatusType.unapply("UNKNOWN") shouldBe None
  }

  // ============ ServiceStatus Tests ============

  test("ServiceStatus should have correct codes") {
    ServiceStatus.SUCCESS_S0.code shouldBe 0
    ServiceStatus.INVALID_REQUEST_U1.code shouldBe 1001
    ServiceStatus.NOT_FOUND_U5.code shouldBe 1005
    ServiceStatus.INTERNAL_ERROR_I0.code shouldBe 2000
    ServiceStatus.UNKNOWN_I1.code shouldBe 2001
    ServiceStatus.RESOURCE_EXHAUSTED_R0.code shouldBe 3000
  }

  test("ServiceStatus should have correct status types") {
    ServiceStatus.SUCCESS_S0.statusType shouldBe ServiceStatusType.SUCCESS
    ServiceStatus.INVALID_REQUEST_U1.statusType shouldBe ServiceStatusType.USER_ERROR
    ServiceStatus.INTERNAL_ERROR_I0.statusType shouldBe ServiceStatusType.INTERNAL_ERROR
    ServiceStatus.RESOURCE_EXHAUSTED_R0.statusType shouldBe ServiceStatusType.RESOURCE_EXHAUSTED
  }

  test("ServiceStatus should map to correct HTTP status") {
    ServiceStatus.SUCCESS_S0.httpStatus shouldBe HttpStatus.Ok_200
    ServiceStatus.INVALID_REQUEST_U1.httpStatus shouldBe HttpStatus.BadRequest_400
    ServiceStatus.NOT_FOUND_U5.httpStatus shouldBe HttpStatus.NotFound_404
    ServiceStatus.UNAUTHENTICATED_U13.httpStatus shouldBe HttpStatus.Unauthorized_401
    ServiceStatus.PERMISSION_DENIED_U14.httpStatus shouldBe HttpStatus.Forbidden_403
    ServiceStatus.INTERNAL_ERROR_I0.httpStatus shouldBe HttpStatus.InternalServerError_500
    ServiceStatus.UNAVAILABLE_I2.httpStatus shouldBe HttpStatus.ServiceUnavailable_503
    ServiceStatus.RESOURCE_EXHAUSTED_R0.httpStatus shouldBe HttpStatus.TooManyRequests_429
  }

  test("ServiceStatus.isSuccess and isFailure") {
    ServiceStatus.SUCCESS_S0.isSuccess shouldBe true
    ServiceStatus.SUCCESS_S0.isFailure shouldBe false

    ServiceStatus.INVALID_REQUEST_U1.isSuccess shouldBe false
    ServiceStatus.INVALID_REQUEST_U1.isFailure shouldBe true

    ServiceStatus.INTERNAL_ERROR_I0.isSuccess shouldBe false
    ServiceStatus.RESOURCE_EXHAUSTED_R0.isSuccess shouldBe false
  }

  test("ServiceStatus.shouldReportStackTrace") {
    // Auth errors never report stack traces, even with detailedErrors
    ServiceStatus.UNAUTHENTICATED_U13.shouldReportStackTrace shouldBe false
    ServiceStatus.PERMISSION_DENIED_U14.shouldReportStackTrace shouldBe false

    // Other errors report stack traces only with detailedErrors
    ServiceStatus.INVALID_REQUEST_U1.shouldReportStackTrace shouldBe true
    ServiceStatus.INTERNAL_ERROR_I0.shouldReportStackTrace shouldBe true
  }

  test("ServiceStatus.ofCode should return correct status") {
    ServiceStatus.ofCode(0) shouldBe ServiceStatus.SUCCESS_S0
    ServiceStatus.ofCode(1001) shouldBe ServiceStatus.INVALID_REQUEST_U1
    ServiceStatus.ofCode(1005) shouldBe ServiceStatus.NOT_FOUND_U5
    ServiceStatus.ofCode(2000) shouldBe ServiceStatus.INTERNAL_ERROR_I0
    ServiceStatus.ofCode(3000) shouldBe ServiceStatus.RESOURCE_EXHAUSTED_R0
  }

  test("ServiceStatus.ofCode should throw on invalid code") {
    intercept[IllegalArgumentException] {
      ServiceStatus.ofCode(9999)
    }
  }

  test("ServiceStatus.ofCodeName should return correct status") {
    ServiceStatus.ofCodeName("SUCCESS_S0") shouldBe ServiceStatus.SUCCESS_S0
    ServiceStatus.ofCodeName("INVALID_REQUEST_U1") shouldBe ServiceStatus.INVALID_REQUEST_U1
    ServiceStatus.ofCodeName("INTERNAL_ERROR_I0") shouldBe ServiceStatus.INTERNAL_ERROR_I0
  }

  test("ServiceStatus.ofCodeName should throw on invalid name") {
    intercept[IllegalArgumentException] {
      ServiceStatus.ofCodeName("INVALID_NAME")
    }
  }

  test("ServiceStatus.fromHttpStatus should map HTTP status to RPC status") {
    // Direct mappings
    ServiceStatus.fromHttpStatus(HttpStatus.Ok_200) shouldBe ServiceStatus.SUCCESS_S0
    ServiceStatus.fromHttpStatus(HttpStatus.BadRequest_400) shouldBe
      ServiceStatus.INVALID_REQUEST_U1
    ServiceStatus.fromHttpStatus(HttpStatus.Unauthorized_401) shouldBe
      ServiceStatus.UNAUTHENTICATED_U13
    ServiceStatus.fromHttpStatus(HttpStatus.NotFound_404) shouldBe ServiceStatus.NOT_FOUND_U5
    ServiceStatus.fromHttpStatus(HttpStatus.InternalServerError_500) shouldBe
      ServiceStatus.INTERNAL_ERROR_I0
    ServiceStatus.fromHttpStatus(HttpStatus.TooManyRequests_429) shouldBe
      ServiceStatus.RESOURCE_EXHAUSTED_R0

    // Fallback mappings
    ServiceStatus.fromHttpStatus(HttpStatus.Created_201) shouldBe ServiceStatus.SUCCESS_S0
    ServiceStatus.fromHttpStatus(HttpStatus.ServiceUnavailable_503) shouldBe
      ServiceStatus.UNAVAILABLE_I2
  }

  test("ServiceStatus.values should return all statuses") {
    val values = ServiceStatus.values
    (values.length >= 30) shouldBe true

    // Check each category has values
    values.exists(_.statusType == ServiceStatusType.SUCCESS) shouldBe true
    values.exists(_.statusType == ServiceStatusType.USER_ERROR) shouldBe true
    values.exists(_.statusType == ServiceStatusType.INTERNAL_ERROR) shouldBe true
    values.exists(_.statusType == ServiceStatusType.RESOURCE_EXHAUSTED) shouldBe true
  }

  test("ServiceStatus.name should return the enum name") {
    ServiceStatus.SUCCESS_S0.name shouldBe "SUCCESS_S0"
    ServiceStatus.INVALID_REQUEST_U1.name shouldBe "INVALID_REQUEST_U1"
    ServiceStatus.INTERNAL_ERROR_I0.name shouldBe "INTERNAL_ERROR_I0"
  }

  // ============ ServiceException Tests ============

  test("ServiceStatus.newException should create exception") {
    val ex = ServiceStatus.INVALID_REQUEST_U1.newException("test error")
    ex.status shouldBe ServiceStatus.INVALID_REQUEST_U1
    ex.message shouldBe "test error"
    ex.cause shouldBe None
    ex.appErrorCode shouldBe None
  }

  test("ServiceStatus.newException with cause") {
    val cause = RuntimeException("root cause")
    val ex    = ServiceStatus.INTERNAL_ERROR_I0.newException("wrapped error", cause)
    ex.status shouldBe ServiceStatus.INTERNAL_ERROR_I0
    ex.message shouldBe "wrapped error"
    ex.cause shouldBe Some(cause)
  }

  test("ServiceStatus.newException with appErrorCode") {
    val ex = ServiceStatus.USER_ERROR_U0.newException("app error", appErrorCode = Some(42))
    ex.appErrorCode shouldBe Some(42)
  }

  test("ServiceException should serialize to JSON") {
    val ex   = ServiceStatus.NOT_FOUND_U5.newException("resource not found")
    val json = ex.toJson
    json shouldContain "1005"
    json shouldContain "NOT_FOUND_U5"
    json shouldContain "resource not found"
  }

  test("ServiceException should deserialize from JSON") {
    val original = ServiceStatus.INVALID_ARGUMENT_U2.newException("bad argument")
    val json     = original.toJson
    val restored = ServiceException.fromJson(json)
    restored.status shouldBe original.status
    restored.message shouldBe original.message
  }

  test("ServiceException should convert to Response") {
    val ex       = ServiceStatus.NOT_FOUND_U5.newException("not found")
    val response = ex.toResponse
    response.status shouldBe HttpStatus.NotFound_404
  }

  test("ServiceException.noStackTrace should create exception without stack trace") {
    val ex = ServiceStatus.INVALID_REQUEST_U1.newException("test").noStackTrace
    ex.shouldReportStackTrace shouldBe false
  }

end ServiceStatusTest
