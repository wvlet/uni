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

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry as JZipEntry

/**
  * JVM implementation of zip archive operations using java.util.zip.
  */
trait ZipCompat extends ZipApi:
  private val BufferSize = 8192

  override def create(target: IOPath, sources: Seq[IOPath]): Unit =
    val targetPath = Path.of(target.path)
    val parent     = targetPath.getParent
    if parent != null && !Files.exists(parent) then
      Files.createDirectories(parent)

    val fos = FileOutputStream(target.path)
    val zos = ZipOutputStream(BufferedOutputStream(fos))
    try sources.foreach { source =>
        val sourcePath = Path.of(source.path)
        if Files.isDirectory(sourcePath) then
          addDirectory(zos, sourcePath, sourcePath.getFileName.toString)
        else
          addFile(zos, sourcePath, sourcePath.getFileName.toString)
      }
    finally zos.close()

  override def extract(archive: IOPath, destination: IOPath): Unit =
    val destPath = Path.of(destination.path)
    if !Files.exists(destPath) then
      Files.createDirectories(destPath)

    val fis = FileInputStream(archive.path)
    val zis = ZipInputStream(BufferedInputStream(fis))
    try
      var entry = zis.getNextEntry
      while entry != null do
        val entryPath = destPath.resolve(entry.getName)
        // Guard against zip slip attack
        if !entryPath.normalize().startsWith(destPath.normalize()) then
          throw IOOperationException(s"Zip entry outside target directory: ${entry.getName}")
        if entry.isDirectory then
          Files.createDirectories(entryPath)
        else
          val parent = entryPath.getParent
          if parent != null && !Files.exists(parent) then
            Files.createDirectories(parent)
          val fos    = FileOutputStream(entryPath.toFile)
          val buffer = Array.ofDim[Byte](BufferSize)
          try
            var len = zis.read(buffer)
            while len > 0 do
              fos.write(buffer, 0, len)
              len = zis.read(buffer)
          finally
            fos.close()
        zis.closeEntry()
        entry = zis.getNextEntry
    finally
      zis.close()

  end extract

  override def list(archive: IOPath): Seq[ZipEntry] =
    val zipFile = ZipFile(archive.path)
    try
      val entries = scala.collection.mutable.ArrayBuffer[ZipEntry]()
      val it      = zipFile.entries()
      while it.hasMoreElements do
        val entry = it.nextElement()
        entries +=
          ZipEntry(
            name = entry.getName,
            size = entry.getSize,
            compressedSize = entry.getCompressedSize,
            isDirectory = entry.isDirectory,
            lastModified = entry.getTime
          )
      entries.toSeq
    finally
      zipFile.close()

  private def addFile(zos: ZipOutputStream, file: Path, entryName: String): Unit =
    val entry = JZipEntry(entryName)
    entry.setTime(Files.getLastModifiedTime(file).toMillis)
    zos.putNextEntry(entry)
    val fis    = FileInputStream(file.toFile)
    val buffer = Array.ofDim[Byte](BufferSize)
    try
      var len = fis.read(buffer)
      while len > 0 do
        zos.write(buffer, 0, len)
        len = fis.read(buffer)
    finally
      fis.close()
    zos.closeEntry()

  private def addDirectory(zos: ZipOutputStream, dir: Path, prefix: String): Unit =
    // Add directory entry
    val dirEntry = JZipEntry(s"${prefix}/")
    dirEntry.setTime(Files.getLastModifiedTime(dir).toMillis)
    zos.putNextEntry(dirEntry)
    zos.closeEntry()

    // Add files and subdirectories
    val stream = Files.list(dir)
    try stream.forEach { child =>
        val childName = s"${prefix}/${child.getFileName}"
        if Files.isDirectory(child) then
          addDirectory(zos, child, childName)
        else
          addFile(zos, child, childName)
      }
    finally stream.close()

end ZipCompat
