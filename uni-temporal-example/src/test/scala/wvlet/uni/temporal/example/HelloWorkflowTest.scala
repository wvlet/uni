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

  test("HelloWorkflow returns greeting from activity") {
    val testEnv = TestWorkflowEnvironment.newInstance()
    try
      val worker = testEnv.newWorker(TemporalExample.TaskQueue)
      worker.registerWorkflowImplementationTypes(classOf[HelloWorkflowImpl])
      worker.registerActivitiesImplementations(GreetingActivitiesImpl())
      testEnv.start()

      val stub = testEnv
        .getWorkflowClient
        .newWorkflowStub(
          classOf[HelloWorkflow],
          WorkflowOptions.newBuilder().setTaskQueue(TemporalExample.TaskQueue).build()
        )

      val result = stub.sayHello("World")
      result shouldBe "Hello, World!"
    finally
      testEnv.close()
  }

  test("HelloWorkflow greets different names") {
    val testEnv = TestWorkflowEnvironment.newInstance()
    try
      val worker = testEnv.newWorker(TemporalExample.TaskQueue)
      worker.registerWorkflowImplementationTypes(classOf[HelloWorkflowImpl])
      worker.registerActivitiesImplementations(GreetingActivitiesImpl())
      testEnv.start()

      val client = testEnv.getWorkflowClient
      for name <- Seq("Alice", "Bob", "Temporal") do
        val stub = client.newWorkflowStub(
          classOf[HelloWorkflow],
          WorkflowOptions.newBuilder().setTaskQueue(TemporalExample.TaskQueue).build()
        )
        stub.sayHello(name) shouldBe s"Hello, ${name}!"
    finally
      testEnv.close()
  }

end HelloWorkflowTest
