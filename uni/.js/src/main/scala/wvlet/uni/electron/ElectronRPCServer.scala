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
package wvlet.uni.electron

import wvlet.uni.http.Response
import wvlet.uni.http.rpc.{RPCDispatcher, RPCRouter, RPCStatus}
import wvlet.uni.rx.{OnCompletion, OnError, OnNext, RxRunner}

import scala.scalajs.js

/**
  * Main-process side of the Electron RPC transport.
  *
  * Registers an `ipcMain.handle(channel, ...)` listener that dispatches incoming requests through a
  * transport-neutral [[RPCDispatcher]] and returns a `Promise` of the response payload — exactly
  * what [[ElectronRendererChannel]] on the other end awaits.
  *
  * Electron's `ipcMain` is passed in as a value (rather than `require("electron")`-ed here) so this
  * code never couples to a particular Scala.js module/bundler setup; the thin JS main entry obtains
  * `ipcMain` and hands it over.
  *
  * {{{
  * // Scala.js main process
  * val server = ElectronRPCServer.serve(
  *   ipcMain,
  *   RPCRouter.of[CounterApi](CounterApiImpl())
  * )
  * }}}
  */
object ElectronRPCServer:

  /**
    * Register an RPC handler on the default channel ([[ElectronIPC.DefaultChannel]]).
    *
    * @param ipcMain
    *   Electron's `ipcMain` object.
    * @param routers
    *   one or more service routers built via `RPCRouter.of[T](impl)`.
    */
  def serve(ipcMain: js.Dynamic, routers: RPCRouter*): Unit = serve(
    ipcMain,
    ElectronIPC.DefaultChannel,
    routers*
  )

  /**
    * Register an RPC handler on an explicit channel name.
    */
  def serve(ipcMain: js.Dynamic, channel: String, routers: RPCRouter*): Unit =
    val dispatcher = new RPCDispatcher(routers.toSeq)
    val handler: js.Function2[js.Any, js.Any, js.Promise[js.Object]] =
      (_event: js.Any, payload: js.Any) => dispatch(dispatcher, payload.asInstanceOf[js.Dynamic])
    ipcMain.applyDynamic("handle")(channel, handler)

  /**
    * Run a single request through the dispatcher and bridge the resulting `Rx[Response]` into a JS
    * `Promise`. The dispatcher never fails its `Rx` (RPC errors are encoded as error responses),
    * but the `OnError` branch is handled defensively so the renderer always receives a well-formed
    * response payload.
    */
  private def dispatch(dispatcher: RPCDispatcher, payload: js.Dynamic): js.Promise[js.Object] =
    val request = ElectronIPC.toRequest(payload)
    new js.Promise[js.Object]((resolve, _reject) =>
      RxRunner.run(dispatcher.dispatch(request)) {
        case OnNext(v) =>
          resolve(ElectronIPC.fromResponse(v.asInstanceOf[Response]))
        case OnError(e) =>
          val errorResponse = RPCStatus.INTERNAL_ERROR_I0.newException(e.getMessage, e).toResponse
          resolve(ElectronIPC.fromResponse(errorResponse))
        case OnCompletion =>
        // No value emitted: nothing to resolve with (should not happen for RPC).
      }
    )

end ElectronRPCServer
