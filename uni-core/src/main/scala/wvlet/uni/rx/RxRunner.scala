package wvlet.uni.rx

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import wvlet.uni.log.LogSupport
import wvlet.uni.util.Result

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/**
  * States for propagating the result of the downstream operators.
  *
  * TODO: Add a state for telling how many elements can be received in downstream operators for
  * implementing back-pressure
  */
sealed trait RxResult:
  def toContinue: Boolean
  def &&(other: RxResult): RxResult =
    if this.toContinue && other.toContinue then
      RxResult.Continue
    else
      RxResult.Stop

object RxResult:
  object Continue extends RxResult:
    override def toContinue: Boolean = true

  object Stop extends RxResult:
    override def toContinue: Boolean = false

object RxRunner extends LogSupport:

  private val defaultRunner = new RxRunner(continuous = false)
  // Used for continuous RxVar evaluation (e.g., RxVar -> DOM rendering).
  // This runner will not report OnCompleiton event
  private val continuousRunner = new RxRunner(continuous = true)

  def run[A, U](rx: RxOps[A])(effect: RxEvent => U): Cancelable =
    defaultRunner.run(rx) { ev =>
      ev match
        case v @ OnNext(_) =>
          effect(v)
          RxResult.Continue
        case other =>
          effect(other)
          RxResult.Stop
    }

  /**
    * Run until the first event is observed. Only the first event (OnNext, OnError, or OnCompletion)
    * is passed to the effect: some sources (e.g., RxVar, Rx.single) keep emitting after the
    * downstream returns Stop, and later events are dropped here. The upstream subscription is not
    * cancelled, so its own completion still drives operators such as concat.
    */
  def runOnce[A, U](rx: RxOps[A])(effect: RxEvent => U): Cancelable =
    val observed = AtomicBoolean(false)
    defaultRunner.run(rx) { ev =>
      if observed.compareAndSet(false, true) then
        effect(ev)
      RxResult.Stop
    }

  def runContinuously[A, U](rx: RxOps[A])(effect: RxEvent => U): Cancelable =
    continuousRunner.run(rx) { ev =>
      ev match
        case v @ OnNext(_) =>
          effect(v)
          RxResult.Continue
        case other =>
          effect(other)
          RxResult.Stop
    }

end RxRunner

class RxRunner(
    // If this value is true, evaluating Rx keeps reporting events after OnError or OnCompletion is observed
    continuous: Boolean
) extends LogSupport:
  runner =>

  import Rx.*

  /**
    * Build an executable chain of Rx operators. The resulting chain will be registered as a
    * subscriber to the root node (see RxVar.foreach). If the root value changes, the effect code
    * block will be executed.
    *
    * @param rx
    * @param effect
    *   a function to process the generated RxEvent. This function must return [[RxResult.Continue]]
    *   when the downstream operator can receive further events (OnNext). If the leaf sink operator
    *   issued OnError or OnCompletion event, this must return [[RxResult.Stop]].
    * @tparam A
    */
  def run[A](rx: RxOps[A])(effect: RxEvent => RxResult): Cancelable =
    rx match
      case MapOp(in, f) =>
        run(in) {
          case OnNext(v) =>
            Try(f.asInstanceOf[Any => A](v)) match
              case Success(x) =>
                effect(OnNext(x))
              case Failure(e) =>
                effect(OnError(e))
          case other =>
            effect(other)
        }
      case fm @ FlatMapOp(in, f) =>
        // This var is a placeholder to remember the preceding Cancelable operator, which will be updated later
        var c1      = Cancelable.empty
        val tracker = NestedStreamTracker()
        val c2      =
          run(fm.input) {
            case OnNext(x) =>
              var toContinue: RxResult = RxResult.Continue
              Try(fm.f.asInstanceOf[Function[Any, RxOps[?]]](x)) match
                case Success(rxb) =>
                  // This code is necessary to properly cancel the effect if this operator is evaluated before
                  c1.cancel
                  val current = tracker.startNested()
                  c1 =
                    run(rxb) {
                      case n @ OnNext(x) =>
                        toContinue = effect(n)
                        toContinue
                      case OnCompletion =>
                        if tracker.endNested(current) then
                          toContinue = effect(OnCompletion)
                        toContinue
                      case ev @ OnError(e) =>
                        toContinue = tracker.nestedError(current, ev, effect)
                        toContinue
                    }
                  toContinue
                case Failure(e) =>
                  effect(OnError(e))
              end match
            case OnCompletion =>
              tracker.completeInput(effect)
            case other =>
              effect(other)
          }
        Cancelable { () =>
          c1.cancel;
          c2.cancel
        }
      case FilterOp(in, cond) =>
        run(in) { ev =>
          ev match
            case OnNext(x) =>
              Try(cond.asInstanceOf[A => Boolean](x.asInstanceOf[A])) match
                case Success(true) =>
                  effect(OnNext(x))
                case Success(false) =>
                  // Skip the rejected value. OnCompletion is reported only when the upstream
                  // completes; emitting it here would end runOnce/await (and trigger concat) before
                  // a later value, e.g. a pending Rx.future, arrives
                  RxResult.Continue
                case Failure(e) =>
                  effect(OnError(e))
            case other =>
              effect(other)
        }
      case TransformOp(in, f) =>
        val tryFunc = f.asInstanceOf[Try[?] => ?]
        run(in) { ev =>
          ev match
            case OnNext(x) =>
              Try(tryFunc(Success(x))) match
                case Success(x) =>
                  effect(OnNext(x))
                case Failure(e) =>
                  effect(OnError(e))
            case OnError(e) =>
              Try(tryFunc(Failure(e))) match
                case Success(x) =>
                  effect(OnNext(x))
                case Failure(e) =>
                  effect(OnError(e))
            case other =>
              effect(other)
        }
      case TransformTryOp(in, f) =>
        val tryFunc = f.asInstanceOf[Try[?] => Try[?]]
        run(in) { ev =>
          ev match
            case OnNext(x) =>
              tryFunc(Success(x)) match
                case Success(x) =>
                  effect(OnNext(x))
                case Failure(e) =>
                  effect(OnError(e))
            case OnError(e) =>
              tryFunc(Failure(e)) match
                case Success(x) =>
                  effect(OnNext(x))
                case Failure(e) =>
                  effect(OnError(e))
            case other =>
              effect(other)
        }
      case TransformRxOp(in, f) =>
        val tryFunc = f.asInstanceOf[Try[?] => RxOps[?]]
        // A place holder for properly cancel the subscription against the result of Try[?] => Rx[?]
        var c1: Cancelable = Cancelable.empty
        val tracker        = NestedStreamTracker()

        def evalRx(rxb: RxOps[?]): RxResult =
          var toContinue: RxResult = RxResult.Continue
          c1.cancel
          val current = tracker.startNested()
          c1 =
            run(rxb) {
              case n @ OnNext(x) =>
                toContinue = effect(n)
                toContinue
              case OnCompletion =>
                if tracker.endNested(current) then
                  toContinue = effect(OnCompletion)
                toContinue
              case ev @ OnError(e) =>
                toContinue = tracker.nestedError(current, ev, effect)
                toContinue
            }
          toContinue

        // Call f: Try[?] => Rx[?] using the input
        val c2: Cancelable =
          run(in) {
            case OnNext(x) =>
              Try(tryFunc(Success(x))) match
                case Success(rxb) =>
                  evalRx(rxb)
                case Failure(e) =>
                  effect(OnError(e))
            case OnError(e) =>
              Try(tryFunc(Failure(e))) match
                case Success(rxb) =>
                  val result = evalRx(rxb)
                  // An error terminates the input (e.g., a failed Rx.future reports no
                  // OnCompletion), so complete once the nested stream built from it ends.
                  // A continuous source (RxVar) may emit again after an error, so keep waiting.
                  if continuous || !result.toContinue then
                    result
                  else
                    tracker.completeInput(effect)
                case Failure(e) =>
                  effect(OnError(e))
            case OnCompletion =>
              tracker.completeInput(effect)
          }
        Cancelable { () =>
          c1.cancel;
          c2.cancel
        }
      case ConcatOp(first, next) =>
        var c1 = Cancelable.empty
        val c2 =
          run(first) {
            case OnCompletion =>
              var toContinue: RxResult = RxResult.Continue
              // Properly cancel the effect if this operator is evaluated before
              c1.cancel
              c1 =
                run(next) { ev =>
                  toContinue = effect(ev)
                  toContinue
                }
              toContinue
            case other =>
              effect(other)
          }
        Cancelable { () =>
          c1.cancel;
          c2.cancel
        }
      case LastOp(in) =>
        var last: Option[A] = None
        run(in) {
          case OnNext(v) =>
            last = Some(v.asInstanceOf[A])
            RxResult.Continue
          case err @ OnError(e) =>
            effect(err)
          case OnCompletion =>
            Try(effect(OnNext(last))) match
              case Success(v) =>
                effect(OnCompletion)
              case Failure(e) =>
                effect(OnError(e))
        }
      case cache @ CacheOp(in, lastValue, lastUpdatedNanos, _, ticker) =>
        lastValue match
          case Some(v) =>
            val currentNanos         = ticker.currentNanos
            val nanosSinceLastUpdate = currentNanos - lastUpdatedNanos
            val isExpired            = cache
              .expirationAfterWriteNanos
              .map(expireNanos => expireNanos <= nanosSinceLastUpdate)
              .getOrElse(false)
            if !isExpired then
              effect(OnNext(v))
          case None =>
        run(in) {
          case OnNext(v) =>
            cache.asInstanceOf[CacheOp[A]].lastValue = Some(v.asInstanceOf[A])
            cache.lastUpdatedNanos = ticker.currentNanos
            effect(OnNext(v))
          case other =>
            effect(other)
        }
      case TakeOp(in, n) =>
        if n <= 0 then
          effect(OnCompletion)
          Cancelable.empty
        else
          var count = 0L
          val done  = AtomicBoolean(false)
          // A placeholder for the upstream Cancelable, which is set after run(in) returns
          var c = Cancelable.empty
          c =
            run(in) {
              case _ if done.get() =>
                // Some sources (e.g., RxVar, Rx.single) keep emitting after Stop
                RxResult.Stop
              case OnNext(v) =>
                count += 1
                val result = effect(OnNext(v.asInstanceOf[A]))
                if count < n then
                  if !continuous && !result.toContinue then
                    // The downstream stopped early. Forward nothing more, but keep the upstream
                    // subscribed so that its own completion still drives operators such as concat.
                    // In continuous mode, Stop after a downstream error is transient.
                    done.set(true)
                  result
                else
                  // Complete right after the nth value without waiting for the next upstream event
                  if done.compareAndSet(false, true) && result.toContinue then
                    effect(OnCompletion)
                  c.cancel
                  RxResult.Stop
              case other =>
                if done.compareAndSet(false, true) then
                  c.cancel
                  effect(other)
                else
                  RxResult.Stop
            }
          // The nth value may arrive synchronously before run(in) returns
          if done.get() then
            c.cancel
          c
      case IntervalOp(interval, unit) =>
        val intervalMillis = TimeUnit.MILLISECONDS.convert(interval, unit).max(1)
        val timer: Timer   = compat.newTimer
        timer.schedule(intervalMillis) { interval =>
          val canContinue = effect(OnNext(interval))
          if !canContinue.toContinue then
            timer.cancel
        }
        Cancelable { () =>
          timer.cancel
        }
      case TimerOp(interval, unit) =>
        val delayMillis = TimeUnit.MILLISECONDS.convert(interval, unit).max(1)
        compat.scheduleOnce(delayMillis) {
          Try(effect(OnNext(0L))) match
            case Success(c) =>
              effect(OnCompletion)
            case Failure(e) =>
              effect(OnError(e))
        }
      case ThrottleFirstOp(in, interval, unit) =>
        var lastUpdateTimeNanos = -interval
        run(in) {
          case next @ OnNext(v) =>
            val currentTimeNanos = System.nanoTime()
            val elapsed = unit.convert(currentTimeNanos - lastUpdateTimeNanos, TimeUnit.NANOSECONDS)
            if elapsed >= interval then
              lastUpdateTimeNanos = currentTimeNanos
              effect(next)
            else
              // Do not emit the value, but continue the subscription
              RxResult.Continue
          case other =>
            effect(other)
        }
      case ThrottleLastOp(in, interval, unit) =>
        val intervalMillis          = TimeUnit.MILLISECONDS.convert(interval, unit).max(1)
        var lastItem: Option[A]     = None
        var lastReported: Option[A] = None
        val timer: Timer            = compat.newTimer
        var canContinue: RxResult   = RxResult.Continue
        timer.schedule(intervalMillis) { interval =>
          lastItem match
            case Some(x) =>
              lastItem = Some(x)
              if lastReported != lastItem then
                lastReported = lastItem
                canContinue = effect(OnNext(x))
                if !canContinue.toContinue then
                  timer.cancel
            case None =>
            // Do nothing
        }
        val c =
          run(in) {
            case OnNext(v) =>
              lastItem = Some(v.asInstanceOf[A])
              canContinue
            case other =>
              canContinue && effect(other)
          }
        Cancelable { () =>
          timer.cancel
          c.cancel
        }
      case TimeoutOp(in, duration, unit) =>
        val timeoutMillis   = TimeUnit.MILLISECONDS.convert(duration, unit).max(1)
        val timedOut        = new AtomicBoolean(false)
        var inputCancelable = Cancelable.empty
        // Schedule the timeout
        val timeoutCancelable =
          compat.scheduleOnce(timeoutMillis) {
            if timedOut.compareAndSet(false, true) then
              inputCancelable.cancel
              effect(OnError(Rx.TimeoutException(duration, unit)))
          }
        inputCancelable =
          run(in) {
            case ev @ OnNext(v) =>
              // Cancel timeout on first event, but continue processing
              if timedOut.compareAndSet(false, true) then
                timeoutCancelable.cancel
              effect(ev)
            case ev @ OnError(e) =>
              if timedOut.compareAndSet(false, true) then
                timeoutCancelable.cancel
              effect(ev)
            case OnCompletion =>
              if timedOut.compareAndSet(false, true) then
                timeoutCancelable.cancel
              effect(OnCompletion)
          }
        Cancelable { () =>
          timeoutCancelable.cancel
          inputCancelable.cancel
        }
      case z @ ZipOp(r1, r2) =>
        zip(z)(effect)
      case z @ Zip3Op(r1, r2, r3) =>
        zip(z)(effect)
      case z @ Zip4Op(r1, r2, r3, r4) =>
        zip(z)(effect)
      case z @ Zip5Op(r1, r2, r3, r4, r5) =>
        zip(z)(effect)
      case z @ Zip6Op(r1, r2, r3, r4, r5, r6) =>
        zip(z)(effect)
      case z @ Zip7Op(r1, r2, r3, r4, r5, r6, r7) =>
        zip(z)(effect)
      case z @ Zip8Op(r1, r2, r3, r4, r5, r6, r7, r8) =>
        zip(z)(effect)
      case z @ Zip9Op(r1, r2, r3, r4, r5, r6, r7, r8, r9) =>
        zip(z)(effect)
      case z @ Zip10Op(r1, r2, r3, r4, r5, r6, r7, r8, r9, r10) =>
        zip(z)(effect)
      case j @ JoinOp(r1, r2) =>
        join(j)(effect)
      case j @ Join3Op(r1, r2, r3) =>
        join(j)(effect)
      case j @ Join4Op(r1, r2, r3, r4) =>
        join(j)(effect)
      case j @ Join5Op(r1, r2, r3, r4, r5) =>
        join(j)(effect)
      case j @ Join6Op(r1, r2, r3, r4, r5, r6) =>
        join(j)(effect)
      case j @ Join7Op(r1, r2, r3, r4, r5, r6, r7) =>
        join(j)(effect)
      case j @ Join8Op(r1, r2, r3, r4, r5, r6, r7, r8) =>
        join(j)(effect)
      case j @ Join9Op(r1, r2, r3, r4, r5, r6, r7, r8, r9) =>
        join(j)(effect)
      case j @ Join10Op(r1, r2, r3, r4, r5, r6, r7, r8, r9, r10) =>
        join(j)(effect)
      case RxOptionOp(in) =>
        run(in) {
          case e @ OnNext(v) =>
            effect(e)
          case other =>
            effect(other)
        }
      case RxOptionCacheOp(input) =>
        run(input)(effect)
      case NamedOp(input, name) =>
        run(input)(effect)
      case TryOp(e) =>
        e.eval match
          case Success(x) =>
            // A single-value source: complete after the value, as SingleOp does
            Try(effect(OnNext(x))) match
              case Success(_) =>
                effect(OnCompletion)
              case Failure(e) =>
                effect(OnError(e))
          case Failure(e) =>
            effect(OnError(e))
        Cancelable.empty
      case o: RxOptionVar[?] =>
        o.asInstanceOf[RxOptionVar[A]]
          .foreachEvent { ev =>
            effect(ev)
          }
      case v: RxVar[?] =>
        v.asInstanceOf[RxVar[A]]
          .foreachEvent { ev =>
            effect(ev)
          }
      case RecoverOp(in, f) =>
        run(in) { ev =>
          ev match
            case OnNext(v) =>
              effect(ev)
            case OnError(e) if f.isDefinedAt(e) =>
              Try(effect(OnNext(f(e)))) match
                case Success(x) =>
                  // recovery succeeded
                  RxResult.Continue
                case Failure(e) =>
                  effect(OnError(e))
            case other =>
              effect(other)
        }
      case MaterializeOp(in) =>
        run(in) { ev =>
          ev match
            case OnNext(v) =>
              effect(OnNext(Result.Success(v)))
            case OnError(e) =>
              val downstream = effect(OnNext(Result.Failure(e)))
              if downstream.toContinue then
                effect(OnCompletion)
              else
                downstream
            case OnCompletion =>
              effect(OnCompletion)
        }
      case RecoverWithOp(in, f) =>
        var toContinue: RxResult = RxResult.Continue
        var c1                   = Cancelable.empty
        val c2                   =
          run(in) { ev =>
            ev match
              case OnError(e) if f.isDefinedAt(e) =>
                c1.cancel
                Try(f(e)) match
                  case Success(recoverySource) =>
                    c1 =
                      run(recoverySource) { ev =>
                        toContinue = effect(ev)
                        toContinue
                      }
                    toContinue
                  case Failure(e) =>
                    effect(OnError(e))
              case other =>
                effect(other)
          }
        Cancelable { () =>
          c1.cancel;
          c2.cancel
        }
      case TapOnOp(in, f) =>
        val f0 = f.asInstanceOf[PartialFunction[Try[?], Unit]]
        run(in) { ev =>
          ev match
            case OnNext(v) =>
              Try(f0.applyOrElse(Success(v), (_: Try[?]) => ())) match
                case Success(value) =>
                  effect(ev)
                case Failure(e) =>
                  effect(OnError(e))
            case OnError(e) =>
              Try(f0.applyOrElse(Failure(e), (_: Try[?]) => ())) match
                case Success(value) =>
                  effect(ev)
                case Failure(e) =>
                  effect(OnError(e))
            case _ =>
              effect(ev)
        }
      case AsyncOp(register, _) =>
        // Deliver at most one result, and none after cancellation
        val done         = AtomicBoolean(false)
        val registration = register { result =>
          if done.compareAndSet(false, true) then
            result match
              case Success(v) =>
                Try(effect(OnNext(v.asInstanceOf[A]))) match
                  case Success(_) =>
                    effect(OnCompletion)
                  case Failure(e) =>
                    effect(OnError(e))
              case Failure(e) =>
                effect(OnError(e))
            true
          else
            false
        }
        // Mark as done before unregistering, so a waiter woken in between reports
        // non-delivery and its source can hand back the permit or element
        Cancelable { () =>
          done.set(true)
          registration.cancel
        }
      case MergeOp(sources) =>
        if sources.isEmpty then
          effect(OnCompletion)
          Cancelable.empty
        else
          val fanIn = FanIn(effect)
          // Open every slot first, so a source that completes synchronously cannot end the merge
          val slots = sources.flatMap(_ => fanIn.open())
          sources
            .zip(slots)
            .foreach { (source, slot) =>
              // An earlier source may have stopped the merge synchronously
              if !fanIn.isStopped then
                fanIn.attach(
                  slot,
                  run(source) {
                    case ev @ OnNext(_) =>
                      fanIn.next(ev)
                    case OnCompletion =>
                      fanIn.end(slot)
                    case ev @ OnError(_) =>
                      fanIn.error(slot, ev)
                  }
                )
            }
          fanIn.cancelable
      case ParFlatMapOp(in, parallelism, f) =>
        val fanIn = FanIn(effect)
        // The fields below are guarded by `pending`
        val pending  = mutable.Queue.empty[Any]
        var active   = 0
        val ready    = mutable.Queue.empty[(fanIn.Slot, Any)]
        var draining = false

        // Hand a finished nested stream's permit to the next pending value, or release it
        def releasePermit(): Unit =
          val next = pending.synchronized {
            if pending.nonEmpty then
              Some(pending.dequeue())
            else
              active -= 1
              None
          }
          next.foreach(launch)

        def nextReady(): Option[(fanIn.Slot, Any)] = pending.synchronized {
          if ready.isEmpty then
            draining = false
            None
          else
            Some(ready.dequeue())
        }

        // Open the slot now, so the operator cannot complete before x starts. Nested streams are
        // subscribed from one loop, not recursively: a synchronous nested stream completes inside
        // its subscription and launches the next pending value
        def launch(x: Any): Unit = fanIn
          .open()
          .foreach { slot =>
            val drainHere = pending.synchronized {
              ready.enqueue((slot, x))
              val idle = !draining
              draining = true
              idle
            }
            if drainHere then
              var item = nextReady()
              while item.nonEmpty do
                val (s, v) = item.get
                if !fanIn.isStopped then
                  subscribe(s, v)
                item = nextReady()
          }

        def subscribe(slot: fanIn.Slot, x: Any): Unit =
          Try(f.asInstanceOf[Any => RxOps[?]](x)) match
            case Success(rxb) =>
              fanIn.attach(
                slot,
                run(rxb) {
                  case ev @ OnNext(_) =>
                    fanIn.next(ev)
                  case OnCompletion =>
                    fanIn.end(slot, releasePermit)
                  case ev @ OnError(_) =>
                    fanIn.error(slot, ev, releasePermit)
                }
              )
            case Failure(e) =>
              fanIn.error(slot, OnError(e), releasePermit)

        fanIn
          .open()
          .foreach { inputSlot =>
            fanIn.attach(
              inputSlot,
              run(in) {
                case OnNext(x) =>
                  val startNow = pending.synchronized {
                    if active < parallelism then
                      active += 1
                      true
                    else
                      pending.enqueue(x)
                      false
                  }
                  if startNow then
                    launch(x)
                  fanIn.result
                case OnCompletion =>
                  fanIn.end(inputSlot)
                case ev @ OnError(_) =>
                  fanIn.error(inputSlot, ev)
              }
            )
          }
        fanIn.cancelable
      case SingleOp(v) =>
        Try(effect(OnNext(v.eval))) match
          case Success(c) =>
            effect(OnCompletion)
          case Failure(e) =>
            effect(OnError(e))
        Cancelable.empty
      case SeqOp(inputList) =>
        var lastResult: RxResult = RxResult.Continue
        @tailrec
        def loop(lst: List[A]): Unit =
          if continuous || lastResult.toContinue then
            lst match
              case Nil =>
                lastResult = effect(OnCompletion)
              case head :: tail =>
                lastResult = effect(OnNext(head))
                loop(tail)
        loop(inputList.eval.toList)
        // Stop reading the next element if cancelled
        Cancelable { () =>
          lastResult = RxResult.Stop
        }
      case source: RxSource[?] =>
        val cancelled = AtomicBoolean(false)
        // Set by a terminal event or a downstream Stop; continuous mode keeps reading
        val finished = AtomicBoolean(false)
        // True from calling source.next until its event arrives
        val awaiting = AtomicBoolean(false)
        val current  = AtomicReference[Cancelable](Cancelable.empty)

        def active: Boolean = !cancelled.get && (continuous || !finished.get)

        def deliver(ev: RxEvent): RxResult =
          awaiting.set(false)
          if cancelled.get then
            RxResult.Stop
          else
            ev match
              case OnNext(v) =>
                val r = effect(OnNext(v.asInstanceOf[A]))
                if !r.toContinue then
                  finished.set(true)
                r
              case other =>
                finished.set(true)
                effect(other)

        // Read one event at a time. An event that is already available continues this loop; one
        // that arrives later continues from its callback. Polling `next` again before its event
        // arrives would spin on an empty source.
        def pull(): Unit =
          var again = true
          while again && active do
            // 0: waiting, 1: the event arrived during run(next), 2: run(next) returned first
            val state = AtomicInteger(0)
            awaiting.set(true)
            current.set(
              run(source.next) {
                case OnNext(ev: RxEvent) =>
                  deliver(ev)
                case OnCompletion =>
                  if !state.compareAndSet(0, 1) then
                    pull()
                  RxResult.Continue
                case other =>
                  deliver(other)
              }
            )
            again = !state.compareAndSet(0, 2)

        pull()
        Cancelable { () =>
          cancelled.set(true)
          current.get.cancel
          if awaiting.get then
            source.cancelNext()
        }

  /**
    * A base implementation for merging streams and generating tuples
    * @param input
    * @tparam A
    */
  private[rx] abstract class CombinedStream[A](input: Rx[A]) extends LogSupport:
    protected val size = input.parents.size

    protected val lastEvent: Array[Option[RxEvent]] = Array.fill(size)(None)
    private val c: Array[Cancelable]                = Array.fill(size)(Cancelable.empty)
    private val completed: AtomicBoolean            = new AtomicBoolean(false)

    protected def nextValue: Option[Seq[Any]]

    protected def update(index: Int, v: A): Unit

    protected def isCompleted: Boolean

    def run(effect: RxEvent => RxResult): Cancelable =
      def emit: RxResult =
        // Emit the tuple result.
        val toContinue =
          nextValue match
            case None =>
              // Nothing to emit
              RxResult.Continue
            case Some(values) =>
              // Generate tuples from last values.
              // This code is a bit ad-hoc because there is no way to produce tuples from Seq[X] of lastValues
              values.size match
                case 2 =>
                  effect(OnNext((values(0), values(1)).asInstanceOf[A]))
                case 3 =>
                  effect(OnNext((values(0), values(1), values(2)).asInstanceOf[A]))
                case 4 =>
                  effect(OnNext((values(0), values(1), values(2), values(3)).asInstanceOf[A]))
                case 5 =>
                  effect(
                    OnNext((values(0), values(1), values(2), values(3), values(4)).asInstanceOf[A])
                  )
                case 6 =>
                  effect(
                    OnNext(
                      (values(0), values(1), values(2), values(3), values(4), values(5))
                        .asInstanceOf[A]
                    )
                  )
                case 7 =>
                  effect(
                    OnNext(
                      (values(0), values(1), values(2), values(3), values(4), values(5), values(6))
                        .asInstanceOf[A]
                    )
                  )
                case 8 =>
                  effect(
                    OnNext(
                      (
                        values(0),
                        values(1),
                        values(2),
                        values(3),
                        values(4),
                        values(5),
                        values(6),
                        values(7)
                      ).asInstanceOf[A]
                    )
                  )
                case 9 =>
                  effect(
                    OnNext(
                      (
                        values(0),
                        values(1),
                        values(2),
                        values(3),
                        values(4),
                        values(5),
                        values(6),
                        values(7),
                        values(8)
                      ).asInstanceOf[A]
                    )
                  )
                case 10 =>
                  effect(
                    OnNext(
                      (
                        values(0),
                        values(1),
                        values(2),
                        values(3),
                        values(4),
                        values(5),
                        values(6),
                        values(7),
                        values(8),
                        values(9)
                      ).asInstanceOf[A]
                    )
                  )
                case other =>
                  throw new NotImplementedError(
                    s"combining 10+ more Rx operators is not yet supported: ${other}"
                  )
        toContinue
      end emit

      // Scan the last events and emit the next value or a completion event
      def processEvents(doEmit: Boolean): RxResult =
        val errors = lastEvent.collect { case Some(e @ OnError(ex)) =>
          ex
        }
        if errors.isEmpty then
          if doEmit then
            emit
          else if isCompleted && completed.compareAndSet(false, true) then
            trace(s"emit OnCompletion")
            effect(OnCompletion)
          else
            RxResult.Continue
        else
          // Report the completion event only once
          if continuous || completed.compareAndSet(false, true) then
            // If there are multiple exceptions, add them to the suppressed list
            val ex: Throwable = errors.reduce { (e1, e2) =>
              e1.addSuppressed(e2);
              e1
            }
            effect(OnError(ex))
          else
            RxResult.Continue

      for i <- 0 until size do
        c(i) =
          runner.run(input.parents(i)) { e =>
            lastEvent(i) = Some(e)
            trace(s"c(${i}) ${e}")
            e match
              case OnNext(v) =>
                update(i, v.asInstanceOf[A])
                processEvents(true)
              case _ =>
                processEvents(false)
          }

      processEvents(false)
      Cancelable { () =>
        c.foreach(_.cancel)
      }

    end run

  end CombinedStream

  private class ZipStream[A](input: Rx[A]) extends CombinedStream(input):
    private val lastValueBuffer: Array[Queue[A]] = Array.fill(size)(Queue.empty[A])

    override protected def nextValue: Option[Seq[Any]] =
      if lastValueBuffer.forall(_.nonEmpty) then
        val values =
          for i <- 0 until lastValueBuffer.size
          yield
            val (v, newQueue) = lastValueBuffer(i).dequeue
            lastValueBuffer(i) = newQueue
            v
        Some(values)
      else
        None

    override protected def update(index: Int, v: A): Unit =
      lastValueBuffer(index) = lastValueBuffer(index).enqueue(v)

    override protected def isCompleted: Boolean = !continuous && lastEvent.forall(_.isDefined)

  private def zip[A](input: Rx[A])(effect: RxEvent => RxResult): Cancelable = new ZipStream(input)
    .run(effect)

  private class JoinStream[A](input: Rx[A]) extends CombinedStream(input):
    private val lastValue: Array[Option[A]] = Array.fill(size)(None)

    override protected def nextValue: Option[Seq[Any]] =
      if lastValue.forall(_.nonEmpty) then
        val values =
          for i <- 0 until lastValue.size
          yield lastValue(i).get
        Some(values)
      else
        None

    override protected def update(index: Int, v: A): Unit = lastValue(index) = Some(v)

    override protected def isCompleted: Boolean =
      !continuous && lastEvent.forall(x => x.isDefined && x.get == OnCompletion)

  private def join[A](input: Rx[A])(effect: RxEvent => RxResult): Cancelable = new JoinStream(input)
    .run(effect)

  /**
    * Tracks an input stream and its current nested stream (flatMap, transformRx), so that the
    * operator completes only after both terminate: an asynchronous nested stream (e.g., Rx.future)
    * must not be cut off by the input's completion. The two may terminate on different threads, so
    * the state is guarded by a lock.
    */
  private class NestedStreamTracker:
    private var inputDone    = false
    private var nestedActive = false
    // Identifies the current nested stream, so a replaced (cancelled) one cannot end it
    private var generation = 0

    /**
      * Register a new nested stream and return its generation
      */
    def startNested(): Int = synchronized {
      generation += 1
      nestedActive = true
      generation
    }

    /**
      * Mark the nested stream `current` as terminated; true if the input has already completed
      */
    def endNested(current: Int): Boolean = synchronized {
      if current == generation then
        nestedActive = false
        inputDone
      else
        false
    }

    /**
      * An error also terminates the nested stream (e.g., a failed Rx.future reports no
      * OnCompletion). If the downstream recovered from it, complete here when the input has already
      * completed. The error is delivered before the nested stream is marked as ended, so an input
      * completing on another thread waits instead of overtaking the error or the recovered value.
      */
    def nestedError(current: Int, ev: OnError, effect: RxEvent => RxResult): RxResult =
      val result = effect(ev)
      val done   = endNested(current)
      if done && result.toContinue then
        effect(OnCompletion)
      else
        result

    /**
      * Mark the input as completed. Report the completion now unless the nested stream is still
      * active, in which case it is reported when the nested stream terminates. A repeated call is
      * ignored: a source may report OnCompletion after an OnError that transformRx already treated
      * as the input's end.
      */
    def completeInput(effect: RxEvent => RxResult): RxResult =
      val (alreadyDone, waitForNested) = synchronized {
        val done = inputDone
        inputDone = true
        (done, nestedActive)
      }
      if alreadyDone then
        RxResult.Stop
      else if waitForNested then
        RxResult.Continue
      else
        effect(OnCompletion)

  end NestedStreamTracker

  /**
    * Joins streams that may run concurrently (merge, parFlatMap) into one downstream. Events are
    * forwarded under a lock, since the streams may emit from different threads. The operator
    * completes when every opened slot has ended. An error the downstream recovers from ends only
    * the failing slot, as in flatMap. An unrecovered error, a downstream Stop, or a cancellation
    * stops the operator: running streams are cancelled and later events are dropped. In continuous
    * mode, errors and Stop are transient and only a cancellation stops it.
    */
  private class FanIn(effect: RxEvent => RxResult):
    class Slot:
      var cancelable: Cancelable = Cancelable.empty

    private val running = mutable.LinkedHashSet.empty[Slot]
    private var stopped = false

    def isStopped: Boolean = synchronized(stopped)

    /**
      * Register a stream before starting it. None if the operator has already stopped
      */
    def open(): Option[Slot] = synchronized {
      if stopped then
        None
      else
        val slot = Slot()
        running += slot
        Some(slot)
    }

    /**
      * Keep the subscription of a running slot, or cancel it if the operator has already stopped
      */
    def attach(slot: Slot, c: Cancelable): Unit =
      val keep = synchronized {
        if !stopped && running.contains(slot) then
          slot.cancelable = c
        !stopped
      }
      if !keep then
        c.cancel

    def result: RxResult =
      if isStopped then
        RxResult.Stop
      else
        RxResult.Continue

    def next(ev: OnNext): RxResult = deliver(ev)

    /**
      * Forward an error from a slot's stream. If the downstream recovers, the slot has ended
      */
    def error(slot: Slot, ev: OnError, beforeEnd: () => Unit = () => ()): RxResult =
      val r = deliver(ev)
      if r.toContinue && !continuous then
        end(slot, beforeEnd)
      else
        r

    /**
      * End a slot after running `beforeEnd`, which may open other slots. Reports OnCompletion when
      * no slot is left
      */
    def end(slot: Slot, beforeEnd: () => Unit = () => ()): RxResult =
      beforeEnd()
      val last = synchronized {
        running -= slot
        running.isEmpty
      }
      if last then
        synchronized {
          if !stopped then
            stopped = true
            effect(OnCompletion)
        }
        RxResult.Stop
      else
        result

    def cancelable: Cancelable = Cancelable { () =>
      synchronized {
        stopped = true
      }
      cancelRunning()
    }

    private def deliver(ev: RxEvent): RxResult =
      val r = synchronized {
        if stopped then
          RxResult.Stop
        else
          val r = effect(ev)
          if !continuous && !r.toContinue then
            stopped = true
          r
      }
      if !continuous && !r.toContinue then
        cancelRunning()
      r

    private def cancelRunning(): Unit =
      val toCancel = synchronized {
        val cs = running.toList.map(_.cancelable)
        running.clear()
        cs
      }
      toCancel.foreach(_.cancel)

  end FanIn

end RxRunner
