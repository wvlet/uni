package wvlet.uni.cloud.parquet.npm

import wvlet.uni.io.FileSystem
import wvlet.uni.cloud.AwsCredentials
import wvlet.uni.cloud.storage.AzureConfig
import wvlet.uni.cloud.storage.GcsConfig
import wvlet.uni.cloud.storage.ObjectStore
import wvlet.uni.cloud.storage.ObjectUri
import wvlet.uni.cloud.storage.S3Config

import scala.scalajs.js

/**
  * Builds the object store for a dataset from the JS `storage` option (see `StorageOptions` in
  * parquet.d.ts). Settings are read once, per call; nothing is kept outside the returned store.
  */
private[npm] object JsStorage:

  private def fail(msg: String): Nothing = throw IllegalArgumentException(msg)

  private def obj(o: js.Dynamic, key: String, at: String): Option[js.Dynamic] =
    if o == null || js.isUndefined(o) then
      None
    else
      val v = o.selectDynamic(key)
      if js.isUndefined(v) || v == null then
        None
      else if js.typeOf(v) != "object" then
        fail(s"${at}.${key} must be an object")
      else
        Some(v)

  private def str(o: js.Dynamic, key: String, at: String): Option[String] =
    val v = o.selectDynamic(key)
    if js.isUndefined(v) || v == null then
      None
    else if js.typeOf(v) != "string" then
      fail(s"${at}.${key} must be a string")
    else
      Some(v.asInstanceOf[String])

  private def bool(o: js.Dynamic, key: String, at: String): Option[Boolean] =
    val v = o.selectDynamic(key)
    if js.isUndefined(v) || v == null then
      None
    else if js.typeOf(v) != "boolean" then
      fail(s"${at}.${key} must be a boolean")
    else
      Some(v.asInstanceOf[Boolean])

  /** Browsers have no environment or credential files, so the default chains find nothing there */
  private def needCredentials(at: String, how: String): Nothing = fail(
    s"Pass ${at} credentials (${how}) in browsers, which have no environment or credential files"
  )

  def s3Config(o: js.Dynamic, browser: Boolean): S3Config =
    val at   = "storage.s3"
    var conf = S3Config.default
    str(o, "region", at).foreach(r => conf = conf.withRegion(r))
    str(o, "endpoint", at).foreach(e => conf = conf.withEndpoint(e))
    bool(o, "pathStyle", at).foreach(p => conf = conf.withPathStyle(p))
    val c = o.credentials
    if js.isUndefined(c) || c == null then
      if browser then
        needCredentials(
          at,
          "credentials: { accessKeyId, secretAccessKey, sessionToken } or \"anonymous\""
        )
    else if js.typeOf(c) == "string" then
      if c.asInstanceOf[String] != "anonymous" then
        fail(s"${at}.credentials must be an object or \"anonymous\"")
      conf = conf.noCredentials
    else if js.typeOf(c) == "object" then
      val ca   = s"${at}.credentials"
      val id   = str(c, "accessKeyId", ca).getOrElse(fail(s"${ca}.accessKeyId is missing"))
      val key  = str(c, "secretAccessKey", ca).getOrElse(fail(s"${ca}.secretAccessKey is missing"))
      val cred = str(c, "sessionToken", ca).foldLeft(AwsCredentials(id, key))(_.withSessionToken(_))
      conf = conf.withCredentials(cred)
    else
      fail(s"${at}.credentials must be an object or \"anonymous\"")
    conf

  def gcsConfig(o: js.Dynamic, browser: Boolean): GcsConfig =
    val at   = "storage.gcs"
    var conf = GcsConfig.default
    str(o, "endpoint", at).foreach(e => conf = conf.withEndpoint(e))
    val token     = str(o, "accessToken", at)
    val json      = str(o, "credentialsJson", at)
    val anonymous = bool(o, "anonymous", at).getOrElse(false)
    if Seq(token.isDefined, json.isDefined, anonymous).count(identity) > 1 then
      fail(s"${at} takes one of accessToken, credentialsJson, or anonymous")
    token.foreach(t => conf = conf.withAccessToken(t))
    json.foreach { j =>
      if browser then
        // The key exchange is a sync HTTP call, and the page would expose the private key
        fail(s"${at}.credentialsJson works only in Node.js. In browsers, pass an accessToken")
      conf = conf.withCredentialsJson(j)
    }
    if anonymous then
      conf = conf.noCredentials
    if browser && token.isEmpty && !anonymous then
      needCredentials(at, "accessToken, or anonymous: true")
    conf

  def azureConfig(o: js.Dynamic, browser: Boolean): AzureConfig =
    val at         = "storage.azure"
    val connection = str(o, "connectionString", at)
    var conf       = connection.map(AzureConfig.fromConnectionString).getOrElse(AzureConfig.default)
    str(o, "account", at).foreach(a => conf = conf.withAccount(a))
    str(o, "endpoint", at).foreach(e => conf = conf.withEndpoint(e))
    val sas       = str(o, "sasToken", at)
    val shared    = obj(o, "sharedKey", at)
    val anonymous = bool(o, "anonymous", at).getOrElse(false)
    if Seq(sas.isDefined, shared.isDefined, anonymous).count(identity) > 1 then
      fail(s"${at} takes one of sasToken, sharedKey, or anonymous")
    sas.foreach(t => conf = conf.withSasToken(t))
    shared.foreach { k =>
      val ka   = s"${at}.sharedKey"
      val name = str(k, "accountName", ka).getOrElse(fail(s"${ka}.accountName is missing"))
      val key  = str(k, "accountKey", ka).getOrElse(fail(s"${ka}.accountKey is missing"))
      conf = conf.withSharedKey(name, key)
    }
    if anonymous then
      conf = conf.noCredentials
    if browser && connection.isEmpty && sas.isEmpty && shared.isEmpty && !anonymous then
      needCredentials(at, "sasToken, sharedKey, connectionString, or anonymous: true")
    conf

  /** The store for an object URI, configured by `storage.<scheme>` */
  def storeFor(uri: String, storage: js.Dynamic): ObjectStore =
    val browser = FileSystem.isBrowser
    val empty   = js.Dynamic.literal()
    ObjectUri.parse(uri).scheme match
      case "s3" =>
        ObjectStore.s3(s3Config(obj(storage, "s3", "storage").getOrElse(empty), browser))
      case "gs" =>
        ObjectStore.gcs(gcsConfig(obj(storage, "gcs", "storage").getOrElse(empty), browser))
      case "az" | "azure" =>
        ObjectStore.azure(azureConfig(obj(storage, "azure", "storage").getOrElse(empty), browser))
      case other =>
        fail(s"Unsupported scheme ${other}:// in ${uri}. Use s3://, gs://, az://, or a local path")

end JsStorage
