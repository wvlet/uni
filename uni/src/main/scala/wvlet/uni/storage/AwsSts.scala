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

import wvlet.uni.http.Http
import wvlet.uni.http.HttpClientConfig
import wvlet.uni.http.HttpContent
import wvlet.uni.http.HttpException
import wvlet.uni.http.HttpMaxRetryException
import wvlet.uni.http.Request
import wvlet.uni.http.Response
import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.util.URLEncoder

/**
  * The STS calls that credential sources need: `AssumeRole` (signed with the source credentials)
  * and `AssumeRoleWithWebIdentity` (unsigned; the token goes in the POST body, never the URL).
  *
  * The endpoint is regional (`sts.<region>.amazonaws.com`) when a region is known, else the global
  * one. Only `AWS_ENDPOINT_URL_STS` overrides it: the generic `AWS_ENDPOINT_URL` often points at an
  * S3-compatible store (R2, MinIO), which must not receive web identity tokens.
  */
private[storage] class AwsSts(env: AwsEnv, region: Option[String]):
  private val endpoint: String = env("AWS_ENDPOINT_URL_STS")
    .map(_.stripSuffix("/"))
    .getOrElse(
      region.map(r => s"https://sts.${r}.amazonaws.com").getOrElse("https://sts.amazonaws.com")
    )

  private val signingRegion: String = region.getOrElse("us-east-1")

  def assumeRole(
      source: AwsCredentials,
      roleArn: String,
      sessionName: String,
      externalId: Option[String],
      durationSeconds: Option[Int]
  ): AwsCredentials = call(
    "AssumeRole",
    Seq("RoleArn" -> roleArn, "RoleSessionName" -> sessionName) ++
      externalId.map("ExternalId" -> _) ++ durationSeconds.map("DurationSeconds" -> _.toString),
    Some(source)
  )

  def assumeRoleWithWebIdentity(
      roleArn: String,
      sessionName: String,
      token: String,
      durationSeconds: Option[Int]
  ): AwsCredentials = call(
    "AssumeRoleWithWebIdentity",
    Seq("RoleArn" -> roleArn, "RoleSessionName" -> sessionName, "WebIdentityToken" -> token) ++
      durationSeconds.map("DurationSeconds" -> _.toString),
    None
  )

  private def call(
      action: String,
      params: Seq[(String, String)],
      signWith: Option[AwsCredentials]
  ): AwsCredentials =
    val form = (Seq("Action" -> action, "Version" -> "2011-06-15") ++ params)
      .map((k, v) => s"${URLEncoder.encode(k)}=${URLEncoder.encode(v)}")
      .mkString("&")
    val body              = form.getBytes("UTF-8")
    val contentType       = "application/x-www-form-urlencoded; charset=utf-8"
    val (authority, path) = AwsSts.splitUrl(endpoint)
    val scheme            = endpoint.take(endpoint.indexOf("://"))
    var req               = Request
      .post(s"${scheme}://${authority}${path}")
      .setHeader("content-type", contentType)
      .withContent(HttpContent.bytes(body))
    signWith.foreach { c =>
      SigV4
        .signHeaders(
          SigV4Request(
            "POST",
            authority,
            path,
            Nil,
            Seq("content-type" -> contentType),
            Sha256.hex(body)
          ),
          c,
          signingRegion,
          service = "sts"
        )
        .foreach((k, v) => req = req.setHeader(k, v))
    }
    val resp = AwsSts.send(req)
    val xml  =
      try
        Some(S3Xml.parse(resp.contentAsString.getOrElse("")))
      catch
        case e: Exception =>
          None
    if !resp.isSuccessful then
      val err  = xml.flatMap(x => x.child("Error").orElse(Some(x)))
      val code = err.flatMap(_.childText("Code")).getOrElse(s"HTTP ${resp.statusCode}")
      val msg  = err.flatMap(_.childText("Message")).map(m => s": ${AwsSts.short(m)}").getOrElse("")
      throw StorageException(
        StorageErrorCode.NoCredentials,
        s"STS ${action} for ${params
            .collectFirst { case ("RoleArn", r) =>
              r
            }
            .getOrElse("")} failed: ${code}${msg}",
        Some(resp.statusCode)
      )
    val creds =
      for
        root   <- xml
        result <- root.child(s"${action}Result")
        c      <- result.child("Credentials")
        id     <- c.childText("AccessKeyId").filter(_.nonEmpty)
        secret <- c.childText("SecretAccessKey").filter(_.nonEmpty)
      yield AwsCredentials(
        id,
        secret,
        c.childText("SessionToken").filter(_.nonEmpty),
        c.childText("Expiration").flatMap(AwsTime.parseIso)
      )
    creds.getOrElse(
      throw StorageException(
        StorageErrorCode.NoCredentials,
        s"STS ${action} returned no credentials"
      )
    )

  end call

end AwsSts

private[storage] object AwsSts:
  def defaultSessionName: String = s"uni-${System.currentTimeMillis()}"

  def readToken(file: String): String =
    val path = IOPath.parse(file)
    if !FileSystem.isFile(path) then
      throw StorageException(
        StorageErrorCode.NoCredentials,
        s"Web identity token file not found: ${file}"
      )
    FileSystem.readString(path).trim

  /** Splits `https://host:port/path` into the authority and the path (`/` when empty) */
  def splitUrl(url: String): (String, String) =
    val rest  = url.drop(url.indexOf("://") + 3)
    val slash = rest.indexOf('/')
    if slash < 0 then
      (rest, "/")
    else
      (rest.substring(0, slash), rest.substring(slash))

  /** Keeps error messages short: one line, at most 300 characters */
  def short(msg: String): String =
    val line = msg.linesIterator.nextOption().getOrElse("").trim
    if line.length > 300 then
      s"${line.take(300)}..."
    else
      line

  private lazy val http: HttpClientConfig =
    Http.client.withConnectTimeoutMillis(5000).withReadTimeoutMillis(10000).noFollowRedirects

  /** Sends a request and returns error responses instead of throwing */
  def send(req: Request): Response =
    val client = http.newSyncClient
    try
      client.send(req)
    catch
      case e: HttpMaxRetryException =>
        e.response
      case e: HttpException if e.response.isDefined =>
        e.response.get
    finally
      client.close()

end AwsSts
