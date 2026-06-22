/* Compile-checked examples from Book Chapter 8 — Living With Failure. */
package wvlet.uni.book

import wvlet.uni.control.{CircuitBreaker, CircuitBreakerOpenException, Control, Retry}

object Ch08Control:
  private def callFlakyService(): String = "ok"
  private def useFallback(): String       = "fallback"

  class Connection extends AutoCloseable:
    def query(sql: String): String = "rows"
    def get(url: String): String   = "body"
    def close(): Unit              = ()

  private def openConnection(): Connection = Connection()

  def retry(): Unit =
    val result = Retry
      .withBackOff(maxRetry = 3)
      .run {
        callFlakyService()
      }
    println(result)

  def breaker(): Unit =
    val breaker = CircuitBreaker.withConsecutiveFailures(5)
    try
      breaker.run {
        callFlakyService()
      }
    catch
      case e: CircuitBreakerOpenException =>
        useFallback()

  def resource(): Unit =
    Control.withResource(openConnection()) { conn =>
      conn.query("select 1")
    }

  def combined(url: String): String =
    val breaker = CircuitBreaker.withConsecutiveFailures(5)
    breaker.run {
      Retry.withBackOff(maxRetry = 3).run {
        Control.withResource(openConnection()) { conn =>
          conn.get(url)
        }
      }
    }
