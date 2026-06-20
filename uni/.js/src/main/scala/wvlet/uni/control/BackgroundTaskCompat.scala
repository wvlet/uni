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
package wvlet.uni.control

/**
  * Scala.js worker: there are no threads (and Node `worker_threads` run a separate JS realm that
  * can't host an arbitrary Scala closure — see adr/2026-05-14-nodejs-sync-http.md), so the body
  * runs inline and completes before `start` returns. The gate is therefore a no-op: by the time the
  * caller can call `await`, the task is already done.
  */
private[control] object BackgroundTaskCompat:
  def runWorker(body: () => Unit): Unit = body()

  def newGate(): Gate = NoOpGate

private[control] object NoOpGate extends Gate:
  override def await(): Unit  = ()
  override def signal(): Unit = ()
