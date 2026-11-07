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

/**
  * Generate HTTP clients for service traits in the application's classpath. sbt-uni's
  * `uniHttpClients` runs this in a forked JVM, so the generator is the uni version the app depends
  * on, not the one sbt's metabuild can load.
  *
  * Arguments: `--out <dir>` and `--client <spec>` (repeatable), where a spec is
  * `TraitName:clientType[:targetPackage]` (see [[HttpCodeGenerator.parseConfig]]).
  */
object HttpCodeGeneratorMain:

  def main(args: Array[String]): Unit =
    // The logger writes to stderr, which sbt shows as errors; report generated files on stdout
    Logger.setDefaultLogLevel(LogLevel.WARN)
    run(args, Thread.currentThread().getContextClassLoader).foreach(f =>
      println(s"Generated ${f.getPath}")
    )

  /**
    * Run with an explicit class loader for the service traits, e.g. a test's own loader. Returns
    * the files whose content changed.
    */
  def run(args: Array[String], loader: ClassLoader): Seq[File] =
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
    clients.flatMap(spec => HttpCodeGenerator.run(spec, loader, File(out)))

  private def usage(message: String): Nothing = throw IllegalArgumentException(message)

end HttpCodeGeneratorMain
