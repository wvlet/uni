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

import wvlet.uni.http

// RouterHandler and RPCHandler never depended on Netty. They moved to the uni module so the
// built-in JVM server, Scala.js, and Scala Native servers can serve them without uni-netty. Type
// aliases keep source compatibility only; binaries compiled against the old classes must be
// rebuilt.

@deprecated("Use wvlet.uni.http.router.RouterHandler", "2026.2.2")
type RouterHandler = http.router.RouterHandler

@deprecated("Use wvlet.uni.http.router.RouterHandler", "2026.2.2")
val RouterHandler: http.router.RouterHandler.type = http.router.RouterHandler

@deprecated("Use wvlet.uni.http.rpc.RPCHandler", "2026.2.2")
type RPCHandler = http.rpc.RPCHandler

@deprecated("Use wvlet.uni.http.rpc.RPCHandler", "2026.2.2")
val RPCHandler: http.rpc.RPCHandler.type = http.rpc.RPCHandler
