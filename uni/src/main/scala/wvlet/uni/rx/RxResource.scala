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

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

/**
  * A resource that can be safely acquired and released, with guaranteed cleanup.
  *
  * RxResource is inspired by cats-effect Resource. It provides bracket semantics: acquire a
  * resource, use it, and guarantee that cleanup runs even if an error occurs.
  *
  * @tparam A
  *   the type of the resource
  */
trait RxResource[A]:

  /**
    * Use the resource with guaranteed cleanup.
    *
    * The cleanup action will run even if the body throws an exception.
    */
  def use[B](body: A => Rx[B]): Rx[B]

  /**
    * Map over the resource.
    */
  def map[B](f: A => B): RxResource[B]

  /**
    * FlatMap over the resource, composing with another resource.
    */
  def flatMap[B](f: A => RxResource[B]): RxResource[B]

  /**
    * Combine with another resource, releasing in reverse order of acquisition.
    */
  def zip[B](other: RxResource[B]): RxResource[(A, B)]

  /**
    * Add a finalizer that runs after the main cleanup.
    */
  def onFinalize(finalizer: Rx[Unit]): RxResource[A]

object RxResource:

  /**
    * Create a resource with acquire and release actions.
    */
  def make[A](acquire: Rx[A])(release: A => Rx[Unit]): RxResource[A] =
    new RxResourceImpl(acquire, release)

  /**
    * Create a resource from an AutoCloseable.
    */
  def fromAutoCloseable[A <: AutoCloseable](acquire: Rx[A]): RxResource[A] =
    make(acquire)(a => Rx.single(a.close()))

  /**
    * Create a resource that doesn't need cleanup.
    */
  def pure[A](a: A): RxResource[A] = make(Rx.single(a))(_ => Rx.single(()))

  /**
    * Create a resource that runs an action but doesn't produce a value.
    */
  def eval[A](rx: Rx[A]): RxResource[A] = make(rx)(_ => Rx.single(()))

  /**
    * Create an empty resource.
    */
  val unit: RxResource[Unit] = pure(())

  private class RxResourceImpl[A](
      acquire: Rx[A],
      release: A => Rx[Unit],
      finalizers: List[Rx[Unit]] = Nil
  ) extends RxResource[A]:

    override def use[B](body: A => Rx[B]): Rx[B] = acquire.flatMap { a =>
      body(a).transformRx { result =>
        // Always run cleanup: the main release, then the finalizers, collecting their errors.
        // This is composed as an Rx chain (not Rx.await) so that it works on every platform,
        // including Scala.js where blocking is unsupported.
        val releaseRx: Rx[Unit] =
          try
            release(a)
          catch
            case NonFatal(e) =>
              Rx.exception(e)
        val cleanups: Seq[Rx[Unit]] = releaseRx +: finalizers
        cleanups
          .foldLeft(Rx.single(List.empty[Throwable])) { (acc, cleanup) =>
            acc.flatMap { errors =>
              cleanup.transform {
                case Success(_) =>
                  errors
                case Failure(e) =>
                  e :: errors
              }
            }
          }
          .transform {
            case Success(errors) =>
              // Handle errors
              result match
                case Success(b) =>
                  if errors.isEmpty then
                    b
                  else
                    val combined = errors.reduceLeft { (a, b) =>
                      a.addSuppressed(b)
                      a
                    }
                    throw combined
                case Failure(e) =>
                  errors.foreach(e.addSuppressed)
                  throw e
            case Failure(e) =>
              throw e
          }
      }
    }

    override def map[B](f: A => B): RxResource[B] =
      // Map creates a new resource that wraps the original, preserving its release
      new RxResource[B]:
        override def use[C](body: B => Rx[C]): Rx[C] = RxResourceImpl.this.use(a => body(f(a)))

        override def map[C](g: B => C): RxResource[C] = RxResourceImpl.this.map(a => g(f(a)))

        override def flatMap[C](g: B => RxResource[C]): RxResource[C] = RxResourceImpl.this.flatMap(
          a => g(f(a))
        )

        override def zip[C](other: RxResource[C]): RxResource[(B, C)] = RxResourceImpl.this
          .map(f)
          .zip(other)

        override def onFinalize(finalizer: Rx[Unit]): RxResource[B] = RxResourceImpl.this
          .onFinalize(finalizer)
          .map(f)

    override def flatMap[B](f: A => RxResource[B]): RxResource[B] =
      // FlatMap composes resources, ensuring both are released properly
      new RxResource[B]:
        override def use[C](body: B => Rx[C]): Rx[C] = RxResourceImpl.this.use { a =>
          f(a).use(body)
        }

        override def map[C](g: B => C): RxResource[C] = flatMap(b => RxResource.pure(g(b)))

        override def flatMap[C](g: B => RxResource[C]): RxResource[C] = RxResourceImpl.this.flatMap(
          a => f(a).flatMap(g)
        )

        override def zip[C](other: RxResource[C]): RxResource[(B, C)] = flatMap(b =>
          other.map(c => (b, c))
        )

        override def onFinalize(finalizer: Rx[Unit]): RxResource[B] = RxResourceImpl.this.flatMap(
          a => f(a).onFinalize(finalizer)
        )

    override def zip[B](other: RxResource[B]): RxResource[(A, B)] =
      // Zip acquires both resources and releases in reverse order
      new RxResource[(A, B)]:
        override def use[C](body: ((A, B)) => Rx[C]): Rx[C] = RxResourceImpl.this.use { a =>
          other.use { b =>
            body((a, b))
          }
        }

        override def map[C](f: ((A, B)) => C): RxResource[C] = this.flatMap(ab =>
          RxResource.pure(f(ab))
        )

        override def flatMap[C](f: ((A, B)) => RxResource[C]): RxResource[C] = RxResourceImpl.this
          .flatMap { a =>
            other.flatMap { b =>
              f((a, b))
            }
          }

        override def zip[C](another: RxResource[C]): RxResource[((A, B), C)] = flatMap(ab =>
          another.map(c => (ab, c))
        )

        override def onFinalize(finalizer: Rx[Unit]): RxResource[(A, B)] = RxResourceImpl.this
          .onFinalize(finalizer)
          .zip(other)

    override def onFinalize(finalizer: Rx[Unit]): RxResource[A] =
      new RxResourceImpl(acquire, release, finalizer :: finalizers)

  end RxResourceImpl

end RxResource
