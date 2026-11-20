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

/**
  * Settings for a Google Cloud Storage store.
  *
  * @param endpoint
  *   the service URL. When unset: `STORAGE_EMULATOR_HOST` (an emulator such as fake-gcs-server),
  *   then `https://storage.googleapis.com`
  * @param credentials
  *   when unset: Application Default Credentials, or anonymous requests to an emulator named by
  *   `STORAGE_EMULATOR_HOST`
  * @param chunkSizeBytes
  *   the resumable upload chunk size for [[ObjectStore.newOutputStream]] (a multiple of 256 KiB)
  */
case class GcsConfig(
    endpoint: Option[String] = None,
    credentials: Option[GoogleCredentialsProvider] = None,
    chunkSizeBytes: Int = GcsConfig.DefaultChunkSize,
    httpClient: HttpClientConfig = Http.client
):
  def withEndpoint(endpoint: String): GcsConfig = copy(endpoint = Some(endpoint.stripSuffix("/")))
  def noEndpoint: GcsConfig                     = copy(endpoint = None)

  def withCredentials(provider: GoogleCredentialsProvider): GcsConfig = copy(credentials =
    Some(provider)
  )

  /** A fixed OAuth access token, such as one a server hands to a browser */
  def withAccessToken(token: String): GcsConfig = withCredentials(
    GoogleCredentialsProvider.static(token)
  )

  /** A service-account key or `authorized_user` credentials JSON */
  def withCredentialsJson(json: String): GcsConfig = withCredentials(
    GoogleCredentialsProvider.fromJson(json)
  )

  /** Sends requests without `Authorization`, for public buckets and emulators */
  def noCredentials: GcsConfig = withCredentials(GoogleCredentialsProvider.anonymous)

  def withChunkSizeBytes(bytes: Int): GcsConfig =
    if bytes < GcsConfig.ChunkUnit || bytes % GcsConfig.ChunkUnit != 0 then
      throw IllegalArgumentException(
        s"GCS resumable upload chunks must be a multiple of ${GcsConfig.ChunkUnit} bytes: ${bytes}"
      )
    copy(chunkSizeBytes = bytes)

  def withHttpClient(config: HttpClientConfig): GcsConfig = copy(httpClient = config)

  private def emulatorHost: Option[String] = StorageEnv
    .get("STORAGE_EMULATOR_HOST")
    .map { h =>
      val e = h.stripSuffix("/")
      if e.startsWith("http://") || e.startsWith("https://") then
        e
      else
        s"http://${e}"
    }

  /** The service URL requests go to */
  def resolvedEndpoint: String = endpoint.orElse(emulatorHost).getOrElse(GcsConfig.DefaultEndpoint)

  /** The credentials used for requests */
  def resolvedCredentials: GoogleCredentialsProvider = credentials.getOrElse {
    if endpoint.isEmpty && emulatorHost.isDefined then
      GoogleCredentialsProvider.anonymous
    else
      GoogleCredentialsProvider.default
  }

end GcsConfig

object GcsConfig:
  val DefaultEndpoint: String = "https://storage.googleapis.com"

  /** Resumable upload chunks must be a multiple of 256 KiB */
  val ChunkUnit: Int        = 256 * 1024
  val DefaultChunkSize: Int = 8 * 1024 * 1024

  def default: GcsConfig = GcsConfig()
