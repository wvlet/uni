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

import wvlet.uni.rx.OnCompletion
import wvlet.uni.rx.OnError
import wvlet.uni.rx.OnNext
import wvlet.uni.rx.Rx
import wvlet.uni.rx.RxEvent
import wvlet.uni.rx.RxRunner
import wvlet.uni.test.UniTest

import scala.util.{Failure as TryFailure, Success as TrySuccess}

class ResultTest extends UniTest:

  private val boom = new RuntimeException("boom")

  test("Result.apply wraps non-fatal throwables") {
    Result(1) shouldBe Result.Success(1)
    Result(throw boom) shouldBe Result.Failure(boom)
  }

  test("fatal errors escape Result.apply") {
    intercept[InterruptedException] {
      Result(throw new InterruptedException("fatal"))
    }
  }

  test("map/flatMap propagate thrown exceptions as Failure") {
    val r1: Result[Int] = Result.Success(1).map(_ => throw boom)
    r1 shouldMatch { case Result.Failure(e) =>
      e shouldBe boom
    }

    val r2: Result[Int] = Result.Success(1).flatMap(_ => throw boom)
    r2 shouldMatch { case Result.Failure(e) =>
      e shouldBe boom
    }

    Result.Failure(boom).map((x: Int) => x + 1) shouldBe Result.Failure(boom)
    Result.Failure(boom).flatMap((x: Int) => Result.Success(x + 1)) shouldBe Result.Failure(boom)
  }

  test("for-comprehension short-circuits on Failure") {
    val good: Result[Int] = Result.Success(1)
    val bad: Result[Int]  = Result.Failure(boom)

    val sum =
      for
        a <- good
        b <- good
      yield a + b
    sum shouldBe Result.Success(2)

    val shortCircuited =
      for
        a <- good
        b <- bad
        c <- good
      yield a + b + c
    shortCircuited shouldBe Result.Failure(boom)
  }

  test("filter turns a failing predicate into Failure(NoSuchElementException)") {
    val kept: Result[Int] = Result.Success(1).filter(_ > 0)
    kept shouldBe Result.Success(1)

    Result.Success(0).filter(_ > 0) shouldMatch { case Result.Failure(_: NoSuchElementException) =>
    }
  }

  test("recover / recoverWith only fire for matching exceptions") {
    val matchAll: PartialFunction[Throwable, Int] = { case _: RuntimeException =>
      42
    }
    val matchIO: PartialFunction[Throwable, Int] = { case _: java.io.IOException =>
      42
    }

    Result.Failure(boom).recover(matchAll) shouldBe Result.Success(42)
    Result.Failure(boom).recover(matchIO) shouldBe Result.Failure(boom)

    Result
      .Failure(boom)
      .recoverWith { case _: RuntimeException =>
        Result.Success(7)
      } shouldBe Result.Success(7)

    Result.Success(1).recover(matchAll) shouldBe Result.Success(1)
  }

  test("recover / recoverWith catch exceptions thrown inside the partial function") {
    val throwingRecover: PartialFunction[Throwable, Int] = { case _: RuntimeException =>
      throw new IllegalStateException("recovery failed")
    }
    Result.Failure(boom).recover(throwingRecover) shouldMatch {
      case Result.Failure(e: IllegalStateException) =>
        e.getMessage shouldBe "recovery failed"
    }

    val throwingRecoverWith: PartialFunction[Throwable, Result[Int]] = { case _: RuntimeException =>
      throw new IllegalStateException("recoverWith failed")
    }
    Result.Failure(boom).recoverWith(throwingRecoverWith) shouldMatch {
      case Result.Failure(e: IllegalStateException) =>
        e.getMessage shouldBe "recoverWith failed"
    }
  }

  test("mapError translates the wrapped throwable") {
    val wrapped = Result.Failure(boom).mapError(e => new IllegalStateException(e))
    wrapped shouldMatch { case Result.Failure(e: IllegalStateException) =>
      e.getCause shouldBe boom
    }
    Result.Success(1).mapError(_ => boom) shouldBe Result.Success(1)
  }

  test("mapError catches exceptions thrown by the translator") {
    val translated = Result
      .Failure(boom)
      .mapError(_ => throw new IllegalStateException("translator failed"))
    translated shouldMatch { case Result.Failure(e: IllegalStateException) =>
      e.getMessage shouldBe "translator failed"
    }
  }

  test("conversions to Option/Either/Try") {
    Result.Success(1).toOption shouldBe Some(1)
    Result.Failure(boom).toOption shouldBe None

    Result.Success(1).toEither shouldBe Right(1)
    Result.Failure(boom).toEither shouldBe Left(boom)

    Result.Success(1).toTry shouldBe TrySuccess(1)
    Result.Failure(boom).toTry shouldBe TryFailure(boom)
  }

  test("fromTry / fromEither / fromOption") {
    Result.fromTry(TrySuccess(1)) shouldBe Result.Success(1)
    Result.fromTry(TryFailure(boom)) shouldBe Result.Failure(boom)

    Result.fromEither(Right(1)) shouldBe Result.Success(1)
    Result.fromEither(Left(boom)) shouldBe Result.Failure(boom)

    Result.fromOption(Some(1), boom) shouldBe Result.Success(1)
    Result.fromOption(None, boom) shouldBe Result.Failure(boom)
  }

  test("getOrElse / orElse / fold / get") {
    Result.Success(1).getOrElse(0) shouldBe 1
    Result.Failure(boom).getOrElse(0) shouldBe 0

    Result.Failure(boom).orElse(Result.Success(2)) shouldBe Result.Success(2)
    Result.Success(1).orElse(Result.Success(2)) shouldBe Result.Success(1)

    Result.Success(1).fold(_ => -1, _ + 10) shouldBe 11
    Result.Failure(boom).fold(_ => -1, (x: Int) => x + 10) shouldBe -1

    Result.Success(1).get shouldBe 1
    intercept[RuntimeException] {
      Result.Failure(boom).get
    }
  }

  test("Rx.materialize reifies OnNext and OnError as Result values and completes") {
    val events = Seq.newBuilder[RxEvent]
    RxRunner.run(Rx.exception[Int](boom).materialize)(events += _)
    events.result() shouldBe Seq(OnNext(Result.Failure(boom)), OnCompletion)

    val ok = Seq.newBuilder[RxEvent]
    RxRunner.run(Rx.single(1).materialize)(ok += _)
    ok.result() shouldBe Seq(OnNext(Result.Success(1)), OnCompletion)
  }

  test("Rx.materialize emits Result values for multi-element streams") {
    val events = Seq.newBuilder[RxEvent]
    RxRunner.run(Rx.fromSeq(Seq(1, 2, 3)).materialize)(events += _)
    events.result() shouldBe
      Seq(
        OnNext(Result.Success(1)),
        OnNext(Result.Success(2)),
        OnNext(Result.Success(3)),
        OnCompletion
      )
  }

  test("Rx.fromResult and Result.toRx round-trip") {
    import wvlet.uni.rx.ResultConverter

    val successEvents = Seq.newBuilder[RxEvent]
    RxRunner.run(Rx.fromResult(Result.Success(1)))(successEvents += _)
    successEvents.result() shouldMatch { case OnNext(1) +: _ =>
    }

    val failureEvents = Seq.newBuilder[RxEvent]
    RxRunner.run(Result.Failure(boom).toRx)(failureEvents += _)
    failureEvents.result() shouldMatch { case OnError(e) +: _ =>
      e shouldBe boom
    }
  }

end ResultTest
