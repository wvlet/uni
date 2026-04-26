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
package wvlet.uni.reflect

import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException

/**
  * A reflectively invokable constructor wrapper. Mirrors the API of
  * `scala.scalajs.reflect.InvokableConstructor` so cross-platform code can use the same shape.
  */
final class InvokableConstructor private[reflect] (ctor: Constructor[?]):
  val parameterTypes: List[Class[?]] = ctor.getParameterTypes.toList

  /**
    * Invokes this constructor. If the underlying constructor throws an exception, that exception is
    * rethrown directly (rather than wrapped in `InvocationTargetException`).
    */
  def newInstance(args: Any*): Any =
    try
      ctor.newInstance(args.asInstanceOf[Seq[AnyRef]]*)
    catch
      case e: InvocationTargetException =>
        val cause = e.getCause
        if cause == null then
          throw e
        else
          throw cause

end InvokableConstructor
