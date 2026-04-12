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
package wvlet.uni.io

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js.typedarray.Uint8Array

/**
  * Node.js zlib facade for raw deflate/inflate (used by ZIP format).
  */
@js.native
@JSImport("zlib", JSImport.Namespace)
private[io] object NodeZlibRawModule extends js.Object:
  def deflateRawSync(buffer: Uint8Array): Uint8Array = js.native
  def inflateRawSync(buffer: Uint8Array): Uint8Array = js.native

/**
  * Node.js Buffer facade for binary data manipulation.
  */
@js.native
private[io] trait NodeBuffer extends js.Object:
  val length: Int                                              = js.native
  def writeUInt32LE(value: Double, offset: Int): Int           = js.native
  def writeUInt16LE(value: Int, offset: Int): Int              = js.native
  def readUInt32LE(offset: Int): Double                        = js.native
  def readUInt16LE(offset: Int): Int                           = js.native
  def subarray(start: Int, end: Int): NodeBuffer               = js.native
  def toString(encoding: String, start: Int, end: Int): String = js.native

@js.native
@JSGlobal("Buffer")
private[io] object NodeBufferFactory extends js.Object:
  def alloc(size: Int): NodeBuffer                    = js.native
  def concat(list: js.Array[js.Any]): NodeBuffer      = js.native
  def from(data: Uint8Array): NodeBuffer              = js.native
  def from(str: String, encoding: String): NodeBuffer = js.native

/**
  * Scala.js (Node.js) implementation of zip archive operations.
  *
  * Uses Node's zlib module for raw deflate/inflate compression and Buffer for binary data
  * manipulation to implement the ZIP file format directly. Archives and individual entries are
  * loaded into memory, so this implementation is limited to archives that fit in available heap
  * (typically under 2GB). For streaming support, use the JVM or Native platforms.
  *
  * Browser environments throw UnsupportedOperationException.
  */
trait ZipCompat extends ZipApi:
  private val LocalFileHeaderSig = 0x04034b50.toDouble
  private val CentralDirSig      = 0x02014b50.toDouble
  private val EndOfCentralDirSig = 0x06054b50.toDouble
  private val FlagUtf8       = 0x800 // General purpose bit flag: UTF-8 filename encoding (bit 11)
  private val MethodStored   = 0
  private val MethodDeflated = 8
  private val VersionNeeded  = 20
  private val MaxEOCDSearchOffset = 22 + 65535 // EOCD size + max comment length

  private def requireNode(): Unit =
    if FileSystem.isBrowser then
      throw UnsupportedOperationException(
        "Zip operations are not supported in browser environments"
      )

  override def create(target: IOPath, sources: Seq[IOPath]): Unit =
    requireNode()

    val resolvedTarget = NodePathModule.resolve(target.path)
    val targetDir      = NodePathModule.dirname(resolvedTarget)
    NodeFSModule.mkdirSync(targetDir, js.Dynamic.literal(recursive = true))

    val filesToAdd = collectFiles(sources, resolvedTarget)
    val buffers    = js.Array[js.Any]()

    case class EntryRecord(
        name: String,
        localOffset: Int,
        crc: Double,
        compressedSize: Int,
        uncompressedSize: Int,
        method: Int,
        dosTime: Int,
        dosDate: Int
    )
    val entries = scala.collection.mutable.ArrayBuffer[EntryRecord]()
    var offset  = 0

    for (entryName, sourcePath) <- filesToAdd do
      val isDir = entryName.endsWith("/")

      val stats = NodeFSModule.statSync(sourcePath)

      // Keep compressed data as Uint8Array to avoid extra copies
      val (uncompressedSize, crc, compressedData, method) =
        if isDir then
          (0, 0.0, Uint8Array(0), MethodStored)
        else
          val raw      = NodeFSModule.readFileSync(sourcePath)
          val bytes    = uint8ArrayToByteArray(raw)
          val crcValue = toUnsigned(CRC32.compute(bytes))
          if bytes.isEmpty then
            (0, crcValue, Uint8Array(0), MethodStored)
          else
            val deflated = NodeZlibRawModule.deflateRawSync(raw)
            (bytes.length, crcValue, deflated, MethodDeflated)

      val (dosTime, dosDate) = epochMillisToDosDateTime(stats.mtimeMs)

      // Local file header
      val nameBytes = NodeBufferFactory.from(entryName, "utf8")
      val header    = NodeBufferFactory.alloc(30)
      header.writeUInt32LE(LocalFileHeaderSig, 0)
      header.writeUInt16LE(VersionNeeded, 4)
      header.writeUInt16LE(FlagUtf8, 6)
      header.writeUInt16LE(method, 8)
      header.writeUInt16LE(dosTime, 10)
      header.writeUInt16LE(dosDate, 12)
      header.writeUInt32LE(crc, 14)
      header.writeUInt32LE(compressedData.length.toDouble, 18)
      header.writeUInt32LE(uncompressedSize.toDouble, 22)
      header.writeUInt16LE(nameBytes.length, 26)
      header.writeUInt16LE(0, 28)

      buffers.push(header)
      buffers.push(nameBytes)
      buffers.push(NodeBufferFactory.from(compressedData))

      entries +=
        EntryRecord(
          name = entryName,
          localOffset = offset,
          crc = crc,
          compressedSize = compressedData.length,
          uncompressedSize = uncompressedSize,
          method = method,
          dosTime = dosTime,
          dosDate = dosDate
        )
      offset += 30 + nameBytes.length + compressedData.length
    end for

    // Central directory
    val cdStart = offset
    for entry <- entries do
      val nameBytes = NodeBufferFactory.from(entry.name, "utf8")
      val cdEntry   = NodeBufferFactory.alloc(46)
      cdEntry.writeUInt32LE(CentralDirSig, 0)
      cdEntry.writeUInt16LE(VersionNeeded, 4)
      cdEntry.writeUInt16LE(VersionNeeded, 6)
      cdEntry.writeUInt16LE(FlagUtf8, 8)
      cdEntry.writeUInt16LE(entry.method, 10)
      cdEntry.writeUInt16LE(entry.dosTime, 12)
      cdEntry.writeUInt16LE(entry.dosDate, 14)
      cdEntry.writeUInt32LE(entry.crc, 16)
      cdEntry.writeUInt32LE(entry.compressedSize.toDouble, 20)
      cdEntry.writeUInt32LE(entry.uncompressedSize.toDouble, 24)
      cdEntry.writeUInt16LE(nameBytes.length, 28)
      cdEntry.writeUInt16LE(0, 30)
      cdEntry.writeUInt16LE(0, 32)
      cdEntry.writeUInt16LE(0, 34)
      cdEntry.writeUInt16LE(0, 36)
      cdEntry.writeUInt32LE(0, 38)
      cdEntry.writeUInt32LE(entry.localOffset.toDouble, 42)

      buffers.push(cdEntry)
      buffers.push(nameBytes)
      offset += 46 + nameBytes.length

    val cdSize = offset - cdStart

    // End of central directory
    val eocd = NodeBufferFactory.alloc(22)
    eocd.writeUInt32LE(EndOfCentralDirSig, 0)
    eocd.writeUInt16LE(0, 4)
    eocd.writeUInt16LE(0, 6)
    eocd.writeUInt16LE(entries.size, 8)
    eocd.writeUInt16LE(entries.size, 10)
    eocd.writeUInt32LE(cdSize.toDouble, 12)
    eocd.writeUInt32LE(cdStart.toDouble, 16)
    eocd.writeUInt16LE(0, 20)
    buffers.push(eocd)

    val result = NodeBufferFactory.concat(buffers)
    NodeFSModule.writeFileSync(target.path, result.asInstanceOf[Uint8Array], js.Dynamic.literal())

  end create

  override def extract(archive: IOPath, destination: IOPath): Unit =
    requireNode()
    NodeFSModule.mkdirSync(destination.path, js.Dynamic.literal(recursive = true))

    val data         = NodeBufferFactory.from(NodeFSModule.readFileSync(archive.path))
    val eocdOffset   = findEOCD(data)
    val totalEntries = data.readUInt16LE(eocdOffset + 10)
    val cdOffset     = data.readUInt32LE(eocdOffset + 16).toInt

    var pos = cdOffset
    var i   = 0
    while i < totalEntries do
      val method         = data.readUInt16LE(pos + 10)
      val compressedSize = data.readUInt32LE(pos + 20).toInt
      val nameLength     = data.readUInt16LE(pos + 28)
      val extraLength    = data.readUInt16LE(pos + 30)
      val commentLength  = data.readUInt16LE(pos + 32)
      val localOffset    = data.readUInt32LE(pos + 42).toInt
      val name           = data.toString("utf8", pos + 46, pos + 46 + nameLength)

      val localNameLen  = data.readUInt16LE(localOffset + 26)
      val localExtraLen = data.readUInt16LE(localOffset + 28)
      val dataOffset    = localOffset + 30 + localNameLen + localExtraLen

      // Guard against zip slip — use IOPath segment-based startsWith
      val destIOPath  = destination.normalize
      val entryIOPath = destIOPath.resolve(name).normalize
      if !entryIOPath.startsWith(destIOPath) then
        throw IOOperationException(s"Zip entry outside target directory: ${name}")
      val entryPath = entryIOPath.path

      if name.endsWith("/") then
        NodeFSModule.mkdirSync(entryPath, js.Dynamic.literal(recursive = true))
      else
        val parentDir = NodePathModule.dirname(entryPath)
        NodeFSModule.mkdirSync(parentDir, js.Dynamic.literal(recursive = true))

        val compressedData = data.subarray(dataOffset, dataOffset + compressedSize)
        val fileData       =
          if method == MethodStored then
            compressedData
          else if method == MethodDeflated then
            NodeZlibRawModule.inflateRawSync(compressedData.asInstanceOf[Uint8Array])
          else
            throw IOOperationException(s"Unsupported compression method: ${method}")

        NodeFSModule.writeFileSync(
          entryPath,
          fileData.asInstanceOf[Uint8Array],
          js.Dynamic.literal()
        )

      pos += 46 + nameLength + extraLength + commentLength
      i += 1
    end while

  end extract

  override def list(archive: IOPath): Seq[ZipEntry] =
    requireNode()

    val data         = NodeBufferFactory.from(NodeFSModule.readFileSync(archive.path))
    val eocdOffset   = findEOCD(data)
    val totalEntries = data.readUInt16LE(eocdOffset + 10)
    val cdOffset     = data.readUInt32LE(eocdOffset + 16).toInt

    val result = scala.collection.mutable.ArrayBuffer[ZipEntry]()
    var pos    = cdOffset
    var i      = 0
    while i < totalEntries do
      val compressedSize   = data.readUInt32LE(pos + 20).toLong
      val uncompressedSize = data.readUInt32LE(pos + 24).toLong
      val nameLength       = data.readUInt16LE(pos + 28)
      val extraLength      = data.readUInt16LE(pos + 30)
      val commentLength    = data.readUInt16LE(pos + 32)
      val name             = data.toString("utf8", pos + 46, pos + 46 + nameLength)
      val dosTime          = data.readUInt16LE(pos + 12)
      val dosDate          = data.readUInt16LE(pos + 14)
      val lastModified     = dosDateTimeToEpochMillis(dosTime, dosDate)

      result +=
        ZipEntry(
          name = name,
          size = uncompressedSize,
          compressedSize = compressedSize,
          isDirectory = name.endsWith("/"),
          lastModified = lastModified
        )

      pos += 46 + nameLength + extraLength + commentLength
      i += 1

    result.toSeq

  end list

  private def findEOCD(data: NodeBuffer): Int =
    var pos = data.length - 22
    while pos >= math.max(0, data.length - MaxEOCDSearchOffset) do
      if data.readUInt32LE(pos) == EndOfCentralDirSig then
        return pos
      pos -= 1
    throw IOOperationException("Invalid zip archive: end of central directory not found")

  private def collectFiles(sources: Seq[IOPath], excludePath: String): Seq[(String, String)] =
    val files = scala.collection.mutable.ArrayBuffer[(String, String)]()
    for source <- sources do
      val path = source.path
      if NodeFSModule.statSync(path).isDirectory() then
        val baseName = NodePathModule.basename(path)
        collectDirectory(files, path, baseName, excludePath)
      else if NodePathModule.resolve(path) != excludePath then
        files += ((NodePathModule.basename(path), path))
    files.toSeq

  private def collectDirectory(
      files: scala.collection.mutable.ArrayBuffer[(String, String)],
      dirPath: String,
      prefix: String,
      excludePath: String
  ): Unit =
    files += ((s"${prefix}/", dirPath))
    val entries = NodeFSModule.readdirSync(dirPath, js.Dynamic.literal(withFileTypes = true))
    for entry <- entries do
      val dirent    = entry.asInstanceOf[NodeDirent]
      val childPath = NodePathModule.join(dirPath, dirent.name)
      val childName = s"${prefix}/${dirent.name}"
      if dirent.isDirectory() then
        collectDirectory(files, childPath, childName, excludePath)
      else if NodePathModule.resolve(childPath) != excludePath then
        files += ((childName, childPath))

  private def epochMillisToDosDateTime(millis: Double): (Int, Int) =
    val d    = new js.Date(millis)
    val time =
      ((d.getHours().toInt << 11) | (d.getMinutes().toInt << 5) | (d.getSeconds().toInt / 2))
    val date =
      (((d.getFullYear().toInt - 1980) << 9) | ((d.getMonth().toInt + 1) << 5) | d.getDate().toInt)
    (time, date)

  private def dosDateTimeToEpochMillis(dosTime: Int, dosDate: Int): Long =
    val year    = ((dosDate >> 9) & 0x7f) + 1980
    val month   = ((dosDate >> 5) & 0x0f) - 1 // JS Date months are 0-based
    val day     = dosDate & 0x1f
    val hours   = (dosTime >> 11) & 0x1f
    val minutes = (dosTime >> 5) & 0x3f
    val seconds = (dosTime & 0x1f) * 2
    val d       = new js.Date(year, month, day, hours, minutes, seconds)
    d.getTime().toLong

  private def toUnsigned(value: Int): Double = (value.toLong & 0xffffffffL).toDouble

  private def uint8ArrayToByteArray(uint8: Uint8Array): Array[Byte] =
    Int8Array(uint8.buffer, uint8.byteOffset, uint8.length).toArray

end ZipCompat
