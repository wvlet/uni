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

import sbt.testing.Runner
import sbt.testing.Task
import sbt.testing.TaskDef

/**
  * sbt test runner for UniTest
  */
class UniTestRunner(_args: Array[String], _remoteArgs: Array[String], testClassLoader: ClassLoader)
    extends Runner:

  def args: Array[String]         = _args
  def remoteArgs(): Array[String] = _remoteArgs

  // Parse test configuration from arguments
  private val config: TestConfig = TestConfig.parse(_args)

  // Shared test statistics across all test tasks
  private val stats: TestStats = TestStats()

  // Apply log configuration at runner initialization
  TestConfig.apply(config)

  override def tasks(taskDefs: Array[TaskDef]): Array[Task] = taskDefs.map { td =>
    UniTestTask(td, testClassLoader, config, stats)
  }

  override def done(): String =
    if stats.total > 0 then
      val totalTimeMs = stats.totalTime / 1000000
      val timeStr     =
        if totalTimeMs >= 1000 then
          f"${totalTimeMs / 1000.0}%.2fs"
        else
          s"${totalTimeMs}ms"
      s"Total: ${stats.summary} (${timeStr})"
    else
      ""

  // The following methods are defined for Scala.js support:

  /**
    * Receive a message from the test server. Not used in our implementation.
    */
  def receiveMessage(msg: String): Option[String] = None

  /**
    * Deserialize a task from a string representation. Used for remote test execution.
    */
  def deserializeTask(task: String, deserializer: String => TaskDef): Task = UniTestTask(
    deserializer(task),
    testClassLoader,
    config,
    stats
  )

  /**
    * Serialize a task to a string representation. Used for remote test execution.
    */
  def serializeTask(task: Task, serializer: TaskDef => String): String = serializer(task.taskDef())

end UniTestRunner
