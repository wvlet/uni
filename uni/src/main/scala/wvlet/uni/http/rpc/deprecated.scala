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

import wvlet.uni.http

// Aliases for the names these types had before they moved to wvlet.uni.http, so RPC, Electron
// IPC, and MCP servers can share them. The wire format did not change. Type aliases keep source
// compatibility only; binaries compiled against the old classes must be rebuilt.

@deprecated("Use wvlet.uni.http.ServiceStatus", "2026.1.42")
type RPCStatus = http.ServiceStatus

@deprecated("Use wvlet.uni.http.ServiceStatus", "2026.1.42")
val RPCStatus: http.ServiceStatus.type = http.ServiceStatus

@deprecated("Use wvlet.uni.http.ServiceStatusType", "2026.1.42")
type RPCStatusType = http.ServiceStatusType

@deprecated("Use wvlet.uni.http.ServiceStatusType", "2026.1.42")
val RPCStatusType: http.ServiceStatusType.type = http.ServiceStatusType

@deprecated("Use wvlet.uni.http.ServiceException", "2026.1.42")
type RPCException = http.ServiceException

@deprecated("Use wvlet.uni.http.ServiceException", "2026.1.42")
val RPCException: http.ServiceException.type = http.ServiceException

@deprecated("Use wvlet.uni.http.ServiceErrorMessage", "2026.1.42")
type RPCErrorMessage = http.ServiceErrorMessage

@deprecated("Use wvlet.uni.http.ServiceErrorMessage", "2026.1.42")
val RPCErrorMessage: http.ServiceErrorMessage.type = http.ServiceErrorMessage

@deprecated("Use wvlet.uni.http.ServiceStackTraceElement", "2026.1.42")
type RPCStackTraceElement = http.ServiceStackTraceElement

@deprecated("Use wvlet.uni.http.ServiceStackTraceElement", "2026.1.42")
val RPCStackTraceElement: http.ServiceStackTraceElement.type = http.ServiceStackTraceElement
