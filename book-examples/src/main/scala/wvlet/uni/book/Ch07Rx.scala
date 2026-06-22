/* Compile-checked examples from Book Chapter 7 — Rx, the Composable Stream. */
package wvlet.uni.book

import wvlet.uni.rx.Rx

object Ch07Rx:
  def aValueThatChanges(): Unit =
    val count = Rx.variable(0)
    val sub = count.subscribe { v =>
      println(s"count is now ${v}")
    }
    count := 1
    count := 2
    sub.cancel

  def composing(): Unit =
    val count = Rx.variable(0)
    val label: Rx[String] =
      count
        .filter(_ % 2 == 0)
        .map(n => s"even: ${n}")
    label.subscribe(println)
    count := 1
    count := 2
