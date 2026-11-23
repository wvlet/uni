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
package wvlet.uni.cloud

import wvlet.uni.http.{HttpException, HttpMaxRetryException, Response}

/**
  * Error codes shared by cloud credential providers and the services that use them
  */
object CloudErrorCode:
  val AccessDenied: String  = "AccessDenied"
  val NoCredentials: String = "NoCredentials"

  /** Credentials were found but can't be used (a malformed key, an unsupported file type) */
  val InvalidCredentials: String = "InvalidCredentials"
  val InvalidResponse: String    = "InvalidResponse"

  /** The configuration can't work, such as a bearer token over plain HTTP */
  val InvalidConfig: String = "InvalidConfig"

/**
  * A cloud service or credential provider refused or failed a request. Object stores throw the
  * subclass `wvlet.uni.cloud.storage.StorageException`.
  *
  * @param code
  *   the error code, such as `NoCredentials`, `AccessDenied`, or a service's own code
  * @param statusCode
  *   the HTTP status, when the service answered
  */
class CloudException(
    val code: String,
    message: String,
    val statusCode: Option[Int] = None,
    cause: Throwable = null
) extends Exception(message, cause)

private[uni] object CloudHttp:
  /** The client throws for 3xx and 4xx; callers map the response to their own errors */
  val unwrapHttpError: PartialFunction[Throwable, Response] = {
    case e: HttpMaxRetryException =>
      e.response
    case e: HttpException if e.response.isDefined =>
      e.response.get
  }
