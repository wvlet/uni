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
package wvlet.uni.http

import java.util.concurrent.atomic.AtomicBoolean

/**
  * Platform-neutral ping/pong heartbeat policy for detecting a half-open WebSocket peer.
  *
  * A backend drives this from a periodic clock (a poll timeout or a timer) by calling [[onTick]]
  * every `pingIntervalMillis`, and calls [[onActivity]] whenever any inbound frame is observed (any
  * frame proves the peer is alive, so there's no need to single out Pong). The state machine gives
  * a disconnected peer up to ~2 intervals: an idle interval sends a Ping; a second idle interval
  * with the Ping still unanswered reports [[Decision.Close]].
  */
private[http] class WebSocketHeartbeat:
  import WebSocketHeartbeat.Decision

  // Set by the reader on any inbound frame; consumed (and cleared) by each tick.
  private val sawActivity  = AtomicBoolean(false)
  private val awaitingPong = AtomicBoolean(false)

  /** Record inbound activity (any frame). Clears a pending ping. */
  def onActivity(): Unit =
    sawActivity.set(true)
    awaitingPong.set(false)

  /** Decide what to do at a heartbeat interval. */
  def onTick(): Decision =
    if sawActivity.getAndSet(false) then
      // Recent traffic already proves liveness; no ping needed this interval.
      awaitingPong.set(false)
      Decision.Idle
    else if awaitingPong.get() then
      // We pinged last interval and saw nothing since → the peer is gone.
      Decision.Close
    else
      awaitingPong.set(true)
      Decision.SendPing

end WebSocketHeartbeat

private[http] object WebSocketHeartbeat:
  enum Decision:
    case SendPing,
      Close,
      Idle
