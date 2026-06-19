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
package wvlet.uni.http

/**
  * Shared validation of a WebSocket upgrade request, used by the backends that perform the
  * handshake by hand (Native, Node.js). Centralizes the `Sec-WebSocket-Key` and
  * `Sec-WebSocket-Version` checks so both backends reject malformed handshakes identically.
  */
private[http] object WebSocketHandshake:

  /** The only WebSocket protocol version this server speaks (RFC 6455). */
  final val SupportedVersion = "13"

  /**
    * Validate the upgrade headers. `Right(key)` carries the `Sec-WebSocket-Key` when the handshake
    * is acceptable; `Left(response)` is the rejection to send:
    *   - 400 if `Sec-WebSocket-Key` is missing/empty or `Sec-WebSocket-Version` is absent,
    *   - 426 (advertising `Sec-WebSocket-Version: 13`) if the requested version is not 13.
    */
  def validate(request: Request): Either[Response, String] =
    request.header(HttpHeader.SecWebSocketKey).filter(_.nonEmpty) match
      case None =>
        Left(Response.badRequest("Missing Sec-WebSocket-Key"))
      case Some(key) =>
        request.header(HttpHeader.SecWebSocketVersion).map(_.trim) match
          case Some(SupportedVersion) =>
            Right(key)
          case Some(_) =>
            // Unsupported version: tell the client which version we speak (RFC 6455 §4.2.2 / 4.4).
            Left(
              Response(HttpStatus.UpgradeRequired_426)
                .addHeader(HttpHeader.SecWebSocketVersion, SupportedVersion)
                .withTextContent("Unsupported Sec-WebSocket-Version")
            )
          case None =>
            Left(Response.badRequest("Missing Sec-WebSocket-Version"))

end WebSocketHandshake
