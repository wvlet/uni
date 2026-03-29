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
package wvlet.uni.test

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.scalajs.js
import scala.scalajs.reflect.Reflect

/**
  * Scala.js specific compatibility layer for uni-test
  */
private[test] object compat:

  /**
    * Platform-specific matcher for js.Object comparison. Based on airspec's JsObjectMatcher.
    */
  def platformSpecificMatcher: PartialFunction[(Any, Any), MatchResult] =
    case (a: js.Object, b: js.Object) =>
      MatchResult.check(jsObjEquals(a, b))

  /**
    * Deep equality check for JavaScript objects
    */
  private def jsObjEquals(v1: js.Object, v2: js.Object): Boolean =
    if v1 == v2 then
      true
    else if v1 == null || v2 == null then
      false
    else
      deepEqual(v1, v2)

  private def getValues(v: js.Object): js.Array[(String, Any)] = js
    .Object
    .entries(v)
    .sortBy(_._1)
    .map(p => (p._1, p._2.asInstanceOf[js.Any]))

  private def deepEqual(v1: js.Object, v2: js.Object): Boolean =
    val k1 = js.Object.keys(v1)
    val k2 = js.Object.keys(v2)

    if k1.length != k2.length then
      false
    else if k1.length == 0 then
      js.JSON.stringify(v1) == js.JSON.stringify(v2)
    else
      val values1 = getValues(v1)
      val values2 = getValues(v2)
      values1
        .zip(values2)
        .forall {
          case ((k1, _), (k2, _)) if k1 != k2 =>
            false
          case ((_, v1), (_, v2)) =>
            if js.typeOf(v1.asInstanceOf[js.Any]) == "object" &&
              js.typeOf(v2.asInstanceOf[js.Any]) == "object"
            then
              jsObjEquals(v1.asInstanceOf[js.Object], v2.asInstanceOf[js.Object])
            else
              v1 == v2
        }

  /**
    * Execution context for async operations. Uses macrotask executor for proper async handling in
    * JavaScript environment.
    */
  val executionContext: ExecutionContext =
    org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

  /**
    * Create a new instance of the test class or get singleton instance if it's an object. Uses
    * Scala.js built-in reflection. Throws exception if class cannot be found or instantiated.
    */
  def newInstance(className: String, classLoader: ClassLoader): UniTest =
    // Try module lookup first (for Scala objects)
    lookupModule(className)
      .orElse(
        // Try with $ suffix (sbt may pass class name without $)
        if !className.endsWith("$") then
          lookupModule(s"${className}$$")
        else
          None
      )
      .orElse(
        // Fall back to instantiatable class (regular class)
        Reflect.lookupInstantiatableClass(className).map(_.newInstance().asInstanceOf[UniTest])
      )
      .getOrElse(throw new ClassNotFoundException(s"Cannot find or instantiate: ${className}"))

  /**
    * Try to load a module and verify it extends UniTest
    */
  private def lookupModule(name: String): Option[UniTest] = Reflect
    .lookupLoadableModuleClass(name)
    .map(_.loadModule())
    .collect { case t: UniTest =>
      t
    }

  /**
    * Find the root cause of an exception. Scala.js doesn't have InvocationTargetException.
    */
  def findCause(e: Throwable): Throwable = e

  /**
    * Run an Rx stream for test purposes. On JS, we can't block so we just run for side effects. The
    * Rx is executed but we can't await the result.
    */
  def runRxTest[A](rx: wvlet.uni.rx.RxOps[A]): A =
    import wvlet.uni.rx.*
    // Run the Rx for side effects - this is non-blocking
    var result: Option[A]        = None
    var error: Option[Throwable] = None
    // Discard the cancelable - we run synchronously on JS
    val _ =
      RxRunner.runOnce(rx) {
        case OnNext(v) =>
          result = Some(v.asInstanceOf[A])
        case OnError(e) =>
          error = Some(e)
        case OnCompletion => // Done
      }
    // Check for errors
    error.foreach(throw _)
    // Return result or Unit (for side-effect tests)
    result.getOrElse(().asInstanceOf[A])

  /**
    * Run a Future for test purposes. On JS, we cannot block, so we extract the value from
    * already-completed Futures. Non-completed Futures are treated as side effects and ignored,
    * since JS is single-threaded and cannot await them.
    */
  def runFutureTest[A](future: Future[A]): A =
    future.value match
      case Some(Success(v)) =>
        v
      case Some(Failure(e)) =>
        throw e
      case None =>
        // Cannot block on JS; treat as a side-effect Future
        ().asInstanceOf[A]

end compat
