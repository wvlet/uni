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

import wvlet.uni.http.Http
import wvlet.uni.http.HttpClientConfig
import wvlet.uni.http.HttpContent
import wvlet.uni.http.HttpException
import wvlet.uni.http.Request
import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.log.LogSupport
import wvlet.uni.util.URLEncoder

/**
  * Credentials for Azure Blob Storage. `toString` hides keys, SAS signatures, and tokens, so they
  * can't leak into logs.
  */
sealed trait AzureCredentials

object AzureCredentials:
  /** A storage account key (Shared Key authorization) */
  case class SharedKey(accountName: String, accountKey: String) extends AzureCredentials:
    override def toString: String = s"SharedKey(accountName=${accountName}, accountKey=****)"

  /**
    * A shared access signature, such as `sv=...&sp=r&sig=...`. It is appended to every request.
    */
  case class Sas(token: String) extends AzureCredentials:
    /** The token without a leading `?` */
    def query: String = token.stripPrefix("?")

    override def toString: String =
      val masked = query
        .split("&")
        .map { kv =>
          if kv.startsWith("sig=") then
            "sig=****"
          else
            kv
        }
        .mkString("&")
      s"Sas(${masked})"

  /**
    * A Microsoft Entra ID access token for `https://storage.azure.com/`
    *
    * @param expiresOnMillis
    *   when the token expires (epoch millis), if known
    */
  case class Bearer(token: String, expiresOnMillis: Option[Long] = None) extends AzureCredentials:
    override def toString: String = "Bearer(****)"

end AzureCredentials

/**
  * Supplies Azure credentials. `credentials` returns `None` when the provider has none to give: the
  * default chain then tries the next provider, and an anonymous store sends unsigned requests.
  */
trait AzureCredentialsProvider:
  def name: String
  def credentials: Option[AzureCredentials]

object AzureCredentialsProvider:
  /** The OAuth scope for Azure Storage */
  val StorageScope: String = "https://storage.azure.com/.default"

  /** The resource that managed identity endpoints take */
  val StorageResource: String = "https://storage.azure.com/"

  val DefaultAuthorityHost: String = "https://login.microsoftonline.com"

  def static(credentials: AzureCredentials): AzureCredentialsProvider = StaticProvider(credentials)

  def sharedKey(accountName: String, accountKey: String): AzureCredentialsProvider = static(
    AzureCredentials.SharedKey(accountName, accountKey)
  )

  def sas(token: String): AzureCredentialsProvider = static(AzureCredentials.Sas(token))

  /** A fixed Entra ID access token, for example from `az account get-access-token` */
  def bearer(token: String): AzureCredentialsProvider = static(AzureCredentials.Bearer(token))

  /** No credentials: requests are sent unsigned, for public containers */
  def anonymous: AzureCredentialsProvider = Anonymous

  /**
    * `AZURE_STORAGE_KEY` (with the account in `AZURE_STORAGE_ACCOUNT`), then
    * `AZURE_STORAGE_SAS_TOKEN`, then a client secret from `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, and
    * `AZURE_CLIENT_SECRET`
    */
  def environment: AzureCredentialsProvider = EnvironmentProvider

  /**
    * An Entra ID app registration's client secret (OAuth 2.0 client credentials)
    *
    * @param authorityHost
    *   the sign-in host; when unset, `AZURE_AUTHORITY_HOST` or `https://login.microsoftonline.com`.
    *   Sovereign clouds use their own, such as `https://login.chinacloudapi.cn`
    */
  def clientSecret(
      tenantId: String,
      clientId: String,
      clientSecret: String,
      authorityHost: Option[String] = None
  ): AzureCredentialsProvider = Cached(
    ClientCredentialsProvider(
      "client-secret",
      tenantId,
      clientId,
      authorityHost,
      () => Seq("client_secret" -> clientSecret)
    )
  )

  /**
    * Workload identity (AKS and other federated identity setups): exchanges the token in
    * `AZURE_FEDERATED_TOKEN_FILE` for an access token, with `AZURE_TENANT_ID` and
    * `AZURE_CLIENT_ID`. The file is read again on every refresh, because the platform rotates it.
    */
  def workloadIdentity: AzureCredentialsProvider = WorkloadIdentityProvider

  /**
    * Managed identity: App Service and Functions (`IDENTITY_ENDPOINT` and `IDENTITY_HEADER`), then
    * the VM instance metadata service (IMDS)
    *
    * @param clientId
    *   a user-assigned identity's client id. When unset, `AZURE_CLIENT_ID`, or the system-assigned
    *   identity
    */
  def managedIdentity(clientId: Option[String] = None): AzureCredentialsProvider = Cached(
    ManagedIdentityProvider(clientId, ImdsEndpoint)
  )

  /** Tries each provider in order and uses the first that has credentials. Fails when none has. */
  def chain(providers: AzureCredentialsProvider*): AzureCredentialsProvider = Chain(providers)

  /**
    * Environment variables, workload identity, then managed identity, the order Azure's SDKs use.
    * Browsers have none of these, so pass a SAS token there.
    */
  def default: AzureCredentialsProvider = chain(environment, workloadIdentity, managedIdentity())

  private[uni] val ImdsEndpoint: String = "http://169.254.169.254/metadata/identity/oauth2/token"

  private object Anonymous extends AzureCredentialsProvider:
    def name: String                          = "anonymous"
    def credentials: Option[AzureCredentials] = None

  private final class StaticProvider(c: AzureCredentials) extends AzureCredentialsProvider:
    def name: String                          = "static"
    def credentials: Option[AzureCredentials] = Some(c)

  private object EnvironmentProvider extends AzureCredentialsProvider:
    def name: String = "environment"

    // Keyed by the variables, so changing them (in tests) builds a new provider
    private var secretProvider: Option[((String, String, String), AzureCredentialsProvider)] = None

    def credentials: Option[AzureCredentials] =
      val key =
        for
          account <- CloudEnv.get("AZURE_STORAGE_ACCOUNT")
          key     <- CloudEnv.get("AZURE_STORAGE_KEY")
        yield AzureCredentials.SharedKey(account, key)
      key
        .orElse(CloudEnv.get("AZURE_STORAGE_SAS_TOKEN").map(AzureCredentials.Sas(_)))
        .orElse {
          for
            tenant <- CloudEnv.get("AZURE_TENANT_ID")
            client <- CloudEnv.get("AZURE_CLIENT_ID")
            secret <- CloudEnv.get("AZURE_CLIENT_SECRET")
            c      <- secretProviderFor((tenant, client, secret)).credentials
          yield c
        }

    private def secretProviderFor(k: (String, String, String)): AzureCredentialsProvider =
      synchronized {
        secretProvider match
          case Some((key, p)) if key == k =>
            p
          case _ =>
            val p = clientSecret(k._1, k._2, k._3)
            secretProvider = Some(k -> p)
            p
      }

  end EnvironmentProvider

  private object WorkloadIdentityProvider extends AzureCredentialsProvider:
    def name: String = "workload-identity"

    private var cached: Option[((String, String, String), AzureCredentialsProvider)] = None

    def credentials: Option[AzureCredentials] =
      if CloudEnv.isBrowser then
        None
      else
        for
          tenant <- CloudEnv.get("AZURE_TENANT_ID")
          client <- CloudEnv.get("AZURE_CLIENT_ID")
          file   <- CloudEnv.get("AZURE_FEDERATED_TOKEN_FILE")
          c      <- providerFor((tenant, client, file)).credentials
        yield c

    private def providerFor(k: (String, String, String)): AzureCredentialsProvider = synchronized {
      cached match
        case Some((key, p)) if key == k =>
          p
        case _ =>
          val (tenant, client, file) = k
          val p                      = Cached(
            ClientCredentialsProvider(
              name,
              tenant,
              client,
              None,
              () =>
                Seq(
                  "client_assertion_type" ->
                    "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                  "client_assertion" -> FileSystem.readString(IOPath.parse(file)).trim
                )
            )
          )
          cached = Some(k -> p)
          p
    }

  end WorkloadIdentityProvider

  private final class Chain(providers: Seq[AzureCredentialsProvider])
      extends AzureCredentialsProvider
      with LogSupport:
    def name: String = providers.map(_.name).mkString("chain(", ", ", ")")

    // The provider that answered last time, so a laptop doesn't probe IMDS on every request
    @volatile
    private var resolved: Option[AzureCredentialsProvider] = None

    private def tryProvider(
        p: AzureCredentialsProvider,
        failures: scala.collection.mutable.ListBuffer[String]
    ): Option[AzureCredentials] =
      try
        p.credentials
      catch
        case e: Exception =>
          debug(s"Azure credentials provider ${p.name} failed: ${e.getMessage}")
          failures += s"${p.name}: ${e.getMessage}"
          None

    // A provider that answered before may fail later (a token refresh that times out), so its
    // failure falls back to scanning the rest of the chain
    def credentials: Option[AzureCredentials] =
      val failures = scala.collection.mutable.ListBuffer.empty[String]
      val previous = resolved
      previous
        .flatMap(tryProvider(_, failures))
        .orElse {
          resolved = None
          val found = providers
            .iterator
            .filterNot(p => previous.contains(p) && failures.nonEmpty)
            .map(p => p -> tryProvider(p, failures))
            .collectFirst { case (p, Some(c)) =>
              resolved = Some(p)
              c
            }
          if found.isEmpty then
            // Name the failures: a rejected client secret is otherwise invisible here
            val why =
              if failures.isEmpty then
                ""
              else
                failures.mkString(". Failures: ", "; ", "")
            throw CloudException(
              CloudErrorCode.NoCredentials,
              s"No Azure credentials found. Checked: ${providers.map(_.name).mkString(", ")}${why}"
            )
          found
        }

  end Chain

  /** Caches tokens until 5 minutes before they expire, so a token isn't fetched per request */
  private[uni] final class Cached(underlying: AzureCredentialsProvider)
      extends AzureCredentialsProvider:
    def name: String = underlying.name

    private var cached: Option[AzureCredentials] = None

    def credentials: Option[AzureCredentials] = synchronized {
      val now   = System.currentTimeMillis()
      val fresh = cached.filter {
        case b: AzureCredentials.Bearer =>
          b.expiresOnMillis.forall(_ - 5 * 60 * 1000L > now)
        case _ =>
          true
      }
      fresh.orElse {
        cached = underlying.credentials
        cached
      }
    }

  private def authority(host: Option[String]): String = host
    .orElse(CloudEnv.get("AZURE_AUTHORITY_HOST"))
    .getOrElse(DefaultAuthorityHost)
    .stripSuffix("/")

  private lazy val loginHttp: HttpClientConfig =
    Http.client.withConnectTimeoutMillis(10000).withReadTimeoutMillis(30000).noFollowRedirects

  private lazy val metadataHttp: HttpClientConfig =
    Http.client.withConnectTimeoutMillis(1000).withReadTimeoutMillis(2000).noRetry.noFollowRedirects

  /** The OAuth 2.0 client credentials grant, with a secret or a federated assertion */
  private[uni] final class ClientCredentialsProvider(
      val name: String,
      tenantId: String,
      clientId: String,
      authorityHost: Option[String],
      secret: () => Seq[(String, String)]
  ) extends AzureCredentialsProvider:
    def credentials: Option[AzureCredentials] =
      if CloudEnv.isBrowser then
        None
      else
        val url  = s"${authority(authorityHost)}/${URLEncoder.encode(tenantId)}/oauth2/v2.0/token"
        val form = (
          Seq(
            "grant_type" -> "client_credentials",
            "client_id"  -> clientId,
            "scope"      -> StorageScope
          ) ++ secret()
        ).map((k, v) => s"${URLEncoder.encode(k)}=${URLEncoder.encode(v)}").mkString("&")
        val client = loginHttp.newSyncClient
        val resp   =
          try
            client.send(
              Request
                .post(url)
                .withContent(HttpContent.bytes(form.getBytes("UTF-8")))
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
            )
          catch
            case e: HttpException if e.response.isDefined =>
              e.response.get
          finally
            client.close()
        if !resp.isSuccessful then
          throw CloudException(
            CloudErrorCode.NoCredentials,
            s"Entra ID token request (${name}) failed: HTTP ${resp.statusCode} ${tokenError(
                resp.contentAsString.getOrElse("")
              )}".trim,
            Some(resp.statusCode)
          )
        parseTokenJson(resp.contentAsString.getOrElse(""), System.currentTimeMillis())

  end ClientCredentialsProvider

  private[uni] final class ManagedIdentityProvider(clientId: Option[String], imds: String)
      extends AzureCredentialsProvider:
    def name: String = "managed-identity"

    def credentials: Option[AzureCredentials] =
      if CloudEnv.isBrowser then
        None
      else
        val id             = clientId.orElse(CloudEnv.get("AZURE_CLIENT_ID"))
        val (url, headers) =
          (CloudEnv.get("IDENTITY_ENDPOINT"), CloudEnv.get("IDENTITY_HEADER")) match
            case (Some(endpoint), Some(header)) =>
              // App Service and Functions
              val q =
                Seq("resource" -> StorageResource, "api-version" -> "2019-08-01") ++
                  id.map("client_id" -> _)
              (withQuery(endpoint, q), Seq("X-IDENTITY-HEADER" -> header))
            case _ =>
              val q =
                Seq("api-version" -> "2018-02-01", "resource" -> StorageResource) ++
                  id.map("client_id" -> _)
              (withQuery(imds, q), Seq("Metadata" -> "true"))
        val client = metadataHttp.newSyncClient
        val resp   =
          try
            client.send(headers.foldLeft(Request.get(url))((r, h) => r.setHeader(h._1, h._2)))
          catch
            case e: HttpException if e.response.isDefined =>
              e.response.get
          finally
            client.close()
        if resp.isSuccessful then
          parseTokenJson(resp.contentAsString.getOrElse(""), System.currentTimeMillis())
        else
          // No identity is assigned, or this isn't an Azure host
          None

    private def withQuery(base: String, q: Seq[(String, String)]): String =
      val sep =
        if base.contains("?") then
          "&"
        else
          "?"
      s"${base}${sep}${q.map((k, v) => s"${k}=${URLEncoder.encode(v)}").mkString("&")}"

  end ManagedIdentityProvider

  /**
    * Reads `access_token` and the expiry from a token response: `expires_on` (epoch seconds, as
    * managed identity endpoints send) or `expires_in` (seconds from now, as Entra ID sends). Both
    * may be numbers or strings.
    */
  private[uni] def parseTokenJson(json: String, nowMillis: Long): Option[AzureCredentials] =
    JSON.parse(json) match
      case o: JSON.JSONObject =>
        def field(key: String): Option[String] = o
          .get(key)
          .collect {
            case JSON.JSONString(s) =>
              s
            case JSON.JSONLong(n) =>
              n.toString
            case JSON.JSONDouble(d) =>
              d.toLong.toString
          }
          .map(_.trim)
          .filter(_.nonEmpty)
        val expiresOn = field("expires_on")
          .flatMap(_.toLongOption)
          .map(_ * 1000L)
          .orElse(field("expires_in").flatMap(_.toLongOption).map(s => nowMillis + s * 1000L))
        field("access_token").map(t => AzureCredentials.Bearer(t, expiresOn))
      case _ =>
        None

  private def tokenError(json: String): String =
    try
      JSON.parse(json) match
        case o: JSON.JSONObject =>
          o.get("error_description")
            .orElse(o.get("error"))
            .collect { case JSON.JSONString(s) =>
              s.linesIterator.nextOption().getOrElse("")
            }
            .getOrElse("")
        case _ =>
          ""
    catch
      case _: Exception =>
        ""

end AzureCredentialsProvider
