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
package wvlet.uni.util

import scala.util.control.NonFatal
import scala.util.{Try, Success as TrySuccess, Failure as TryFailure}

/**
  * A standard container for "a value that may have failed," carrying a `Throwable` on failure.
  *
  * `Result[A]` is Uni's Rust-flavored counterpart to `scala.util.Try`: it wraps exceptions into a
  * value so that callers can compose potentially-failing computations without losing Scala's native
  * exception mechanism. Its combinators (`recover`, `recoverWith`, `map`, `flatMap`, ...) mirror
  * those of [[wvlet.uni.rx.Rx]] so switching between the two is mechanical.
  *
  * Prefer `Result` at API boundaries that need to represent failure as a value; keep normal `throw`
  * / `try` inside internal code.
  */
enum Result[+A]:
  case Success(value: A)         extends Result[A]
  case Failure(error: Throwable) extends Result[Nothing]

  def isSuccess: Boolean =
    this match
      case _: Success[?] =>
        true
      case _: Failure =>
        false

  def isFailure: Boolean = !isSuccess

  /** Return the wrapped value, or rethrow the wrapped `Throwable` on failure. */
  def get: A =
    this match
      case Success(v) =>
        v
      case Failure(e) =>
        throw e

  def getOrElse[B >: A](default: => B): B =
    this match
      case Success(v) =>
        v
      case _: Failure =>
        default

  def orElse[B >: A](alt: => Result[B]): Result[B] =
    this match
      case _: Success[?] =>
        this
      case _: Failure =>
        alt

  /** Apply `f` to the success value. Exceptions thrown by `f` become `Failure`. */
  def map[B](f: A => B): Result[B] =
    this match
      case Success(v) =>
        try
          Result.Success(f(v))
        catch
          case NonFatal(e) =>
            Result.Failure(e)
      case fail: Failure =>
        fail

  /** Chain another `Result`-returning step. Exceptions thrown by `f` become `Failure`. */
  def flatMap[B](f: A => Result[B]): Result[B] =
    this match
      case Success(v) =>
        try
          f(v)
        catch
          case NonFatal(e) =>
            Result.Failure(e)
      case fail: Failure =>
        fail

  /**
    * Keep the success if `p` holds, otherwise turn it into a `Failure(NoSuchElementException)`.
    * Enables `for`-comprehension guards.
    */
  def filter(p: A => Boolean): Result[A] =
    this match
      case Success(v) =>
        try
          if p(v) then
            this
          else
            Result.Failure(new NoSuchElementException("Result.filter predicate is not satisfied"))
        catch
          case NonFatal(e) =>
            Result.Failure(e)
      case _: Failure =>
        this

  /** Alias of [[filter]] for `for`-comprehension support. */
  def withFilter(p: A => Boolean): Result[A] = filter(p)

  def foreach(f: A => Unit): Unit =
    this match
      case Success(v) =>
        f(v)
      case _: Failure =>
        ()

  /** Recover from a matching throwable by emitting a replacement value. Mirrors `Rx.recover`. */
  def recover[B >: A](pf: PartialFunction[Throwable, B]): Result[B] =
    this match
      case Failure(e) if pf.isDefinedAt(e) =>
        try
          Result.Success(pf(e))
        catch
          case NonFatal(e2) =>
            Result.Failure(e2)
      case _ =>
        this

  /** Recover from a matching throwable by returning another `Result`. Mirrors `Rx.recoverWith`. */
  def recoverWith[B >: A](pf: PartialFunction[Throwable, Result[B]]): Result[B] =
    this match
      case Failure(e) if pf.isDefinedAt(e) =>
        try
          pf(e)
        catch
          case NonFatal(e2) =>
            Result.Failure(e2)
      case _ =>
        this

  /** Translate the wrapped throwable on failure. */
  def mapError(f: Throwable => Throwable): Result[A] =
    this match
      case Failure(e) =>
        Result.Failure(f(e))
      case _ =>
        this

  /** Eliminator: handle both branches. */
  def fold[B](onFailure: Throwable => B, onSuccess: A => B): B =
    this match
      case Success(v) =>
        onSuccess(v)
      case Failure(e) =>
        onFailure(e)

  def toOption: Option[A] =
    this match
      case Success(v) =>
        Some(v)
      case _: Failure =>
        None

  def toEither: Either[Throwable, A] =
    this match
      case Success(v) =>
        Right(v)
      case Failure(e) =>
        Left(e)

  def toTry: Try[A] =
    this match
      case Success(v) =>
        TrySuccess(v)
      case Failure(e) =>
        TryFailure(e)

end Result

object Result:

  /**
    * Evaluate `body` and wrap the outcome. Non-fatal exceptions become `Failure`; fatal errors
    * (e.g. `InterruptedException`, `VirtualMachineError`) escape the wrapper.
    */
  def apply[A](body: => A): Result[A] =
    try
      Success(body)
    catch
      case NonFatal(e) =>
        Failure(e)

  def success[A](v: A): Result[A]            = Success(v)
  def failure(e: Throwable): Result[Nothing] = Failure(e)

  def fromTry[A](t: Try[A]): Result[A] =
    t match
      case TrySuccess(v) =>
        Success(v)
      case TryFailure(e) =>
        Failure(e)

  def fromEither[A](e: Either[Throwable, A]): Result[A] =
    e match
      case Right(v) =>
        Success(v)
      case Left(err) =>
        Failure(err)

  def fromOption[A](o: Option[A], ifNone: => Throwable): Result[A] =
    o match
      case Some(v) =>
        Success(v)
      case None =>
        Failure(ifNone)

end Result
