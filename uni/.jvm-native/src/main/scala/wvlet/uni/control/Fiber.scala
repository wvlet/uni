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

import wvlet.uni.log.LogSupport
import wvlet.uni.rx.RxScheduler
import wvlet.uni.util.Result

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import scala.util.control.NonFatal

/** Thrown by [[Fiber.join]] and [[Fiber.checkCancelled]] once a fiber has been cancelled. */
final class FiberCancelledException extends RuntimeException("Fiber was cancelled")

/**
  * A computation running concurrently on its own thread, written as plain blocking code.
  *
  * Fibers run on [[RxScheduler.blocking]]: a virtual thread on JDK 24+, a pooled platform thread on
  * JDK 17-23 and on Scala Native. Available on the JVM and Scala Native; Scala.js code uses
  * `RxFiber`.
  *
  * {{{
  *   val a = Fiber { fetch(x) }
  *   val b = Fiber { fetch(y) }
  *   (a.join(), b.join())
  * }}}
  *
  * [[cancel]] interrupts the fiber's thread, which stops `sleep`, `join`, the JDK `HttpClient`, NIO
  * channels, and socket reads on virtual threads. Calls that an interrupt cannot stop (FFI calls,
  * JDBC) register a hook with [[Fiber.onCancel]]; CPU loops call [[Fiber.checkCancelled]].
  */
final class Fiber[A] private (body: () => A):
  import Fiber.State

  private val lock = Object()
  // Written under lock; volatile so checkCancelled() in a CPU loop reads it without the lock
  @volatile
  private var state: State = State.Pending

  private var thread: Thread                         = null
  private var cancelHooks: List[() => Unit]          = Nil
  private var doneCallbacks: List[Result[A] => Unit] = Nil
  @volatile
  private var result: Result[A] = null

  private val terminated = CountDownLatch(1)

  /**
    * Wait until the fiber terminates and return its value. Rethrows the body's exception, or throws
    * [[FiberCancelledException]] if the fiber was cancelled, even when the body returned a value.
    */
  def join(): A =
    terminated.await()
    result.get

  /** The result once the fiber has terminated, without blocking. */
  def poll: Option[Result[A]] = Option(result)

  def isDone: Boolean = terminated.getCount == 0

  def isCancelled: Boolean = state == State.Cancelled

  /**
    * Cancel the fiber: interrupt its thread and run the hooks it registered with
    * [[Fiber.onCancel]], on the calling thread. Does nothing once the fiber has completed.
    */
  def cancel(): Unit =
    var hooks: List[() => Unit] = Nil
    var notStarted              = false
    lock.synchronized {
      state match
        case State.Pending =>
          state = State.Cancelled
          notStarted = true
        case State.Running =>
          state = State.Cancelled
          // Interrupt only while the body runs on this thread, so the interrupt never reaches the
          // next task on a pooled thread
          if thread != null then
            thread.interrupt()
          hooks = cancelHooks
          cancelHooks = Nil
        case _ =>
    }
    hooks.reverse.foreach(Fiber.runHook)
    if notStarted then
      finish(Result.Failure(FiberCancelledException()))

  private[control] def start(scheduler: RxScheduler = RxScheduler.blocking): Fiber[A] =
    scheduler.execute(() => run())
    this

  /** Wait until the fiber terminates, without throwing its result. */
  private[control] def awaitTermination(): Unit = terminated.await()

  /** Run `f` with the result once the fiber terminates; at once if it already has. */
  private[control] def whenDone(f: Result[A] => Unit): Unit =
    val r = lock.synchronized {
      if result == null then
        doneCallbacks = f :: doneCallbacks
      result
    }
    if r != null then
      f(r)

  private[control] def addCancelHook(hook: () => Unit): Unit =
    val runNow = lock.synchronized {
      state match
        case State.Running =>
          cancelHooks = hook :: cancelHooks
          false
        case State.Cancelled =>
          true
        case _ =>
          false
    }
    if runNow then
      Fiber.runHook(hook)

  private def run(): Unit =
    val started = lock.synchronized {
      if state == State.Pending then
        state = State.Running
        thread = Thread.currentThread()
        true
      else
        false
    }
    if started then
      Fiber.current.set(this)
      // Catch every Throwable, so join() never waits forever on a body that died
      val r =
        try
          Result.Success(body())
        catch
          case e: Throwable =>
            Result.Failure(e)
      Fiber.current.remove()
      val finalResult = lock.synchronized {
        thread = null
        cancelHooks = Nil
        if state == State.Cancelled then
          Result.Failure(FiberCancelledException())
        else
          state = State.Done
          r
      }
      // cancel() interrupts only while holding the lock with thread set, so after the block above
      // no later interrupt can arrive. Clear a pending one before the thread returns to the pool.
      Thread.interrupted()
      finish(finalResult)

  end run

  private def finish(r: Result[A]): Unit =
    val callbacks = lock.synchronized {
      result = r
      val cbs = doneCallbacks
      doneCallbacks = Nil
      cbs
    }
    // Run callbacks before releasing waiters, so a scope sees a fork's failure once it terminates
    try callbacks.reverse.foreach(cb => cb(r))
    finally terminated.countDown()

end Fiber

object Fiber extends LogSupport:

  private enum State:
    case Pending,
      Running,
      Done,
      Cancelled

  private val current = ThreadLocal[Fiber[?]]()

  /** Start `body` concurrently on [[RxScheduler.blocking]]. */
  def apply[A](body: => A): Fiber[A] = create(() => body).start()

  // `new` is needed: apply above replaces the constructor proxy
  private[control] def create[A](body: () => A): Fiber[A] = new Fiber(body)

  /**
    * True when the current fiber has been cancelled, or, outside a fiber, when the current thread
    * has been interrupted.
    */
  def isCancelled: Boolean =
    val f = current.get()
    (f != null && f.isCancelled) || Thread.currentThread().isInterrupted

  /** Throw [[FiberCancelledException]] if [[isCancelled]]: call it at safe points in CPU loops. */
  def checkCancelled(): Unit =
    if isCancelled then
      throw FiberCancelledException()

  /**
    * Register a hook that runs when the current fiber is cancelled, for calls an interrupt cannot
    * stop (e.g. `Statement.cancel` for JDBC). The hook runs on the thread that calls `cancel()`, or
    * at once if the fiber is already cancelled. Outside a fiber this does nothing.
    */
  def onCancel(hook: () => Unit): Unit =
    val f = current.get()
    if f != null then
      f.addCancelHook(hook)

  /**
    * Run `block` with a [[FiberScope]] and wait for every fiber it forked before returning. The
    * first fork that fails cancels the other forks, and its exception is rethrown. If the block
    * itself throws, the forks are cancelled too.
    */
  def scope[A](block: FiberScope => A): A =
    val s = FiberScope()
    val r =
      try
        Result.Success(block(s))
      catch
        case e: Throwable =>
          Result.Failure(e)
    if r.isFailure then
      s.cancelAll()
    s.close()
    s.failure match
      case Some(e) =>
        throw e
      case None =>
        r.get

  /** Run `a` and `b` concurrently and return both results. A failure cancels the other. */
  def par[A, B](a: => A, b: => B): (A, B) = scope { s =>
    val fa = s.fork(a)
    val fb = s.fork(b)
    (fa.join(), fb.join())
  }

  /**
    * Apply `f` to every item concurrently, running at most `maxConcurrency` at a time, and return
    * the results in input order. The first failure cancels the rest and is rethrown.
    */
  def parMap[A, B](items: Seq[A], maxConcurrency: Int)(f: A => B): Seq[B] =
    require(maxConcurrency > 0, s"maxConcurrency must be positive: ${maxConcurrency}")
    scope { s =>
      val permits = Semaphore(maxConcurrency)
      // Fork from a launcher fiber, so a failure that cancels the scope also stops one that waits
      // for a permit
      val launcher = s.fork {
        items.map { item =>
          permits.acquire()
          s.fork {
            try f(item)
            finally permits.release()
          }
        }
      }
      launcher.join().map(_.join())
    }

  /** Run `a` and `b` concurrently and return the first to succeed, cancelling the other. */
  def race[A](a: => A, b: => A): A = race(Seq(() => a, () => b))

  /**
    * Run every body concurrently and return the first to succeed, cancelling the rest. If all fail,
    * rethrows the last failure with the others added as suppressed exceptions.
    */
  def race[A](bodies: Seq[() => A]): A =
    require(bodies.nonEmpty, "race needs at least one body")
    val results = LinkedBlockingQueue[Result[A]]()
    val fibers  = bodies.map(create)
    fibers.foreach(_.whenDone(r => results.add(r)))
    fibers.foreach(_.start())
    try
      var failures = List.empty[Throwable]
      var winner   = Option.empty[A]
      while winner.isEmpty && failures.size < fibers.size do
        val r =
          try
            results.take()
          catch
            case _: InterruptedException =>
              throw FiberCancelledException()
        r match
          case Result.Success(v) =>
            winner = Some(v)
          case Result.Failure(e) =>
            failures = e :: failures
      winner match
        case Some(v) =>
          v
        case None =>
          val last = failures.head
          failures.tail.reverse.filterNot(_ eq last).foreach(last.addSuppressed)
          throw last
    finally
      fibers.foreach(_.cancel())
      awaitAll(fibers)
    end try

  end race

  /**
    * Wait for every fiber to terminate. If this thread is interrupted while waiting, cancel them
    * all, keep waiting, and then throw [[FiberCancelledException]], so no fiber outlives the wait.
    */
  private[control] def awaitAll(fibers: Iterable[Fiber[?]]): Unit =
    var cancelled = false
    fibers.foreach { f =>
      var waiting = true
      while waiting do
        try
          f.awaitTermination()
          waiting = false
        catch
          case _: InterruptedException =>
            cancelled = true
            fibers.foreach(_.cancel())
    }
    if cancelled then
      throw FiberCancelledException()

  private def runHook(hook: () => Unit): Unit =
    try
      hook()
    catch
      case NonFatal(e) =>
        warn(s"A Fiber cancel hook failed: ${e.getMessage}", e)

end Fiber

/**
  * Forks fibers that all finish before [[Fiber.scope]] returns. The first fork to fail cancels the
  * others.
  */
final class FiberScope private[control] ():
  private val lock                    = Object()
  private var forks: Vector[Fiber[?]] = Vector.empty
  private var closed: Boolean         = false
  @volatile
  private var firstFailure: Throwable = null

  // Set by cancelAll(); forks added afterwards are cancelled at once
  @volatile
  private var cancelling: Boolean = false

  /**
    * Start `body` as a fiber of this scope. Throws `IllegalStateException` once the scope has
    * closed.
    */
  def fork[A](body: => A): Fiber[A] =
    val f = Fiber.create(() => body)
    lock.synchronized {
      if closed then
        throw IllegalStateException("fork on a FiberScope that has already closed")
      forks = forks :+ f
    }
    f.whenDone {
      case Result.Failure(e) if !f.isCancelled =>
        onFailure(e)
      case _ =>
    }
    // A fork added while the scope is being cancelled could miss cancelAll()'s snapshot. cancelAll()
    // sets the flag under the same lock that adds forks, so one of the two always cancels it.
    if cancelling then
      f.cancel()
    f.start()

  /** The first failure of a fork, if any. */
  private[control] def failure: Option[Throwable] = Option(firstFailure)

  private[control] def cancelAll(): Unit =
    val snapshot = lock.synchronized {
      cancelling = true
      forks
    }
    snapshot.foreach(_.cancel())

  /** Close the scope and wait for every fork, including any a fork starts while closing. */
  private[control] def close(): Unit =
    var waited      = 0
    var done        = false
    var interrupted = false
    try
      while !done do
        val pending = lock.synchronized {
          if forks.size == waited then
            closed = true
          forks.drop(waited)
        }
        if pending.isEmpty then
          done = true
        else
          try
            Fiber.awaitAll(pending)
          catch
            case _: FiberCancelledException =>
              // Interrupted while waiting: cancel every fork, including ones a fork starts from
              // now on, and keep waiting so none outlives the scope
              interrupted = true
              cancelAll()
          waited += pending.size
    finally lock.synchronized {
        closed = true
      }
    if interrupted then
      throw FiberCancelledException()

  private def onFailure(e: Throwable): Unit =
    val first = lock.synchronized {
      if firstFailure == null then
        firstFailure = e
        true
      else
        false
    }
    if first then
      cancelAll()

end FiberScope
