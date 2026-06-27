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
package wvlet.uni.test.spi

import wvlet.uni.log.Logger
import wvlet.uni.log.LogLevel

/**
  * Test configuration parsed from command-line arguments
  *
  * Supported arguments:
  *   - `-l debug` or `-l trace` : Set default log level
  *   - `-l:pattern=level` : Set log level for classes matching pattern (e.g.,
  *     `-l:wvlet.uni.*=debug`)
  *   - `-t:filter` : Run only tests whose full name contains `filter`
  *   - `--tags <tag>` : Run only tests carrying `tag`; repeat to narrow further — each added tag is
  *     ANDed, like GitHub issue label filters, e.g. `--tags ui --tags slow` runs tests tagged both
  *     `ui` and `slow`
  *   - `--exclude-tags <tag>` : Skip tests carrying `tag` (exclude filter); repeatable
  */
case class TestConfig(
    defaultLogLevel: Option[LogLevel] = None,
    logLevelPatterns: List[(String, LogLevel)] = Nil,
    testFilter: Option[String] = None,
    includeTags: Set[String] = Set.empty,
    excludeTags: Set[String] = Set.empty
):
  /**
    * Whether a test with the given tags should run under this config. A test runs when it carries
    * every `--tags` tag (AND — each added tag narrows the selection) and none of the
    * `--exclude-tags` tags. With no `--tags`, all tests are included. Exclusion wins over
    * inclusion.
    */
  def includesTags(tags: Set[String]): Boolean =
    includeTags.subsetOf(tags) && !tags.exists(excludeTags.contains)

object TestConfig:

  /**
    * Parse command-line arguments into TestConfig
    */
  def parse(args: Array[String]): TestConfig =
    var config = TestConfig()
    var i      = 0

    while i < args.length do
      args(i) match
        case "-l" if i + 1 < args.length =>
          // -l debug or -l trace
          val levelStr = args(i + 1)
          LogLevel.unapply(levelStr) match
            case Some(level) =>
              config = config.copy(defaultLogLevel = Some(level))
            case None =>
              Console.err.println(s"Unknown log level: ${levelStr}")
          i += 2

        case s if s.startsWith("-l:") =>
          // -l:pattern=level format
          val spec = s.substring(3)
          spec.split("=", 2) match
            case Array(pattern, levelStr) =>
              LogLevel.unapply(levelStr) match
                case Some(level) =>
                  config = config.copy(logLevelPatterns =
                    (pattern, level) :: config.logLevelPatterns
                  )
                case None =>
                  Console.err.println(s"Unknown log level: ${levelStr}")
            case _ =>
              Console
                .err
                .println(s"Invalid log level pattern: ${s}. Expected format: -l:pattern=level")
          i += 1

        case "--tags" if i + 1 < args.length =>
          // --tags <tag> include filter; repeat to narrow by AND (e.g. --tags ui --tags slow).
          // If a union (OR) is ever needed, this is the natural place to also accept an expression
          // form (e.g. `--tags "ui|electron"`) without breaking the plain single-tag usage.
          val tag = args(i + 1).trim
          if tag.nonEmpty then
            config = config.copy(includeTags = config.includeTags + tag)
          i += 2

        case "--exclude-tags" if i + 1 < args.length =>
          // --exclude-tags <tag> exclude filter; repeatable
          val tag = args(i + 1).trim
          if tag.nonEmpty then
            config = config.copy(excludeTags = config.excludeTags + tag)
          i += 2

        case s if s.startsWith("-t:") =>
          // -t:testFilter for filtering tests by name
          config = config.copy(testFilter = Some(s.substring(3)))
          i += 1

        case _ =>
          // Unknown argument, skip
          i += 1
    end while

    config

  end parse

  /**
    * Apply the test configuration (set log levels)
    */
  def apply(config: TestConfig): Unit =
    // Set default log level
    config
      .defaultLogLevel
      .foreach { level =>
        Logger.setDefaultLogLevel(level)
      }

    // Set pattern-based log levels
    config
      .logLevelPatterns
      .foreach { (pattern, level) =>
        Logger.setLogLevel(pattern, level)
      }

end TestConfig
