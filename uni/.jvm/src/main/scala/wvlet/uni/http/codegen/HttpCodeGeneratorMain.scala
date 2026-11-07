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

import wvlet.uni.log.{LogLevel, Logger}

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
  * Generate HTTP clients for service traits in the application's classpath. sbt-uni's
  * `uniHttpClients` runs this in a forked JVM, so the generator is the uni version the app depends
  * on, not the one sbt's metabuild can load.
  *
  * Arguments: `--out <dir>`, `--client <spec>` (repeatable), where a spec is
  * `TraitName:clientType[:targetPackage]` (see [[HttpCodeGenerator.parseConfig]]), and optionally
  * `--manifest <file>`, which receives the path of every client file, one per line, changed or not.
  */
object HttpCodeGeneratorMain:

  def main(args: Array[String]): Unit =
    // The logger writes to stderr, which sbt shows as errors; report generated files on stdout
    Logger.setDefaultLogLevel(LogLevel.WARN)
    run(args, Thread.currentThread().getContextClassLoader)

  /** Run with an explicit class loader for the service traits, e.g. a test's own loader */
  def run(args: Array[String], loader: ClassLoader): Unit =
    val opts =
      args
        .toList
        .grouped(2)
        .map {
          case List(k, v) if k.startsWith("--") =>
            k.stripPrefix("--") -> v
          case other =>
            usage(s"Invalid arguments: ${other.mkString(" ")}")
        }
        .toList
        .groupMap(_._1)(_._2)
    val out     = opts.get("out").flatMap(_.headOption).getOrElse(usage("--out is required"))
    val clients = opts.getOrElse("client", Nil)
    if clients.isEmpty then
      usage("--client is required")
    val files = clients.map { spec =>
      val config  = HttpCodeGenerator.parseConfig(spec)
      val service = ServiceScanner.scan(config.apiClassName, loader)
      val file    = HttpCodeGenerator.outputFileOf(service, config, File(out))
      if HttpCodeGenerator.generateAndWrite(service, config, File(out)).isDefined then
        println(s"Generated ${file.getPath}")
      file
    }
    opts
      .get("manifest")
      .flatMap(_.headOption)
      .foreach { m =>
        val path = File(m).toPath
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.writeString(path, files.map(_.getAbsolutePath).mkString("\n"), StandardCharsets.UTF_8)
      }

  end run

  private def usage(message: String): Nothing = throw IllegalArgumentException(message)

end HttpCodeGeneratorMain
