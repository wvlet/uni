package wvlet.uni.cloud.plazma

import wvlet.uni.cloud.parquet.ByteSource
import wvlet.uni.cloud.storage.AzureConfig
import wvlet.uni.cloud.storage.GcsConfig
import wvlet.uni.cloud.storage.ObjectStore
import wvlet.uni.cloud.storage.ObjectUri
import wvlet.uni.cloud.storage.S3Config
import wvlet.uni.http.Http
import wvlet.uni.http.HttpClientConfig
import wvlet.uni.io.IOPath

/**
  * Reads and writes MPC1 files, the column-oriented MessagePack format of PlazmaDB partitions.
  *
  * An MPC1 file stores each column as its own compressed block of MessagePack values, one per row,
  * so rows of any shape (schema-less maps) are stored by column. See [[MPC1File]] for reading and
  * [[MPC1Writer]] for writing.
  *
  * {{{
  * val writer = MPC1.newWriter()
  * writer.addRow("time" -> LongValue(1700000000L), "user" -> StringValue("a"))
  * val bytes = writer.toByteArray
  *
  * val file = MPC1.open(bytes)
  * file.readRows().foreach(println)   // {"time":1700000000,"user":"a"}
  * }}}
  *
  * Reading works on every platform. Writing gzip files (the default) needs a gzip codec, available
  * on the JVM, Scala Native, and Node.js but not in browsers.
  */
object MPC1:
  def open(path: IOPath): MPC1File       = MPC1File.open(ByteSource.ofFile(path))
  def open(path: String): MPC1File       = open(IOPath.parse(path))
  def open(bytes: Array[Byte]): MPC1File = MPC1File.open(ByteSource.ofBytes(bytes))
  def open(source: ByteSource): MPC1File = MPC1File.open(source)

  /** Opens a file over HTTP range requests */
  def openUrl(url: String, config: HttpClientConfig = Http.client): MPC1File = open(
    ByteSource.ofUrl(url, config)
  )

  def open(store: ObjectStore, uri: ObjectUri): MPC1File = open(ByteSource.ofObject(store, uri))

  def openS3(uri: String, config: S3Config = S3Config.default): MPC1File = open(
    ByteSource.ofS3(uri, config)
  )

  def openGcs(uri: String, config: GcsConfig = GcsConfig.default): MPC1File = open(
    ByteSource.ofGcs(uri, config)
  )

  def openAzure(uri: String, config: AzureConfig = AzureConfig.default): MPC1File = open(
    ByteSource.ofAzure(uri, config)
  )

  def newWriter(options: MPC1WriteOptions = MPC1WriteOptions.default): MPC1Writer = MPC1Writer(
    options
  )

end MPC1
