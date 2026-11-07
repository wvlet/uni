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
package wvlet.uni.http.rpc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks an RPC method that returns {@code Rx[A]} as a streaming method. sbt-uni's
 * {@code uniHttpClients} generator then writes {@code rpcClient.stream} for it, so the generated
 * async client delivers every value the server's Rx emits, not only the first one.
 *
 * <pre>{@code
 * trait ChatApi:
 *   @rpcStream
 *   def streamMessage(conversationId: String, text: String): Rx[ChatEvent]
 * }</pre>
 *
 * The marker doesn't change the server or the wire format: the server streams whenever the
 * request accepts {@code text/event-stream} first, which {@code RPCAsyncClient.stream} does. It is
 * a Java annotation with runtime retention so the generator, which reads compiled classes by
 * reflection, can see it.
 */
@Retention(RUNTIME)
@Target(ElementType.METHOD)
public @interface rpcStream {
}
