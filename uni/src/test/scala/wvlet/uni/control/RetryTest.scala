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
package wvlet.uni.control

import wvlet.uni.control.Retry.MaxRetryException
import wvlet.uni.control.Retry.RetryContext
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest
import wvlet.uni.test.empty
import wvlet.uni.test.defined

import scala.concurrent.TimeoutException

/**
  */
class RetryTest extends UniTest:

  test("support backoff retry") {
    var count = 0

    val r = Retry.withBackOff(maxRetry = 3).noRetryLogging

    val e = r.run {
      count += 1
      trace("hello retry")
      if count < 3 then
        throw new IllegalStateException("retry test")
      else
        "success"
    }

    e shouldBe "success"
    count shouldBe 3
  }

  test("support bounded backoff retry") {
    val maxWait = 50000
    var r       =
      Retry
        .withBoundedBackoff(initialIntervalMillis = 1000, maxTotalWaitMillis = maxWait)
        .noRetryLogging

    r = r.init()
    var waitTotal = 0
    while r.canContinue do
      waitTotal += r.nextWaitMillis
      r = r.nextRetry(new IllegalStateException())
    waitTotal <= maxWait shouldBe true
  }

  test("support jitter retry") {
    var count = 0

    val r = Retry
      .withJitter(maxRetry = 3)
      .noRetryLogging
      .run {
        count += 1
        trace("hello retry")
        if count < 2 then
          throw new IllegalStateException("retry test")
        else
          "success"
      }

    r shouldBe "success"
    count shouldBe 2
  }

  test("throw max retry exception") {
    val e = intercept[MaxRetryException] {
      Retry
        .withBackOff(maxRetry = 3)
        .noRetryLogging
        .retryOn { case e: IllegalStateException =>
          trace(e.getMessage)
          ResultClass.retryableFailure(e)
        }
        .run {
          trace("hello retry")
          throw new IllegalStateException("retry test")
        }
    }

    e.retryContext.maxRetry shouldBe 3
    e.retryContext.retryCount shouldBe 3
    e.retryContext.lastError.getClass shouldBe classOf[IllegalStateException]
  }

  test("change retry wait strategy") {
    val r = Retry.withJitter().withBackOff(initialIntervalMillis = 3)

    r.retryWaitStrategy.retryPolicyConfig.initialIntervalMillis shouldBe 3

    val j = r.withJitter(initialIntervalMillis = 20)
    j.retryWaitStrategy.retryPolicyConfig.initialIntervalMillis shouldBe 20

    val m = j.withMaxRetry(100)
    m.maxRetry shouldBe 100
  }

  test("pass the execution context") {
    val r = Retry.withBackOff(initialIntervalMillis = 0)

    var count   = 0
    var checked = false
    r.beforeRetry { (ctx: RetryContext) =>
        ctx.context shouldBe Some("hello world")
        checked = true
      }
      .runWithContext("hello world") {
        if count == 0 then
          count += 1
          throw new Exception()
      }

    count shouldBe 1
    checked shouldBe true
  }

  test("add extra wait") {
    intercept[MaxRetryException] {
      Retry
        .withBackOff(initialIntervalMillis = 10)
        .withMaxRetry(1)
        .withErrorClassifier { case e: TimeoutException =>
          ResultClass.retryableFailure(e).withExtraWaitMillis(100)
        }
        .beforeRetry { (ctx: RetryContext) =>
          debug(s"${ctx.retryCount} ${ctx.nextWaitMillis}")
          ctx.nextWaitMillis shouldBe 10 + 100
        }
        .run {
          throw new TimeoutException()
        }
    }
  }

  test("add extra wait factor") {
    intercept[MaxRetryException] {
      Retry
        .withBackOff(initialIntervalMillis = 10)
        .withMaxRetry(1)
        .withErrorClassifier { case e: TimeoutException =>
          ResultClass.retryableFailure(e).withExtraWaitFactor(0.2)
        }
        .beforeRetry { (ctx: RetryContext) =>
          debug(s"${ctx.retryCount} ${ctx.nextWaitMillis}")
          ctx.nextWaitMillis shouldBe 10 + 2
        }
        .run {
          throw new TimeoutException()
        }
    }
  }

  // A long backoff that a test must never wait for
  private val longBackoffMillis = 10000

  private def elapsedMillisSince(startNanos: Long): Long =
    (System.nanoTime() - startNanos) / 1000000

  private def alwaysRetryable(maxRetry: Int, initialIntervalMillis: Int): RetryContext = Retry
    .withBackOff(
      maxRetry = maxRetry,
      initialIntervalMillis = initialIntervalMillis,
      multiplier = 1.0
    )
    .noRetryLogging
    .retryOn { case e: IllegalStateException =>
      ResultClass.retryableFailure(e)
    }

  test("fail without waiting the backoff when no retry is allowed") {
    var count = 0
    val start = System.nanoTime()
    val e     = intercept[MaxRetryException] {
      alwaysRetryable(maxRetry = 0, initialIntervalMillis = longBackoffMillis).run {
        count += 1
        throw IllegalStateException("retry test")
      }
    }
    (elapsedMillisSince(start) < longBackoffMillis / 2) shouldBe true
    count shouldBe 1
    e.retryContext.maxRetry shouldBe 0
    e.retryContext.retryCount shouldBe 1
    e.retryContext.lastError shouldMatch { case _: IllegalStateException =>
    }
  }

  test("wait the backoff between retries") {
    var count = 0
    val start = System.nanoTime()
    intercept[MaxRetryException] {
      alwaysRetryable(maxRetry = 3, initialIntervalMillis = 50).run {
        count += 1
        throw IllegalStateException("retry test")
      }
    }
    count shouldBe 3
    // Scala.js has no blocking sleep, so the sync path cannot wait there
    if !isScalaJS then
      (elapsedMillisSince(start) >= 90) shouldBe true
  }

  test("fail an async call without waiting the backoff when no retry is allowed") {
    var count = 0
    val start = System.nanoTime()
    alwaysRetryable(maxRetry = 0, initialIntervalMillis = longBackoffMillis)
      .runAsyncWithContext(()) {
        count += 1
        Rx.exception[String](IllegalStateException("retry test"))
      }
      .map(_ => fail("should not succeed"))
      .recover { case e: MaxRetryException =>
        (elapsedMillisSince(start) < longBackoffMillis / 2) shouldBe true
        count shouldBe 1
        e.retryContext.maxRetry shouldBe 0
        e.retryContext.retryCount shouldBe 1
        e.retryContext.lastError shouldMatch { case _: IllegalStateException =>
        }
      }
  }

  test("retry an async call N times with the backoff") {
    var count = 0
    val start = System.nanoTime()
    alwaysRetryable(maxRetry = 3, initialIntervalMillis = 50)
      .runAsyncWithContext(()) {
        count += 1
        Rx.exception[String](IllegalStateException("retry test"))
      }
      .map(_ => fail("should not succeed"))
      .recover { case e: MaxRetryException =>
        count shouldBe 3
        e.retryContext.retryCount shouldBe 3
        (elapsedMillisSince(start) >= 90) shouldBe true
      }
  }

end RetryTest
