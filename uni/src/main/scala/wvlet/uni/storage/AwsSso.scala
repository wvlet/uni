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

import wvlet.uni.http.HttpContent
import wvlet.uni.http.Request
import wvlet.uni.http.Sha1
import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.io.PermSet
import wvlet.uni.io.WriteMode
import wvlet.uni.json.JSON
import wvlet.uni.log.LogSupport
import wvlet.uni.util.URLEncoder

/**
  * IAM Identity Center (SSO) profiles. `aws sso login` caches an access token under
  * `~/.aws/sso/cache/<sha1>.json`, keyed by the `sso_session` name (or, for legacy profiles, the
  * `sso_start_url`); this exchanges it for role credentials through the SSO portal's
  * `GetRoleCredentials`.
  *
  * An `sso_session` token that expires (or has under 5 minutes left) is refreshed through SSO-OIDC
  * `CreateToken` with the cached refresh token, and the new token is written back to the cache
  * file, as the AWS SDKs do: refresh tokens rotate, so a refresh kept only in memory would leave
  * the CLI's copy stale. Legacy profiles have no refresh token and need `aws sso login` again.
  *
  * The portal and OIDC hosts use `sso_region`. Only `AWS_ENDPOINT_URL_SSO` and
  * `AWS_ENDPOINT_URL_SSO_OIDC` override them, never the generic `AWS_ENDPOINT_URL`.
  */
private[storage] class AwsSso(
    env: AwsEnv,
    profiles: AwsProfiles,
    now: () => Long = () => System.currentTimeMillis()
) extends LogSupport:
  import AwsSso.*

  private val refreshWindowMillis = 5 * 60 * 1000L

  private def fail(msg: String): Nothing =
    throw StorageException(StorageErrorCode.NoCredentials, msg)

  def credentials(profile: String, p: Map[String, String]): AwsCredentials =
    def required(key: String): String = p
      .get(key)
      .filter(_.nonEmpty)
      .getOrElse(fail(s"SSO profile ${profile} has no ${key}"))
    val accountId                          = required("sso_account_id")
    val roleName                           = required("sso_role_name")
    val login                              = s"run `aws sso login --profile ${profile}`"
    val (cacheKey, ssoRegion, refreshable) =
      p.get("sso_session").filter(_.nonEmpty) match
        case Some(session) =>
          val s = profiles
            .ssoSession(session)
            .getOrElse(
              fail(s"Profile ${profile} names sso_session ${session}, which is not defined")
            )
          val region = s
            .get("sso_region")
            .filter(_.nonEmpty)
            .getOrElse(fail(s"sso-session ${session} has no sso_region"))
          (session, region, true)
        case None =>
          (required("sso_start_url"), required("sso_region"), false)

    val token = accessToken(cacheKey, ssoRegion, refreshable, login)

    val portal = env("AWS_ENDPOINT_URL_SSO")
      .map(_.stripSuffix("/"))
      .getOrElse(s"https://portal.sso.${ssoRegion}.amazonaws.com")
    val resp = AwsSts.send(
      Request
        .get(
          s"${portal}/federation/credentials?role_name=${URLEncoder.encode(
              roleName
            )}&account_id=${URLEncoder.encode(accountId)}"
        )
        .setHeader("x-amz-sso_bearer_token", token)
    )
    if resp.statusCode == 401 || resp.statusCode == 403 then
      fail(s"The SSO session of profile ${profile} is expired or not authorized; ${login}")
    if !resp.isSuccessful then
      fail(s"SSO GetRoleCredentials for profile ${profile} failed: HTTP ${resp.statusCode}")
    parseRoleCredentials(resp.contentAsString.getOrElse("")).getOrElse(
      fail(s"SSO GetRoleCredentials for profile ${profile} returned no credentials")
    )

  end credentials

  private def accessToken(
      cacheKey: String,
      ssoRegion: String,
      refreshable: Boolean,
      login: String
  ): String =
    val dir = profiles
      .home
      .map(_.resolve(".aws/sso/cache"))
      .getOrElse(fail(s"No home directory; ${login}"))
    val file = dir.resolve(s"${cacheFileName(cacheKey)}")
    if !FileSystem.isFile(file) then
      fail(s"No cached SSO token; ${login}")
    val cache = AwsJson
      .parseObject(FileSystem.readString(file))
      .getOrElse(fail(s"The cached SSO token is not valid JSON; ${login}"))
    val token = str(cache, "accessToken").getOrElse(
      fail(s"The cached SSO token is empty; ${login}")
    )
    val expiresAt = str(cache, "expiresAt").flatMap(parseExpiry)
    val current   = now()
    val fresh     = expiresAt.exists(_ - refreshWindowMillis > current)
    if fresh then
      token
    else
      val refreshed =
        if refreshable then
          try
            refresh(cache, ssoRegion, file)
          catch
            case e: Exception =>
              debug(s"SSO token refresh failed: ${e.getClass.getSimpleName}")
              None
        else
          None
      refreshed
        .orElse {
          // Refresh failed or isn't possible: an unexpired token is still usable
          if expiresAt.exists(_ > current) then
            Some(token)
          else
            None
        }
        .getOrElse(fail(s"The SSO token has expired; ${login}"))

  end accessToken

  /** Refreshes the token with SSO-OIDC CreateToken and writes it back to the cache */
  private def refresh(cache: JSON.JSONObject, ssoRegion: String, file: IOPath): Option[String] =
    val current = now()
    val params  =
      for
        clientId     <- str(cache, "clientId")
        clientSecret <- str(cache, "clientSecret")
        refreshToken <- str(cache, "refreshToken")
        if str(cache, "registrationExpiresAt").flatMap(parseExpiry).forall(_ > current)
      yield (clientId, clientSecret, refreshToken)
    params.flatMap { (clientId, clientSecret, refreshToken) =>
      val oidc = env("AWS_ENDPOINT_URL_SSO_OIDC")
        .map(_.stripSuffix("/"))
        .getOrElse(s"https://oidc.${ssoRegion}.amazonaws.com")
      val body =
        JSON
          .JSONObject(
            Seq(
              "grantType"    -> JSON.JSONString("refresh_token"),
              "clientId"     -> JSON.JSONString(clientId),
              "clientSecret" -> JSON.JSONString(clientSecret),
              "refreshToken" -> JSON.JSONString(refreshToken)
            )
          )
          .toJSON
      val resp = AwsSts.send(Request.post(s"${oidc}/token").withJsonContent(body), retry = false)
      if !resp.isSuccessful then
        // Only the status: the body may echo request fields
        debug(s"SSO token refresh failed: HTTP ${resp.statusCode}")
        None
      else
        AwsJson
          .parseObject(resp.contentAsString.getOrElse(""))
          .flatMap { o =>
            for
              access <- str(o, "accessToken")
              secs   <- AwsJson.long(o, "expiresIn")
            yield
              val updates =
                Seq(
                  "accessToken" -> JSON.JSONString(access),
                  "expiresAt"   -> JSON.JSONString(AwsTime.iso(current + secs * 1000L))
                ) ++ str(o, "refreshToken").map("refreshToken" -> JSON.JSONString(_))
              writeCache(file, merge(cache, updates))
              access
          }
      end if
    }

  end refresh

  /**
    * Replaces the cache file atomically. The temp file is made owner-only before the token is
    * written, since the cache holds the refresh token and client secret. A failed write is logged
    * (without contents) and the refreshed token is still used.
    */
  private def writeCache(file: IOPath, cache: JSON.JSONObject): Unit =
    // A random suffix, so two refreshes at once never share (or delete) a temp file. Not UUID:
    // Scala Native has no SecureRandom
    val tmp = file
      .parent
      .getOrElse(IOPath.parse("."))
      .resolve(
        s".${file.fileName}.${now()}-${java
            .lang
            .Long
            .toHexString(scala.util.Random.nextLong())}.tmp"
      )
    var created = false
    try
      FileSystem.writeString(tmp, "", WriteMode.CreateNew)
      created = true
      try
        FileSystem.setPermissions(tmp, PermSet(OwnerOnly))
      catch
        case _: UnsupportedOperationException =>
          () // No POSIX permissions (Windows): the user profile directory's ACLs apply
      FileSystem.writeString(tmp, cache.toJSON, WriteMode.Create)
      FileSystem.move(tmp, file, overwrite = true)
    catch
      case e: Exception =>
        warn(s"Could not update the SSO token cache ${file.path}: ${e.getClass.getSimpleName}")
        if created then
          FileSystem.deleteIfExists(tmp)

end AwsSso

private[storage] object AwsSso:
  /** `rw-------` */
  val OwnerOnly: Int = 0x180

  /** The cache file name: hex SHA-1 of the session name or legacy start URL */
  def cacheFileName(key: String): String =
    s"${Sha256.toHex(Sha1.digest(key.getBytes("UTF-8")))}.json"

  /** Cache timestamps are ISO-8601; older CLI versions wrote a `UTC` suffix instead of `Z` */
  def parseExpiry(s: String): Option[Long] =
    val t = s.trim
    AwsTime.parseIso(
      if t.endsWith("UTC") then
        s"${t.stripSuffix("UTC")}Z"
      else
        t
    )

  private def str(o: JSON.JSONObject, key: String): Option[String] = AwsJson.str(o, key)

  private def merge(o: JSON.JSONObject, updates: Seq[(String, JSON.JSONValue)]): JSON.JSONObject =
    val keys = updates.map(_._1).toSet
    JSON.JSONObject(o.v.filterNot((k, _) => keys.contains(k)) ++ updates)

  /** Parses `{"roleCredentials": {...}}`, whose `expiration` is epoch millis */
  def parseRoleCredentials(json: String): Option[AwsCredentials] = AwsJson
    .parseObject(json)
    .flatMap(_.get("roleCredentials"))
    .collect { case c: JSON.JSONObject =>
      c
    }
    .flatMap { c =>
      for
        id     <- str(c, "accessKeyId")
        secret <- str(c, "secretAccessKey")
      yield AwsCredentials(id, secret, str(c, "sessionToken"), AwsJson.long(c, "expiration"))
    }

end AwsSso
