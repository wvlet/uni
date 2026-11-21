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

import wvlet.uni.util.Result

/**
  * Observable event types. http://reactivex.io/documentation/observable.html
  */
sealed trait RxEvent

case class OnNext(v: Any)        extends RxEvent
case class OnError(e: Throwable) extends RxEvent

/**
  * The end of a stream, with how it ended:
  *
  * {{{
  *   case OnCompletion(Result.Success(_)) => // completed
  *   case OnCompletion(Result.Failure(e)) => // ended with a failure
  * }}}
  */
case class OnCompletion(result: Result[Unit]) extends RxEvent:
  def isSuccess: Boolean = result.isSuccess
  def isFailure: Boolean = !isSuccess

/**
  * The events `RxRunner.run` and `runOnce` deliver: they stop at the first error, which arrives as
  * the stream's end, `OnCompletion(Result.Failure(e))`, so a handler never sees an [[OnError]].
  */
type RxRunEvent = OnNext | OnCompletion

object OnCompletion:
  /**
    * The end of a stream that completed.
    */
  val success: OnCompletion = OnCompletion(Result.Success(()))

  /**
    * The end of a stream that failed with `e`.
    */
  def failure(e: Throwable): OnCompletion = OnCompletion(Result.Failure(e))

/**
  * An error raised by an operator's function (a throwing `map`, `filter`, or `flatMap` function)
  * while its source keeps running. A source that ends with an error (`RxVar.setException`, a failed
  * `Rx.future`, `Rx.exception`) reports a plain [[OnError]] instead.
  *
  * Internal: it is an [[OnError]], so `case OnError(e)` matches it and it equals `OnError(e)`, and
  * every operator treats it as one. The public entry points of [[RxRunner]] deliver it as a plain
  * `OnError`. It lets operators move one at a time to R3-style events, where `OnError` never ends a
  * stream and a stream ends only with `OnCompletion` (see `adr/2026-11-21-rx-resumable-error.md`).
  */
private[rx] final class ResumableError(e: Throwable) extends OnError(e):
  override def toString: String = s"ResumableError(${e})"

private[rx] object ResumableError:
  def apply(e: Throwable): ResumableError = new ResumableError(e)

  def unapply(ev: RxEvent): Option[Throwable] =
    ev match
      case r: ResumableError =>
        Some(r.e)
      case _ =>
        None

  /**
    * The same kind of error as `cause`, for `e`: an error raised while handling a resumable error
    * is resumable too.
    */
  def like(cause: RxEvent, e: Throwable): OnError =
    cause match
      case _: ResumableError =>
        ResumableError(e)
      case _ =>
        OnError(e)

  /**
    * The event as the public sinks see it: a resumable error is a plain [[OnError]].
    */
  def toPublic(ev: RxEvent): RxEvent =
    ev match
      case r: ResumableError =>
        OnError(r.e)
      case other =>
        other

  /**
    * The event as `RxRunner.run` and `runOnce` deliver it: they stop at the first error, so it is
    * the stream's end, `OnCompletion(Result.Failure(e))` (R3's `OnErrorResumeAsFailure`).
    */
  def toTerminal(ev: RxEvent): RxRunEvent =
    ev match
      case n: OnNext =>
        n
      case c: OnCompletion =>
        c
      case OnError(e) =>
        OnCompletion.failure(e)

end ResumableError
