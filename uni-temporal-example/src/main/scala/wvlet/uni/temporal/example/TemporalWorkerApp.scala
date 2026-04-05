package wvlet.uni.temporal.example

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.worker.WorkerFactory
import wvlet.uni.log.LogSupport

/** Shared task-queue name used by all workers and clients in this example. */
object TemporalExample:
  val TaskQueue = "uni-example-queue"

/**
  * Starts a Temporal worker connected to a locally-running Temporal dev server (`temporal server
  * start-dev`).
  *
  * This is an entry point for manual exploration — run the Temporal dev server first, then run this
  * app alongside `TemporalClientApp` to see workflows execute end-to-end.
  *
  * {{{
  *   # Terminal 1
  *   temporal server start-dev
  *   # Terminal 2
  *   ./sbt "temporalExample/run"        # starts worker
  * }}}
  */
object TemporalWorkerApp extends App with LogSupport:

  logger.info("Connecting to local Temporal dev server...")

  val service = WorkflowServiceStubs.newLocalServiceStubs()
  val client  = WorkflowClient.newInstance(service)
  val factory = WorkerFactory.newInstance(client)

  val worker = factory.newWorker(TemporalExample.TaskQueue)

  // Register workflow and activity implementations
  worker.registerWorkflowImplementationTypes(classOf[HelloWorkflowImpl])
  worker.registerWorkflowImplementationTypes(classOf[DataPipelineWorkflowImpl])
  worker.registerActivitiesImplementations(GreetingActivitiesImpl())
  worker.registerActivitiesImplementations(DataActivitiesImpl())

  factory.start()
  logger.info(s"Worker started on task queue '${TemporalExample.TaskQueue}'. Ctrl+C to stop.")

/**
  * Submits example workflows to the running worker.
  *
  * Run after `TemporalWorkerApp` is running.
  */
object TemporalClientApp extends App with LogSupport:

  val service = WorkflowServiceStubs.newLocalServiceStubs()
  val client  = WorkflowClient.newInstance(service)

  // --- Hello workflow ---
  val helloStub = client.newWorkflowStub(
    classOf[HelloWorkflow],
    WorkflowOptions
      .newBuilder()
      .setTaskQueue(TemporalExample.TaskQueue)
      .setWorkflowId("hello-workflow-1")
      .build()
  )

  val greeting = helloStub.sayHello("Temporal")
  logger.info(s"HelloWorkflow result: ${greeting}")

  // --- Data pipeline workflow ---
  val pipelineStub = client.newWorkflowStub(
    classOf[DataPipelineWorkflow],
    WorkflowOptions
      .newBuilder()
      .setTaskQueue(TemporalExample.TaskQueue)
      .setWorkflowId("pipeline-workflow-1")
      .build()
  )

  val result = pipelineStub.process(PipelineInput("sensor-42", "raw,data,stream"))
  logger.info(
    s"DataPipeline result: processed=${result.processedData}, records=${result.recordCount}"
  )

  service.shutdownNow()

end TemporalClientApp
