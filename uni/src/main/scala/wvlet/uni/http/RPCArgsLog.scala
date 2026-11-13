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

import wvlet.uni.surface.{Parameter, Surface}
import wvlet.uni.util.ULID

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

/**
  * Builds the `rpc_*` fields of an access log entry. `rpc_args` walks the method arguments by their
  * Surface and leaves out every parameter or field annotated with `@secret`, as Airframe's
  * `HttpLogs.extractRpcArgLog` does.
  *
  * The log writer encodes values it doesn't know with `toString`, which would print secret fields,
  * so only scalars and values this walk can inspect are logged as they are. Anything else (e.g. a
  * value typed as a sealed trait, or a case class inside `Any`) is logged as its class name.
  */
private[http] object RPCArgsLog:
  // Guards against deeply nested or cyclic values
  private val MaxDepth = 32

  def entries(call: HttpAccessLogFilter.RPCCall, withArgs: Boolean): Map[String, Any] =
    val b = ListMap.newBuilder[String, Any]
    b += "rpc_interface" -> call.interfaceName
    b += "rpc_class"     -> call.className
    b += "rpc_method"    -> call.method.name
    if withArgs then
      val args = params(call.method.args.zip(call.args), depth = 0)
      if args.nonEmpty then
        b += "rpc_args" -> args
    b.result()

  private def params(values: Seq[(Parameter, Any)], depth: Int): ListMap[String, Any] =
    val b = ListMap.newBuilder[String, Any]
    values.foreach { (p, v) =>
      // A failure on one field drops only that field
      try
        param(p, v, depth).foreach(b += _)
      catch
        case NonFatal(_) =>
          ()
    }
    b.result()

  private def param(p: Parameter, v: Any, depth: Int): Option[(String, Any)] =
    v match
      case _ if p.isSecret =>
        None
      // Duplicates the request fields of the entry
      case _: Request | _: RequestAttributes =>
        None
      case _ =>
        Some(p.name -> value(p.surface, v, depth + 1))

  private def value(s: Surface, v: Any, depth: Int): Any =
    v match
      case null =>
        null
      case _ if depth > MaxDepth =>
        className(v)
      // A body or file can be large, and its bytes are rarely readable in a log
      case b: Array[Byte] =>
        s"[${b.length} bytes]"
      case _ if isScalar(v) =>
        v
      case o: Option[?] =>
        o.map(value(typeArg(s, 0), _, depth + 1))
      case m: Map[?, ?] =>
        val valueSurface = typeArg(s, 1)
        ListMap.from(
          m.map { (k, x) =>
            val key =
              if isScalar(k) then
                k
              else
                className(k)
            key -> value(valueSurface, x, depth + 1)
          }
        )
      case seq: Seq[?] =>
        val elem = typeArg(s, 0)
        seq.map(value(elem, _, depth + 1))
      case arr: Array[?] =>
        val elem = typeArg(s, 0)
        arr.toSeq.map(value(elem, _, depth + 1))
      case _ if s != null && s.params.nonEmpty && s.rawType.isInstance(v) =>
        params(s.params.map(p => p -> p.get(v)), depth)
      case _ =>
        className(v)

  private def typeArg(s: Surface, i: Int): Surface =
    if s == null then
      null
    else
      s.dealias.typeArgs.lift(i).orNull

  private def isScalar(v: Any): Boolean =
    v match
      case _: String | _: Boolean | _: Char | _: java.lang.Number | _: ULID | _: java.util.UUID |
          _: java.time.Instant | _: java.lang.Enum[?] =>
        true
      // An enum case with fields prints them in toString, so only a singleton case is a scalar
      case e: scala.reflect.Enum =>
        e.productArity == 0
      case _ =>
        false

  private def className(v: Any): String = v.getClass.getName

end RPCArgsLog
