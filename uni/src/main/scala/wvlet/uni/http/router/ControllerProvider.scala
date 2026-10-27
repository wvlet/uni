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
package wvlet.uni.http.router

import wvlet.uni.surface.Surface

/**
  * Provider interface for obtaining controller instances.
  *
  * This allows for different instantiation strategies (e.g., singleton, per-request, DI
  * integration).
  */
trait ControllerProvider:

  /**
    * Get a controller instance for the given Surface.
    *
    * @param surface
    *   The Surface describing the controller type
    * @return
    *   An instance of the controller
    */
  def get(surface: Surface): Any

  /**
    * Get a filter instance for the given Surface.
    *
    * @param surface
    *   The Surface describing the filter type
    * @return
    *   An instance of the filter
    */
  def getFilter(surface: Surface): Any = get(surface)

end ControllerProvider

/**
  * A simple controller provider that creates new instances using reflection.
  *
  * This provider uses the Surface's object factory to create instances. If the Surface doesn't have
  * an object factory (e.g., traits or abstract classes), it will throw an exception.
  */
class SimpleControllerProvider extends ControllerProvider:
  // Use mutable Map with synchronized access for cross-platform compatibility
  private val cache = scala.collection.mutable.Map[String, Any]()

  override def get(surface: Surface): Any = synchronized {
    cache.getOrElseUpdate(surface.fullName, createInstance(surface))
  }

  private def createInstance(surface: Surface): Any =
    surface.objectFactory match
      case Some(factory) =>
        factory.newInstance(Seq.empty)
      case None =>
        throw IllegalArgumentException(
          s"Cannot create instance of ${surface.fullName}: no object factory available. " +
            "Make sure the controller has a no-arg constructor."
        )

end SimpleControllerProvider

/**
  * A controller provider that uses a pre-registered map of instances.
  *
  * Useful for testing or when you want to provide pre-configured controller instances.
  */
class MapControllerProvider(instances: Map[String, Any]) extends ControllerProvider:

  override def get(surface: Surface): Any = instances
    .get(surface.fullName)
    .getOrElse {
      throw IllegalArgumentException(
        s"No instance registered for ${surface.fullName}. " +
          s"Available instances: ${instances.keys.mkString(", ")}"
      )
    }

end MapControllerProvider

object MapControllerProvider:

  /**
    * Create a MapControllerProvider from a varargs list of controller instances.
    */
  def apply(controllers: Any*): MapControllerProvider =
    val map =
      controllers
        .map { c =>
          c.getClass.getName.stripSuffix("$") -> c
        }
        .toMap
    // `new` is required: without it, this call resolves to this varargs apply and recurses forever
    new MapControllerProvider(map)

end MapControllerProvider
