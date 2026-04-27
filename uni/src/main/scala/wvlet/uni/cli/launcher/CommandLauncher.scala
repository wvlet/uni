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
  * Information about a command
  */
case class CommandInfo(name: String, description: String, usage: String, isDefault: Boolean)

/**
  * Result of executing a command
  */
case class LauncherResult(
    instance: Any,
    executedMethod: Option[(MethodSurface, Any)],
    showedHelp: Boolean
)

/**
  * Wraps a single command with its options and sub-commands
  */
class CommandLauncher(
    val info: CommandInfo,
    val surface: Surface,
    val schema: OptionSchema,
    val methods: Seq[MethodSurface],
    val subCommands: Seq[CommandLauncher]
):
  private val helpPrefixes = Seq("-h", "--help")

  /**
    * Execute the command with the given arguments
    */
  def execute(config: LauncherConfig, args: Seq[String]): LauncherResult =
    val parser      = OptionParser(schema)
    val parseResult = parser.parse(args.toArray, config.helpPrefixes)

    if parseResult.showHelp then
      printHelp(config)
      return LauncherResult(null, None, showedHelp = true)

    // Build the command instance
    val instance = buildInstance(parseResult)

    // Check for sub-command in unused arguments
    val (subCommandName, remainingArgs) =
      parseResult.unusedArguments match
        case head +: tail if !head.startsWith("-") =>
          (Some(head), tail)
        case _ =>
          (None, parseResult.unusedArguments)

    subCommandName match
      case Some(cmdName) =>
        // Find matching sub-command
        findSubCommand(cmdName) match
          case Some(subLauncher) =>
            val subResult = subLauncher.execute(config, remainingArgs)
            LauncherResult(instance, subResult.executedMethod, subResult.showedHelp)
          case None =>
            // Check for method command
            findMethodCommand(cmdName) match
              case Some(method) =>
                val methodResult = executeMethod(config, instance, method, remainingArgs)
                methodResult
              case None =>
                throw IllegalArgumentException(s"Unknown command: ${cmdName}")

      case None =>
        // No sub-command specified
        // Check for default method command
        findDefaultMethodCommand() match
          case Some(method) =>
            executeMethod(config, instance, method, remainingArgs)
          case None =>
            if subCommands.nonEmpty || hasMethodCommands then
              // Show help if no command specified but commands are available
              if config.showHelpOnNoArgs && args.isEmpty then
                printHelp(config)
                LauncherResult(instance, None, showedHelp = true)
              else
                LauncherResult(instance, None, showedHelp = false)
            else
              LauncherResult(instance, None, showedHelp = false)
    end match

  end execute

  /**
    * Execute a method command
    */
  private def executeMethod(
      config: LauncherConfig,
      instance: Any,
      method: MethodSurface,
      args: Seq[String]
  ): LauncherResult =
    val methodSchema = MethodOptionSchema(method)
    val parser       = OptionParser(methodSchema)
    val parseResult  = parser.parse(args.toArray, config.helpPrefixes)

    if parseResult.showHelp then
      printMethodHelp(config, method, methodSchema)
      return LauncherResult(instance, Some((method, null)), showedHelp = true)

    val methodArgs = buildMethodArgs(method, parseResult)
    val result     = method.call(instance, methodArgs*)
    LauncherResult(instance, Some((method, result)), showedHelp = false)

  /**
    * Build an instance of the command class from parsed values
    */
  private def buildInstance(parseResult: ParseResult): Any = buildInstanceFromSurface(
    surface,
    parseResult
  )

  /**
    * Recursively build an instance from a surface
    */
  private def buildInstanceFromSurface(surf: Surface, parseResult: ParseResult): Any =
    surf.objectFactory match
      case Some(factory) =>
        val args = surf
          .params
          .map { p =>
            if isUnannotatedNested(p) then
              // Recursively build nested instance
              buildInstanceFromSurface(p.surface, parseResult)
            else
              resolveParamValue(p, parseResult, "parameter")
          }
        factory.newInstance(args)
      case None =>
        throw IllegalStateException(s"Cannot create instance of ${surf.fullName}")

  /**
    * Build method arguments from parsed values
    */
  private def buildMethodArgs(method: MethodSurface, parseResult: ParseResult): Seq[Any] = method
    .args
    .map { p =>
      if isUnannotatedNested(p) then
        // Recursively build nested config-class instance from parsed flags
        buildInstanceFromSurface(p.surface, parseResult)
      else
        resolveParamValue(p, parseResult, "argument")
    }

  private def isUnannotatedNested(p: Parameter): Boolean =
    isNestedConfigClass(p.surface) && p.findAnnotation("option").isEmpty &&
      p.findAnnotation("argument").isEmpty

  private def resolveParamValue(p: Parameter, parseResult: ParseResult, kind: String): Any =
    parseResult.optionValues.get(p.name) match
      case Some(values) =>
        convertValue(p.surface, values)
      case None =>
        p.getDefaultValue
          .getOrElse {
            if p.isRequired then
              throw IllegalArgumentException(s"Missing required ${kind}: ${p.name}")
            else
              getDefaultForType(p.surface)
          }

  /**
    * Convert string values to the target type
    */
  private def convertValue(targetSurface: Surface, values: Seq[String]): Any =
    import wvlet.uni.surface.Primitive

    val innerSurface =
      if targetSurface.isOption then
        targetSurface.typeArgs.headOption.getOrElse(targetSurface)
      else
        targetSurface

    def convertSingle(value: String, surface: Surface): Any =
      surface match
        case Primitive.String =>
          value
        case Primitive.Int =>
          value.toInt
        case Primitive.Long =>
          value.toLong
        case Primitive.Float =>
          value.toFloat
        case Primitive.Double =>
          value.toDouble
        case Primitive.Boolean =>
          value.toBoolean
        case Primitive.Short =>
          value.toShort
        case Primitive.Byte =>
          value.toByte
        case Primitive.Char =>
          value.headOption.getOrElse('\u0000')
        case _ if surface.fullName == "wvlet.uni.cli.launcher.KeyValue" =>
          KeyValue.parse(value)
        case _ =>
          // Try to use string as-is for unknown types
          value

    if targetSurface.isOption then
      if values.isEmpty then
        None
      else
        Some(convertSingle(values.head, innerSurface))
    else if targetSurface.isSeq then
      val elemSurface = targetSurface.typeArgs.headOption.getOrElse(Primitive.String)
      values.map(v => convertSingle(v, elemSurface)).toList
    else if targetSurface.isArray then
      val elemSurface = targetSurface.typeArgs.headOption.getOrElse(Primitive.String)
      val converted   = values.map(v => convertSingle(v, elemSurface))
      // Create a properly typed array using reflection to avoid ClassCastException
      val arr = java.lang.reflect.Array.newInstance(elemSurface.rawType, converted.size)
      converted
        .zipWithIndex
        .foreach { case (v, i) =>
          java.lang.reflect.Array.set(arr, i, v)
        }
      arr
    else if values.nonEmpty then
      convertSingle(values.head, targetSurface)
    else
      getDefaultForType(targetSurface)

  end convertValue

  /**
    * Get default value for a type
    */
  private def getDefaultForType(surface: Surface): Any =
    import wvlet.uni.surface.Primitive

    surface match
      case Primitive.String =>
        ""
      case Primitive.Int =>
        0
      case Primitive.Long =>
        0L
      case Primitive.Float =>
        0.0f
      case Primitive.Double =>
        0.0
      case Primitive.Boolean =>
        false
      case Primitive.Short =>
        0.toShort
      case Primitive.Byte =>
        0.toByte
      case Primitive.Char =>
        '\u0000'
      case _ if surface.isOption =>
        None
      case _ if surface.isSeq =>
        Nil
      case _ if surface.isArray =>
        val elemType = surface.typeArgs.headOption.getOrElse(wvlet.uni.surface.AnyRefSurface)
        java.lang.reflect.Array.newInstance(elemType.rawType, 0)
      case _ =>
        null

  end getDefaultForType

  /**
    * Print help message for this command
    */
  def printHelp(config: LauncherConfig): Unit =
    val methodCommands = getMethodCommands
    val allSubCommands =
      subCommands.map(s => (s.info.name, s.info.description)) ++
        methodCommands.map(m => (m.name, getMethodDescription(m)))

    val help = config
      .helpPrinter
      .render(
        commandName =
          if info.name.isEmpty then
            "command"
          else
            info.name
        ,
        description = info.description,
        usage =
          if info.usage.isEmpty then
            None
          else
            Some(info.usage)
        ,
        options = schema.options,
        arguments = schema.arguments,
        subCommands = allSubCommands
      )
    println(help)

  /**
    * Print help message for a method command
    */
  private def printMethodHelp(
      config: LauncherConfig,
      method: MethodSurface,
      methodSchema: MethodOptionSchema
  ): Unit =
    val cmdName =
      if info.name.isEmpty then
        method.name
      else
        s"${info.name} ${method.name}"
    val help = config
      .helpPrinter
      .render(
        commandName = cmdName,
        description = getMethodDescription(method),
        usage = getMethodUsage(method),
        options = methodSchema.options,
        arguments = methodSchema.arguments,
        subCommands = Seq.empty
      )
    println(help)

  private def findSubCommand(name: String): Option[CommandLauncher] = subCommands.find(
    _.info.name == name
  )

  private def findMethodCommand(name: String): Option[MethodSurface] = methods.find(m =>
    m.name == name && m.hasAnnotation("command")
  )

  private def findDefaultMethodCommand(): Option[MethodSurface] = methods.find { m =>
    m.findAnnotation("command")
      .exists { annot =>
        annot.getAs[Boolean]("isDefault").getOrElse(false)
      }
  }

  private def hasMethodCommands: Boolean = methods.exists(_.hasAnnotation("command"))

  private def getMethodCommands: Seq[MethodSurface] = methods.filter(_.hasAnnotation("command"))

  private def getMethodDescription(method: MethodSurface): String = method
    .findAnnotation("command")
    .flatMap(_.getAs[String]("description"))
    .getOrElse("")

  private def getMethodUsage(method: MethodSurface): Option[String] = method
    .findAnnotation("command")
    .flatMap(_.getAs[String]("usage"))
    .filter(_.nonEmpty)

end CommandLauncher
