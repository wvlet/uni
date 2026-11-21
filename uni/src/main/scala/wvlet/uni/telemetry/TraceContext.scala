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
package wvlet.uni.telemetry

/**
  * The identity of a span within a trace, as carried by the W3C Trace Context `traceparent` header
  * (https://www.w3.org/TR/trace-context/).
  *
  * @param traceId
  *   32 lower-case hex digits, not all zero
  * @param spanId
  *   16 lower-case hex digits, not all zero
  * @param sampled
  *   whether the trace is recorded. An unsampled context is still propagated, so downstream
  *   services make the same decision
  * @param traceState
  *   the `tracestate` header received with this context, passed on unchanged
  */
case class TraceContext(
    traceId: String,
    spanId: String,
    sampled: Boolean,
    traceState: Option[String] = None
):
  /** The `traceparent` header value for this context. */
  def toTraceParent: String =
    val flags =
      if sampled then
        "01"
      else
        "00"
    s"00-${traceId}-${spanId}-${flags}"

  /** False for the all-zero ids of a disabled [[Telemetry]]; such a context is never propagated. */
  def isValid: Boolean =
    traceId != TraceContext.InvalidTraceId && spanId != TraceContext.InvalidSpanId

  /** A context for a new child span in the same trace. */
  def newChild: TraceContext = copy(spanId = TraceContext.newSpanId)

object TraceContext:
  /** The W3C header that carries the trace id, the parent span id, and the sampled flag. */
  val TraceParentHeader: String = "traceparent"

  /** The W3C header that carries vendor-specific trace data, passed on unchanged. */
  val TraceStateHeader: String = "tracestate"

  private[telemetry] val InvalidTraceId = "0" * 32
  private[telemetry] val InvalidSpanId  = "0" * 16

  /** The context of a span that belongs to no trace. */
  val invalid: TraceContext = TraceContext(InvalidTraceId, InvalidSpanId, sampled = false)

  /** A new root context with fresh trace and span ids. */
  def newRoot(sampled: Boolean): TraceContext = TraceContext(newTraceId, newSpanId, sampled)

  /**
    * Parse a `traceparent` header. Returns None for a malformed value, which the caller treats as
    * no parent, as the specification requires.
    */
  def parse(traceParent: String, traceState: Option[String] = None): Option[TraceContext] =
    val s = traceParent.trim
    // version-traceid-spanid-flags: 2 + 1 + 32 + 1 + 16 + 1 + 2 = 55 characters for version 00.
    // Later versions may append fields after another dash.
    if s.length < 55 || s.charAt(2) != '-' || s.charAt(35) != '-' || s.charAt(52) != '-' then
      None
    else
      val version     = s.substring(0, 2)
      val traceId     = s.substring(3, 35)
      val spanId      = s.substring(36, 52)
      val flags       = s.substring(53, 55)
      val validLength =
        if version == "00" then
          s.length == 55
        else
          s.length == 55 || s.charAt(55) == '-'
      if !validLength || version == "ff" || !isHex(version) || !isHex(traceId) || !isHex(spanId) ||
        !isHex(flags) || traceId == InvalidTraceId || spanId == InvalidSpanId
      then
        None
      else
        val sampled = (Integer.parseInt(flags, 16) & 0x01) != 0
        Some(TraceContext(traceId, spanId, sampled, traceState.map(_.trim).filter(_.nonEmpty)))

  private def isHex(s: String): Boolean =
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) then
        return false
      i += 1
    true

  // scala.util.Random rather than SecureRandom, which Scala Native lacks. Trace ids identify a
  // trace for correlation only; they are not secrets.
  private def randomHex(bytes: Int): String =
    val sb = StringBuilder(bytes * 2)
    var i  = 0
    while i < bytes do
      val b = scala.util.Random.nextInt(256)
      sb.append(Character.forDigit(b >>> 4, 16)).append(Character.forDigit(b & 0xf, 16))
      i += 1
    sb.toString

  def newTraceId: String =
    val id = randomHex(16)
    if id == InvalidTraceId then
      newTraceId
    else
      id

  def newSpanId: String =
    val id = randomHex(8)
    if id == InvalidSpanId then
      newSpanId
    else
      id

end TraceContext
