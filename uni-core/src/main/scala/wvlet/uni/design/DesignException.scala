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
package wvlet.uni.design

import wvlet.uni.surface.Surface
import wvlet.uni.util.SourceCode

enum DesignErrorCode:
  case CYCLIC_DEPENDENCY  extends DesignErrorCode
  case MISSING_DEPENDENCY extends DesignErrorCode
  case SHUTDOWN_FAILURE   extends DesignErrorCode

  def newException(message: String): DesignException = DesignException(this, message)
  def newException(message: String, cause: Throwable): DesignException = DesignException(
    this,
    message,
    cause
  )

case class DesignException(code: DesignErrorCode, message: String, cause: Throwable = null)
    extends Exception(message, cause):
  override def getMessage: String = s"[${code}] ${message}"

object DesignException:
  def cyclicDependency(deps: List[Surface], sourceCode: SourceCode): DesignException =
    DesignException(
      DesignErrorCode.CYCLIC_DEPENDENCY,
      s"${deps.reverse.mkString(" -> ")} at ${sourceCode}"
    )

  def missingDependency(stack: List[Surface], sourceCode: SourceCode): DesignException =
    DesignException(
      DesignErrorCode.MISSING_DEPENDENCY,
      s"Binding for ${stack.head} at ${sourceCode} is not found: ${stack.mkString(" <- ")}"
    )

end DesignException
