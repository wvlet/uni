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

/**
  * Represents a single RPC endpoint route.
  *
  * @param serviceName
  *   Full package path of the service (e.g., com.example.api.UserService)
  * @param methodName
  *   Name of the RPC method
  * @param path
  *   URL path for this route (e.g., /com.example.api.UserService/getUser)
  * @param codec
  *   Codec for encoding/decoding parameters and return values
  */
case class RPCRoute(serviceName: String, methodName: String, path: String, codec: MethodCodec)
