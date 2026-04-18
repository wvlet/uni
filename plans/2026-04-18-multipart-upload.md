# Multipart Upload Support for HTTP Client

Date: 2026-04-18
Status: Implemented in PR #484

## Design changes during the PR cycle

- **Scope confirmed as `multipart/form-data` only.** `multipart/form-data` is
  about bundling form fields + small-to-moderate files into one request, not
  about streaming/large uploads. Large-content concerns (streaming bodies,
  S3-style chunked uploads) are a separate design.
- **Removed `withSubtype`.** It would have advertised `multipart/mixed` etc.
  while the encoder always writes `Content-Disposition: form-data` per part.
  YAGNI: drop it until we actually implement per-part disposition for the
  other subtypes.
- **Channels must propagate `content.contentType` to the wire.** The original
  design missed that the three HTTP channels only serialized `request.headers`
  and never derived `Content-Type` from `content.contentType`. Fixed via a new
  `Request.wireHeaders` helper used by all three channels. This also
  incidentally fixes a latent issue for `withJsonContent` / `withTextContent`
  etc. (though those cases had no explicit test coverage before).
- **Custom part headers and boundaries are CR/LF-validated** to prevent header
  injection.
- **Zero-part multipart is not collapsed to `Empty`** — it still has a
  meaningful closing-boundary wire representation.
- **Ergonomic API:** `Multipart.of(Seq(...))` and `MultipartPart.field` /
  `MultipartPart.file` factories, plus `Request.withMultipart(Seq[...])` as
  the one-liner. Builder retained for the fixed-boundary case (tests).
- **Prefer `Seq[T]` over varargs** in public API (user preference).

## Problem

The `uni` HTTP client (`uni/src/main/scala/wvlet/uni/http/`) has no first-class
multipart/form-data support. Today users must hand-assemble the RFC 7578 byte
layout and set `Content-Type: multipart/form-data; boundary=...` themselves.
What exists:

- `ContentType.MultipartFormData` / `MultipartMixed` / `MultipartAlternative`
  constants (`ContentType.scala:50-52`)
- `ContentType.withBoundary(...)` helper (`ContentType.scala:135`)
- `isMultipart` classifier (`ContentType.scala:139`)

`HttpContent` only has `Text`, `Byte`, and `Json` variants (`HttpContent.scala:47-84`).

## Scope

**In scope (this PR):**

- Client-side multipart/form-data body construction
- Form field parts (name + text value)
- File parts (name + filename + content-type + bytes)
- Builder API with automatic boundary generation
- Encoder producing the RFC 7578 byte stream
- Helper on `Request` for ergonomic usage
- Unit tests covering encoding layout and builder API

**Out of scope (follow-up PRs):**

- Server-side multipart decoder (Netty handler currently opaque — needs a
  separate PR wiring `HttpPostRequestDecoder`)
- Streaming/chunked multipart uploads (all parts held in memory for now)
- `multipart/mixed` and `multipart/alternative` variants
- File-backed parts reading from `java.io.File` or `Path` directly (JVM-only
  concern; keep cross-platform by accepting `Array[Byte]` only)

## API

New file `uni/src/main/scala/wvlet/uni/http/Multipart.scala`:

```scala
sealed trait MultipartPart:
  def name: String
  def headers: HttpMultiMap
  def bodyBytes: Array[Byte]

object MultipartPart:
  case class FormField(
      name: String,
      value: String,
      headers: HttpMultiMap = HttpMultiMap.empty
  ) extends MultipartPart:
    def bodyBytes: Array[Byte] = value.getBytes("UTF-8")

  case class FilePart(
      name: String,
      filename: String,
      bytes: Array[Byte],
      contentType: ContentType = ContentType.ApplicationOctetStream,
      headers: HttpMultiMap = HttpMultiMap.empty
  ) extends MultipartPart:
    def bodyBytes: Array[Byte] = bytes

case class Multipart(
    boundary: String,
    parts: List[MultipartPart],
    subtype: String = "form-data"
):
  def contentType: ContentType = ContentType(s"multipart/${subtype}").withBoundary(boundary)
  def encode: Array[Byte] = Multipart.encode(this)

object Multipart:
  def builder(): Multipart.Builder = new Builder()

  def generateBoundary(): String = ...  // cryptographically-random 24-char hex

  def encode(mp: Multipart): Array[Byte] = ...

  final class Builder:
    def addField(name: String, value: String): Builder
    def addFile(name: String, filename: String, bytes: Array[Byte]): Builder
    def addFile(name: String, filename: String, bytes: Array[Byte], contentType: ContentType): Builder
    def addPart(part: MultipartPart): Builder
    def withBoundary(boundary: String): Builder
    def withSubtype(subtype: String): Builder
    def build(): Multipart
```

Extend `HttpContent` with a new case:

```scala
case class MultipartContent(multipart: Multipart) extends HttpContent:
  override val contentType: Option[ContentType] = Some(multipart.contentType)
  private lazy val encoded: Array[Byte] = multipart.encode
  // asBytes, toContentBytes, length, etc. delegate to encoded
```

Request helper:

```scala
def withMultipart(mp: Multipart): Request = copy(content = HttpContent.multipart(mp))
```

## Encoder details (RFC 7578)

For each part, emit:

```
--<boundary>\r\n
Content-Disposition: form-data; name="<name>"[; filename="<filename>"]\r\n
[Content-Type: <ct>\r\n]
[<extra headers>\r\n]
\r\n
<body bytes>\r\n
```

After the last part:

```
--<boundary>--\r\n
```

Notes:

- Name/filename values: quote literal `"` as `\"` per WHATWG HTML spec
  (browsers do this; Jersey/Spring also accept). Reject embedded CR/LF.
- For `FilePart`, always emit `Content-Type`. For `FormField`, omit it
  (text/plain; charset=utf-8 is implicit when UTF-8 bytes are used, and most
  servers treat it as such).
- Boundary: 24 random hex chars prefixed with `----uni-`. Example:
  `----uni-a1b2c3d4e5f6a7b8c9d0e1f2`. Must not appear in any part body — we do
  not scan part bodies because the random suffix makes collision negligible
  (2^-96). Document this caveat.
- Use `java.util.Random` in Native/JS (no SecureRandom dependency). Or use
  `wvlet.uni.util.` existing utilities if there is a cross-platform random.

## Boundary generation cross-platform

Check existing util modules for a random source. If none, use a simple
`java.util.Random` seeded with `System.currentTimeMillis ^ hashCode`. The
boundary just needs to not collide with user content, not be cryptographically
secret.

## Tests

New file `uni/src/test/scala/wvlet/uni/http/MultipartTest.scala`:

- Builder adds fields and files in order
- `encode` produces expected byte layout for a single text field
- `encode` produces expected byte layout for a single file part
- `encode` produces expected byte layout for a mix (field + file + field)
- `contentType` includes `multipart/form-data; boundary=<boundary>`
- Filename with `"` is escaped to `\"`
- CR/LF in field name/filename is rejected with `IllegalArgumentException`
- `generateBoundary()` returns unique values across calls
- `Request.withMultipart(...)` sets content and content-type correctly
- Round-trip: build → encode → decode manually → parts match

## Implementation order

1. `Multipart.scala` with types, builder, encoder, boundary gen
2. Extend `HttpContent` with `MultipartContent` case + `HttpContent.multipart(...)` factory
3. `Request.withMultipart(...)` helper
4. Tests
5. `scalafmtAll`
6. `coreJVM/test` + full `test` (JVM + JS where applicable)

## Open questions / risks

- **Cross-platform**: `java.util.Random` is available on all three platforms
  via `scala.util.Random`. Use that.
- **Size limits**: all parts held in memory. Acceptable for current scale; doc
  a limitation in the class-level scaladoc.
- **Chunked transfer encoding**: not needed — we know the full encoded length
  so `Content-Length` is set normally.

## Non-goals re-stated

Server-side decoding, streaming, and `multipart/mixed`/`alternative` are
explicitly deferred. Follow-up PR(s) when a real use case arrives.
