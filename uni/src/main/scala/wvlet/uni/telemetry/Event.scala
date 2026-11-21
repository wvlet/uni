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
package wvlet.uni.telemetry

import scala.collection.mutable

/**
  * A named event, such as `ui.press`, exported as an OpenTelemetry log record with `event.name`.
  *
  * @param epochNanos
  *   when the event happened, e.g. in the browser that reported it
  */
case class EventData(
    resource: Map[String, AttributeValue],
    name: String,
    epochNanos: Long,
    attributes: Map[String, AttributeValue]
)

/**
  * Receives events. `exportEvent` runs on the thread that emitted the event, so an exporter that
  * does I/O should buffer and send from its own thread.
  */
trait EventExporter extends TelemetryExporter:
  def exportEvent(event: EventData): Unit

object EventExporter:
  /** Drops every event. */
  val none: EventExporter = (_: EventData) => ()

  /** Keeps events in memory, for tests. */
  def inMemory(): InMemoryEventExporter = InMemoryEventExporter()

class InMemoryEventExporter extends EventExporter:
  private val buffer = mutable.ArrayBuffer.empty[EventData]

  override def exportEvent(event: EventData): Unit = synchronized(buffer += event)

  def events: Seq[EventData] = synchronized(buffer.toSeq)
  def clear(): Unit          = synchronized(buffer.clear())
