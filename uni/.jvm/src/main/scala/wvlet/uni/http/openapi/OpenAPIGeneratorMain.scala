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
package wvlet.uni.http.openapi

import wvlet.uni.http.router.Router

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
  * Write an OpenAPI document for routers in the application's classpath. sbt-uni's `uniOpenAPI`
  * task runs this in a forked JVM, so the build never loads application classes.
  *
  * Arguments: `--router <spec>` (repeatable), `--out <file>`, and optionally `--title`,
  * `--version`, `--schema-package-prefix`, and `--format yaml|json` (default: yaml). A router spec
  * names a Scala object member that returns a [[Router]], e.g. `com.example.Api.router`, or an
  * object that is itself a `Router`.
  */
object OpenAPIGeneratorMain:

  def main(args: Array[String]): Unit = run(args, Thread.currentThread().getContextClassLoader)

  /** Run with an explicit class loader for the routers, e.g. a test's own loader */
  def run(args: Array[String], loader: ClassLoader): Unit =
    val opts  = parse(args.toList)
    val out   = opts.get("out").flatMap(_.headOption).getOrElse(usage("--out is required"))
    val specs = opts.getOrElse("router", Nil)
    if specs.isEmpty then
      usage("--router is required")
    val config      = OpenAPIConfig()
    val withTitle   = opts.get("title").flatMap(_.headOption).fold(config)(config.withTitle)
    val withVersion =
      opts.get("version").flatMap(_.headOption).fold(withTitle)(withTitle.withVersion)
    val finalConfig =
      opts
        .get("schema-package-prefix")
        .flatMap(_.headOption)
        .fold(withVersion)(withVersion.withSchemaPackagePrefix)
    val openAPI = OpenAPI.of(specs.map(loadRouter(_, loader)), finalConfig)
    val text    =
      opts.get("format").flatMap(_.headOption).getOrElse("yaml") match
        case "yaml" =>
          openAPI.toYAML
        case "json" =>
          openAPI.toJSON
        case other =>
          usage(s"Unknown --format: ${other}")
    val path = Path.of(out)
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.writeString(path, text + "\n", StandardCharsets.UTF_8)

  end run

  private def parse(args: List[String]): Map[String, List[String]] =
    args
      .grouped(2)
      .map {
        case List(k, v) if k.startsWith("--") =>
          k.stripPrefix("--") -> v
        case other =>
          usage(s"Invalid arguments: ${other.mkString(" ")}")
      }
      .toList
      .groupMap(_._1)(_._2)

  /** Resolve `pkg.Obj` (an object that is a Router) or `pkg.Obj.member` (a member returning one) */
  private[openapi] def loadRouter(spec: String, loader: ClassLoader): Router =
    def moduleOf(name: String): Option[Any] =
      try
        Some(Class.forName(s"${name}$$", true, loader).getField("MODULE$").get(null))
      catch
        case _: ClassNotFoundException | _: NoSuchFieldException =>
          None
    moduleOf(spec) match
      case Some(r: Router) =>
        r
      case Some(other) =>
        usage(s"${spec} is not a Router: ${other.getClass.getName}")
      case None =>
        val i = spec.lastIndexOf('.')
        if i <= 0 then
          usage(s"Router not found: ${spec}")
        val (objName, member) = (spec.substring(0, i), spec.substring(i + 1))
        val module            = moduleOf(objName).getOrElse(usage(s"Object not found: ${objName}"))
        val method            =
          try
            module.getClass.getMethod(member)
          catch
            case _: NoSuchMethodException =>
              usage(s"${objName} has no member ${member}")
        method.invoke(module) match
          case r: Router =>
            r
          case other =>
            usage(s"${spec} is not a Router: ${other}")

  end loadRouter

  private def usage(message: String): Nothing = throw IllegalArgumentException(message)

end OpenAPIGeneratorMain
