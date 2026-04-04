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
package wvlet.uni.http.codegen

import wvlet.uni.http.codegen.client.RPCClientGenerator
import wvlet.uni.log.LogSupport

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

/**
  * Orchestrator for HTTP client code generation. Parses configuration strings, scans .tasty files,
  * and delegates to the appropriate generator (RPC or REST).
  */
object HttpCodeGenerator extends LogSupport:

  /**
    * Parse an sbt setting string into a CodegenConfig.
    *
    * Format: "fully.qualified.TraitName:clientType[:target.package]"
    *
    * Examples:
    *   - "com.example.api.UserService:rpc"
    *   - "com.example.api.UserService:sync:com.example.client"
    *   - "com.example.api.UserApi:both:com.example.client"
    */
  def parseConfig(spec: String): CodegenConfig =
    val parts = spec.split(":")
    if parts.length < 2 then
      throw IllegalArgumentException(
        s"Invalid codegen spec '${spec}'. Expected format: 'TraitName:clientType[:targetPackage]'"
      )

    val apiClassName = parts(0).trim
    val clientType   = ClientType.fromString(parts(1).trim)
    val targetPkg    =
      if parts.length >= 3 then
        Some(parts(2).trim)
      else
        None

    CodegenConfig(apiClassName = apiClassName, clientType = clientType, targetPackage = targetPkg)

  /**
    * Generate client code for a service and write it to the output directory. Uses hash-based
    * caching to skip unchanged files.
    *
    * @param service
    *   Service definition extracted from trait
    * @param config
    *   Code generation configuration
    * @param outputDir
    *   Base output directory (e.g., target/scala-3.x/src_managed/main)
    * @return
    *   Path to the generated file, or None if the file was unchanged
    */
  def generateAndWrite(service: ServiceDef, config: CodegenConfig, outputDir: File): Option[File] =
    val source        = generateSource(service, config)
    val targetPackage = config.resolvedTargetPackage
    val objectName    = s"${service.serviceName}Client"

    // Determine output file path
    val packageDir = targetPackage.replace('.', File.separatorChar)
    val outputFile = Path.of(outputDir.getAbsolutePath, packageDir, s"${objectName}.scala").toFile

    writeIfChanged(outputFile, source)

  /**
    * Generate client source code for a service without writing to disk.
    */
  def generateSource(service: ServiceDef, config: CodegenConfig): String =
    // Currently only RPC is supported (Phase 1)
    RPCClientGenerator.generate(service, config)

  /**
    * Run the full code generation pipeline: parse config, scan .tasty, generate source, write file.
    *
    * @param spec
    *   Configuration string (e.g., "com.example.api.UserService:rpc")
    * @param tastyFilePath
    *   Path to the .tasty file for the service trait
    * @param classpath
    *   Classpath entries for resolving types
    * @param outputDir
    *   Base output directory
    * @return
    *   Generated file path, or None if unchanged
    */
  def run(
      spec: String,
      tastyFilePath: String,
      classpath: List[String],
      outputDir: File
  ): Option[File] =
    val config  = parseConfig(spec)
    val service = TastyServiceScanner.scan(tastyFilePath, classpath)
    info(s"Generating ${config.clientType} client for ${service.fullName}")
    generateAndWrite(service, config, outputDir)

  /**
    * Write file only if content has changed (hash-based caching).
    */
  private def writeIfChanged(file: File, content: String): Option[File] =
    val newHash = sha256(content)

    val needsUpdate =
      if file.exists() then
        val existingContent = Files.readString(file.toPath, StandardCharsets.UTF_8)
        sha256(existingContent) != newHash
      else
        true

    if needsUpdate then
      file.getParentFile.mkdirs()
      Files.writeString(file.toPath, content, StandardCharsets.UTF_8)
      info(s"Generated: ${file.getAbsolutePath}")
      Some(file)
    else
      debug(s"Unchanged: ${file.getAbsolutePath}")
      Some(file)

  private def sha256(s: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(s.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString

end HttpCodeGenerator
