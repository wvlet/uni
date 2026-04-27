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
package wvlet.uni.cli.launcher

import wvlet.uni.surface.{MethodSurface, Parameter, Surface}

/**
  * Detect a nested configuration class — a structured (non-primitive) parameter that should
  * contribute its own fields as options/arguments rather than being parsed directly.
  */
private[launcher] def isNestedConfigClass(surface: Surface): Boolean =
  !surface.isPrimitive && !surface.isOption && !surface.isSeq && !surface.isArray &&
    surface.params.nonEmpty

/**
  * Schema for command-line options and arguments, built from Surface annotations
  */
trait OptionSchema:
  def options: Seq[CLOption]
  def arguments: Seq[CLArgument]

  /**
    * Find an option by its prefix (e.g., "-v" or "--verbose")
    */
  def findOption(name: String): Option[CLOption] = options.find(_.prefixes.contains(name))

  /**
    * Find a flag option (boolean option that doesn't take argument)
    */
  def findFlagOption(name: String): Option[CLOption] = options.find(o =>
    o.prefixes.contains(name) && !o.takesArgument
  )

  /**
    * Find an option that needs an argument value
    */
  def findOptionNeedsArg(name: String): Option[CLOption] = options.find(o =>
    o.prefixes.contains(name) && o.takesArgument
  )

  /**
    * Find an argument by its position index
    */
  def findArgument(index: Int): Option[CLArgument] = arguments.find(_.index == index)

  /**
    * Find a key-value option by its prefix (e.g., "-D" for -Dkey=value)
    */
  def findKeyValueOption(prefix: String): Option[CLOption] = options.find(o =>
    o.isKeyValue && o.prefixes.exists(p => prefix.startsWith(p))
  )

end OptionSchema

/**
  * Schema built from a class surface (constructor parameters)
  */
class ClassOptionSchema(
    val surface: Surface,
    val options: Seq[CLOption],
    val arguments: Seq[CLArgument]
) extends OptionSchema

object ClassOptionSchema:
  /**
    * Build an option schema from a Surface by reading @option and @argument annotations
    */
  def apply(surface: Surface, argIndexOffset: Int = 0): ClassOptionSchema =
    var argCount         = argIndexOffset
    val optionsBuilder   = Seq.newBuilder[CLOption]
    val argumentsBuilder = Seq.newBuilder[CLArgument]

    for p <- surface.params do
      val optAnnot = p.findAnnotation("option")
      val argAnnot = p.findAnnotation("argument")

      optAnnot match
        case Some(annot) =>
          val prefix   = annot.getAs[String]("prefix").getOrElse("")
          val desc     = annot.getAs[String]("description").getOrElse("")
          val prefixes = splitPrefixes(prefix, p.name)
          optionsBuilder += CLOption(prefixes, desc, Some(p))
        case None =>
          argAnnot match
            case Some(annot) =>
              val name = annot.getAs[String]("name").filter(_.nonEmpty).getOrElse(p.name)
              val desc = annot.getAs[String]("description").getOrElse("")
              argumentsBuilder += CLArgument(argCount, name, desc, Some(p))
              argCount += 1
            case None =>
              // Recursively process nested config classes so their fields become options/arguments
              if isNestedConfigClass(p.surface) then
                val nested = ClassOptionSchema(p.surface, argCount)
                optionsBuilder ++= nested.options
                argumentsBuilder ++= nested.arguments
                argCount += nested.arguments.length

    new ClassOptionSchema(surface, optionsBuilder.result(), argumentsBuilder.result())

  end apply

  /**
    * Split comma-separated prefixes and validate them. If no prefix is given, generate from
    * parameter name.
    */
  private def splitPrefixes(prefix: String, paramName: String): Seq[String] =
    if prefix.isEmpty then
      // Generate default prefix from parameter name
      Seq(s"--${toKebabCase(paramName)}")
    else
      prefix
        .split(",")
        .toSeq
        .map(_.trim)
        .filter(_.nonEmpty)
        .map { p =>
          if !p.startsWith("-") then
            throw IllegalArgumentException(s"Invalid prefix '${p}' (must start with - or --)")
          p
        }

  private def toKebabCase(name: String): String =
    name.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase

end ClassOptionSchema

/**
  * Schema built from a method surface (method parameters)
  */
class MethodOptionSchema(
    val method: MethodSurface,
    val options: Seq[CLOption],
    val arguments: Seq[CLArgument]
) extends OptionSchema

object MethodOptionSchema:
  /**
    * Build an option schema from a MethodSurface by reading @option and @argument annotations from
    * method parameters
    */
  def apply(method: MethodSurface): MethodOptionSchema =
    var argCount         = 0
    val optionsBuilder   = Seq.newBuilder[CLOption]
    val argumentsBuilder = Seq.newBuilder[CLArgument]

    for p <- method.args do
      val optAnnot = p.findAnnotation("option")
      val argAnnot = p.findAnnotation("argument")

      optAnnot match
        case Some(annot) =>
          val prefix   = annot.getAs[String]("prefix").getOrElse("")
          val desc     = annot.getAs[String]("description").getOrElse("")
          val prefixes = splitPrefixes(prefix, p.name)
          optionsBuilder += CLOption(prefixes, desc, Some(p))
        case None =>
          argAnnot match
            case Some(annot) =>
              val name = annot.getAs[String]("name").filter(_.nonEmpty).getOrElse(p.name)
              val desc = annot.getAs[String]("description").getOrElse("")
              argumentsBuilder += CLArgument(argCount, name, desc, Some(p))
              argCount += 1
            case None =>
              if isNestedConfigClass(p.surface) then
                // Recursively expose nested config class options/arguments
                val nested = ClassOptionSchema(p.surface, argCount)
                optionsBuilder ++= nested.options
                argumentsBuilder ++= nested.arguments
                argCount += nested.arguments.length
              else
                // Plain parameters are treated as positional arguments
                argumentsBuilder += CLArgument(argCount, p.name, "", Some(p))
                argCount += 1

    val options   = optionsBuilder.result()
    val arguments = argumentsBuilder.result()

    // Detect collisions early — flattened nested-config options share the OptionParser's
    // parameter-name namespace, and OptionParser.findOption binds each prefix to the first
    // CLOption, so duplicates would silently overwrite each other or be unreachable.
    val dupNames = (options.flatMap(_.param) ++ arguments.flatMap(_.param))
      .groupBy(_.name)
      .collect {
        case (n, ps) if ps.size > 1 =>
          n
      }
      .toSeq
      .sorted
    val dupPrefixes =
      options
        .flatMap(_.prefixes)
        .groupBy(identity)
        .collect {
          case (p, ps) if ps.size > 1 =>
            p
        }
        .toSeq
        .sorted
    if dupNames.nonEmpty || dupPrefixes.nonEmpty then
      val parts = Seq(
        Option.when(dupNames.nonEmpty)(s"field names: ${dupNames.mkString(", ")}"),
        Option.when(dupPrefixes.nonEmpty)(s"option prefixes: ${dupPrefixes.mkString(", ")}")
      ).flatten.mkString("; ")
      throw IllegalArgumentException(
        s"Conflicting parameters in command '${method.name}' (${parts}). " +
          "Methods cannot mix nested config classes that expose duplicate option flags or field names."
      )

    new MethodOptionSchema(method, options, arguments)

  end apply

  private def splitPrefixes(prefix: String, paramName: String): Seq[String] =
    if prefix.isEmpty then
      Seq(s"--${toKebabCase(paramName)}")
    else
      prefix
        .split(",")
        .toSeq
        .map(_.trim)
        .filter(_.nonEmpty)
        .map { p =>
          if !p.startsWith("-") then
            throw IllegalArgumentException(s"Invalid prefix '${p}' (must start with - or --)")
          p
        }

  private def toKebabCase(name: String): String =
    name.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase

end MethodOptionSchema
