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

import wvlet.uni.http.Http
import wvlet.uni.http.HttpClientConfig

/**
  * Settings for an Azure Blob Storage store.
  *
  * @param account
  *   the storage account name. When unset: the account in the endpoint's host name
  *   (`<account>.blob.core.windows.net`) or an emulator's path, then `AZURE_STORAGE_ACCOUNT`
  * @param endpoint
  *   the Blob service URL. When unset, `https://<account>.blob.core.windows.net`. Emulators put the
  *   account in the path, such as `http://127.0.0.1:10000/devstoreaccount1` for Azurite
  * @param blockSizeBytes
  *   the block size for [[ObjectStore.newOutputStream]] (64 KiB to 2000 MiB; Azure allows 4000 MiB,
  *   but one block must fit in a byte array)
  */
case class AzureConfig(
    account: Option[String] = None,
    endpoint: Option[String] = None,
    credentials: AzureCredentialsProvider = AzureCredentialsProvider.default,
    blockSizeBytes: Int = AzureConfig.DefaultBlockSize,
    httpClient: HttpClientConfig = Http.client
):
  def withAccount(account: String): AzureConfig   = copy(account = Some(account))
  def noAccount: AzureConfig                      = copy(account = None)
  def withEndpoint(endpoint: String): AzureConfig = copy(endpoint = Some(endpoint.stripSuffix("/")))
  def noEndpoint: AzureConfig                     = copy(endpoint = None)

  def withCredentials(provider: AzureCredentialsProvider): AzureConfig = copy(credentials =
    provider
  )

  /** Fixed credentials. A [[AzureCredentials.SharedKey]] also sets the account when unset. */
  def withCredentials(credentials: AzureCredentials): AzureConfig =
    val c = withCredentials(AzureCredentialsProvider.static(credentials))
    credentials match
      case k: AzureCredentials.SharedKey if account.isEmpty =>
        c.withAccount(k.accountName)
      case _ =>
        c

  /** An account name and key (Shared Key). Sets the account too. */
  def withSharedKey(accountName: String, accountKey: String): AzureConfig = withAccount(accountName)
    .withCredentials(AzureCredentials.SharedKey(accountName, accountKey))

  /** A SAS token such as `sv=...&sp=rl&sig=...`, appended to every request */
  def withSasToken(token: String): AzureConfig = withCredentials(AzureCredentials.Sas(token))

  /** Sends unsigned requests, for containers with public read access */
  def noCredentials: AzureConfig = withCredentials(AzureCredentialsProvider.anonymous)

  def withBlockSizeBytes(bytes: Int): AzureConfig =
    if bytes < AzureConfig.MinBlockSize || bytes > AzureConfig.MaxBlockSize then
      throw IllegalArgumentException(
        s"Azure blocks must be ${AzureConfig.MinBlockSize} to ${AzureConfig
            .MaxBlockSize} bytes: ${bytes}"
      )
    copy(blockSizeBytes = bytes)

  def withHttpClient(config: HttpClientConfig): AzureConfig = copy(httpClient = config)

  /** The storage account name. Fails when no source names one. */
  def resolvedAccount: String = account
    .orElse(endpoint.flatMap(AzureConfig.accountOfEndpoint))
    .orElse(CloudEnv.get("AZURE_STORAGE_ACCOUNT"))
    .getOrElse(
      throw StorageException(
        CloudErrorCode.InvalidConfig,
        "No Azure storage account: set AzureConfig.withAccount, withSharedKey, withEndpoint, " +
          "a connection string, or AZURE_STORAGE_ACCOUNT"
      )
    )

  /** The Blob service URL, without a trailing `/` */
  def resolvedEndpoint: String = endpoint.getOrElse(
    s"https://${resolvedAccount}.blob.${AzureConfig.DefaultEndpointSuffix}"
  )

end AzureConfig

object AzureConfig:
  val MinBlockSize: Int         = 64 * 1024
  val MaxBlockSize: Int         = 2000 * 1024 * 1024
  val MaxBlocks: Int            = 50000
  val DefaultBlockSize: Int     = 8 * 1024 * 1024
  val DefaultEndpointSuffix     = "core.windows.net"
  private val AzuriteAccount    = "devstoreaccount1"
  private val AzuriteAccountKey =
    "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=="

  /**
    * `AZURE_STORAGE_CONNECTION_STRING` when set; otherwise the account from `AZURE_STORAGE_ACCOUNT`
    * (or the credentials) and the default credential chain
    */
  def default: AzureConfig = CloudEnv
    .get("AZURE_STORAGE_CONNECTION_STRING")
    .map(fromConnectionString)
    .getOrElse(AzureConfig())

  /**
    * The Azurite emulator with its well-known development account
    *
    * @param endpoint
    *   Azurite's Blob service URL, including the account path
    */
  def azurite(endpoint: String = s"http://127.0.0.1:10000/${AzuriteAccount}"): AzureConfig =
    AzureConfig().withEndpoint(endpoint).withSharedKey(AzuriteAccount, AzuriteAccountKey)

  /**
    * Parses a connection string from the Azure portal, such as
    * `DefaultEndpointsProtocol=https;AccountName=...;AccountKey=...;EndpointSuffix=core.windows.net`.
    * Reads `AccountName`, `AccountKey`, `SharedAccessSignature`, `BlobEndpoint`, `EndpointSuffix`,
    * `DefaultEndpointsProtocol`, and `UseDevelopmentStorage=true` (Azurite on its default port).
    */
  def fromConnectionString(connectionString: String): AzureConfig =
    val kv =
      connectionString
        .split(";")
        .map(_.trim)
        .filter(_.nonEmpty)
        .flatMap { part =>
          val eq = part.indexOf('=')
          if eq <= 0 then
            None
          else
            // SAS values contain '=', so split at the first one only
            Some(part.substring(0, eq).trim.toLowerCase -> part.substring(eq + 1).trim)
        }
        .toMap
    if kv.get("usedevelopmentstorage").exists(_.equalsIgnoreCase("true")) then
      azurite()
    else
      val account  = kv.get("accountname")
      val protocol = kv.getOrElse("defaultendpointsprotocol", "https")
      val suffix   = kv.getOrElse("endpointsuffix", DefaultEndpointSuffix)
      val endpoint = kv
        .get("blobendpoint")
        .orElse(account.map(a => s"${protocol}://${a}.blob.${suffix}"))
        .getOrElse(
          throw IllegalArgumentException(
            "The connection string has neither AccountName nor BlobEndpoint"
          )
        )
      val base = account.foldLeft(AzureConfig().withEndpoint(endpoint))(_.withAccount(_))
      (kv.get("accountkey"), kv.get("sharedaccesssignature")) match
        case (Some(key), _) =>
          val name = account.getOrElse(
            throw IllegalArgumentException(
              "The connection string has AccountKey but no AccountName"
            )
          )
          base.withSharedKey(name, key)
        case (None, Some(sas)) =>
          base.withSasToken(sas)
        case (None, None) =>
          base.noCredentials

  end fromConnectionString

  /**
    * `<account>` from `https://<account>.blob.<suffix>`, or from an emulator's first path segment
    */
  private[storage] def accountOfEndpoint(endpoint: String): Option[String] =
    val i = endpoint.indexOf("://")
    if i <= 0 then
      None
    else
      val rest  = endpoint.substring(i + 3)
      val slash = rest.indexOf('/')
      val host  =
        if slash < 0 then
          rest
        else
          rest.substring(0, slash)
      val path =
        if slash < 0 then
          ""
        else
          rest.substring(slash + 1)
      val dot = host.indexOf(".blob.")
      if dot > 0 then
        Some(host.substring(0, dot))
      else
        path.split("/").find(_.nonEmpty)

end AzureConfig
