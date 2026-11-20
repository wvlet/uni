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
  def environment: AwsCredentialsProvider = EnvironmentProvider

  /**
    * A profile in the shared credentials file (`AWS_SHARED_CREDENTIALS_FILE`, or
    * `~/.aws/credentials`) or the config file (`AWS_CONFIG_FILE`, or `~/.aws/config`). The profile
    * defaults to `AWS_PROFILE`, then `default`.
    */
  def profile(name: String = AwsProfiles.currentProfile): AwsCredentialsProvider = Cached(
    ProfileProvider(name)
  )

  /** The ECS / EKS Pod Identity container credentials endpoint */
  def container: AwsCredentialsProvider = Cached(ContainerProvider)

  /** EC2 instance profile credentials through IMDSv2 */
  def instanceMetadata: AwsCredentialsProvider = Cached(InstanceMetadataProvider)

  /**
    * Tries each provider in order and uses the first that has credentials. Fails when none has.
    */
  def chain(providers: AwsCredentialsProvider*): AwsCredentialsProvider = Chain(providers)

  /**
    * The standard AWS order: environment variables, shared profile files, the container endpoint,
    * then EC2 instance metadata. Browsers have no environment or files, so pass keys or use
    * presigned URLs there.
    */
  def default: AwsCredentialsProvider = chain(environment, profile(), container, instanceMetadata)

  private object Anonymous extends AwsCredentialsProvider:
    def name: String                        = "anonymous"
    def credentials: Option[AwsCredentials] = None

  private final class StaticProvider(c: AwsCredentials) extends AwsCredentialsProvider:
    def name: String                        = "static"
    def credentials: Option[AwsCredentials] = Some(c)

  private object EnvironmentProvider extends AwsCredentialsProvider:
    def name: String                        = "environment"
    def credentials: Option[AwsCredentials] =
      for
        id     <- StorageEnv.get("AWS_ACCESS_KEY_ID")
        secret <- StorageEnv.get("AWS_SECRET_ACCESS_KEY")
      yield AwsCredentials(id, secret, StorageEnv.get("AWS_SESSION_TOKEN"))

  private final class ProfileProvider(profile: String) extends AwsCredentialsProvider:
    def name: String                        = s"profile ${profile}"
    def credentials: Option[AwsCredentials] = AwsProfiles.credentials(profile)

  private final class Chain(providers: Seq[AwsCredentialsProvider])
      extends AwsCredentialsProvider
      with LogSupport:
    def name: String = providers.map(_.name).mkString("chain(", ", ", ")")

    // The provider that answered last time, so a laptop doesn't probe IMDS on every request
    @volatile
    private var resolved: Option[AwsCredentialsProvider] = None

    private def tryProvider(p: AwsCredentialsProvider): Option[AwsCredentials] =
      try
        p.credentials
      catch
        case e: Exception =>
          debug(s"AWS credentials provider ${p.name} failed: ${e.getMessage}")
          None

    // A provider that answered before may fail later (an expired role whose refresh times out),
    // so its failure falls back to scanning the whole chain again
    def credentials: Option[AwsCredentials] = resolved
      .flatMap(tryProvider)
      .orElse {
        resolved = None
        val found = providers
          .iterator
          .map(p => p -> tryProvider(p))
          .collectFirst { case (p, Some(c)) =>
            resolved = Some(p)
            c
          }
        if found.isEmpty then
          throw StorageException(
            StorageErrorCode.NoCredentials,
            s"No AWS credentials found. Checked: ${providers.map(_.name).mkString(", ")}"
          )
        found
      }

  end Chain

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
  private[storage] def parseCredentialsJson(json: String): Option[AwsCredentials] =
    JSON.parse(json) match
      case o: JSON.JSONObject =>
        def str(key: String): Option[String] = o
          .get(key)
          .collect { case JSON.JSONString(s) =>
            s
          }
          .filter(_.nonEmpty)
        for
          id     <- str("AccessKeyId")
          secret <- str("SecretAccessKey")
        yield AwsCredentials(id, secret, str("Token"), str("Expiration").flatMap(AwsTime.parseIso))
      case _ =>
        None

  private lazy val metadataHttp: HttpClientConfig =
    Http.client.withConnectTimeoutMillis(1000).withReadTimeoutMillis(2000).noRetry.noFollowRedirects

  private object ContainerProvider extends AwsCredentialsProvider:
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
        val uri = StorageEnv
          .get("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
          .map(rel => s"http://169.254.170.2${rel}")
          .orElse(StorageEnv.get("AWS_CONTAINER_CREDENTIALS_FULL_URI").map(checkFullUri))
        uri.flatMap { u =>
          val token = StorageEnv
            .get("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE")
            .map(f => FileSystem.readString(IOPath.parse(f)).trim)
            .orElse(StorageEnv.get("AWS_CONTAINER_AUTHORIZATION_TOKEN"))
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

  private object InstanceMetadataProvider extends AwsCredentialsProvider:
    def name: String = "instance-metadata"

    def credentials: Option[AwsCredentials] =
      if StorageEnv.isBrowser ||
        StorageEnv.get("AWS_EC2_METADATA_DISABLED").exists(_.equalsIgnoreCase("true"))
      then
        None
      else
        val base = StorageEnv
          .get("AWS_EC2_METADATA_SERVICE_ENDPOINT")
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

/** Reads `~/.aws/credentials` and `~/.aws/config` */
private[storage] object AwsProfiles:
  def currentProfile: String = StorageEnv.get("AWS_PROFILE").getOrElse("default")

  private def home: Option[IOPath] = StorageEnv
    .get("HOME")
    .orElse(StorageEnv.get("USERPROFILE"))
    .map(IOPath.parse)
    .orElse(
      if StorageEnv.isBrowser then
        None
      else
        Some(FileSystem.homeDirectory)
    )

  private def credentialsFile: Option[IOPath] = StorageEnv
    .get("AWS_SHARED_CREDENTIALS_FILE")
    .map(IOPath.parse)
    .orElse(home.map(_.resolve(".aws/credentials")))

  private def configFile: Option[IOPath] = StorageEnv
    .get("AWS_CONFIG_FILE")
    .map(IOPath.parse)
    .orElse(home.map(_.resolve(".aws/config")))

  private def read(path: Option[IOPath]): Map[String, Map[String, String]] =
    if StorageEnv.isBrowser then
      Map.empty
    else
      path
        .filter(FileSystem.isFile)
        .map(p => parseIni(FileSystem.readString(p)))
        .getOrElse(Map.empty)

  /** Profiles from the config file, whose sections read `[profile name]` (`[default]` as is) */
  private def configProfile(profile: String): Map[String, String] =
    val sections = read(configFile)
    sections.getOrElse(s"profile ${profile}", sections.getOrElse(profile, Map.empty))

  def credentials(profile: String): Option[AwsCredentials] =
    def from(section: Map[String, String]): Option[AwsCredentials] =
      for
        id     <- section.get("aws_access_key_id")
        secret <- section.get("aws_secret_access_key")
      yield AwsCredentials(id, secret, section.get("aws_session_token"))
    read(credentialsFile).get(profile).flatMap(from).orElse(from(configProfile(profile)))

  def region(profile: String = currentProfile): Option[String] = configProfile(profile)
    .get("region")
    .filter(_.nonEmpty)

  /** A minimal INI reader: `[section]` headers and `key = value` lines */
  def parseIni(text: String): Map[String, Map[String, String]] =
    val sections = scala.collection.mutable.LinkedHashMap.empty[String, Map[String, String]]
    var current  = ""
    text
      .linesIterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#") && !l.startsWith(";"))
      .foreach { line =>
        if line.startsWith("[") && line.endsWith("]") then
          current = line.substring(1, line.length - 1).trim.replaceAll("\\s+", " ")
          sections.getOrElseUpdate(current, Map.empty)
        else
          val eq = line.indexOf('=')
          if eq > 0 then
            val key   = line.substring(0, eq).trim.toLowerCase
            val value = line.substring(eq + 1).trim
            sections(current) = sections.getOrElse(current, Map.empty) + (key -> value)
      }
    sections.toMap

end AwsProfiles
