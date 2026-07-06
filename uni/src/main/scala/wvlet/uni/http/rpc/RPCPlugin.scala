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

import wvlet.uni.plugin.{ExtensionPoint, PluginContext, PluginHost}

/**
  * RPC integration for the plugin model: plugins contribute [[RPCRouter]]s through
  * [[RPCPlugin.routerPoint]], and the host application serves them all through a single
  * [[RPCDispatcher]] (e.g. over HTTP or Electron IPC).
  *
  * Defined here — not in `wvlet.uni.plugin` — so the core plugin model stays free of RPC concepts;
  * the dependency arrow points from the RPC layer into the plugin layer.
  */
object RPCPlugin:
  /** Extension point through which plugins contribute RPC service routers. */
  val routerPoint: ExtensionPoint[RPCRouter] = ExtensionPoint[RPCRouter]("rpc.router")

  extension (context: PluginContext)
    /** Contribute an RPC service router, typically built with `RPCRouter.of[T](impl)`. */
    def registerRpcRouter(router: RPCRouter): Unit = context.contribute(routerPoint)(router)

  extension (host: PluginHost)
    /** All RPC routers contributed by activated plugins, in contribution order. */
    def rpcRouters: Seq[RPCRouter] = host.contributions(routerPoint)

    /** An [[RPCDispatcher]] over all contributed routers, ready to serve. */
    def rpcDispatcher: RPCDispatcher = RPCDispatcher(host.rpcRouters*)
