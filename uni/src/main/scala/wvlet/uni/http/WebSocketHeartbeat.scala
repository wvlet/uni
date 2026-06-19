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

  // onActivity (reader thread) and onTick (timer thread) can race on multi-threaded backends, so the
  // compound state transition is guarded by the instance lock (uncontended: ~one tick per interval).
  private var sawActivity  = false
  private var awaitingPong = false

  /** Record inbound activity (any frame). Clears a pending ping. */
  def onActivity(): Unit = synchronized {
    sawActivity = true
    awaitingPong = false
  }

  /** Decide what to do at a heartbeat interval. */
  def onTick(): Decision = synchronized {
    if sawActivity then
      // Recent traffic already proves liveness; no ping needed this interval.
      sawActivity = false
      awaitingPong = false
      Decision.Idle
    else if awaitingPong then
      // We pinged last interval and saw nothing since → the peer is gone.
      Decision.Close
    else
      awaitingPong = true
      Decision.SendPing
  }

end WebSocketHeartbeat

private[http] object WebSocketHeartbeat:
  enum Decision:
    case SendPing,
      Close,
      Idle
