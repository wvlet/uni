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

import org.junit.platform.engine.*
import org.junit.platform.engine.discovery.*
import org.junit.platform.engine.support.descriptor.*
import wvlet.uni.test.compat
import wvlet.uni.test.TestResult
import wvlet.uni.test.UniTest

import java.util.Optional
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*

/**
  * JUnit Platform TestEngine for UniTest.
  *
  * This enables native IDE integration (IntelliJ, VS Code) for running UniTest tests.
  */
class UniTestEngine extends TestEngine:

  override def getId: String = "uni-test"

  override def discover(request: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor =
    val rootDescriptor = EngineDescriptor(uniqueId, "UniTest")

    // Track which classes have been added to avoid duplicates
    val addedClasses = scala.collection.mutable.Set.empty[Class[?]]

    // Handle class selectors (run all tests in a class)
    request
      .getSelectorsByType(classOf[ClassSelector])
      .asScala
      .foreach { selector =>
        val clazz = selector.getJavaClass
        if classOf[UniTest].isAssignableFrom(clazz) && !clazz.isInterface then
          addedClasses.add(clazz)
          addTestClass(rootDescriptor, clazz.asInstanceOf[Class[? <: UniTest]], testFilter = None)
      }

    // Handle unique ID selectors (re-run specific tests by ID)
    // Use context class loader for IDE environments where test classes
    // are loaded by a different class loader than the engine
    val classLoader = Thread.currentThread().getContextClassLoader
    request
      .getSelectorsByType(classOf[UniqueIdSelector])
      .asScala
      .foreach { selector =>
        val selectedId = selector.getUniqueId
        // Extract class name from unique ID segments
        selectedId
          .getSegments
          .asScala
          .find(_.getType == "class")
          .foreach { segment =>
            try
              val clazz = classLoader.loadClass(segment.getValue)
              if classOf[UniTest].isAssignableFrom(clazz) && !addedClasses.contains(clazz) then
                addedClasses.add(clazz)
                // Extract test name filter if present
                val testFilter = selectedId
                  .getSegments
                  .asScala
                  .find(_.getType == "test")
                  .map(_.getValue)
                addTestClass(
                  rootDescriptor,
                  clazz.asInstanceOf[Class[? <: UniTest]],
                  testFilter = testFilter
                )
            catch
              case _: ClassNotFoundException =>
              // Ignore if class not found
          }
      }

    // Handle package selectors
    request
      .getSelectorsByType(classOf[PackageSelector])
      .asScala
      .foreach { selector =>
        // Note: Package scanning would require classpath scanning
        // For now, IDE typically uses class selectors
      }

    rootDescriptor

  end discover

  override def execute(request: ExecutionRequest): Unit =
    val root     = request.getRootTestDescriptor
    val listener = request.getEngineExecutionListener

    listener.executionStarted(root)

    root
      .getChildren
      .asScala
      .foreach { classDescriptor =>
        executeClassDescriptor(classDescriptor, listener)
      }

    listener.executionFinished(root, TestExecutionResult.successful())

  private def addTestClass(
      parent: TestDescriptor,
      testClass: Class[? <: UniTest],
      testFilter: Option[String]
  ): Unit =
    val classId         = parent.getUniqueId.append("class", testClass.getName)
    val classDescriptor = UniTestClassDescriptor(classId, testClass)

    // Instantiate to get registered tests (handles both classes and objects)
    try
      val instance = compat.getInstanceOf(testClass)
      instance
        .registeredTests
        .filter { testDef =>
          // Apply filter if specified (for running individual tests)
          testFilter.forall(filter => testDef.fullName == filter)
        }
        .foreach { testDef =>
          val testId         = classId.append("test", testDef.fullName)
          val testDescriptor = UniTestMethodDescriptor(testId, testDef.fullName, testClass)
          classDescriptor.addChild(testDescriptor)
        }
      parent.addChild(classDescriptor)
    catch
      case e: Throwable =>
        // Failed to instantiate, add class without methods
        parent.addChild(classDescriptor)

  private def executeClassDescriptor(
      descriptor: TestDescriptor,
      listener: EngineExecutionListener
  ): Unit =
    descriptor match
      case classDesc: UniTestClassDescriptor =>
        listener.executionStarted(classDesc)

        try
          val instance = compat.getInstanceOf(classDesc.testClass)

          // Get the set of test names that were discovered (selected for execution)
          val discoveredTests =
            classDesc
              .getChildren
              .asScala
              .collect { case m: UniTestMethodDescriptor =>
                m.testName
              }
              .toSet

          // Use queue-based approach to handle dynamically registered nested tests
          // Filter to only discovered tests (enables single test selection in IDE)
          val testQueue = scala
            .collection
            .mutable
            .Queue
            .from(
              instance
                .registeredTests
                .filter { testDef =>
                  discoveredTests.isEmpty || discoveredTests.contains(testDef.fullName)
                }
            )
          val executedTests = scala.collection.mutable.Set.empty[String]

          while testQueue.nonEmpty do
            val testDef = testQueue.dequeue()
            if !executedTests.contains(testDef.fullName) then
              executedTests.add(testDef.fullName)

              val beforeCount = instance.registeredTests.size
              val result      = Await.result(instance.executeTest(testDef), Duration.Inf)
              val isContainer = instance.registeredTests.size > beforeCount

              // Report failing containers or any leaf test
              if result.isFailure || !isContainer then
                // Find or create descriptor for this test
                val testDescriptor = classDesc
                  .getChildren
                  .asScala
                  .collectFirst {
                    case m: UniTestMethodDescriptor if m.testName == testDef.fullName =>
                      m
                  }
                  .getOrElse {
                    val testId  = classDesc.getUniqueId.append("test", testDef.fullName)
                    val newDesc = UniTestMethodDescriptor(
                      testId,
                      testDef.fullName,
                      classDesc.testClass
                    )
                    classDesc.addChild(newDesc)
                    listener.dynamicTestRegistered(newDesc)
                    newDesc
                  }

                listener.executionStarted(testDescriptor)
                reportResult(testDescriptor, result, listener)

              // Queue nested tests for execution (if container didn't fail)
              if !result.isFailure && isContainer then
                instance
                  .registeredTests
                  .foreach { t =>
                    if !executedTests.contains(t.fullName) then
                      testQueue.enqueue(t)
                  }
            end if
          end while

          listener.executionFinished(classDesc, TestExecutionResult.successful())
        catch
          case e: Throwable =>
            listener.executionFinished(classDesc, TestExecutionResult.failed(e))
        end try

      case _ =>
        descriptor
          .getChildren
          .asScala
          .foreach { child =>
            executeClassDescriptor(child, listener)
          }

  private def reportResult(
      descriptor: TestDescriptor,
      result: TestResult,
      listener: EngineExecutionListener
  ): Unit =
    result match
      case TestResult.Success(_) =>
        listener.executionFinished(descriptor, TestExecutionResult.successful())
      case TestResult.Failure(_, msg, causeOpt) =>
        val throwable = causeOpt.getOrElse(AssertionError(msg))
        listener.executionFinished(descriptor, TestExecutionResult.failed(throwable))
      case TestResult.Error(_, _, cause) =>
        listener.executionFinished(descriptor, TestExecutionResult.failed(cause))
      case TestResult.Skipped(_, reason) =>
        listener.executionSkipped(descriptor, reason)
      case TestResult.Pending(_, reason) =>
        listener.executionSkipped(descriptor, s"Pending: ${reason}")
      case TestResult.Cancelled(_, reason) =>
        listener.executionSkipped(descriptor, s"Cancelled: ${reason}")
      case TestResult.Ignored(_, reason) =>
        listener.executionSkipped(descriptor, s"Ignored: ${reason}")

end UniTestEngine

/**
  * Test descriptor for a UniTest class
  */
class UniTestClassDescriptor(uniqueId: UniqueId, val testClass: Class[? <: UniTest])
    extends AbstractTestDescriptor(uniqueId, testClass.getSimpleName, ClassSource.from(testClass)):

  override def getType: TestDescriptor.Type = TestDescriptor.Type.CONTAINER

/**
  * Test descriptor for a single test method
  */
class UniTestMethodDescriptor(uniqueId: UniqueId, val testName: String, testClass: Class[?])
    extends AbstractTestDescriptor(uniqueId, testName, ClassSource.from(testClass)):

  override def getType: TestDescriptor.Type = TestDescriptor.Type.TEST
