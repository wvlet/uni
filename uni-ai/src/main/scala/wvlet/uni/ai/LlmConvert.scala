package wvlet.uni.ai

import wvlet.uni.json.JSON.*

/**
  * Helpers shared by the vendor converters
  */
object LlmConvert:
  /**
    * Rewrite a tool-call id into the characters a vendor accepts. The rewrite is deterministic, so
    * a call and its result map to the same id, and appends a hash when characters were replaced or
    * the id was cut, so distinct ids stay distinct.
    */
  def sanitizeToolId(id: String, allowed: Char => Boolean, maxLength: Int = 64): String =
    if id.nonEmpty && id.length <= maxLength && id.forall(allowed) then
      id
    else
      val base = id.map(c =>
        if allowed(c) then
          c
        else
          '_'
      )
      val hash = f"${fnv1a(id)}%08x"
      if id.isEmpty then
        s"call_${hash}"
      else
        base.take(maxLength - 9) + "_" + hash

  /** Anthropic tool ids: `^[a-zA-Z0-9_-]+$` */
  def anthropicToolId(id: String): String = sanitizeToolId(
    id,
    c => c.isLetterOrDigit && c < 128 || c == '_' || c == '-',
    maxLength = 128
  )

  /** Bedrock tool ids: `[a-zA-Z0-9_.:-]+`, at most 64 characters */
  def bedrockToolId(id: String): String = sanitizeToolId(
    id,
    c => c.isLetterOrDigit && c < 128 || c == '_' || c == '-' || c == '.' || c == ':'
  )

  /** A deterministic id for a tool call that came without one (Gemini before version 3) */
  def generatedToolId(name: String, index: Int): String = f"call_${fnv1a(s"${name}:${index}")}%08x"

  private def fnv1a(s: String): Int =
    var h = 0x811c9dc5
    s.foreach { c =>
      h ^= c
      h *= 0x01000193
    }
    h

  /** Split `data:<mediaType>;base64,<data>` */
  def parseDataUrl(url: String): Option[(String, String)] =
    if url.startsWith("data:") then
      val comma = url.indexOf(',')
      if comma > 0 then
        val meta = url.substring(5, comma)
        Some(meta.stripSuffix(";base64") -> url.substring(comma + 1))
      else
        None
    else
      None

  def dataUrl(mediaType: String, base64: String): String = s"data:${mediaType};base64,${base64}"

  /** A File part from a URL, decoding `data:` URLs into base64 bytes */
  def fileFromUrl(url: String, defaultMediaType: String): LlmPart.File =
    parseDataUrl(url) match
      case Some((mediaType, data)) =>
        LlmPart.File(mediaType, FileSource.Base64(data))
      case None =>
        LlmPart.File(defaultMediaType, FileSource.Url(url))

  /** The URL form of a file source, as OpenAI and Chat Completions take images */
  def urlOf(f: LlmPart.File): Option[String] =
    f.source match
      case FileSource.Base64(d) =>
        Some(dataUrl(f.mediaType, d))
      case FileSource.Url(u) =>
        Some(u)
      case FileSource.FileId(_) =>
        None

  /** The text of a tool result, with files and other parts dropped */
  def toolOutputText(output: Seq[LlmPart]): String = output
    .collect { case LlmPart.Text(t, _) =>
      t
    }
    .mkString("\n")

  /**
    * Merge one vendor's `providerOptions` into a request: its keys are set on the request JSON,
    * replacing fields of the same name
    */
  def withProviderOptions[A](a: A, options: ProviderMetadata, vendor: String)(using
      c: JsonCodec[A]
  ): A =
    options.get(vendor) match
      case Some(opts) if !opts.isEmpty =>
        c.encode(a) match
          case JSONObject(fields) =>
            val keys = opts.v.map(_._1).toSet
            c.decode(JSONObject(fields.filterNot(f => keys.contains(f._1)) ++ opts.v))
          case _ =>
            a
      case _ =>
        a

  /** Request fields not consumed by the neutral model, as a vendor's providerOptions */
  def providerOptionsOf(vendor: String, fields: Map[String, JSONValue]): ProviderMetadata =
    if fields.isEmpty then
      ProviderMetadata.empty
    else
      Map(vendor -> JSONObject(fields.toSeq))

  /** Encode tool input as the JSON string OpenAI-style APIs take */
  def toolInputString(input: JSONValue): String =
    input match
      case JSONString(s) =>
        s
      case other =>
        other.toJSON

  /** Parse tool input JSON, keeping it as a string when it is not valid JSON */
  def parseToolInput(s: String): JSONValue =
    if s.trim.isEmpty then
      JSONObject.empty
    else
      Json.parseOrString(s)

  /**
    * Carries reasoning data of one vendor through a client of another vendor. A client such as
    * Claude Code sends back the thinking blocks it received, signatures unchanged, so a proxy can
    * put OpenAI encrypted reasoning or a Gemini thought signature in an Anthropic thinking
    * `signature` and recover it on the next turn. The prefix keeps carriers apart from real
    * signatures.
    */
  object Carrier:
    val Prefix: String = "uni-ai:"

    def encode(vendor: String, data: JSONObject): String = s"${Prefix}${vendor}:${data.toJSON}"

    def decode(signature: String): Option[(String, JSONObject)] =
      if signature.startsWith(Prefix) then
        val rest  = signature.substring(Prefix.length)
        val colon = rest.indexOf(':')
        if colon > 0 then
          Json.parseOrString(rest.substring(colon + 1)) match
            case o: JSONObject =>
              Some(rest.substring(0, colon) -> o)
            case _ =>
              None
        else
          None
      else
        None

    def isCarrier(signature: String): Boolean = signature.startsWith(Prefix)

  end Carrier

end LlmConvert
