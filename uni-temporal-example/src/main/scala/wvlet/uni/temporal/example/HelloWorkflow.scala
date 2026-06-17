package wvlet.uni.temporal.example

import io.temporal.activity.{ActivityInterface, ActivityMethod}
import io.temporal.workflow.{WorkflowInterface, WorkflowMethod}

/**
  * The simplest possible Temporal workflow: greet a user.
  *
  * Temporal requires workflow and activity interfaces to be annotated with @WorkflowInterface /
  * @ActivityInterface.
  *   Implementations are registered separately with the worker.
  */
@WorkflowInterface
trait HelloWorkflow:
  @WorkflowMethod
  def sayHello(name: String): String

@ActivityInterface
trait GreetingActivities:
  @ActivityMethod
  def composeGreeting(greeting: String, name: String): String
