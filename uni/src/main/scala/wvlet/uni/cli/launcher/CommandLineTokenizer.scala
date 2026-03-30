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

import wvlet.uni.io.ProcessApi

/**
  * Tokenizes command line strings into Array[String]. Delegates to ProcessApi.tokenize in uni-core.
  */
object CommandLineTokenizer:
  /**
    * Tokenize a command line string into an array of arguments. Handles:
    *   - Single quotes: 'hello world' -> hello world
    *   - Double quotes: "hello world" -> hello world
    *   - Escaped characters: hello\ world -> hello world
    *   - Escape sequences in double quotes: \n, \t, \r
    *   - Mixed: 'hello "world"' -> hello "world"
    *
    * @throws IllegalArgumentException
    *   if the command line has unclosed quotes
    */
  def tokenize(line: String): Array[String] =
    if line == null || line.trim.isEmpty then
      Array.empty
    else
      ProcessApi.tokenize(line).toArray

end CommandLineTokenizer
