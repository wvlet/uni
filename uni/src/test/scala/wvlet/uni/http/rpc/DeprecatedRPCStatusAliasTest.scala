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

import wvlet.uni.http.{ServiceException, ServiceStatus}
import wvlet.uni.test.UniTest

import scala.annotation.nowarn

/**
  * Code written against the pre-rename names must keep compiling and behave the same
  */
@nowarn("cat=deprecation")
class DeprecatedRPCStatusAliasTest extends UniTest:

  test("RPCStatus aliases ServiceStatus") {
    val s: RPCStatus = RPCStatus.NOT_FOUND_U5
    s shouldBe ServiceStatus.NOT_FOUND_U5
    RPCStatus.ofCodeName("NOT_FOUND_U5") shouldBe ServiceStatus.NOT_FOUND_U5
    RPCStatusType.USER_ERROR shouldBe s.statusType
  }

  test("pattern matching through the old names") {
    val e: Throwable = ServiceStatus.PERMISSION_DENIED_U14.newException("denied")
    e shouldMatch {
      case ex: RPCException if ex.status == RPCStatus.PERMISSION_DENIED_U14 =>
    }
    val status: ServiceStatus = ServiceStatus.UNAUTHENTICATED_U13
    val label                 =
      status match
        case RPCStatus.UNAUTHENTICATED_U13 =>
          "auth"
        case _ =>
          "other"
    label shouldBe "auth"
  }

  test("RPCException and RPCErrorMessage companions keep working") {
    val ex: RPCException   = RPCException(status = RPCStatus.INVALID_ARGUMENT_U2, message = "bad")
    val m: RPCErrorMessage = RPCErrorMessage.fromJson(ex.toJson)
    m.codeName shouldBe "INVALID_ARGUMENT_U2"
    RPCException.fromJson(ex.toJson).status shouldBe ServiceStatus.INVALID_ARGUMENT_U2
  }

end DeprecatedRPCStatusAliasTest
