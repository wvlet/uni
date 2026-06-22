/* Compile-checked examples from Book Chapter 5 — Logging That Finds You. */
package wvlet.uni.book

import wvlet.uni.log.LogSupport

object Ch05Logging:
  class OrderService extends LogSupport:
    def placeOrder(id: String): Unit =
      info(s"Placing order ${id}")
      debug(s"order details for ${id}")

  def expensiveSnapshot(): String = "state"

  class Diagnostics extends LogSupport:
    def dump(): Unit =
      // Unguarded: the message is only built when DEBUG is enabled.
      debug(s"State dump: ${expensiveSnapshot()}")

  class PaymentService extends LogSupport:
    private def gatewayCharge(amount: Int): Unit = ()
    def charge(amount: Int): Unit =
      try gatewayCharge(amount)
      catch
        case e: Exception =>
          error(s"Charge of ${amount} failed", e)
          throw e
