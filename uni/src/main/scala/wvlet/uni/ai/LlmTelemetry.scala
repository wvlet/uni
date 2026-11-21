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
package wvlet.uni.ai

import wvlet.uni.http.{HttpAsyncClient, Request}
import wvlet.uni.rx.Rx
import wvlet.uni.telemetry.{
  HistogramInstrument,
  OperationTrace,
  Span,
  SpanKind,
  Telemetry,
  TraceContext
}

import scala.util.control.NonFatal

/**
  * What a GenAI inference span says about its request, following the OpenTelemetry GenAI
  * conventions. Messages, prompts, and completions are never part of it.
  *
  * @param operation
  *   `gen_ai.operation.name`, e.g. `chat` or `generate_content`
  * @param provider
  *   `gen_ai.provider.name`, e.g. `anthropic`, `aws.bedrock`, or `gcp.vertex_ai`
  */
private[ai] case class LlmOperation(
    operation: String,
    provider: String,
    model: String,
    stream: Boolean = false,
    maxTokens: Option[Long] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None
)

/**
  * The span and metrics of one inference call. Vendor clients report the response's id, model,
  * usage, and finish reasons as they arrive; they are written when the call ends.
  */
private[ai] class LlmCallTrace(telemetry: Telemetry, span: Span)
    extends OperationTrace(telemetry, span, HistogramInstrument.GenAiClientInferenceDuration):
  @volatile
  private var responseId: Option[String] = None

  @volatile
  private var usage: Option[LlmUsage] = None

  @volatile
  private var finishReasons: Seq[String] = Nil

  def response(id: String, model: String): Unit =
    if id.nonEmpty then
      responseId = Some(id)
    if model.nonEmpty then
      setDimension("gen_ai.response.model", model)

  def setUsage(u: LlmUsage): Unit = usage = Some(u)

  def setFinishReasons(reasons: Seq[String]): Unit =
    if reasons.nonEmpty then
      finishReasons = reasons

  override protected def errorTypeOf(e: Throwable): String =
    e match
      case a: LlmApiException =>
        a.errorType.getOrElse(a.statusCode.toString)
      case _ =>
        super.errorTypeOf(e)

  override protected def onEnd(): Unit =
    responseId.foreach(setAttribute("gen_ai.response.id", _))
    // Attribute values are scalars here, so several choices are joined
    if finishReasons.nonEmpty then
      setAttribute("gen_ai.response.finish_reasons", finishReasons.mkString(","))
    usage.foreach { u =>
      setAttribute("gen_ai.usage.input_tokens", u.inputTokens)
      setAttribute("gen_ai.usage.output_tokens", u.outputTokens)
      if u.cacheReadTokens > 0 then
        setAttribute("gen_ai.usage.cache_read.input_tokens", u.cacheReadTokens)
      if u.cacheWriteTokens > 0 then
        setAttribute("gen_ai.usage.cache_write.input_tokens", u.cacheWriteTokens)
      if u.reasoningTokens > 0 then
        setAttribute("gen_ai.usage.reasoning.output_tokens", u.reasoningTokens)
      if telemetry.isMetricsEnabled then
        // Token metrics take no error.type
        val attrs = metricAttributes - "error.type"
        telemetry.recordHistogram(
          HistogramInstrument.GenAiClientInferenceInputTokens,
          u.inputTokens.toDouble,
          attrs
        )
        telemetry.recordHistogram(
          HistogramInstrument.GenAiClientInferenceOutputTokens,
          u.outputTokens.toDouble,
          attrs
        )
    }

end LlmCallTrace

private[ai] object LlmTelemetry:
  /**
    * Trace one inference call made with `http`, when that client has telemetry. The span starts
    * when the returned Rx runs, as a child of the span current where this method was called, and is
    * current while `send` starts, so the HTTP client span becomes its child. `observe` sees each
    * value (the response, or every stream event), and is evaluated again for each run, so it can
    * keep state; the span ends with the stream.
    *
    * Without telemetry this is `send(request)`.
    */
  def traced[A](http: HttpAsyncClient, op: => LlmOperation, request: => Request)(
      send: Request => Rx[A]
  )(observe: => (LlmCallTrace, A) => Unit): Rx[A] =
    val telemetry = http.config.telemetry
    if !telemetry.isEnabled then
      send(request)
    else
      val parent = Telemetry.currentContext
      Rx.single(())
        .flatMap { _ =>
          val trace = start(telemetry, op, parent)
          try
            // A new observer per run: stream observers keep state
            val obs = observe
            val req = request
            trace.setServer(req.uri)
            val events = Telemetry.withSpan(trace.span)(send(req))
            trace.endWith(
              events.map { a =>
                try
                  obs(trace, a)
                catch
                  // Telemetry must never fail the call it observes
                  case NonFatal(_) =>
                    ()
                a
              }
            )
          catch
            case NonFatal(e) =>
              trace.fail(e)
              Rx.exception(e)
        }

  end traced

  private def start(
      telemetry: Telemetry,
      op: LlmOperation,
      parent: Option[TraceContext]
  ): LlmCallTrace =
    val name =
      if op.model.isEmpty then
        op.operation
      else
        s"${op.operation} ${op.model}"
    val trace = LlmCallTrace(telemetry, telemetry.startSpan(name, SpanKind.Client, parent))
    trace.setDimension("gen_ai.operation.name", op.operation)
    trace.setDimension("gen_ai.provider.name", op.provider)
    if op.model.nonEmpty then
      trace.setDimension("gen_ai.request.model", op.model)
    if op.stream then
      trace.setAttribute("gen_ai.request.stream", true)
    op.maxTokens.foreach(trace.setAttribute("gen_ai.request.max_tokens", _))
    op.temperature.foreach(trace.setAttribute("gen_ai.request.temperature", _))
    op.topP.foreach(trace.setAttribute("gen_ai.request.top_p", _))
    trace

  /** The finish reason the spec expects: the vendor's own value, else the unified kind. */
  def finishReason(r: FinishReason): Option[String] = r
    .raw
    .orElse(
      r.kind match
        case FinishReason.Kind.Stop =>
          Some("stop")
        case FinishReason.Kind.Length =>
          Some("length")
        case FinishReason.Kind.ToolCalls =>
          Some("tool_calls")
        case FinishReason.Kind.ContentFilter =>
          Some("content_filter")
        case FinishReason.Kind.Error =>
          Some("error")
        case FinishReason.Kind.Interrupted | FinishReason.Kind.Other =>
          None
    )

end LlmTelemetry
