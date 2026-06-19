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
  * An HTTP header name-value pair
  */
case class HttpHeader(name: String, value: String):
  def nameEquals(other: String): Boolean = name.equalsIgnoreCase(other)

  override def toString: String = s"${name}: ${value}"

/**
  * Standard HTTP header names as defined in various RFCs
  */
object HttpHeader:
  // General headers
  val CacheControl: String     = "Cache-Control"
  val Connection: String       = "Connection"
  val Date: String             = "Date"
  val Pragma: String           = "Pragma"
  val Trailer: String          = "Trailer"
  val TransferEncoding: String = "Transfer-Encoding"
  val Upgrade: String          = "Upgrade"
  val Via: String              = "Via"
  val Warning: String          = "Warning"

  // WebSocket handshake headers (RFC 6455)
  val SecWebSocketKey: String      = "Sec-WebSocket-Key"
  val SecWebSocketAccept: String   = "Sec-WebSocket-Accept"
  val SecWebSocketVersion: String  = "Sec-WebSocket-Version"
  val SecWebSocketProtocol: String = "Sec-WebSocket-Protocol"

  // Request headers
  val Accept: String             = "Accept"
  val AcceptCharset: String      = "Accept-Charset"
  val AcceptEncoding: String     = "Accept-Encoding"
  val AcceptLanguage: String     = "Accept-Language"
  val Authorization: String      = "Authorization"
  val Cookie: String             = "Cookie"
  val Expect: String             = "Expect"
  val From: String               = "From"
  val Host: String               = "Host"
  val IfMatch: String            = "If-Match"
  val IfModifiedSince: String    = "If-Modified-Since"
  val IfNoneMatch: String        = "If-None-Match"
  val IfRange: String            = "If-Range"
  val IfUnmodifiedSince: String  = "If-Unmodified-Since"
  val MaxForwards: String        = "Max-Forwards"
  val Origin: String             = "Origin"
  val ProxyAuthorization: String = "Proxy-Authorization"
  val Range: String              = "Range"
  val Referer: String            = "Referer"
  val TE: String                 = "TE"
  val UserAgent: String          = "User-Agent"

  // Response headers
  val AcceptRanges: String      = "Accept-Ranges"
  val Age: String               = "Age"
  val ETag: String              = "ETag"
  val Location: String          = "Location"
  val ProxyAuthenticate: String = "Proxy-Authenticate"
  val RetryAfter: String        = "Retry-After"
  val Server: String            = "Server"
  val SetCookie: String         = "Set-Cookie"
  val Vary: String              = "Vary"
  val WWWAuthenticate: String   = "WWW-Authenticate"

  // Entity headers
  val Allow: String              = "Allow"
  val ContentDisposition: String = "Content-Disposition"
  val ContentEncoding: String    = "Content-Encoding"
  val ContentLanguage: String    = "Content-Language"
  val ContentLength: String      = "Content-Length"
  val ContentLocation: String    = "Content-Location"
  val ContentMD5: String         = "Content-MD5"
  val ContentRange: String       = "Content-Range"
  val ContentType: String        = "Content-Type"
  val Expires: String            = "Expires"
  val LastModified: String       = "Last-Modified"

  // CORS headers
  val AccessControlAllowCredentials: String = "Access-Control-Allow-Credentials"
  val AccessControlAllowHeaders: String     = "Access-Control-Allow-Headers"
  val AccessControlAllowMethods: String     = "Access-Control-Allow-Methods"
  val AccessControlAllowOrigin: String      = "Access-Control-Allow-Origin"
  val AccessControlExposeHeaders: String    = "Access-Control-Expose-Headers"
  val AccessControlMaxAge: String           = "Access-Control-Max-Age"
  val AccessControlRequestHeaders: String   = "Access-Control-Request-Headers"
  val AccessControlRequestMethod: String    = "Access-Control-Request-Method"

  // Proxy headers
  val XForwardedFor: String   = "X-Forwarded-For"
  val XForwardedHost: String  = "X-Forwarded-Host"
  val XForwardedProto: String = "X-Forwarded-Proto"
  val XRealIP: String         = "X-Real-IP"

  // Custom headers
  val XRequestId: String     = "X-Request-Id"
  val XCorrelationId: String = "X-Correlation-Id"

  // RPC headers
  val XRPCStatus: String = "X-RPC-Status"

end HttpHeader
