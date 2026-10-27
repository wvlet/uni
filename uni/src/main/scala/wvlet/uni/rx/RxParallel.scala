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
package wvlet.uni.rx

/**
  * Parallel operators for Rx streams.
  *
  * These operators enable concurrent execution of Rx computations with bounded parallelism.
  */
object RxParallel:

  /**
    * Run multiple Rx computations in parallel and return the first result. The slower computation
    * is cancelled.
    */
  def race[A](a: Rx[A], b: Rx[A]): Rx[A] = race(Seq(a, b))

  /**
    * Run multiple Rx computations in parallel and return the first result (a value or an error).
    * All slower computations are cancelled. Each computation runs as a fiber on
    * [[RxScheduler.default]]; nothing blocks, so this also works on Scala.js.
    */
  def race[A](rxs: Seq[Rx[A]]): Rx[A] =
    require(rxs.nonEmpty, "Cannot race an empty sequence")
    Rx.single(rxs.map(rx => RxFiber.start(rx, RxScheduler.default)))
      .flatMap { fibers =>
        merge(fibers.map(_.join))
          .take(1)
          .tapOn { case _ =>
            // Cancel later: a cancelled fiber wakes its join with an error, which must arrive after
            // take(1) has stopped the merge, not inside this emission
            RxScheduler.default.execute(() => fibers.foreach(_.cancel.run()))
          }
      }

  /**
    * Run multiple Rx computations in parallel and collect all results. The order of results matches
    * the order of inputs.
    */
  def parSequence[A](rxs: Seq[Rx[A]]): Rx[Seq[A]] = parSequenceN(Int.MaxValue)(rxs)

  /**
    * Run multiple Rx computations in parallel, at most `parallelism` at a time, and collect their
    * first values in input order. Each computation runs as a fiber on [[RxScheduler.default]]. The
    * first error cancels the others; a computation that completes without a value fails with
    * IllegalStateException. Nothing blocks, so this also works on Scala.js.
    */
  def parSequenceN[A](parallelism: Int)(rxs: Seq[Rx[A]]): Rx[Seq[A]] =
    if rxs.isEmpty then
      Rx.single(Seq.empty)
    else
      Rx.single(new Array[Any](rxs.size))
        .flatMap { results =>
          Rx.fromSeq(rxs.indices)
            .parFlatMap(parallelism)(i => rxs(i).start.flatMap(_.join).map(v => results(i) = v))
            .lastOption
            .toRx
            .map(_ => results.toSeq.asInstanceOf[Seq[A]])
        }

  /**
    * Traverse a sequence with a function, running computations in parallel.
    */
  def parTraverse[A, B](as: Seq[A])(f: A => Rx[B]): Rx[Seq[B]] = parTraverseN(Int.MaxValue)(as)(f)

  /**
    * Traverse a sequence with a function, running computations in parallel with bounded
    * concurrency.
    */
  def parTraverseN[A, B](parallelism: Int)(as: Seq[A])(f: A => Rx[B]): Rx[Seq[B]] =
    parSequenceN(parallelism)(as.map(f))

  /**
    * Run two Rx computations in parallel and combine their results.
    */
  def parZip[A, B](a: Rx[A], b: Rx[B]): Rx[(A, B)] = parSequence(
    Seq(a.map(_.asInstanceOf[Any]), b.map(_.asInstanceOf[Any]))
  ).map { results =>
    (results(0).asInstanceOf[A], results(1).asInstanceOf[B])
  }

  /**
    * Run three Rx computations in parallel and combine their results.
    */
  def parZip3[A, B, C](a: Rx[A], b: Rx[B], c: Rx[C]): Rx[(A, B, C)] = parSequence(
    Seq(a.map(_.asInstanceOf[Any]), b.map(_.asInstanceOf[Any]), c.map(_.asInstanceOf[Any]))
  ).map { results =>
    (results(0).asInstanceOf[A], results(1).asInstanceOf[B], results(2).asInstanceOf[C])
  }

  /**
    * Merge emissions from multiple streams into a single stream. Order of emissions is
    * non-deterministic.
    */
  def merge[A](rxs: Seq[Rx[A]]): Rx[A] =
    if rxs.isEmpty then
      Rx.empty
    else if rxs.size == 1 then
      rxs.head
    else
      Rx.MergeOp(rxs)

end RxParallel

/**
  * Extension methods for parallel Rx operations.
  */
extension [A](rx: Rx[A])

  /**
    * FlatMap with bounded parallelism: at most `parallelism` inner streams are subscribed at a
    * time, and the other values wait in an unbounded queue. Emissions are interleaved in arrival
    * order. Inner streams run where they are subscribed, so a synchronous `f` gains no parallelism;
    * see [[parMapN]].
    */
  def parFlatMap[B](parallelism: Int)(f: A => Rx[B]): Rx[B] =
    require(parallelism > 0, s"parallelism must be positive: ${parallelism}")
    Rx.ParFlatMapOp(rx, parallelism, f)

  /**
    * Map with bounded parallelism: each `f(a)` runs on [[RxScheduler.default]], with at most
    * `parallelism` running at a time. Results arrive in completion order. Cancellation drops the
    * results of calls already running but does not interrupt them.
    */
  def parMapN[B](parallelism: Int)(f: A => B): Rx[B] =
    parFlatMap(parallelism)(a => Rx.single(f(a)).start.flatMap(_.join))

  /**
    * Merge emissions from this stream with another. Order is non-deterministic.
    */
  def merge[B >: A](other: Rx[B]): Rx[B] = RxParallel.merge(Seq(rx, other))

  /**
    * Race with another Rx, returning the first result. The loser is cancelled.
    */
  def race(other: Rx[A]): Rx[A] = RxParallel.race(rx, other)

  /**
    * Race with another Rx, tagging results with Left/Right.
    */
  def raceEither[B](other: Rx[B]): Rx[Either[A, B]] = RxParallel.race(
    rx.map(Left(_)),
    other.map(Right(_))
  )

  /**
    * Run in parallel with another Rx and combine results.
    */
  def parZip[B](other: Rx[B]): Rx[(A, B)] = RxParallel.parZip(rx, other)

  /**
    * Run in parallel with two other Rx and combine results.
    */
  def parZip[B, C](b: Rx[B], c: Rx[C]): Rx[(A, B, C)] = RxParallel.parZip3(rx, b, c)

end extension

/**
  * `Rx.parallel` gives access to [[RxParallel]] from the `Rx` companion.
  */
extension (rx: Rx.type)
  def parallel: RxParallel.type = RxParallel
