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
package wvlet.uni.sbt

import sbt.*
import sbt.Keys.*
import xsbti.FileConverter
import wvlet.uni.http.codegen.{HttpCodeGenerator, TastyServiceScanner}

/**
  * sbt 2.x plugin for generating HTTP/RPC client code from Scala 3 traits.
  *
  * Unlike sbt-airframe (which forks a JVM for code generation), this plugin runs the codegen
  * in-process. This is possible because sbt 2.x uses Scala 3 for the metabuild, so the plugin can
  * directly call uni-http-codegen as a library.
  *
  * Usage in build.sbt:
  * {{{
  * lazy val app = project
  *   .enablePlugins(UniHttpCodegenPlugin)
  *   .settings(
  *     uniHttpClients := Seq("com.example.api.UserService:rpc")
  *   )
  *   .dependsOn(api) // project containing the service trait
  * }}}
  */
object UniHttpCodegenPlugin extends AutoPlugin:

  object autoImport:
    val uniHttpClients = settingKey[Seq[String]](
      "Client generation targets. Format: 'fqcn:clientType[:targetPackage]'"
    )
    @transient
    val uniHttpGenerateClient = taskKey[Seq[File]](
      "Generate HTTP client code from service traits"
    )
    val uniHttpCodegenOutDir = settingKey[File](
      "Output directory for generated code"
    )

  import autoImport.*

  override def requires: Plugins = sbt.plugins.JvmPlugin
  override def trigger           = noTrigger

  override def projectSettings: Seq[Setting[?]] = Seq(
    uniHttpClients := Seq.empty,
    uniHttpCodegenOutDir := (Compile / sourceManaged).value,

    uniHttpGenerateClient := {
      given FileConverter = fileConverter.value
      val log     = streams.value.log
      val outDir  = uniHttpCodegenOutDir.value
      val clients = uniHttpClients.value

      if clients.isEmpty then
        log.debug("uniHttpClients is empty, skipping code generation")
        Seq.empty
      else
        // Compile dependent projects first to produce .tasty files
        val _ = (Compile / compile).all(dependentProjects).value

        // Collect class directories from dependent projects (for .tasty file lookup)
        val depClassDirs: Seq[java.io.File] =
          (Compile / classDirectory).all(dependentProjects).value
        // Also include the current project's classpath for external JARs
        val classpathPaths: Seq[java.nio.file.Path] =
          depClassDirs.map(_.toPath) ++ Seq((Compile / classDirectory).value.toPath)

        val generated = clients.flatMap { spec =>
          val config    = HttpCodeGenerator.parseConfig(spec)
          val className = config.apiClassName

          // Find the .tasty file in the classpath
          val tastyRelPath = className.replace('.', '/') + ".tasty"
          val tastyFilePath = classpathPaths
            .map(dir => dir.resolve(tastyRelPath))
            .find(java.nio.file.Files.exists(_))

          tastyFilePath match
            case Some(f) =>
              log.info(s"Generating client for ${className} from ${f}")
              val service = TastyServiceScanner.scan(f.toAbsolutePath.toString)
              HttpCodeGenerator.generateAndWrite(service, config, outDir)
            case None =>
              log.error(
                s"Could not find .tasty file for ${className}. " +
                  s"Searched in: ${classpathPaths.mkString(", ")}"
              )
              None
        }
        generated
    },

    // Hook into source generation so generated code is compiled with user code
    Compile / sourceGenerators += uniHttpGenerateClient
  )

  private def dependentProjects: ScopeFilter =
    ScopeFilter(inDependencies(ThisProject, transitive = true, includeRoot = false))

end UniHttpCodegenPlugin
