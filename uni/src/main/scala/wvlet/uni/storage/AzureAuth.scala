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

import wvlet.uni.cloud.*
import wvlet.uni.util.Sha256

import wvlet.uni.http.HttpMethod
import wvlet.uni.util.Base64

/**
  * A user delegation key from Get User Delegation Key, for signing user delegation SAS URLs. The
  * timestamps are kept as the service sent them, because they are signed verbatim.
  */
private[storage] case class UserDelegationKey(
    signedOid: String,
    signedTid: String,
    signedStart: String,
    signedExpiry: String,
    signedService: String,
    signedVersion: String,
    value: String
):
  def expiryMillis: Long = AwsTime.parseIso(signedExpiry).getOrElse(0L)

  override def toString: String = s"UserDelegationKey(oid=${signedOid}, expiry=${signedExpiry})"

/**
  * Azure Storage Shared Key and SAS signing. See
  * https://learn.microsoft.com/rest/api/storageservices/authorize-with-shared-key and
  * https://learn.microsoft.com/rest/api/storageservices/create-service-sas
  */
private[storage] object AzureAuth:
  /** The REST API version every request sends and every SAS signs */
  val Version: String = "2024-11-04"

  /** The longest expiry a SAS URL from `presign` may have: 7 days, as user delegation keys allow */
  val MaxPresignSeconds: Long = 7L * 24 * 3600

  /**
    * The Shared Key string-to-sign for the Blob service (version 2015-02-21 and later)
    *
    * @param headers
    *   every header the request sends, besides `Content-Length`
    * @param encodedPath
    *   the URI path exactly as sent
    * @param query
    *   raw (not encoded) query parameters
    */
  def stringToSign(
      method: String,
      headers: Seq[(String, String)],
      contentLength: Long,
      account: String,
      encodedPath: String,
      query: Seq[(String, String)]
  ): String =
    def h(name: String): String = headers
      .collectFirst {
        case (k, v) if k.equalsIgnoreCase(name) =>
          v
      }
      .getOrElse("")
    val length =
      if contentLength == 0 then
        ""
      else
        contentLength.toString
    val standard = Seq(
      method,
      h("Content-Encoding"),
      h("Content-Language"),
      length,
      h("Content-MD5"),
      h("Content-Type"),
      h("Date"),
      h("If-Modified-Since"),
      h("If-Match"),
      h("If-None-Match"),
      h("If-Unmodified-Since"),
      h("Range")
    )
    standard.map(l => s"${l}\n").mkString + canonicalHeaders(headers) +
      canonicalResource(account, encodedPath, query)

  end stringToSign

  /** `x-ms-*` headers: lowercase names, sorted, values with whitespace runs folded */
  def canonicalHeaders(headers: Seq[(String, String)]): String =
    headers
      .filter(_._1.toLowerCase.startsWith("x-ms-"))
      .groupBy(_._1.toLowerCase)
      .view
      .mapValues(_.map((_, v) => v.trim.replaceAll("\\s+", " ")).mkString(","))
      .toSeq
      .sortBy(_._1)
      .map((k, v) => s"${k}:${v}\n")
      .mkString

  /**
    * `/<account><encoded path>`, then each query parameter as `\nname:value` (lowercase names,
    * sorted; several values sorted and joined with commas). With an emulator's path-style endpoint,
    * the path starts with the account again.
    */
  def canonicalResource(
      account: String,
      encodedPath: String,
      query: Seq[(String, String)]
  ): String =
    val params =
      query
        .groupBy(_._1.toLowerCase)
        .toSeq
        .sortBy(_._1)
        .map((k, vs) => s"\n${k}:${vs.map(_._2).sorted.mkString(",")}")
        .mkString
    s"/${account}${encodedPath}${params}"

  /** Base64(HMAC-SHA256(Base64-decoded key, UTF-8 string-to-sign)) */
  def sign(base64Key: String, stringToSign: String): String = Base64.encode(
    Sha256.hmac(Base64.decode(base64Key), stringToSign)
  )

  def authorization(account: String, base64Key: String, stringToSign: String): String =
    s"SharedKey ${account}:${sign(base64Key, stringToSign)}"

  /** SAS permissions for one request kind, in the order Azure requires (`racwd`) */
  def permissions(method: HttpMethod): String =
    method match
      case HttpMethod.GET | HttpMethod.HEAD =>
        "r"
      case HttpMethod.PUT =>
        "cw"
      case HttpMethod.DELETE =>
        "d"
      case other =>
        throw IllegalArgumentException(
          s"Azure SAS URLs cover GET, HEAD, PUT, or DELETE, not ${other}"
        )

  /** `/blob/<account>/<container>/<blob>`, not encoded */
  def sasResource(account: String, container: String, blob: String): String =
    s"/blob/${account}/${container}/${blob}"

  /** The service SAS string-to-sign for a blob (version 2020-12-06 and later) */
  def serviceSasStringToSign(
      permissions: String,
      start: String,
      expiry: String,
      resource: String,
      version: String = Version,
      signedResource: String = "b"
  ): String = Seq(
    permissions,
    start,
    expiry,
    resource,
    "", // signedIdentifier
    "", // signedIP
    "", // signedProtocol
    version,
    signedResource,
    "", // signedSnapshotTime
    "", // signedEncryptionScope
    "", // rscc
    "", // rscd
    "", // rsce
    "", // rscl
    ""  // rsct
  ).mkString("\n")

  /** The user delegation SAS string-to-sign (version 2020-12-06 up to 2025-07-05) */
  def userDelegationStringToSign(
      permissions: String,
      start: String,
      expiry: String,
      resource: String,
      key: UserDelegationKey,
      version: String = Version,
      signedResource: String = "b"
  ): String = Seq(
    permissions,
    start,
    expiry,
    resource,
    key.signedOid,
    key.signedTid,
    key.signedStart,
    key.signedExpiry,
    key.signedService,
    key.signedVersion,
    "", // signedAuthorizedUserObjectId
    "", // signedUnauthorizedUserObjectId
    "", // signedCorrelationId
    "", // signedIP
    "", // signedProtocol
    version,
    signedResource,
    "", // signedSnapshotTime
    "", // signedEncryptionScope
    "", // rscc
    "", // rscd
    "", // rsce
    "", // rscl
    ""  // rsct
  ).mkString("\n")

  /**
    * Percent-decodes a listing name that Azure marked `Encoded="true"` (names with characters XML
    * can't carry). `+` stays as is.
    */
  def percentDecode(s: String): String =
    if s.indexOf('%') < 0 then
      s
    else
      val out = java.io.ByteArrayOutputStream()
      var i   = 0
      while i < s.length do
        if s.charAt(i) == '%' && i + 2 < s.length && hex(s.charAt(i + 1)) >= 0 &&
          hex(s.charAt(i + 2)) >= 0
        then
          out.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)))
          i += 3
        else
          // Copy the run up to the next '%' (keeps surrogate pairs together)
          val next =
            s.indexOf('%', i + 1) match
              case -1 =>
                s.length
              case j =>
                j
          out.write(s.substring(i, next).getBytes("UTF-8"))
          i = next
      String(out.toByteArray, "UTF-8")

  private def hex(c: Char): Int =
    if c >= '0' && c <= '9' then
      c - '0'
    else if c >= 'a' && c <= 'f' then
      c - 'a' + 10
    else if c >= 'A' && c <= 'F' then
      c - 'A' + 10
    else
      -1

end AzureAuth
