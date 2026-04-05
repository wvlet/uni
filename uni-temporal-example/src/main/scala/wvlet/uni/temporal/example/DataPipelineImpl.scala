package wvlet.uni.temporal.example

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import wvlet.uni.log.LogSupport

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
  * DataPipeline workflow implementation.
  *
  * Chaining activities sequentially: fetchData → transformData → storeData. Each activity is
  * scheduled with explicit retry options demonstrating Temporal's built-in retry mechanism.
  */
class DataPipelineWorkflowImpl extends DataPipelineWorkflow with LogSupport:

  private val activities: DataActivities = Workflow.newActivityStub(
    classOf[DataActivities],
    ActivityOptions
      .newBuilder()
      // Each activity call must complete within 30s
      .setStartToCloseTimeout(Duration.ofSeconds(30))
      .setRetryOptions(
        RetryOptions
          .newBuilder()
          // Retry up to 3 times with 1-second initial interval
          .setMaximumAttempts(3)
          .setInitialInterval(Duration.ofSeconds(1))
          .setBackoffCoefficient(2.0)
          .build()
      )
      .build()
  )

  override def process(input: PipelineInput): PipelineResult =
    // Step 1: fetch
    val rawData = activities.fetchData(input.sourceId)
    // Step 2: transform
    val processedData = activities.transformData(rawData)
    // Step 3: store
    val recordCount = activities.storeData(input.sourceId, processedData)
    PipelineResult(input.sourceId, processedData, recordCount)

/**
  * DataActivities implementation with a simulated transient failure on the first fetch attempt.
  *
  * The static failCount tracks attempts across activity retries (the activity worker process is the
  * same in tests). In production each activity runs in its own thread; Temporal supplies the
  * attempt number via `ActivityExecutionContext`.
  */
class DataActivitiesImpl extends DataActivities with LogSupport:

  // Tracks how many times fetchData has been attempted (simulates transient failures)
  private val fetchAttempts = AtomicInteger(0)

  override def fetchData(sourceId: String): String =
    val attempt = fetchAttempts.incrementAndGet()
    logger.info(s"fetchData attempt ${attempt} for source '${sourceId}'")
    if attempt == 1 then
      // Simulate a transient failure on the first attempt
      throw RuntimeException(s"Transient network error fetching '${sourceId}' (attempt 1)")
    s"raw-data-for-${sourceId}"

  override def transformData(rawData: String): String =
    logger.info(s"Transforming: ${rawData}")
    rawData.toUpperCase.replace("-", "_")

  override def storeData(sourceId: String, processedData: String): Int =
    logger.info(s"Storing ${processedData} for source '${sourceId}'")
    // Simulate storing: return a fake record count
    processedData.length
