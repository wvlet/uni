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
package wvlet.uni.design

import wvlet.uni.log.LogSupport
import wvlet.uni.surface.Surface
import TraceEvent.*

/**
  * A base Tracer implementation for tracking DI events
  */
trait Tracer extends LogSupport:
  // Report tracing events
  protected def report(event: TraceEvent): Unit

  private[design] def onSessionInitStart(session: Session): Unit = report(SessionInitStart(session))

  private[design] def onSessionInitEnd(session: Session): Unit = report(SessionInitEnd(session))

  private[design] def onInjectStart(session: Session, surface: Surface): Unit = report(
    InjectStart(session, surface)
  )

  private[design] def onInjectEnd(session: Session, surface: Surface): Unit = report(
    InjectEnd(session, surface)
  )

  private[design] def onInitInstanceStart(session: Session, surface: Surface, injectee: Any): Unit =
    report(InitInstanceStart(session, surface, injectee))

  private[design] def onInitInstanceEnd(session: Session, surface: Surface, injectee: Any): Unit =
    report(InitInstanceEnd(session, surface, injectee))

  private[design] def onStartInstance(session: Session, injectee: Injectee): Unit = report(
    StartInstance(session, injectee)
  )

  private[design] def afterStartInstance(session: Session, injectee: Injectee): Unit = report(
    AfterStartInstance(session, injectee)
  )

  private[design] def beforeShutdownInstance(session: Session, injectee: Injectee): Unit = report(
    BeforeShutdownInstance(session, injectee)
  )

  private[design] def onShutdownInstance(session: Session, injectee: Injectee): Unit = report(
    ShutdownInstance(session, injectee)
  )

  private[design] def onSessionStart(session: Session): Unit = report(SessionStart(session))

  private[design] def beforeSessionShutdown(session: Session): Unit = report(
    SessionBeforeShutdown(session)
  )

  private[design] def onSessionShutdown(session: Session): Unit = report(SessionShutdown(session))
  private[design] def onSessionEnd(session: Session): Unit      = report(SessionEnd(session))

end Tracer

object DefaultTracer extends Tracer with LogSupport:
  override protected def report(event: TraceEvent): Unit = trace(event)
