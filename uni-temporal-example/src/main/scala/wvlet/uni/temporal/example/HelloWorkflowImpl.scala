package wvlet.uni.temporal.example

import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import wvlet.uni.log.LogSupport

import java.time.Duration

/**
  * Workflow implementation.
  *
  * Workflow code must be deterministic — no I/O, random numbers, or wall-clock time. All side
  * effects go through activities. Temporal replays workflow history on restarts; non-determinism
  * breaks replay.
  */
class HelloWorkflowImpl extends HelloWorkflow with LogSupport:

  // Activity stub: calls are scheduled as Temporal activities, not direct method calls.
  private val activities: GreetingActivities = Workflow.newActivityStub(
    classOf[GreetingActivities],
    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build()
  )

  override def sayHello(name: String): String = activities.composeGreeting("Hello", name)

/**
  * Activity implementation. Activities are the side-effectful units — they do real work and can be
  * retried independently.
  */
class GreetingActivitiesImpl extends GreetingActivities with LogSupport:
  override def composeGreeting(greeting: String, name: String): String =
    logger.info(s"Composing greeting for ${name}")
    s"${greeting}, ${name}!"
