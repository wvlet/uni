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
package wvlet.uni.mcp

import wvlet.uni.http.{Http, HttpClientConfig}
import wvlet.uni.rx.Rx

/**
  * Configuration of an [[MCPClient]]. Create one with `MCP.client`.
  *
  * @param name
  *   client name reported in the initialize `clientInfo`
  * @param version
  *   client version reported in the initialize `clientInfo`
  * @param protocolVersion
  *   MCP protocol version requested in initialize and sent in the `MCP-Protocol-Version` header
  * @param httpClientConfig
  *   HTTP client used for the Streamable HTTP transport (timeouts, filters, channel factory)
  */
case class MCPClientConfig(
    name: String = "uni-mcp-client",
    version: String = "0.1.0",
    protocolVersion: String = MCPServer.LatestProtocolVersion,
    httpClientConfig: HttpClientConfig = Http.client
):
  def withName(name: String): MCPClientConfig                       = copy(name = name)
  def withVersion(version: String): MCPClientConfig                 = copy(version = version)
  def withProtocolVersion(protocolVersion: String): MCPClientConfig = copy(protocolVersion =
    protocolVersion
  )

  def withHttpClientConfig(httpClientConfig: HttpClientConfig): MCPClientConfig = copy(
    httpClientConfig = httpClientConfig
  )

  /**
    * Connect to an MCP server over Streamable HTTP and perform the MCP handshake (initialize +
    * notifications/initialized). Close the returned client when done, e.g., with
    * `RxResource.fromAutoCloseable(...)`.
    *
    * HTTP retries are always disabled: JSON-RPC messages (`tools/call` in particular) are not
    * idempotent, so a retry could execute a tool twice.
    */
  def connect(serverUri: String): Rx[MCPClient] =
    // Create the HTTP client lazily so a failed connect run does not leak an unused client
    Rx.single(httpClientConfig.withMaxRetry(0).newSyncClient)
      .flatMap(httpClient => MCPClient.connect(this, httpClient, serverUri))

end MCPClientConfig
