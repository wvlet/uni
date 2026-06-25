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
package wvlet.uni.http.netty

import wvlet.uni.http.Request
import wvlet.uni.http.Response
import wvlet.uni.http.RxHttpHandler
import wvlet.uni.http.rpc.RPCDispatcher
import wvlet.uni.http.rpc.RPCRouter
import wvlet.uni.rx.Rx

/**
  * HTTP handler for RPC requests.
  *
  * Adapts the transport-neutral [[RPCDispatcher]] to netty's [[RxHttpHandler]]. The dispatcher
  * resolves the route defined by [[RPCRouter]]:
  *   - Only POST method is allowed
  *   - Path format: /{serviceName}/{methodName}
  *   - Request body: {"request": {...params...}}
  *   - Response: JSON result or RPCException
  *
  * @param router
  *   The RPC router containing route definitions
  */
class RPCHandler(router: RPCRouter) extends RxHttpHandler:

  private val dispatcher = RPCDispatcher(router)

  override def handle(request: Request): Rx[Response] = dispatcher.dispatch(request)

end RPCHandler

object RPCHandler:

  /**
    * Create an RPC handler from a router.
    */
  def apply(router: RPCRouter): RPCHandler = new RPCHandler(router)

end RPCHandler
