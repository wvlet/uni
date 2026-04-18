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
package wvlet.uni.text.parser

import wvlet.uni.text.Span

/**
  * Thrown by [[ScannerBase]] when it encounters an unexpected character or an unclosed literal and
  * error reporting is configured to raise (see [[ScannerConfig.reportErrorToken]]).
  */
class TextParseException(
    val message: String,
    val offset: Int,
    val span: Span = Span.NoSpan,
    cause: Throwable = null
) extends Exception(message, cause)
