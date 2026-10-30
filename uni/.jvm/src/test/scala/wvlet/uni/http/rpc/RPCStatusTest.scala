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
package wvlet.uni.http.rpc

import wvlet.uni.http.HttpStatus
import wvlet.uni.test.UniTest

class RPCStatusTest extends UniTest:

  // ============ RPCStatusType Tests ============

  test("RPCStatusType should have correct prefixes") {
    RPCStatusType.SUCCESS.prefix shouldBe "S"
    RPCStatusType.USER_ERROR.prefix shouldBe "U"
    RPCStatusType.INTERNAL_ERROR.prefix shouldBe "I"
    RPCStatusType.RESOURCE_EXHAUSTED.prefix shouldBe "R"
  }

  test("RPCStatusType should have correct code ranges") {
    RPCStatusType.SUCCESS.codeRange shouldBe (0, 1000)
    RPCStatusType.USER_ERROR.codeRange shouldBe (1000, 2000)
    RPCStatusType.INTERNAL_ERROR.codeRange shouldBe (2000, 3000)
    RPCStatusType.RESOURCE_EXHAUSTED.codeRange shouldBe (3000, 4000)
  }

  test("RPCStatusType.isValidCode should validate codes") {
    RPCStatusType.SUCCESS.isValidCode(0) shouldBe true
    RPCStatusType.SUCCESS.isValidCode(999) shouldBe true
    RPCStatusType.SUCCESS.isValidCode(1000) shouldBe false

    RPCStatusType.USER_ERROR.isValidCode(1000) shouldBe true
    RPCStatusType.USER_ERROR.isValidCode(1999) shouldBe true
    RPCStatusType.USER_ERROR.isValidCode(2000) shouldBe false
  }

  test("RPCStatusType.isValidHttpStatus should validate HTTP status") {
    RPCStatusType.SUCCESS.isValidHttpStatus(HttpStatus.Ok_200) shouldBe true
    RPCStatusType.SUCCESS.isValidHttpStatus(HttpStatus.BadRequest_400) shouldBe false

    RPCStatusType.USER_ERROR.isValidHttpStatus(HttpStatus.BadRequest_400) shouldBe true
    RPCStatusType.USER_ERROR.isValidHttpStatus(HttpStatus.Ok_200) shouldBe false

    RPCStatusType.INTERNAL_ERROR.isValidHttpStatus(HttpStatus.InternalServerError_500) shouldBe true
    RPCStatusType.RESOURCE_EXHAUSTED.isValidHttpStatus(HttpStatus.TooManyRequests_429) shouldBe true
  }

  test("RPCStatusType.ofPrefix should return correct type") {
    RPCStatusType.ofPrefix('S') shouldBe RPCStatusType.SUCCESS
    RPCStatusType.ofPrefix('U') shouldBe RPCStatusType.USER_ERROR
    RPCStatusType.ofPrefix('I') shouldBe RPCStatusType.INTERNAL_ERROR
    RPCStatusType.ofPrefix('R') shouldBe RPCStatusType.RESOURCE_EXHAUSTED
  }

  test("RPCStatusType.ofPrefix should throw on invalid prefix") {
    intercept[IllegalArgumentException] {
      RPCStatusType.ofPrefix('X')
    }
  }

  test("RPCStatusType.values should return all types") {
    RPCStatusType.values.length shouldBe 4
  }

  test("RPCStatusType.unapply should extract from string") {
    RPCStatusType.unapply("SUCCESS") shouldBe Some(RPCStatusType.SUCCESS)
    RPCStatusType.unapply("user_error") shouldBe Some(RPCStatusType.USER_ERROR)
    RPCStatusType.unapply("UNKNOWN") shouldBe None
  }

  // ============ RPCStatus Tests ============

  test("RPCStatus should have correct codes") {
    RPCStatus.SUCCESS_S0.code shouldBe 0
    RPCStatus.INVALID_REQUEST_U1.code shouldBe 1001
    RPCStatus.NOT_FOUND_U5.code shouldBe 1005
    RPCStatus.INTERNAL_ERROR_I0.code shouldBe 2000
    RPCStatus.UNKNOWN_I1.code shouldBe 2001
    RPCStatus.RESOURCE_EXHAUSTED_R0.code shouldBe 3000
  }

  test("RPCStatus should have correct status types") {
    RPCStatus.SUCCESS_S0.statusType shouldBe RPCStatusType.SUCCESS
    RPCStatus.INVALID_REQUEST_U1.statusType shouldBe RPCStatusType.USER_ERROR
    RPCStatus.INTERNAL_ERROR_I0.statusType shouldBe RPCStatusType.INTERNAL_ERROR
    RPCStatus.RESOURCE_EXHAUSTED_R0.statusType shouldBe RPCStatusType.RESOURCE_EXHAUSTED
  }

  test("RPCStatus should map to correct HTTP status") {
    RPCStatus.SUCCESS_S0.httpStatus shouldBe HttpStatus.Ok_200
    RPCStatus.INVALID_REQUEST_U1.httpStatus shouldBe HttpStatus.BadRequest_400
    RPCStatus.NOT_FOUND_U5.httpStatus shouldBe HttpStatus.NotFound_404
    RPCStatus.UNAUTHENTICATED_U13.httpStatus shouldBe HttpStatus.Unauthorized_401
    RPCStatus.PERMISSION_DENIED_U14.httpStatus shouldBe HttpStatus.Forbidden_403
    RPCStatus.INTERNAL_ERROR_I0.httpStatus shouldBe HttpStatus.InternalServerError_500
    RPCStatus.UNAVAILABLE_I2.httpStatus shouldBe HttpStatus.ServiceUnavailable_503
    RPCStatus.RESOURCE_EXHAUSTED_R0.httpStatus shouldBe HttpStatus.TooManyRequests_429
  }

  test("RPCStatus.isSuccess and isFailure") {
    RPCStatus.SUCCESS_S0.isSuccess shouldBe true
    RPCStatus.SUCCESS_S0.isFailure shouldBe false

    RPCStatus.INVALID_REQUEST_U1.isSuccess shouldBe false
    RPCStatus.INVALID_REQUEST_U1.isFailure shouldBe true

    RPCStatus.INTERNAL_ERROR_I0.isSuccess shouldBe false
    RPCStatus.RESOURCE_EXHAUSTED_R0.isSuccess shouldBe false
  }

  test("RPCStatus.shouldReportStackTrace") {
    // Auth errors never report stack traces, even with detailedErrors
    RPCStatus.UNAUTHENTICATED_U13.shouldReportStackTrace shouldBe false
    RPCStatus.PERMISSION_DENIED_U14.shouldReportStackTrace shouldBe false

    // Other errors report stack traces only with detailedErrors
    RPCStatus.INVALID_REQUEST_U1.shouldReportStackTrace shouldBe true
    RPCStatus.INTERNAL_ERROR_I0.shouldReportStackTrace shouldBe true
  }

  test("RPCStatus.ofCode should return correct status") {
    RPCStatus.ofCode(0) shouldBe RPCStatus.SUCCESS_S0
    RPCStatus.ofCode(1001) shouldBe RPCStatus.INVALID_REQUEST_U1
    RPCStatus.ofCode(1005) shouldBe RPCStatus.NOT_FOUND_U5
    RPCStatus.ofCode(2000) shouldBe RPCStatus.INTERNAL_ERROR_I0
    RPCStatus.ofCode(3000) shouldBe RPCStatus.RESOURCE_EXHAUSTED_R0
  }

  test("RPCStatus.ofCode should throw on invalid code") {
    intercept[IllegalArgumentException] {
      RPCStatus.ofCode(9999)
    }
  }

  test("RPCStatus.ofCodeName should return correct status") {
    RPCStatus.ofCodeName("SUCCESS_S0") shouldBe RPCStatus.SUCCESS_S0
    RPCStatus.ofCodeName("INVALID_REQUEST_U1") shouldBe RPCStatus.INVALID_REQUEST_U1
    RPCStatus.ofCodeName("INTERNAL_ERROR_I0") shouldBe RPCStatus.INTERNAL_ERROR_I0
  }

  test("RPCStatus.ofCodeName should throw on invalid name") {
    intercept[IllegalArgumentException] {
      RPCStatus.ofCodeName("INVALID_NAME")
    }
  }

  test("RPCStatus.fromHttpStatus should map HTTP status to RPC status") {
    // Direct mappings
    RPCStatus.fromHttpStatus(HttpStatus.Ok_200) shouldBe RPCStatus.SUCCESS_S0
    RPCStatus.fromHttpStatus(HttpStatus.BadRequest_400) shouldBe RPCStatus.INVALID_REQUEST_U1
    RPCStatus.fromHttpStatus(HttpStatus.Unauthorized_401) shouldBe RPCStatus.UNAUTHENTICATED_U13
    RPCStatus.fromHttpStatus(HttpStatus.NotFound_404) shouldBe RPCStatus.NOT_FOUND_U5
    RPCStatus.fromHttpStatus(HttpStatus.InternalServerError_500) shouldBe
      RPCStatus.INTERNAL_ERROR_I0
    RPCStatus.fromHttpStatus(HttpStatus.TooManyRequests_429) shouldBe
      RPCStatus.RESOURCE_EXHAUSTED_R0

    // Fallback mappings
    RPCStatus.fromHttpStatus(HttpStatus.Created_201) shouldBe RPCStatus.SUCCESS_S0
    RPCStatus.fromHttpStatus(HttpStatus.ServiceUnavailable_503) shouldBe RPCStatus.UNAVAILABLE_I2
  }

  test("RPCStatus.values should return all statuses") {
    val values = RPCStatus.values
    (values.length >= 30) shouldBe true

    // Check each category has values
    values.exists(_.statusType == RPCStatusType.SUCCESS) shouldBe true
    values.exists(_.statusType == RPCStatusType.USER_ERROR) shouldBe true
    values.exists(_.statusType == RPCStatusType.INTERNAL_ERROR) shouldBe true
    values.exists(_.statusType == RPCStatusType.RESOURCE_EXHAUSTED) shouldBe true
  }

  test("RPCStatus.name should return the enum name") {
    RPCStatus.SUCCESS_S0.name shouldBe "SUCCESS_S0"
    RPCStatus.INVALID_REQUEST_U1.name shouldBe "INVALID_REQUEST_U1"
    RPCStatus.INTERNAL_ERROR_I0.name shouldBe "INTERNAL_ERROR_I0"
  }

  // ============ RPCException Tests ============

  test("RPCStatus.newException should create exception") {
    val ex = RPCStatus.INVALID_REQUEST_U1.newException("test error")
    ex.status shouldBe RPCStatus.INVALID_REQUEST_U1
    ex.message shouldBe "test error"
    ex.cause shouldBe None
    ex.appErrorCode shouldBe None
  }

  test("RPCStatus.newException with cause") {
    val cause = RuntimeException("root cause")
    val ex    = RPCStatus.INTERNAL_ERROR_I0.newException("wrapped error", cause)
    ex.status shouldBe RPCStatus.INTERNAL_ERROR_I0
    ex.message shouldBe "wrapped error"
    ex.cause shouldBe Some(cause)
  }

  test("RPCStatus.newException with appErrorCode") {
    val ex = RPCStatus.USER_ERROR_U0.newException("app error", appErrorCode = Some(42))
    ex.appErrorCode shouldBe Some(42)
  }

  test("RPCException should serialize to JSON") {
    val ex   = RPCStatus.NOT_FOUND_U5.newException("resource not found")
    val json = ex.toJson
    json shouldContain "1005"
    json shouldContain "NOT_FOUND_U5"
    json shouldContain "resource not found"
  }

  test("RPCException should deserialize from JSON") {
    val original = RPCStatus.INVALID_ARGUMENT_U2.newException("bad argument")
    val json     = original.toJson
    val restored = RPCException.fromJson(json)
    restored.status shouldBe original.status
    restored.message shouldBe original.message
  }

  test("RPCException should convert to Response") {
    val ex       = RPCStatus.NOT_FOUND_U5.newException("not found")
    val response = ex.toResponse
    response.status shouldBe HttpStatus.NotFound_404
  }

  test("RPCException.noStackTrace should create exception without stack trace") {
    val ex = RPCStatus.INVALID_REQUEST_U1.newException("test").noStackTrace
    ex.shouldReportStackTrace shouldBe false
  }

end RPCStatusTest
