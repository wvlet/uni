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

import wvlet.uni.surface.{MethodSurface, Surface}

/**
  * Configuration for the launcher
  */
case class LauncherConfig(
    helpPrinter: HelpPrinter = HelpPrinter.default,
    helpPrefixes: Seq[String] = Seq("-h", "--help"),
    showHelpOnNoArgs: Boolean = true
):
  def withHelpPrinter(printer: HelpPrinter): LauncherConfig = copy(helpPrinter = printer)

  def withHelpPrefixes(prefixes: Seq[String]): LauncherConfig = copy(helpPrefixes = prefixes)

  def withShowHelpOnNoArgs(show: Boolean): LauncherConfig = copy(showHelpOnNoArgs = show)

/**
  * Main entry point for CLI command execution
  */
case class Launcher(config: LauncherConfig, private[launcher] val mainLauncher: CommandLauncher):
  /**
    * Print the help message for the main command
    */
  def printHelp: Unit = mainLauncher.printHelp(config)

  /**
    * Execute with a command line string
    */
  def execute(argLine: String): LauncherResult = execute(CommandLineTokenizer.tokenize(argLine))

  /**
    * Execute with an array of arguments
    */
  def execute(args: Array[String]): LauncherResult = mainLauncher.execute(config, args.toSeq)

  /**
    * Execute with a sequence of arguments
    */
  def execute(args: Seq[String]): LauncherResult = mainLauncher.execute(config, args)

  /**
    * Add a sub-command module
    */
  inline def addModule[M](name: String, description: String): Launcher =
    val subLauncher = Launcher.newCommandLauncher[M](name, description)
    val newMain     = CommandLauncher(
      info = mainLauncher.info,
      surface = mainLauncher.surface,
      schema = mainLauncher.schema,
      methods = mainLauncher.methods,
      subCommands = mainLauncher.subCommands :+ subLauncher
    )
    Launcher(config, newMain)

  /**
    * Update configuration
    */
  def withConfig(config: LauncherConfig): Launcher = copy(config = config)

  def withHelpPrinter(printer: HelpPrinter): Launcher = copy(config =
    config.withHelpPrinter(printer)
  )

  def withHelpPrefixes(prefixes: Seq[String]): Launcher = copy(config =
    config.withHelpPrefixes(prefixes)
  )

  def withShowHelpOnNoArgs(show: Boolean): Launcher = copy(config =
    config.withShowHelpOnNoArgs(show)
  )

end Launcher

object Launcher:
  /**
    * Create a launcher for the given command class
    */
  inline def of[A]: Launcher =
    val surface  = Surface.of[A]
    val methods  = Surface.methodsOf[A]
    val launcher = newCommandLauncherFromSurface(surface, methods, "", "")
    Launcher(LauncherConfig(), launcher)

  /**
    * Execute a command and return the instance
    */
  inline def execute[A](argLine: String): A = execute[A](CommandLineTokenizer.tokenize(argLine))

  /**
    * Execute a command and return the instance
    */
  inline def execute[A](args: Array[String]): A =
    val result = of[A].execute(args)
    if result.showedHelp then
      null.asInstanceOf[A]
    else
      result.instance.asInstanceOf[A]

  /**
    * Create a new CommandLauncher for a type
    */
  inline private[launcher] def newCommandLauncher[A](
      name: String,
      description: String
  ): CommandLauncher =
    val surface = Surface.of[A]
    val methods = Surface.methodsOf[A]
    newCommandLauncherFromSurface(surface, methods, name, description)

  /**
    * Create a CommandLauncher from Surface
    */
  private[launcher] def newCommandLauncherFromSurface(
      surface: Surface,
      methods: Seq[MethodSurface],
      name: String,
      description: String
  ): CommandLauncher =
    // Get command annotation info if present. The class-level annotation comes first; the
    // other lookups are kept for surfaces that carry no class annotations
    val (desc, usage, isDefault) = surface
      .findAnnotation("command")
      .orElse(surface.params.headOption.flatMap(_.findAnnotation("command")))
      .orElse {
        methods.find(_.name == "<init>").flatMap(_.findAnnotation("command"))
      }
      .map { annot =>
        (
          // An explicit description given to addModule takes precedence over the annotation
          if description.nonEmpty then
            description
          else
            annot.getAs[String]("description").getOrElse("")
          ,
          annot.getAs[String]("usage").getOrElse(""),
          annot.getAs[Boolean]("isDefault").getOrElse(false)
        )
      }
      .getOrElse((description, "", false))

    val cmdInfo = CommandInfo(name = name, description = desc, usage = usage, isDefault = isDefault)

    val schema = ClassOptionSchema(surface)

    CommandLauncher(
      info = cmdInfo,
      surface = surface,
      schema = schema,
      methods = methods,
      subCommands = Seq.empty
    )

  end newCommandLauncherFromSurface

end Launcher
