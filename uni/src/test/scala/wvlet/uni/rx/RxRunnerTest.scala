package wvlet.uni.rx

import wvlet.uni.test.UniTest
import wvlet.uni.test.empty
import wvlet.uni.test.defined
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.concurrent.Promise

object RxRunnerTest extends UniTest:

  private val ex = new IllegalArgumentException("dummy")

  private def pendingScalaJS =
    if isScalaJS then
      pending("Async test is required")

  test("cancel") {
    test("concat") {
      val c = Rx
        .concat(Rx.const(1), Rx.const(2))
        .run { (event: Int) =>
        }
      c.cancel
    }
  }

  test("Propagate errors as events") {
    test("last") {
      val rx = Rx
        .sequence(1, 2)
        .lastOption
        .map[Int] { x =>
          throw ex
        }
      var c: Cancelable = Cancelable.empty
      RxRunner.run(rx) {
        case OnError(e) =>
          e shouldBeTheSameInstanceAs ex
        case OnCompletion =>
        // ok
        case other =>
          fail(s"should not reach here: ${other}")
      }
      c.cancel
    }

    test("cache") {
      val rx =
        Rx.const(1)
          .map[Int] { x =>
            throw ex
          }
          .cache
      var c = Cancelable.empty
      c =
        RxRunner.run(rx) {
          case OnError(e) =>
            e shouldBeTheSameInstanceAs ex
          case OnCompletion =>
          // ok
          case other =>
            fail(s"should not reach here: ${other}")
        }
      c.cancel
    }

    test("take") {
      val rx = Rx
        .const(1)
        .map { x =>
          throw ex
        }
        .take(1)
      var c = Cancelable.empty
      c =
        RxRunner.run(rx) {
          case OnError(e) =>
            e shouldBeTheSameInstanceAs ex
          case OnCompletion =>
          // ok
          case other =>
            fail(s"should not reach here: ${other}")
        }
      c.cancel

    }

    test("timer") {
      pendingScalaJS

      val rx = Rx
        .timer(1, TimeUnit.MILLISECONDS)
        .map[Int] { x =>
          throw ex
        }
      var c = Cancelable.empty
      c =
        RxRunner.run(rx) {
          case OnError(e) =>
            e shouldBeTheSameInstanceAs ex
          case other =>
            fail(s"should not be here: ${other}")
        }
      // Wait a bit
      compat.scheduleOnce(10) {
        c.cancel
      }
    }

  }

  test("filter does not complete the stream on rejection") {
    test("events") {
      val b = Seq.newBuilder[RxEvent]
      RxRunner.run(Rx.sequence(1, 2, 3).filter(_ % 2 == 1))(b += _)
      b.result() shouldBe Seq(OnNext(1), OnNext(3), OnCompletion)
    }

    test("concat runs after the upstream completes") {
      val b = Seq.newBuilder[Int]
      Rx.sequence(1, 2, 3).filter(_ % 2 == 1).concat(Rx.single(10)).run(b += _)
      b.result() shouldBe Seq(1, 3, 10)
    }

    test("lastOption") {
      val b = Seq.newBuilder[Option[Int]]
      Rx.sequence(1, 2, 3).filter(_ < 3).lastOption.run(b += _)
      b.result() shouldBe Seq(Some(2))
    }

    test("a rejected Rx.const still completes") {
      val b = Seq.newBuilder[RxEvent]
      RxRunner.runOnce(Rx.const(1).filter(_ > 1))(b += _)
      b.result() shouldBe Seq(OnCompletion)
    }
  }

  test("runOnce over a pending Rx.future") {
    // Run future callbacks synchronously so the event order is deterministic on every platform
    given ExecutionContext = ExecutionContext.parasitic
    val p                  = Promise[Int]()
    val b                  = Seq.newBuilder[RxEvent]
    RxRunner.runOnce(Rx.future(p.future))(b += _)
    // No event while the future is pending
    b.result() shouldBe Seq.empty
    p.success(1)
    // Only the first event is reported, not the OnCompletion that follows the value
    b.result() shouldBe Seq(OnNext(1))
  }

  test("flatMap completes after an asynchronous nested stream") {
    given ExecutionContext = ExecutionContext.parasitic
    val p                  = Promise[Int]()
    val b                  = Seq.newBuilder[RxEvent]
    RxRunner.run(Rx.single(1).flatMap(_ => Rx.future(p.future)))(b += _)
    // The input has completed, but the nested stream is still pending
    b.result() shouldBe Seq.empty
    p.success(2)
    b.result() shouldBe Seq(OnNext(2), OnCompletion)
  }

  test("flatMap completes after a recovered nested failure") {
    given ExecutionContext = ExecutionContext.parasitic
    val p                  = Promise[Int]()
    val b                  = Seq.newBuilder[RxEvent]
    RxRunner.run(
      Rx.single(1)
        .flatMap(_ => Rx.future(p.future))
        .recover { case _ =>
          0
        }
    )(b += _)
    p.failure(ex)
    b.result() shouldBe Seq(OnNext(0), OnCompletion)
  }

  test("runOnce reports only the first event") {
    val b = Seq.newBuilder[RxEvent]
    RxRunner.runOnce(Rx.single(1))(b += _)
    b.result() shouldBe Seq(OnNext(1))
  }

end RxRunnerTest
