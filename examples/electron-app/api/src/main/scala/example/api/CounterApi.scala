package example.api

import wvlet.uni.rx.Rx

/** The counter's current value, exchanged as the RPC result. */
case class CounterState(value: Int)

/**
  * A tiny RPC service shared between the Electron main process (which implements it) and the
  * renderer (which calls it over IPC). Returning `Rx[A]` keeps the calls asynchronous — IPC is
  * inherently async.
  */
trait CounterApi:
  def get(): Rx[CounterState]
  def increment(amount: Int): Rx[CounterState]
  def reset(): Rx[CounterState]
