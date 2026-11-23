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
package wvlet.uni.cloud.storage

import wvlet.uni.cloud.*
import wvlet.uni.util.Sha256

import wvlet.uni.http.Http
import wvlet.uni.http.HttpClientConfig

/**
  * Settings for an S3 or S3-compatible store.
  *
  * @param region
  *   the signing region. When unset: `AWS_REGION`, `AWS_DEFAULT_REGION`, the profile's `region` in
  *   `~/.aws/config`, then `us-east-1`
  * @param endpoint
  *   the service URL, such as `http://localhost:9000` for MinIO. When unset, AWS S3 in `region`
  * @param pathStyle
  *   address buckets as `endpoint/bucket/key` instead of `bucket.endpoint/key`
  * @param partSizeBytes
  *   the multipart upload part size for [[ObjectStore.newOutputStream]] (at least 5 MiB)
  */
case class S3Config(
    region: Option[String] = None,
    endpoint: Option[String] = None,
    pathStyle: Boolean = false,
    credentials: AwsCredentialsProvider = AwsCredentialsProvider.default,
    partSizeBytes: Int = S3Config.DefaultPartSize,
    httpClient: HttpClientConfig = Http.client
):
  def withRegion(region: String): S3Config      = copy(region = Some(region))
  def noRegion: S3Config                        = copy(region = None)
  def withEndpoint(endpoint: String): S3Config  = copy(endpoint = Some(endpoint.stripSuffix("/")))
  def noEndpoint: S3Config                      = copy(endpoint = None)
  def withPathStyle(enabled: Boolean): S3Config = copy(pathStyle = enabled)

  def withCredentials(provider: AwsCredentialsProvider): S3Config = copy(credentials = provider)

  def withCredentials(accessKeyId: String, secretAccessKey: String): S3Config = withCredentials(
    AwsCredentialsProvider.static(accessKeyId, secretAccessKey)
  )

  def withCredentials(credentials: AwsCredentials): S3Config = withCredentials(
    AwsCredentialsProvider.static(credentials)
  )

  /** Sends unsigned requests, for public buckets */
  def noCredentials: S3Config = withCredentials(AwsCredentialsProvider.anonymous)

  def withPartSizeBytes(bytes: Int): S3Config =
    if bytes < S3Config.MinPartSize then
      throw IllegalArgumentException(
        s"S3 multipart parts must be at least ${S3Config.MinPartSize} bytes: ${bytes}"
      )
    copy(partSizeBytes = bytes)

  def withHttpClient(config: HttpClientConfig): S3Config = copy(httpClient = config)

  /** The region used for signing */
  def resolvedRegion: String = region
    .orElse(CloudEnv.get("AWS_REGION"))
    .orElse(CloudEnv.get("AWS_DEFAULT_REGION"))
    .orElse(AwsProfiles.region())
    .getOrElse("us-east-1")

end S3Config

object S3Config:
  val MinPartSize: Int     = 5 * 1024 * 1024
  val DefaultPartSize: Int = 8 * 1024 * 1024

  def default: S3Config = S3Config()

  /** Cloudflare R2. Create an R2 API token to get S3 access keys. */
  def r2(accountId: String): S3Config = S3Config()
    .withEndpoint(s"https://${accountId}.r2.cloudflarestorage.com")
    .withRegion("auto")
    .withPathStyle(true)

  /** Google Cloud Storage through its S3-compatible XML API, with HMAC keys */
  def gcs: S3Config = S3Config()
    .withEndpoint("https://storage.googleapis.com")
    .withRegion("auto")
    .withPathStyle(true)

  /** MinIO or another self-hosted S3-compatible server */
  def minio(endpoint: String): S3Config = S3Config()
    .withEndpoint(endpoint)
    .withRegion("us-east-1")
    .withPathStyle(true)
