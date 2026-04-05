package wvlet.uni.temporal.example

import io.temporal.activity.{ActivityInterface, ActivityMethod}
import io.temporal.workflow.{WorkflowInterface, WorkflowMethod}

/** Input/output data types for the pipeline. */
case class PipelineInput(sourceId: String, rawData: String)
case class PipelineResult(sourceId: String, processedData: String, recordCount: Int)

/**
  * A multi-step data pipeline workflow.
  *
  * Demonstrates sequential activity chaining. Each step is independently retried on failure, and
  * the workflow survives worker restarts — Temporal replays history to restore state.
  */
@WorkflowInterface
trait DataPipelineWorkflow:
  @WorkflowMethod
  def process(input: PipelineInput): PipelineResult

/** Activities for the data pipeline. Each method is a separately-retried unit of work. */
@ActivityInterface
trait DataActivities:
  /** Fetch raw data from a source. Simulates transient failures to show retry behaviour. */
  @ActivityMethod
  def fetchData(sourceId: String): String

  /** Transform/clean the raw data. */
  @ActivityMethod
  def transformData(rawData: String): String

  /** Persist the result and return a record count. */
  @ActivityMethod
  def storeData(sourceId: String, processedData: String): Int
