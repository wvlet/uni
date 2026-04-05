package wvlet.uni.temporal.example

import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.TestEnvironmentOptions
import wvlet.uni.test.UniTest

/**
  * Tests for DataPipelineWorkflow including retry behaviour.
  *
  * Each test creates its own TestWorkflowEnvironment so state (e.g. attempt counters in
  * DataActivitiesImpl) is isolated between test runs.
  */
class DataPipelineWorkflowTest extends UniTest:

  private def newTestEnv(): TestWorkflowEnvironment = TestWorkflowEnvironment.newInstance(
    TestEnvironmentOptions
      .newBuilder()
      .setWorkflowClientOptions(
        WorkflowClientOptions.newBuilder().setDataConverter(ScalaDataConverter.converter).build()
      )
      .build()
  )

  test("DataPipelineWorkflow completes after transient fetch failure") {
    val testEnv = newTestEnv()
    try
      val worker = testEnv.newWorker(TemporalExample.TaskQueue)
      worker.registerWorkflowImplementationTypes(classOf[DataPipelineWorkflowImpl])
      worker.registerActivitiesImplementations(DataActivitiesImpl())
      testEnv.start()

      val stub = testEnv
        .getWorkflowClient
        .newWorkflowStub(
          classOf[DataPipelineWorkflow],
          WorkflowOptions.newBuilder().setTaskQueue(TemporalExample.TaskQueue).build()
        )

      val result = stub.process(PipelineInput("sensor-42", "raw,data,stream"))

      // fetchData fails once, retries, then the pipeline completes
      result.sourceId shouldBe "sensor-42"
      // transformData uppercases and replaces "-" with "_"
      result.processedData shouldBe "RAW_DATA_FOR_SENSOR_42"
      // storeData returns processedData.length
      result.recordCount shouldBe "RAW_DATA_FOR_SENSOR_42".length
    finally
      testEnv.close()
  }

  test("PipelineResult captures sourceId") {
    val testEnv = newTestEnv()
    try
      val worker = testEnv.newWorker(TemporalExample.TaskQueue)
      worker.registerWorkflowImplementationTypes(classOf[DataPipelineWorkflowImpl])
      worker.registerActivitiesImplementations(DataActivitiesImpl())
      testEnv.start()

      val stub = testEnv
        .getWorkflowClient
        .newWorkflowStub(
          classOf[DataPipelineWorkflow],
          WorkflowOptions.newBuilder().setTaskQueue(TemporalExample.TaskQueue).build()
        )

      val result = stub.process(PipelineInput("device-99", "another,raw,payload"))
      result.sourceId shouldBe "device-99"
      (result.recordCount > 0) shouldBe true
    finally
      testEnv.close()
  }

end DataPipelineWorkflowTest
