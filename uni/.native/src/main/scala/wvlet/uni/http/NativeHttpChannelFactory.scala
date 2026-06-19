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

import scala.scalanative.unsafe.{CInt, fromCString}

/**
  * Scala Native-specific HTTP channel factory using libcurl.
  *
  * This factory is automatically registered when the Native module is loaded. Requires libcurl to
  * be installed on the system.
  */
object NativeHttpChannelFactory extends HttpChannelFactory:
  import CurlBindings.*

  // Initialize libcurl globally when this object is loaded
  private val initResult: CInt = curl_global_init(CURL_GLOBAL_DEFAULT)

  /**
    * Check if libcurl was initialized successfully
    */
  def isInitialized: Boolean = initResult == CURLE_OK

  /**
    * Get the libcurl version string
    */
  def curlVersion: String = fromCString(curl_version())

  override def newChannel: HttpChannel =
    if !isInitialized then
      throw IllegalStateException(
        s"Failed to initialize libcurl (error code: ${initResult}). " +
          "Please ensure libcurl is properly installed on your system."
      )
    CurlChannel()

  override def newAsyncChannel: HttpAsyncChannel =
    if !isInitialized then
      throw IllegalStateException(
        s"Failed to initialize libcurl (error code: ${initResult}). " +
          "Please ensure libcurl is properly installed on your system."
      )
    CurlAsyncChannel()

  override def newWebSocketClient: WebSocketClient = NativeWebSocketClient()

end NativeHttpChannelFactory
