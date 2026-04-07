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
import wvlet.uni.http.codegen.{HttpCodeGenerator, ServiceScanner}

/**
  * sbt 2.x plugin for generating HTTP/RPC client code from Scala 3 traits.
  *
  * Unlike sbt-airframe (which forks a JVM for code generation), this plugin runs the codegen
  * in-process. This is possible because sbt 2.x uses Scala 3 for the metabuild, so the plugin can
  * directly call uni's codegen as a library.
  *
  * Usage in build.sbt:
  * {{{
  * lazy val app = project
  *   .enablePlugins(UniPlugin)
  *   .settings(
  *     uniHttpClients := Seq("com.example.api.UserService:rpc")
  *   )
  *   .dependsOn(api) // project containing the service trait
  * }}}
  */
object UniPlugin extends AutoPlugin:

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
        // Compile dependent projects first to produce .class files
        val _ = (Compile / compile).all(dependentProjects).value

        // Build a classloader from dependent project class dirs + dependency JARs
        val depClassDirs: Seq[java.io.File] =
          (Compile / classDirectory).all(dependentProjects).value
        val depClasspath: Seq[java.nio.file.Path] =
          (Compile / dependencyClasspath).value.files
        val allUrls =
          (depClassDirs.map(_.toURI.toURL) ++ depClasspath.map(_.toUri.toURL)).toArray
        val classLoader = java.net.URLClassLoader(allUrls, getClass.getClassLoader)

        val generated = clients.flatMap { spec =>
          val config    = HttpCodeGenerator.parseConfig(spec)
          val className = config.apiClassName
          log.info(s"Generating client for ${className}")
          val service = ServiceScanner.scan(className, classLoader)
          HttpCodeGenerator.generateAndWrite(service, config, outDir)
        }
        generated
    },
    // Hook into source generation so generated code is compiled with user code
    Compile / sourceGenerators += uniHttpGenerateClient
  )

  private def dependentProjects: ScopeFilter =
    ScopeFilter(inDependencies(ThisProject, transitive = true, includeRoot = false))

end UniPlugin
