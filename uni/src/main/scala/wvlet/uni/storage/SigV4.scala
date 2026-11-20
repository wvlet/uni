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
package wvlet.uni.storage

import wvlet.uni.util.URLEncoder

/**
  * A request to sign with AWS Signature Version 4.
  *
  * @param path
  *   the URI path exactly as sent on the wire (already percent-encoded)
  * @param query
  *   raw (not encoded) query parameters
  * @param headers
  *   headers to sign, besides `host` and the `x-amz-*` headers the signer adds
  */
case class SigV4Request(
    method: String,
    host: String,
    path: String,
    query: Seq[(String, String)] = Seq.empty,
    headers: Seq[(String, String)] = Seq.empty,
    payloadHash: String = Sha256.emptyHex
)

/**
  * AWS Signature Version 4 for S3 and S3-compatible stores (Cloudflare R2, GCS HMAC interop,
  * MinIO). See https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv.html
  */
object SigV4:
  val Algorithm: String       = "AWS4-HMAC-SHA256"
  val UnsignedPayload: String = "UNSIGNED-PAYLOAD"

  /** The longest expiry a presigned URL may have: 7 days */
  val MaxPresignSeconds: Long = 7L * 24 * 3600

  /**
    * Signs a request with headers. Returns the headers to add, including `Authorization`,
    * `x-amz-date`, `x-amz-content-sha256` (when `includeContentSha256`), and `x-amz-security-token`
    * for temporary credentials.
    */
  def signHeaders(
      req: SigV4Request,
      credentials: AwsCredentials,
      region: String,
      service: String = "s3",
      epochMillis: Long = System.currentTimeMillis(),
      includeContentSha256: Boolean = true
  ): Seq[(String, String)] =
    val amzDate    = AwsTime.amzDate(epochMillis)
    val dateStamp  = amzDate.take(8)
    val amzHeaders =
      Seq("x-amz-date" -> amzDate) ++ (
        if includeContentSha256 then
          Seq("x-amz-content-sha256" -> req.payloadHash)
        else
          Nil
      ) ++ credentials.sessionToken.map("x-amz-security-token" -> _)
    val allHeaders                        = (("host" -> req.host) +: req.headers) ++ amzHeaders
    val (canonicalHeaders, signedHeaders) = canonicalizeHeaders(allHeaders)
    val canonicalRequest                  = Seq(
      req.method,
      req.path,
      canonicalQuery(req.query),
      canonicalHeaders,
      signedHeaders,
      req.payloadHash
    ).mkString("\n")
    val scope     = s"${dateStamp}/${region}/${service}/aws4_request"
    val signature = sign(credentials, dateStamp, region, service, amzDate, scope, canonicalRequest)
    val auth      =
      s"${Algorithm} Credential=${credentials
          .accessKeyId}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}"
    amzHeaders :+ ("Authorization" -> auth)

  end signHeaders

  /**
    * Builds a presigned URL query string (without the leading `?`). Only the `host` header is
    * signed, and the payload is unsigned, so anyone holding the URL can send the request until it
    * expires.
    */
  def presignQuery(
      req: SigV4Request,
      credentials: AwsCredentials,
      region: String,
      expiresSeconds: Long,
      service: String = "s3",
      epochMillis: Long = System.currentTimeMillis()
  ): String =
    if expiresSeconds < 1 || expiresSeconds > MaxPresignSeconds then
      throw IllegalArgumentException(
        s"A presigned URL must expire in 1 to ${MaxPresignSeconds} seconds: ${expiresSeconds}"
      )
    val amzDate                           = AwsTime.amzDate(epochMillis)
    val dateStamp                         = amzDate.take(8)
    val scope                             = s"${dateStamp}/${region}/${service}/aws4_request"
    val (canonicalHeaders, signedHeaders) = canonicalizeHeaders(("host" -> req.host) +: req.headers)
    val query                             =
      req.query ++
        Seq(
          "X-Amz-Algorithm"     -> Algorithm,
          "X-Amz-Credential"    -> s"${credentials.accessKeyId}/${scope}",
          "X-Amz-Date"          -> amzDate,
          "X-Amz-Expires"       -> expiresSeconds.toString,
          "X-Amz-SignedHeaders" -> signedHeaders
        ) ++ credentials.sessionToken.map("X-Amz-Security-Token" -> _)
    val cq               = canonicalQuery(query)
    val canonicalRequest = Seq(
      req.method,
      req.path,
      cq,
      canonicalHeaders,
      signedHeaders,
      UnsignedPayload
    ).mkString("\n")
    val signature = sign(credentials, dateStamp, region, service, amzDate, scope, canonicalRequest)
    s"${cq}&X-Amz-Signature=${signature}"

  end presignQuery

  /** Percent-encodes a query string in canonical order */
  def canonicalQuery(params: Seq[(String, String)]): String = params
    .map((k, v) => (URLEncoder.encode(k), URLEncoder.encode(v)))
    .sorted
    .map((k, v) => s"${k}=${v}")
    .mkString("&")

  /** Percent-encodes an object key for a URI path, keeping `/` separators */
  def encodePath(key: String): String = key.split("/", -1).map(URLEncoder.encode).mkString("/")

  private def canonicalizeHeaders(headers: Seq[(String, String)]): (String, String) =
    val grouped = headers
      .groupBy(_._1.toLowerCase)
      .view
      .mapValues(_.map((_, v) => v.trim.replaceAll("\\s+", " ")).mkString(","))
      .toSeq
      .sortBy(_._1)
    val canonical = grouped.map((k, v) => s"${k}:${v}\n").mkString
    (canonical, grouped.map(_._1).mkString(";"))

  private def sign(
      credentials: AwsCredentials,
      dateStamp: String,
      region: String,
      service: String,
      amzDate: String,
      scope: String,
      canonicalRequest: String
  ): String =
    val stringToSign = Seq(Algorithm, amzDate, scope, Sha256.hex(canonicalRequest)).mkString("\n")
    val kDate    = Sha256.hmac(s"AWS4${credentials.secretAccessKey}".getBytes("UTF-8"), dateStamp)
    val kRegion  = Sha256.hmac(kDate, region)
    val kService = Sha256.hmac(kRegion, service)
    val kSigning = Sha256.hmac(kService, "aws4_request")
    Sha256.toHex(Sha256.hmac(kSigning, stringToSign))

end SigV4

/**
  * UTC timestamps (for AWS, GCS, and Azure) without java.time formatters, which Scala.js and Native
  * don't all ship
  */
private[storage] object AwsTime:
  /** `yyyyMMdd'T'HHmmss'Z'` */
  def amzDate(epochMillis: Long): String =
    val secs      = Math.floorDiv(epochMillis, 1000L)
    val days      = Math.floorDiv(secs, 86400L)
    val sod       = Math.floorMod(secs, 86400L).toInt
    val (y, m, d) = civilFromDays(days)
    f"${y}%04d${m}%02d${d}%02dT${sod / 3600}%02d${(sod / 60) % 60}%02d${sod % 60}%02dZ"

  /** RFC 1123, as HTTP `Date` and Azure `x-ms-date` use: `Fri, 20 Nov 2026 18:00:00 GMT` */
  def httpDate(epochMillis: Long): String =
    val secs      = Math.floorDiv(epochMillis, 1000L)
    val days      = Math.floorDiv(secs, 86400L)
    val sod       = Math.floorMod(secs, 86400L).toInt
    val (y, m, d) = civilFromDays(days)
    // 1970-01-01 was a Thursday
    val dow          = DayNames(Math.floorMod(days + 4, 7L).toInt)
    val (hh, mm, ss) = (sod / 3600, (sod / 60) % 60, sod % 60)
    f"${dow}, ${d}%02d ${MonthNames(m - 1)} ${y}%04d ${hh}%02d:${mm}%02d:${ss}%02d GMT"

  /** ISO-8601 to the second, as Azure SAS tokens use: `2026-11-20T18:00:00Z` */
  def isoSeconds(epochMillis: Long): String =
    val secs      = Math.floorDiv(epochMillis, 1000L)
    val days      = Math.floorDiv(secs, 86400L)
    val sod       = Math.floorMod(secs, 86400L).toInt
    val (y, m, d) = civilFromDays(days)
    f"${y}%04d-${m}%02d-${d}%02dT${sod / 3600}%02d:${(sod / 60) % 60}%02d:${sod % 60}%02dZ"

  private val DayNames   = IndexedSeq("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
  private val MonthNames = IndexedSeq(
    "Jan",
    "Feb",
    "Mar",
    "Apr",
    "May",
    "Jun",
    "Jul",
    "Aug",
    "Sep",
    "Oct",
    "Nov",
    "Dec"
  )

  /** Parses an ISO-8601 UTC timestamp such as `2026-11-20T18:00:00Z` into epoch millis */
  def parseIso(s: String): Option[Long] =
    val Pattern =
      """(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d+)?(Z|[+-]\d{2}:?\d{2})""".r
    s.trim match
      case Pattern(y, mo, d, h, mi, sec, _, zone) =>
        val days   = daysFromCivil(y.toInt, mo.toInt, d.toInt)
        val offset =
          if zone == "Z" then
            0
          else
            val digits = zone.drop(1).replace(":", "")
            val mins   = digits.take(2).toInt * 60 + digits.drop(2).toInt
            if zone.startsWith("-") then
              -mins
            else
              mins
        Some(
          ((days * 86400L + h.toLong * 3600 + mi.toLong * 60 + sec.toLong) - offset * 60L) * 1000L
        )
      case _ =>
        None

  // Howard Hinnant's days-from-civil algorithms
  private def civilFromDays(z0: Long): (Int, Int, Int) =
    val z   = z0 + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y   = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp  = (5 * doy + 2) / 153
    val d   = doy - (153 * mp + 2) / 5 + 1
    val m   =
      if mp < 10 then
        mp + 3
      else
        mp - 9
    (
      (
        if m <= 2 then
          y + 1
        else
          y
      ).toInt,
      m.toInt,
      d.toInt
    )

  private def daysFromCivil(y0: Int, m: Int, d: Int): Long =
    val y =
      if m <= 2 then
        y0 - 1
      else
        y0
    val era = Math.floorDiv(y.toLong, 400L)
    val yoe = y - era * 400
    val mp  =
      if m > 2 then
        m - 3
      else
        m + 9
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    era * 146097 + doe - 719468

end AwsTime
