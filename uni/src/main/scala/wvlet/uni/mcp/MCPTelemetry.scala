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
package wvlet.uni.mcp

import wvlet.uni.json.JSON.{JSONLong, JSONObject, JSONString, JSONValue}
import wvlet.uni.telemetry.{OperationTrace, TraceContext}

/**
  * Span names and attributes of MCP requests, following the OpenTelemetry MCP semantic conventions.
  * Tool arguments and results are never recorded.
  */
private[mcp] object MCPTelemetry:
  /**
    * The trace context a caller put in `params._meta`, which MCP uses instead of transport headers
    * so it reaches the server over stdio too.
    */
  def contextOf(params: Option[JSONObject]): Option[TraceContext] =
    for
      meta <- params
        .flatMap(_.get("_meta"))
        .collect { case o: JSONObject =>
          o
        }
      parent <- meta
        .get(TraceContext.TraceParentHeader)
        .collect { case JSONString(v) =>
          v
        }
      ctx <- TraceContext.parse(
        parent,
        meta
          .get(TraceContext.TraceStateHeader)
          .collect { case JSONString(v) =>
            v
          }
      )
    yield ctx

  /** `params` with `traceparent` (and `tracestate`) of `ctx` in `_meta`, keeping other keys. */
  def withContext(params: Option[JSONObject], ctx: TraceContext): JSONObject =
    val fields = params.map(_.v).getOrElse(Seq.empty)
    val meta   = fields
      .collectFirst { case ("_meta", o: JSONObject) =>
        o.v
      }
      .getOrElse(Seq.empty)
      .filterNot((k, _) =>
        k == TraceContext.TraceParentHeader || k == TraceContext.TraceStateHeader
      )
    val trace =
      Seq(TraceContext.TraceParentHeader -> JSONString(ctx.toTraceParent)) ++
        ctx.traceState.map(s => TraceContext.TraceStateHeader -> JSONString(s))
    JSONObject(fields.filterNot(_._1 == "_meta") :+ ("_meta" -> JSONObject(meta ++ trace)))

  /** The tool name of a `tools/call`. */
  def toolNameOf(params: Option[JSONObject]): Option[String] = params
    .flatMap(_.get("name"))
    .collect { case JSONString(v) =>
      v
    }

  /** `{mcp.method.name} {gen_ai.tool.name}`, or the method alone. */
  def spanName(method: String, tool: Option[String]): String =
    tool.fold(method)(t => s"${method} ${t}")

  /** Set the request attributes. Those on the duration metric are low-cardinality. */
  def describe(
      trace: OperationTrace,
      method: String,
      id: Option[JSONValue],
      tool: Option[String],
      transport: Option[String]
  ): Unit =
    trace.setDimension("mcp.method.name", method)
    id.foreach(i => trace.setAttribute("jsonrpc.request.id", idString(i)))
    tool.foreach { t =>
      trace.setDimension("gen_ai.operation.name", "execute_tool")
      trace.setDimension("gen_ai.tool.name", t)
    }
    transport.foreach(trace.setDimension("network.transport", _))

  /**
    * A JSON-RPC error response: `rpc.status_code` and `error.type` carry the code. The message is
    * left out, since it can echo tool arguments.
    */
  def recordErrorCode(trace: OperationTrace, code: Int): Unit =
    trace.setDimension("rpc.status_code", code.toString)
    trace.setError(code.toString)

  /** A `tools/call` result with `isError: true`. */
  def recordToolError(trace: OperationTrace): Unit = trace.setError("tool_error")

  private def idString(id: JSONValue): String =
    id match
      case JSONString(v) =>
        v
      case JSONLong(v) =>
        v.toString
      case other =>
        other.toJSON

end MCPTelemetry
