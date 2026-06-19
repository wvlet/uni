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
  * JVM-specific HTTP channel factory using Java 11+ HttpClient.
  *
  * This factory is automatically registered when the JVM module is loaded, enabling Http.client to
  * work out of the box on the JVM platform.
  */
object JVMHttpChannelFactory extends HttpChannelFactory:

  override def newChannel: HttpChannel = JavaHttpChannel()

  override def newAsyncChannel: HttpAsyncChannel = JavaHttpAsyncChannel()

  override def newWebSocketClient: WebSocketClient = JavaWebSocketClient()

end JVMHttpChannelFactory
