package wvlet.uni.temporal.example

import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.client.WorkflowOptions
import wvlet.uni.test.UniTest

/**
  * Tests for HelloWorkflow using Temporal's in-process test environment.
  *
  * TestWorkflowEnvironment runs a full Temporal server in memory — no external process needed. This
  * allows testing workflow + activity integration without a real Temporal cluster.
  */
class HelloWorkflowTest extends UniTest:

  private def withHelloEnv(testCode: TestWorkflowEnvironment => Unit): Unit =
    val testEnv = TestWorkflowEnvironment.newInstance()
    try
      val worker = testEnv.newWorker(TemporalExample.TaskQueue)
      worker.registerWorkflowImplementationTypes(classOf[HelloWorkflowImpl])
      worker.registerActivitiesImplementations(GreetingActivitiesImpl())
      testEnv.start()
      testCode(testEnv)
    finally
      testEnv.close()

  private def newHelloStub(testEnv: TestWorkflowEnvironment): HelloWorkflow = testEnv
    .getWorkflowClient
    .newWorkflowStub(
      classOf[HelloWorkflow],
      WorkflowOptions.newBuilder().setTaskQueue(TemporalExample.TaskQueue).build()
    )

  test("HelloWorkflow returns greeting from activity") {
    withHelloEnv { testEnv =>
      newHelloStub(testEnv).sayHello("World") shouldBe "Hello, World!"
    }
  }

  test("HelloWorkflow greets different names") {
    withHelloEnv { testEnv =>
      // Each stub is bound to a single workflow execution, so a new stub is needed per call
      for name <- Seq("Alice", "Bob", "Temporal") do
        newHelloStub(testEnv).sayHello(name) shouldBe s"Hello, ${name}!"
    }
  }

end HelloWorkflowTest
