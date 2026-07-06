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
package wvlet.uni.electron.testing

import wvlet.uni.http.rpc.{RPCClient, RPCRouter}
import wvlet.uni.surface.Surface
import wvlet.uni.test.UniTest

/** Shared counter state, mirroring the `examples/electron-app` reference service. */
case class CounterState(value: Int)

trait CounterApi:
  def get(): CounterState
  def increment(): CounterState
  def add(delta: Int): CounterState

class CounterApiImpl extends CounterApi:
  private var current                    = 0
  override def get(): CounterState       = CounterState(current)
  override def increment(): CounterState =
    current += 1;
    CounterState(current)

  override def add(delta: Int): CounterState =
    current += delta;
    CounterState(current)

/**
  * Integration test for an Electron RPC service the way a desktop app would write it: serve the
  * real router in an [[ElectronTestbed]] and drive it through the renderer-side RPC client, with no
  * Electron runtime in sight.
  */
class ElectronTestbedTest extends UniTest:

  private def rpcClient: RPCClient = RPCClient.build(
    Surface.of[CounterApi],
    Surface.methodsOf[CounterApi]
  )

  test("a stateful service keeps state across IPC calls") {
    val testbed = ElectronTestbed.serve(RPCRouter.of[CounterApi](CounterApiImpl()))
    val client  = testbed.newAsyncClient
    val rpc     = rpcClient
    for
      s0 <- rpc.callAsync[CounterState](client, "get", Seq.empty)
      s1 <- rpc.callAsync[CounterState](client, "increment", Seq.empty)
      s2 <- rpc.callAsync[CounterState](client, "increment", Seq.empty)
      s3 <- rpc.callAsync[CounterState](client, "add", Seq(10))
    yield
      s0.value shouldBe 0
      s1.value shouldBe 1
      s2.value shouldBe 2
      s3.value shouldBe 12
  }

  test("requesting an unserved channel rejects with a clear error") {
    val testbed = ElectronTestbed.serve(RPCRouter.of[CounterApi](CounterApiImpl()))
    val client  =
      wvlet
        .uni
        .http
        .Http
        .client
        .withChannelFactory(testbed.channelFactory("nonexistent-channel"))
        .newAsyncClient
    rpcClient
      .callAsync[CounterState](client, "get", Seq.empty)
      .transform {
        case scala.util.Failure(e) =>
          e.getMessage shouldContain "No Electron RPC handler"
        case scala.util.Success(v) =>
          fail(s"Expected a failure for an unserved channel, but got ${v}")
      }
  }

end ElectronTestbedTest
