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
package wvlet.uni.rx

/**
  * An [[Rx]] whose values are all meant for the receiver, not only the first one. As the return
  * type of an RPC service method, it marks the method as server-streaming: generated async clients
  * call it with `stream`, so they receive every value, while `Rx[A]` methods stay unary calls.
  *
  * Build one by wrapping an Rx; operators return a plain Rx, so wrap the finished chain:
  * {{{
  * trait ChatApi:
  *   def streamMessage(text: String): RxStream[ChatEvent]
  *
  * class ChatApiImpl extends ChatApi:
  *   def streamMessage(text: String): RxStream[ChatEvent] = RxStream(replies(text).map(toEvent))
  * }}}
  */
trait RxStream[+A] extends Rx[A]

object RxStream:
  /** Wrap `rx`; the result runs exactly like `rx`. */
  def apply[A](rx: RxOps[A]): RxStream[A] = StreamOp(rx)

  private[rx] case class StreamOp[A](input: RxOps[A]) extends RxStream[A]:
    override def parents: Seq[RxOps[?]] = Seq(input)
    override def toString: String       = s"RxStream(${input})"
