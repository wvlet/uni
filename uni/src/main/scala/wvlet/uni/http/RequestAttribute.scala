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
package wvlet.uni.http

/**
  * A typed key for a server-side request attribute.
  *
  * Keys compare by identity, so two libraries that pick the same name never see each other's
  * values. Define a key once, e.g. as a `val` in an object, and share that instance:
  * {{{
  * object Auth:
  *   val Identity: RequestAttribute[UserIdentity] = RequestAttribute("identity")
  * }}}
  *
  * @param name
  *   A label for debugging; not used for lookup
  */
final class RequestAttribute[A](val name: String):
  override def toString: String = s"RequestAttribute(${name})"

object RequestAttribute:
  def apply[A](name: String): RequestAttribute[A] = new RequestAttribute[A](name)

  /**
    * The address of the client connected to the server, as `host:port`. This is the direct peer,
    * e.g. a load balancer; it does not read `X-Forwarded-For`. Set by the Netty server only; the
    * Node.js and Native servers do not set it yet.
    */
  val RemoteAddress: RequestAttribute[String] = RequestAttribute("remoteAddress")

/**
  * Immutable attributes that a server, or a filter running in it, attaches to a [[Request]], e.g.
  * the identity resolved by an authentication filter.
  *
  * Attributes never travel over the wire: a request decoded by a server starts with none, and
  * clients do not send them, so a client cannot spoof them with headers. Because `Request` is
  * immutable, attributes set by `next.handle(request.withAttribute(...))` in a filter are scoped to
  * that request.
  *
  * `toString` shows only the attribute names, so logging a request does not print values such as
  * credentials.
  */
final class RequestAttributes private (private val entries: Map[RequestAttribute[?], Any]):
  def get[A](key: RequestAttribute[A]): Option[A] = entries.get(key).map(_.asInstanceOf[A])
  def contains(key: RequestAttribute[?]): Boolean = entries.contains(key)
  def isEmpty: Boolean                            = entries.isEmpty
  def keys: Iterable[RequestAttribute[?]]         = entries.keys

  def updated[A](key: RequestAttribute[A], value: A): RequestAttributes = RequestAttributes(
    entries.updated(key, value)
  )

  def removed(key: RequestAttribute[?]): RequestAttributes = RequestAttributes(entries - key)

  override def equals(other: Any): Boolean =
    other match
      case that: RequestAttributes =>
        entries == that.entries
      case _ =>
        false

  override def hashCode: Int = entries.hashCode

  override def toString: String = entries.keys.map(_.name).mkString("RequestAttributes(", ", ", ")")

end RequestAttributes

object RequestAttributes:
  val empty: RequestAttributes = RequestAttributes(Map.empty)
