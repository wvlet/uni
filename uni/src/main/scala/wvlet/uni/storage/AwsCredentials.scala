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
import wvlet.uni.http.Request
import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.log.LogSupport

/**
  * AWS access keys. `toString` hides the secret and the session token, so credentials can't leak
  * into logs.
  *
  * @param expirationMillis
  *   when temporary credentials expire (epoch millis), if known
  */
case class AwsCredentials(
    accessKeyId: String,
    secretAccessKey: String,
    sessionToken: Option[String] = None,
    expirationMillis: Option[Long] = None
):
  def withSessionToken(token: String): AwsCredentials    = copy(sessionToken = Some(token))
  def noSessionToken: AwsCredentials                     = copy(sessionToken = None)
  def withExpirationMillis(millis: Long): AwsCredentials = copy(expirationMillis = Some(millis))
  def noExpiration: AwsCredentials                       = copy(expirationMillis = None)

  override def toString: String =
    val token =
      if sessionToken.isDefined then
        ", sessionToken=****"
      else
        ""
    s"AwsCredentials(accessKeyId=${accessKeyId}, secretAccessKey=****${token})"

/**
  * Supplies credentials for signing. `credentials` returns `None` when the provider has none to
  * give: the default chain then tries the next provider, and an anonymous store sends unsigned
  * requests.
  */
trait AwsCredentialsProvider:
  def name: String
  def credentials: Option[AwsCredentials]

object AwsCredentialsProvider:
  /** Fixed access keys */
  def static(credentials: AwsCredentials): AwsCredentialsProvider = StaticProvider(credentials)

  def static(accessKeyId: String, secretAccessKey: String): AwsCredentialsProvider = static(
    AwsCredentials(accessKeyId, secretAccessKey)
  )

  /** No credentials: requests are sent unsigned, for public buckets and presigned use only */
  def anonymous: AwsCredentialsProvider = Anonymous

  /** `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN` */
  def environment: AwsCredentialsProvider = EnvironmentProvider(AwsEnv.system)

  /**
    * Web identity from the environment, the EKS IAM Roles for Service Accounts (IRSA) path: reads
    * the token in `AWS_WEB_IDENTITY_TOKEN_FILE` and exchanges it for `AWS_ROLE_ARN` credentials
    * through STS `AssumeRoleWithWebIdentity`. The session name is `AWS_ROLE_SESSION_NAME`, or
    * generated. The token file is read again on every refresh, since EKS rotates it.
    */
  def webIdentity: AwsCredentialsProvider = Cached(WebIdentityEnvProvider(AwsEnv.system))

  /**
    * A profile in the shared credentials file (`AWS_SHARED_CREDENTIALS_FILE`, or
    * `~/.aws/credentials`) or the config file (`AWS_CONFIG_FILE`, or `~/.aws/config`). The profile
    * defaults to `AWS_PROFILE`, then `default`.
    *
    * The first of these that the profile sets is used, in the AWS Java SDK v2 order:
    *   - `role_arn` with `web_identity_token_file`: STS `AssumeRoleWithWebIdentity`
    *   - IAM Identity Center (SSO): `sso_session`, or the legacy `sso_start_url`, with
    *     `sso_account_id` and `sso_role_name`. Reads the token that `aws sso login` cached under
    *     `~/.aws/sso/cache`, refreshing an `sso_session` token when it expires
    *   - `role_arn` with `source_profile` or `credential_source`: STS `AssumeRole`
    *   - `credential_process`: runs the command (JVM, Scala Native, and Node.js)
    *   - `aws_access_key_id` and `aws_secret_access_key`
    */
  def profile(name: String = AwsProfiles.currentProfile): AwsCredentialsProvider = Cached(
    ProfileProvider(name, AwsEnv.system)
  )

  /** The ECS / EKS Pod Identity container credentials endpoint */
  def container: AwsCredentialsProvider = Cached(ContainerProvider(AwsEnv.system))

  /** EC2 instance profile credentials through IMDSv2 */
  def instanceMetadata: AwsCredentialsProvider = Cached(InstanceMetadataProvider(AwsEnv.system))

  /**
    * Tries each provider in order and uses the first that has credentials. Fails when none has,
    * listing why each configured source failed.
    */
  def chain(providers: AwsCredentialsProvider*): AwsCredentialsProvider = Chain(providers)

  /**
    * The AWS Java SDK v2 order: environment variables, web identity from the environment (EKS
    * IRSA), the shared profile (static keys, roles, SSO, `credential_process`), the container
    * endpoint, then EC2 instance metadata. Browsers have no environment or files, so pass keys or
    * use presigned URLs there.
    */
  def default: AwsCredentialsProvider = defaultChain(AwsEnv.system)

  /** The default chain reading the given environment, so tests can point it at temp files */
  private[storage] def defaultChain(env: AwsEnv): AwsCredentialsProvider = chain(
    EnvironmentProvider(env),
    Cached(WebIdentityEnvProvider(env)),
    Cached(ProfileProvider(AwsProfiles(env).currentProfile, env)),
    Cached(ContainerProvider(env)),
    Cached(InstanceMetadataProvider(env))
  )

  /** A profile provider reading the given environment, for tests */
  private[storage] def profileOf(name: String, env: AwsEnv): AwsCredentialsProvider = Cached(
    ProfileProvider(name, env)
  )

  private object Anonymous extends AwsCredentialsProvider:
    def name: String                        = "anonymous"
    def credentials: Option[AwsCredentials] = None

  private final class StaticProvider(c: AwsCredentials) extends AwsCredentialsProvider:
    def name: String                        = "static"
    def credentials: Option[AwsCredentials] = Some(c)

  private[storage] final class EnvironmentProvider(env: AwsEnv) extends AwsCredentialsProvider:
    def name: String                        = "environment"
    def credentials: Option[AwsCredentials] =
      for
        id     <- env("AWS_ACCESS_KEY_ID")
        secret <- env("AWS_SECRET_ACCESS_KEY")
      yield AwsCredentials(id, secret, env("AWS_SESSION_TOKEN"))

  private final class WebIdentityEnvProvider(env: AwsEnv) extends AwsCredentialsProvider:
    def name: String                        = "web-identity"
    def credentials: Option[AwsCredentials] =
      if StorageEnv.isBrowser then
        None
      else
        for
          tokenFile <- env("AWS_WEB_IDENTITY_TOKEN_FILE")
          roleArn   <- env("AWS_ROLE_ARN")
        yield
          val profiles = AwsProfiles(env)
          AwsSts(env, profiles.stsRegion(profiles.currentProfile)).assumeRoleWithWebIdentity(
            roleArn = roleArn,
            sessionName = env("AWS_ROLE_SESSION_NAME").getOrElse(AwsSts.defaultSessionName),
            token = AwsSts.readToken(tokenFile),
            durationSeconds = None
          )

  private final class ProfileProvider(profile: String, env: AwsEnv) extends AwsCredentialsProvider:
    def name: String                        = s"profile ${profile}"
    def credentials: Option[AwsCredentials] = AwsProfileCredentials(env).resolve(profile)

  private final class Chain(providers: Seq[AwsCredentialsProvider])
      extends AwsCredentialsProvider
      with LogSupport:
    def name: String = providers.map(_.name).mkString("chain(", ", ", ")")

    // The provider that answered last time, so a laptop doesn't probe IMDS on every request
    @volatile
    private var resolved: Option[AwsCredentialsProvider] = None

    private def tryProvider(p: AwsCredentialsProvider): Either[String, Option[AwsCredentials]] =
      try
        Right(p.credentials)
      catch
        case e: Exception =>
          val reason = AwsCredentialsProvider.failureReason(e)
          debug(s"AWS credentials provider ${p.name} failed: ${reason}")
          Left(reason)

    // A provider that answered before may fail later (an expired role whose refresh times out),
    // so its failure falls back to scanning the whole chain again
    def credentials: Option[AwsCredentials] = resolved
      .flatMap(p => tryProvider(p).toOption.flatten)
      .orElse {
        resolved = None
        val failures = Seq.newBuilder[String]
        val found    = providers
          .iterator
          .map { p =>
            tryProvider(p) match
              case Right(c) =>
                p -> c
              case Left(reason) =>
                failures += s"${p.name}: ${reason}"
                p -> None
          }
          .collectFirst { case (p, Some(c)) =>
            resolved = Some(p)
            c
          }
        if found.isEmpty then
          val failed = failures.result()
          val detail =
            if failed.isEmpty then
              ""
            else
              failed.mkString("\n  ", "\n  ", "")
          throw StorageException(
            StorageErrorCode.NoCredentials,
            s"No AWS credentials found. Checked: ${providers.map(_.name).mkString(", ")}${detail}"
          )
        found
      }

  end Chain

  /**
    * Why a provider failed, for the chain's error. The credential providers raise
    * `StorageException`s whose messages they write without tokens or secrets; for anything else (a
    * connection error), only the first line of its message is kept, shortened.
    */
  private[storage] def failureReason(e: Throwable): String =
    e match
      case s: StorageException =>
        s.getMessage
      case other =>
        val msg = Option(other.getMessage)
          .map(_.linesIterator.nextOption().getOrElse(""))
          .getOrElse("")
        val short =
          if msg.length > 200 then
            s"${msg.take(200)}..."
          else
            msg
        s"${other.getClass.getSimpleName}${
            if short.isEmpty then
              ""
            else
              s": ${short}"
          }"

  /**
    * Caches credentials until 5 minutes before they expire (forever when they don't), so files and
    * metadata endpoints aren't read on every request
    */
  private final class Cached(underlying: AwsCredentialsProvider) extends AwsCredentialsProvider:
    def name: String = underlying.name

    private var cached: Option[AwsCredentials] = None

    def credentials: Option[AwsCredentials] = synchronized {
      val now   = System.currentTimeMillis()
      val fresh = cached.filter(_.expirationMillis.forall(_ - 5 * 60 * 1000L > now))
      fresh.orElse {
        cached = underlying.credentials
        cached
      }
    }

  /** Parses the JSON that the container and IMDS endpoints return */
  private[storage] def parseCredentialsJson(json: String): Option[AwsCredentials] = AwsJson
    .parseObject(json)
    .flatMap { o =>
      for
        id     <- AwsJson.str(o, "AccessKeyId")
        secret <- AwsJson.str(o, "SecretAccessKey")
      yield AwsCredentials(
        id,
        secret,
        AwsJson.str(o, "Token"),
        AwsJson.str(o, "Expiration").flatMap(AwsTime.parseIso)
      )
    }

  private lazy val metadataHttp: HttpClientConfig =
    Http.client.withConnectTimeoutMillis(1000).withReadTimeoutMillis(2000).noRetry.noFollowRedirects

  private[storage] final class ContainerProvider(env: AwsEnv) extends AwsCredentialsProvider:
    def name: String = "container"

    // Hosts the AWS SDKs allow for AWS_CONTAINER_CREDENTIALS_FULL_URI over plain HTTP
    private val allowedHttpHosts = Set(
      "localhost",
      "127.0.0.1",
      "169.254.170.2",
      "169.254.170.23",
      "[fd00:ec2::23]",
      "[::1]"
    )

    def credentials: Option[AwsCredentials] =
      if StorageEnv.isBrowser then
        None
      else
        val uri = env("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
          .map(rel => s"http://169.254.170.2${rel}")
          .orElse(env("AWS_CONTAINER_CREDENTIALS_FULL_URI").map(checkFullUri))
        uri.flatMap { u =>
          val token = env("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE")
            .map(f => FileSystem.readString(IOPath.parse(f)).trim)
            .orElse(env("AWS_CONTAINER_AUTHORIZATION_TOKEN"))
          val req    = token.foldLeft(Request.get(u))((r, t) => r.withAuthorization(t))
          val client = metadataHttp.newSyncClient
          val resp   =
            try client.send(req)
            finally client.close()
          if !resp.isSuccessful then
            throw StorageException(
              StorageErrorCode.NoCredentials,
              s"The container credentials endpoint answered HTTP ${resp.statusCode}"
            )
          parseCredentialsJson(resp.contentAsString.getOrElse(""))
        }

    private def checkFullUri(uri: String): String =
      val lower = uri.toLowerCase
      if lower.startsWith("https://") then
        uri
      else if lower.startsWith("http://") then
        val host = lower.stripPrefix("http://").takeWhile(c => c != '/').replaceAll(":\\d+$", "")
        if allowedHttpHosts.contains(host) then
          uri
        else
          throw StorageException(
            StorageErrorCode.NoCredentials,
            s"AWS_CONTAINER_CREDENTIALS_FULL_URI must use HTTPS or a loopback/ECS host: ${host}"
          )
      else
        throw StorageException(
          StorageErrorCode.NoCredentials,
          "AWS_CONTAINER_CREDENTIALS_FULL_URI must be an http(s) URL"
        )

  end ContainerProvider

  private[storage] final class InstanceMetadataProvider(env: AwsEnv) extends AwsCredentialsProvider:
    def name: String = "instance-metadata"

    def credentials: Option[AwsCredentials] =
      if StorageEnv.isBrowser || env("AWS_EC2_METADATA_DISABLED").exists(_.equalsIgnoreCase("true"))
      then
        None
      else
        val base = env("AWS_EC2_METADATA_SERVICE_ENDPOINT")
          .getOrElse("http://169.254.169.254")
          .stripSuffix("/")
        val client = metadataHttp.newSyncClient
        try
          val tokenResp = client.send(
            Request
              .put(s"${base}/latest/api/token")
              .setHeader("X-aws-ec2-metadata-token-ttl-seconds", "21600")
          )
          if !tokenResp.isSuccessful then
            None
          else
            val token                             = tokenResp.contentAsString.getOrElse("").trim
            def get(path: String): Option[String] =
              val r = client.send(
                Request.get(s"${base}${path}").setHeader("X-aws-ec2-metadata-token", token)
              )
              if r.isSuccessful then
                r.contentAsString
              else
                None
            for
              roles <- get("/latest/meta-data/iam/security-credentials/")
              role  <- roles.linesIterator.map(_.trim).find(_.nonEmpty)
              json  <- get(s"/latest/meta-data/iam/security-credentials/${role}")
              c     <- parseCredentialsJson(json)
            yield c
        finally
          client.close()

  end InstanceMetadataProvider

end AwsCredentialsProvider
