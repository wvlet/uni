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
package wvlet.uni.io

/**
  * Scala.js stub for process execution. Subprocess execution is not supported in browser or Node.js
  * environments through this synchronous API.
  */
trait IOCompat extends ProcessApi:

  private def unsupported: Nothing =
    throw UnsupportedOperationException(
      "Subprocess execution is not supported in Scala.js environments"
    )

  override def run(command: String*): CommandResult                             = unsupported
  override def run(command: Seq[String], config: ProcessConfig): CommandResult  = unsupported
  override def call(command: String*): CommandResult                            = unsupported
  override def call(command: Seq[String], config: ProcessConfig): CommandResult = unsupported
  override def spawn(command: String*): Process                                 = unsupported
  override def spawn(command: Seq[String], config: ProcessConfig): Process      = unsupported

end IOCompat
