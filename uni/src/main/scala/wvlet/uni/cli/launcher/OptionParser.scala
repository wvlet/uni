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

import scala.collection.mutable

/**
  * Result of parsing command-line arguments
  *
  * @param optionValues
  *   Map of parameter names to their parsed values
  * @param unusedArguments
  *   Arguments that were not matched to any option or argument
  * @param showHelp
  *   Whether help was requested
  */
case class ParseResult(
    optionValues: Map[String, Seq[String]],
    unusedArguments: Seq[String],
    showHelp: Boolean
)

/**
  * Parser for command-line arguments based on an OptionSchema
  */
class OptionParser(val schema: OptionSchema):
  // Regex patterns for option parsing
  private val LongOptionWithValue  = """^(--[\w-]+)[=:](.+)$""".r
  private val LongOption           = """^(--[\w-]+)$""".r
  private val ShortOption          = """^(-\w)$""".r
  private val ShortOptionWithValue = """^(-\w)[=:](.+)$""".r
  // Key-value pattern: -D or -L followed by key=value (no space)
  private val KeyValueOption     = """^(-\w)(.+=.*)$""".r
  private val KeyValueOptionNoEq = """^(-\w)(.+)$""".r

  /**
    * Parse command-line arguments
    *
    * @param args
    *   Array of command-line arguments
    * @param helpPrefixes
    *   Prefixes that trigger help display (default: -h, --help)
    * @param deferHelpAfterCommand
    *   If true, help prefixes that appear after the first unmatched positional (a sub-command name)
    *   are kept in unusedArguments so that the sub-command can show its own help
    * @return
    *   ParseResult containing parsed values and metadata
    */
  def parse(
      args: Array[String],
      helpPrefixes: Seq[String] = Seq("-h", "--help"),
      deferHelpAfterCommand: Boolean = false
  ): ParseResult =
    val optionValues    = mutable.Map[String, mutable.Buffer[String]]()
    val unusedArguments = mutable.Buffer[String]()
    var showHelp        = false
    var argIndex        = 0
    var i               = 0

    def addValue(paramName: String, value: String): Unit =
      optionValues.getOrElseUpdate(paramName, mutable.Buffer()) += value

    while i < args.length do
      val arg = args(i)

      // Check for help
      if helpPrefixes.contains(arg) then
        if deferHelpAfterCommand && unusedArguments.exists(!_.startsWith("-")) then
          // Leave the help flag for the sub-command
          unusedArguments += arg
        else
          showHelp = true
        i += 1
      // Check for key-value option like -Dkey=value
      else if isKeyValueArg(arg) then
        handleKeyValueArg(arg, addValue)
        i += 1
      // Long option with value: --option=value or --option:value
      else
        arg match
          case LongOptionWithValue(opt, value) =>
            schema.findOption(opt) match
              case Some(clOpt) if clOpt.param.isDefined =>
                addValue(clOpt.param.get.name, value)
              case _ =>
                unusedArguments += arg
            i += 1

          case LongOption(opt) =>
            schema.findOption(opt) match
              case Some(clOpt) if clOpt.param.isDefined =>
                if clOpt.takesArgument then
                  // Next argument is the value
                  if i + 1 < args.length then
                    addValue(clOpt.param.get.name, args(i + 1))
                    i += 2
                  else
                    throw IllegalArgumentException(s"Option ${opt} requires a value")
                else
                  // Boolean flag
                  addValue(clOpt.param.get.name, "true")
                  i += 1
              case _ =>
                unusedArguments += arg
                i += 1

          case ShortOptionWithValue(opt, value) =>
            schema.findOption(opt) match
              case Some(clOpt) if clOpt.param.isDefined =>
                addValue(clOpt.param.get.name, value)
              case _ =>
                unusedArguments += arg
            i += 1

          case ShortOption(opt) =>
            schema.findOption(opt) match
              case Some(clOpt) if clOpt.param.isDefined =>
                if clOpt.takesArgument then
                  if i + 1 < args.length then
                    addValue(clOpt.param.get.name, args(i + 1))
                    i += 2
                  else
                    throw IllegalArgumentException(s"Option ${opt} requires a value")
                else
                  addValue(clOpt.param.get.name, "true")
                  i += 1
              case _ =>
                unusedArguments += arg
                i += 1

          case _ =>
            // Check if it starts with - but doesn't match any pattern (unknown option)
            if arg.startsWith("-") then
              unusedArguments += arg
              i += 1
            else
              // Positional argument
              schema.findArgument(argIndex) match
                case Some(clArg) if clArg.param.isDefined =>
                  if clArg.takesMultipleArguments then
                    // Consume all remaining non-option arguments
                    while i < args.length && !args(i).startsWith("-") do
                      addValue(clArg.param.get.name, args(i))
                      i += 1
                  else
                    addValue(clArg.param.get.name, arg)
                    argIndex += 1
                    i += 1
                case _ =>
                  unusedArguments += arg
                  i += 1
      end if
    end while

    ParseResult(
      optionValues = optionValues.view.mapValues(_.toSeq).toMap,
      unusedArguments = unusedArguments.toSeq,
      showHelp = showHelp
    )

  end parse

  /**
    * Check if the argument is a key-value option (e.g., -Dkey=value, -Lpackage=level)
    */
  private def isKeyValueArg(arg: String): Boolean =
    if arg.length < 3 then
      false
    else
      // Check if it starts with a single-letter prefix followed by something
      arg match
        case KeyValueOption(prefix, _) =>
          schema.options.exists(o => o.isKeyValue && o.prefixes.contains(prefix))
        case KeyValueOptionNoEq(prefix, rest) if rest.nonEmpty =>
          // Allow -Dkey (value = "")
          schema.options.exists(o => o.isKeyValue && o.prefixes.contains(prefix))
        case _ =>
          false

  /**
    * Handle key-value argument parsing
    */
  private def handleKeyValueArg(arg: String, addValue: (String, String) => Unit): Unit =
    arg match
      case KeyValueOption(prefix, kvPart) =>
        schema
          .options
          .find(o => o.isKeyValue && o.prefixes.contains(prefix))
          .foreach { opt =>
            opt
              .param
              .foreach { p =>
                // Store the full key=value string
                addValue(p.name, kvPart)
              }
          }
      case KeyValueOptionNoEq(prefix, kvPart) =>
        schema
          .options
          .find(o => o.isKeyValue && o.prefixes.contains(prefix))
          .foreach { opt =>
            opt
              .param
              .foreach { p =>
                // key without value
                addValue(p.name, kvPart)
              }
          }
      case _ =>
        ()

end OptionParser
