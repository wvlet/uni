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
package wvlet.uni.cloud

import wvlet.uni.util.Sha256

import wvlet.uni.http.Http
import wvlet.uni.http.HttpClientConfig
import wvlet.uni.http.HttpContent
import wvlet.uni.http.ContentType
import wvlet.uni.http.Request
import wvlet.uni.http.Response
import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.util.URLEncoder

import java.util.Base64

/**
  * A Google OAuth 2.0 access token. `toString` hides the token, so it can't leak into logs.
  *
  * @param expiresAtMillis
  *   when the token expires (epoch millis), if known
  */
case class GoogleAccessToken(token: String, expiresAtMillis: Option[Long] = None):
  def withExpiresAtMillis(millis: Long): GoogleAccessToken = copy(expiresAtMillis = Some(millis))
  def noExpiration: GoogleAccessToken                      = copy(expiresAtMillis = None)

  /** True while the token is valid for at least `marginMillis` more */
  def isFresh(
      nowMillis: Long,
      marginMillis: Long = GoogleAccessToken.RefreshMarginMillis
  ): Boolean = expiresAtMillis.forall(_ - marginMillis > nowMillis)

  override def toString: String = "GoogleAccessToken(****)"

object GoogleAccessToken:
  /** Tokens are refreshed this long before they expire */
  val RefreshMarginMillis: Long = 5 * 60 * 1000L

/**
  * Signs bytes as a service account, for V4 signed URLs. A service-account key signs locally; the
  * metadata server's account signs through the IAM Credentials `signBlob` API.
  */
trait GoogleSigner:
  def serviceAccountEmail: String

  /** The RSASSA-PKCS1-v1_5 SHA-256 signature of `data` */
  def sign(data: Array[Byte]): Array[Byte]

/**
  * A service-account key from a JSON key file. `toString` hides the private key.
  */
case class GoogleServiceAccountKey(
    clientEmail: String,
    privateKey: RsaPrivateKey,
    privateKeyId: Option[String] = None,
    tokenUri: String = GoogleOAuth.DefaultTokenUri,
    projectId: Option[String] = None
):
  override def toString: String =
    s"GoogleServiceAccountKey(clientEmail=${clientEmail}, privateKey=****)"

/**
  * Credentials of a user, as `gcloud auth application-default login` writes them. `toString` hides
  * the client secret and the refresh token.
  */
case class GoogleAuthorizedUser(
    clientId: String,
    clientSecret: String,
    refreshToken: String,
    tokenUri: String = GoogleOAuth.DefaultTokenUri,
    quotaProjectId: Option[String] = None
):
  override def toString: String =
    s"GoogleAuthorizedUser(clientId=${clientId}, clientSecret=****, refreshToken=****)"

/**
  * Supplies OAuth access tokens. `accessToken` returns `None` when the provider has no source to
  * use (no file, no metadata server): the default chain then tries the next one. A source that
  * exists but fails (a malformed key file, a refused refresh token) raises [[CloudException]], as
  * Google's own libraries do, instead of falling through to another identity.
  */
trait GoogleCredentialsProvider:
  def name: String
  def accessToken: Option[GoogleAccessToken]

  /** The signer for V4 signed URLs, when these credentials can sign */
  def signer: Option[GoogleSigner] = None

object GoogleCredentialsProvider:
  /** Read and write objects */
  val StorageReadWrite: String = "https://www.googleapis.com/auth/devstorage.read_write"

  /** Read objects only */
  val StorageReadOnly: String = "https://www.googleapis.com/auth/devstorage.read_only"

  /** Objects and their access controls */
  val StorageFullControl: String = "https://www.googleapis.com/auth/devstorage.full_control"

  /** Every Google Cloud API the account may use */
  val CloudPlatform: String = "https://www.googleapis.com/auth/cloud-platform"

  val DefaultScopes: Seq[String] = Seq(StorageReadWrite)

  /** A fixed access token, for example one handed to a browser by a server */
  def static(token: GoogleAccessToken): GoogleCredentialsProvider = StaticProvider(token)

  def static(token: String): GoogleCredentialsProvider = static(GoogleAccessToken(token))

  /** No credentials: requests are sent without `Authorization`, for public buckets and emulators */
  def anonymous: GoogleCredentialsProvider = Anonymous

  /** A service account, through the OAuth 2.0 JWT bearer flow */
  def serviceAccount(
      key: GoogleServiceAccountKey,
      scopes: Seq[String] = DefaultScopes
  ): GoogleCredentialsProvider = ServiceAccountProvider(key, scopes)

  /** A user's refresh token */
  def authorizedUser(user: GoogleAuthorizedUser): GoogleCredentialsProvider =
    AuthorizedUserProvider(user)

  /**
    * A credentials JSON (a service-account key or `authorized_user` credentials). Other types, such
    * as `external_account`, fail with [[CloudErrorCode.InvalidCredentials]].
    */
  def fromJson(json: String, scopes: Seq[String] = DefaultScopes): GoogleCredentialsProvider =
    GoogleCredentialsFile.parse(json, "credentials JSON") match
      case Left(key) =>
        serviceAccount(key, scopes)
      case Right(user) =>
        authorizedUser(user)

  /** A credentials JSON file (JVM, Scala Native, and Node.js) */
  def fromFile(path: String, scopes: Seq[String] = DefaultScopes): GoogleCredentialsProvider =
    FileProvider(s"file ${path}", () => Some(IOPath.parse(path)), scopes, required = true)

  /** The file named by `GOOGLE_APPLICATION_CREDENTIALS` */
  def environment(scopes: Seq[String] = DefaultScopes): GoogleCredentialsProvider = FileProvider(
    "GOOGLE_APPLICATION_CREDENTIALS",
    () => CloudEnv.get("GOOGLE_APPLICATION_CREDENTIALS").map(IOPath.parse),
    scopes,
    required = true
  )

  /**
    * The file `gcloud auth application-default login` writes:
    * `$CLOUDSDK_CONFIG/application_default_credentials.json`, else
    * `%APPDATA%\gcloud\application_default_credentials.json` on Windows or
    * `~/.config/gcloud/application_default_credentials.json`
    */
  def gcloud(scopes: Seq[String] = DefaultScopes): GoogleCredentialsProvider = FileProvider(
    "gcloud application default credentials",
    () => GoogleCredentialsFile.wellKnownFile,
    scopes,
    required = false
  )

  /**
    * The GCE / GKE / Cloud Run metadata server (`GCE_METADATA_HOST`, or `169.254.169.254`). It can
    * sign URLs through IAM `signBlob` when the account has the Service Account Token Creator role
    * on itself.
    */
  def metadataServer: GoogleCredentialsProvider = MetadataServerProvider(
    () => CloudEnv.get("GCE_METADATA_HOST").getOrElse("169.254.169.254"),
    GoogleOAuth.IamCredentialsEndpoint
  )

  /** Tries each provider in order and uses the first that has a source */
  def chain(providers: GoogleCredentialsProvider*): GoogleCredentialsProvider = Chain(providers)

  /**
    * Application Default Credentials order: `GOOGLE_APPLICATION_CREDENTIALS`, the gcloud file, then
    * the metadata server
    */
  def applicationDefault(scopes: Seq[String] = DefaultScopes): GoogleCredentialsProvider = chain(
    environment(scopes),
    gcloud(scopes),
    metadataServer
  )

  def default: GoogleCredentialsProvider = applicationDefault(DefaultScopes)

  // ---- Providers ----

  private object Anonymous extends GoogleCredentialsProvider:
    def name: String                           = "anonymous"
    def accessToken: Option[GoogleAccessToken] = None

  private final class StaticProvider(token: GoogleAccessToken) extends GoogleCredentialsProvider:
    def name: String                           = "static"
    def accessToken: Option[GoogleAccessToken] = Some(token)

  /** Caches a token until shortly before it expires, fetching a new one under a lock */
  private[uni] final class TokenCache(fetch: () => Option[GoogleAccessToken]):
    private var cached: Option[GoogleAccessToken] = None

    def get: Option[GoogleAccessToken] = synchronized {
      val now = System.currentTimeMillis()
      cached
        .filter(_.isFresh(now))
        .orElse {
          cached = fetch()
          cached
        }
    }

  private[uni] final class ServiceAccountProvider(key: GoogleServiceAccountKey, scopes: Seq[String])
      extends GoogleCredentialsProvider:
    def name: String = s"service account ${key.clientEmail}"

    private val cache = TokenCache { () =>
      val assertion = GoogleOAuth.jwtAssertion(key, scopes, System.currentTimeMillis())
      Some(
        GoogleOAuth.exchange(
          key.tokenUri,
          Seq(
            "grant_type" -> "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion"  -> assertion
          )
        )
      )
    }

    def accessToken: Option[GoogleAccessToken] = cache.get

    override val signer: Option[GoogleSigner] = Some(
      new GoogleSigner:
        def serviceAccountEmail: String          = key.clientEmail
        def sign(data: Array[Byte]): Array[Byte] = Rsa.signSha256(key.privateKey, data)
    )

  end ServiceAccountProvider

  private[uni] final class AuthorizedUserProvider(user: GoogleAuthorizedUser)
      extends GoogleCredentialsProvider:
    def name: String = s"authorized user ${user.clientId}"

    private val cache = TokenCache { () =>
      Some(
        GoogleOAuth.exchange(
          user.tokenUri,
          Seq(
            "grant_type"    -> "refresh_token",
            "client_id"     -> user.clientId,
            "client_secret" -> user.clientSecret,
            "refresh_token" -> user.refreshToken
          )
        )
      )
    }

    def accessToken: Option[GoogleAccessToken] = cache.get

  /** Reads a credentials file when it exists, and delegates to the provider it describes */
  private final class FileProvider(
      val name: String,
      path: () => Option[IOPath],
      scopes: Seq[String],
      required: Boolean
  ) extends GoogleCredentialsProvider:
    private var loaded: Option[(IOPath, GoogleCredentialsProvider)] = None

    private def delegate: Option[GoogleCredentialsProvider] = synchronized {
      if CloudEnv.isBrowser then
        None
      else
        path().flatMap { p =>
          loaded match
            case Some((lp, provider)) if lp.toString == p.toString =>
              Some(provider)
            case _ =>
              if !FileSystem.isFile(p) then
                if required then
                  throw CloudException(
                    CloudErrorCode.NoCredentials,
                    s"${name}: ${p} does not exist"
                  )
                None
              else
                val provider =
                  GoogleCredentialsFile.parse(FileSystem.readString(p), p.toString) match
                    case Left(key) =>
                      ServiceAccountProvider(key, scopes)
                    case Right(user) =>
                      AuthorizedUserProvider(user)
                loaded = Some((p, provider))
                Some(provider)
        }
    }

    def accessToken: Option[GoogleAccessToken] = delegate.flatMap(_.accessToken)
    override def signer: Option[GoogleSigner]  = delegate.flatMap(_.signer)

  end FileProvider

  private[uni] final class MetadataServerProvider(host: () => String, iamEndpoint: String)
      extends GoogleCredentialsProvider:
    def name: String = "metadata server"

    private def base: String =
      val h = host().stripSuffix("/")
      if h.startsWith("http://") || h.startsWith("https://") then
        h
      else
        s"http://${h}"

    private def get(path: String): Option[Response] =
      val client = GoogleOAuth.metadataHttp.newSyncClient
      try
        val resp =
          try client.send(Request.get(s"${base}${path}").setHeader("Metadata-Flavor", "Google"))
          catch CloudHttp.unwrapHttpError
        // Anything else listening on the address is not a metadata server
        if resp.header("Metadata-Flavor").contains("Google") then
          Some(resp)
        else
          None
      catch
        case e: CloudException =>
          throw e
        case _: Exception =>
          // No metadata server here (not on Google Cloud)
          None
      finally
        client.close()

    private val cache = TokenCache { () =>
      if CloudEnv.isBrowser then
        None
      else
        get("/computeMetadata/v1/instance/service-accounts/default/token").map { resp =>
          if !resp.isSuccessful then
            throw CloudException(
              CloudErrorCode.NoCredentials,
              s"The metadata server answered HTTP ${resp.statusCode} for a token"
            )
          GoogleOAuth.parseTokenResponse(resp.contentAsString.getOrElse(""), "metadata server")
        }
    }

    def accessToken: Option[GoogleAccessToken] = cache.get

    private lazy val email: String = get(
      "/computeMetadata/v1/instance/service-accounts/default/email"
    ).filter(_.isSuccessful)
      .flatMap(_.contentAsString)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(
        throw CloudException(
          CloudErrorCode.NoCredentials,
          "The metadata server has no service-account email"
        )
      )

    override val signer: Option[GoogleSigner] = Some(
      new GoogleSigner:
        def serviceAccountEmail: String          = email
        def sign(data: Array[Byte]): Array[Byte] =
          val token = accessToken.getOrElse(
            throw CloudException(CloudErrorCode.NoCredentials, "No metadata server token")
          )
          GoogleOAuth.signBlob(iamEndpoint, email, token, data)
    )

  end MetadataServerProvider

  private final class Chain(providers: Seq[GoogleCredentialsProvider])
      extends GoogleCredentialsProvider:
    def name: String = providers.map(_.name).mkString("chain(", ", ", ")")

    // The provider that answered, so later requests skip the probes before it
    @volatile
    private var resolved: Option[GoogleCredentialsProvider] = None

    def accessToken: Option[GoogleAccessToken] = resolved
      .flatMap(_.accessToken)
      .orElse {
        resolved = None
        val found = providers
          .iterator
          .map(p => p -> p.accessToken)
          .collectFirst { case (p, Some(t)) =>
            resolved = Some(p)
            t
          }
        if found.isEmpty then
          throw CloudException(
            CloudErrorCode.NoCredentials,
            s"No Google credentials found. Checked: ${providers
                .map(_.name)
                .mkString(", ")}. Run `gcloud auth application-default login`, " +
              "or set GOOGLE_APPLICATION_CREDENTIALS to a service-account key file"
          )
        found
      }

    override def signer: Option[GoogleSigner] =
      if resolved.isEmpty then
        accessToken
      resolved.flatMap(_.signer)

  end Chain

end GoogleCredentialsProvider

/** Parses Google credentials JSON files */
private[uni] object GoogleCredentialsFile:
  /** The gcloud application default credentials file, if one exists */
  def wellKnownFile: Option[IOPath] =
    val name = "application_default_credentials.json"
    CloudEnv
      .get("CLOUDSDK_CONFIG")
      .map(d => IOPath.parse(d).resolve(name))
      .orElse(CloudEnv.get("APPDATA").map(d => IOPath.parse(d).resolve(s"gcloud/${name}")))
      .orElse(
        CloudEnv
          .get("HOME")
          .map(IOPath.parse)
          .orElse(
            if CloudEnv.isBrowser then
              None
            else
              Some(FileSystem.homeDirectory)
          )
          .map(_.resolve(s".config/gcloud/${name}"))
      )
      .filter(FileSystem.isFile)

  /** A service-account key (Left) or user credentials (Right). Never echoes the file's contents. */
  def parse(json: String, source: String): Either[GoogleServiceAccountKey, GoogleAuthorizedUser] =
    def invalid(msg: String): CloudException = CloudException(
      CloudErrorCode.InvalidCredentials,
      s"Invalid Google credentials in ${source}: ${msg}"
    )
    val obj =
      try
        JSON.parse(json) match
          case o: JSON.JSONObject =>
            o
          case _ =>
            throw invalid("not a JSON object")
      catch
        case e: CloudException =>
          throw e
        case _: Exception =>
          throw invalid("not valid JSON")
    def str(key: String): Option[String] = obj
      .get(key)
      .collect { case JSON.JSONString(s) =>
        s
      }
      .filter(_.nonEmpty)
    def required(key: String): String = str(key).getOrElse(throw invalid(s"no ${key}"))
    str("type") match
      case Some("service_account") =>
        Left(
          GoogleServiceAccountKey(
            clientEmail = required("client_email"),
            privateKey = Rsa.parsePem(required("private_key")),
            privateKeyId = str("private_key_id"),
            tokenUri = str("token_uri").getOrElse(GoogleOAuth.DefaultTokenUri),
            projectId = str("project_id")
          )
        )
      case Some("authorized_user") =>
        Right(
          GoogleAuthorizedUser(
            clientId = required("client_id"),
            clientSecret = required("client_secret"),
            refreshToken = required("refresh_token"),
            tokenUri = str("token_uri").getOrElse(GoogleOAuth.DefaultTokenUri),
            quotaProjectId = str("quota_project_id")
          )
        )
      case Some(other) =>
        throw invalid(
          s"credential type ${other} is not supported; use a service-account key or " +
            "`gcloud auth application-default login`"
        )
      case None =>
        throw invalid("no type")

  end parse

end GoogleCredentialsFile

/** OAuth 2.0 token requests, JWTs, and IAM signBlob */
private[uni] object GoogleOAuth:
  val DefaultTokenUri: String        = "https://oauth2.googleapis.com/token"
  val IamCredentialsEndpoint: String = "https://iamcredentials.googleapis.com"

  /** A JWT lives at most one hour */
  private val JwtLifetimeSeconds = 3600L

  lazy val tokenHttp: HttpClientConfig =
    Http.client.withConnectTimeoutMillis(5000).withReadTimeoutMillis(10000).noFollowRedirects

  lazy val metadataHttp: HttpClientConfig =
    Http.client.withConnectTimeoutMillis(1000).withReadTimeoutMillis(2000).noRetry.noFollowRedirects

  def base64Url(b: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(b)

  private def jsonString(s: String): String = JSON.JSONString(s).toJSON

  /** A signed RS256 JWT asking `key.tokenUri` for a token with `scopes` */
  def jwtAssertion(key: GoogleServiceAccountKey, scopes: Seq[String], nowMillis: Long): String =
    val iat    = nowMillis / 1000
    val header = (
      Seq(""""alg":"RS256"""", """"typ":"JWT"""") ++
        key.privateKeyId.map(id => s""""kid":${jsonString(id)}""")
    ).mkString("{", ",", "}")
    val claims = Seq(
      s""""iss":${jsonString(key.clientEmail)}""",
      s""""scope":${jsonString(scopes.mkString(" "))}""",
      s""""aud":${jsonString(key.tokenUri)}""",
      s""""iat":${iat}""",
      s""""exp":${iat + JwtLifetimeSeconds}"""
    ).mkString("{", ",", "}")
    val signingInput =
      s"${base64Url(header.getBytes("UTF-8"))}.${base64Url(claims.getBytes("UTF-8"))}"
    val signature = Rsa.signSha256(key.privateKey, signingInput.getBytes("UTF-8"))
    s"${signingInput}.${base64Url(signature)}"

  /**
    * Token endpoints get secrets, so they must use HTTPS (plain HTTP only to loopback, for tests)
    */
  def checkTokenUri(uri: String): Unit =
    val lower = uri.toLowerCase
    val ok    =
      lower.startsWith("https://") ||
        Seq("http://localhost", "http://127.0.0.1", "http://[::1]").exists { p =>
          lower.startsWith(p) && (lower.length == p.length || ":/".contains(lower.charAt(p.length)))
        }
    if !ok then
      throw CloudException(CloudErrorCode.InvalidCredentials, "The OAuth token_uri must use HTTPS")

  /** POSTs a form to the token endpoint. Errors name Google's error code, never the request. */
  def exchange(tokenUri: String, form: Seq[(String, String)]): GoogleAccessToken =
    checkTokenUri(tokenUri)
    val body = form.map((k, v) => s"${URLEncoder.encode(k)}=${URLEncoder.encode(v)}").mkString("&")
    val req  = Request
      .post(tokenUri)
      .withContent(HttpContent.text(body, ContentType.ApplicationFormUrlEncoded))
    val client = tokenHttp.newSyncClient
    val resp   =
      try
        try client.send(req)
        catch CloudHttp.unwrapHttpError
      catch
        case e: CloudException =>
          throw e
        case e: Exception =>
          // The exception may carry the request; report only its kind
          throw CloudException(
            CloudErrorCode.NoCredentials,
            s"The Google token request failed: ${e.getClass.getSimpleName}"
          )
      finally
        client.close()
    val text = resp.contentAsString.getOrElse("")
    if !resp.isSuccessful then
      throw CloudException(
        CloudErrorCode.NoCredentials,
        s"The Google token endpoint refused the request (HTTP ${resp.statusCode}${oauthError(text)
            .map(e => s", ${e}")
            .getOrElse("")})",
        Some(resp.statusCode)
      )
    parseTokenResponse(text, "token endpoint")

  end exchange

  /** `error` and `error_description` of an OAuth error response, which carry no secrets */
  private def oauthError(text: String): Option[String] =
    try
      JSON.parse(text) match
        case o: JSON.JSONObject =>
          val err = o
            .get("error")
            .collect { case JSON.JSONString(s) =>
              s
            }
          val desc = o
            .get("error_description")
            .collect { case JSON.JSONString(s) =>
              s
            }
          err.map(e => desc.map(d => s"${e}: ${d}").getOrElse(e))
        case _ =>
          None
    catch
      case _: Exception =>
        None

  def parseTokenResponse(
      text: String,
      source: String,
      nowMillis: Long = System.currentTimeMillis()
  ): GoogleAccessToken =
    val parsed =
      try
        Some(JSON.parse(text))
      catch
        case _: Exception =>
          None
    parsed match
      case Some(o: JSON.JSONObject) =>
        val token = o
          .get("access_token")
          .collect { case JSON.JSONString(s) =>
            s
          }
          .filter(_.nonEmpty)
          .getOrElse(
            throw CloudException(
              CloudErrorCode.InvalidResponse,
              s"No access_token in the ${source} response"
            )
          )
        val expiresIn = o
          .get("expires_in")
          .collect {
            case JSON.JSONLong(v) =>
              v
            case JSON.JSONDouble(v) =>
              v.toLong
            case JSON.JSONString(s) if s.toLongOption.isDefined =>
              s.toLong
          }
        GoogleAccessToken(token, expiresIn.map(s => nowMillis + s * 1000))
      case _ =>
        throw CloudException(
          CloudErrorCode.InvalidResponse,
          s"The ${source} response is not a JSON object"
        )
    end match

  end parseTokenResponse

  /** Signs `data` as `email` with the IAM Credentials API */
  def signBlob(
      endpoint: String,
      email: String,
      token: GoogleAccessToken,
      data: Array[Byte]
  ): Array[Byte] =
    val url =
      s"${endpoint.stripSuffix("/")}/v1/projects/-/serviceAccounts/${URLEncoder.encode(
          email
        )}:signBlob"
    val body   = s"""{"payload":${jsonString(Base64.getEncoder.encodeToString(data))}}"""
    val client = tokenHttp.newSyncClient
    val resp   =
      try
        try client.send(Request.post(url).withBearerToken(token.token).withJsonContent(body))
        catch CloudHttp.unwrapHttpError
      catch
        case e: CloudException =>
          throw e
        case e: Exception =>
          // The exception may carry the request and its bearer token; report only its kind
          throw CloudException(
            CloudErrorCode.NoCredentials,
            s"IAM signBlob for ${email} failed: ${e.getClass.getSimpleName}"
          )
      finally
        client.close()
    if !resp.isSuccessful then
      throw CloudException(
        if resp.statusCode == 403 then
          CloudErrorCode.AccessDenied
        else
          CloudErrorCode.NoCredentials
        ,
        s"IAM signBlob for ${email} failed with HTTP ${resp.statusCode}. The account needs the " +
          "Service Account Token Creator role on itself, and a token with the cloud-platform scope",
        Some(resp.statusCode)
      )
    val signed =
      try
        JSON.parse(resp.contentAsString.getOrElse("")) match
          case o: JSON.JSONObject =>
            o.get("signedBlob")
              .collect { case JSON.JSONString(s) =>
                s
              }
          case _ =>
            None
      catch
        case _: Exception =>
          None
    Base64
      .getDecoder
      .decode(
        signed.getOrElse(
          throw CloudException(CloudErrorCode.InvalidResponse, "No signedBlob in the answer")
        )
      )

  end signBlob

end GoogleOAuth
