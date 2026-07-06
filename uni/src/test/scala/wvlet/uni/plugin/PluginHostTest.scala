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
package wvlet.uni.plugin

import wvlet.uni.http.rpc.{RPCDispatcher, RPCPlugin, RPCRouter}
import wvlet.uni.http.rpc.RPCPlugin.*
import wvlet.uni.plugin.Command.*
import wvlet.uni.plugin.testing.PluginTestHost
import wvlet.uni.test.UniTest

trait NotesApi:
  def list(): Seq[String]

class NotesApiImpl extends NotesApi:
  override def list(): Seq[String] = Seq("first", "second")

/** A representative plugin contributing an RPC service, two commands, and a teardown hook. */
class NotesPlugin extends Plugin:
  var deactivated: Boolean                            = false
  override def id: String                             = "notes"
  override def activate(context: PluginContext): Unit =
    context.registerRpcRouter(RPCRouter.of[NotesApi](NotesApiImpl()))
    context.registerCommand("notes.count")(_ => 2)
    context.registerCommand("notes.echo")(args => args.headOption.getOrElse(""))
    context.onDeactivate(() => deactivated = true)

/**
  * Tests the plugin model the way an app developer would test their own plugin: activate it in
  * isolation via [[PluginTestHost]] and assert on its extension-point contributions and lifecycle.
  * Runs on the JVM with no Electron/browser runtime.
  */
class PluginHostTest extends UniTest:

  test("activating a plugin registers its commands") {
    val host = PluginTestHost.activate(NotesPlugin())
    host.activatedPlugins shouldBe Seq("notes")
    host.commandIds shouldBe Set("notes.count", "notes.echo")
    host.hasCommand("notes.count") shouldBe true
    host.hasCommand("notes.missing") shouldBe false
  }

  test("executeCommand invokes the registered handler with arguments") {
    val host = PluginTestHost.activate(NotesPlugin())
    host.executeCommand("notes.count") shouldBe 2
    host.executeCommand("notes.echo", "hi") shouldBe "hi"
  }

  test("executeCommand on an unknown id fails with the available ids listed") {
    val host = PluginTestHost.activate(NotesPlugin())
    val e    = intercept[java.util.NoSuchElementException] {
      host.executeCommand("notes.nope")
    }
    e.getMessage shouldContain "notes.nope"
    e.getMessage shouldContain "notes.count"
  }

  test("a plugin contributes its RPC service router") {
    val host = PluginTestHost.activate(NotesPlugin())
    host.rpcRouters.size shouldBe 1
    val paths = host.rpcRouters.flatMap(_.routes.map(_.path))
    paths.exists(_.endsWith("/list")) shouldBe true
    host.rpcDispatcher shouldMatch { case _: RPCDispatcher =>
    }
  }

  test("plugins contribute to app-defined extension points") {
    val viewPoint = ExtensionPoint[String]("app.view")
    val plugin    =
      new Plugin:
        override def id: String                             = "views"
        override def activate(context: PluginContext): Unit =
          context.contribute(viewPoint)("sidebar")
          context.contribute(viewPoint)("statusbar")
    val host = PluginTestHost.activate(plugin)
    host.contributions(viewPoint) shouldBe Seq("sidebar", "statusbar")
    // An unkeyed point has no keyed lookup, and unrelated points stay empty
    host.contribution(viewPoint, "sidebar") shouldBe None
    host.contributions(ExtensionPoint[String]("app.other")) shouldBe Seq.empty[String]
  }

  test("an unkeyed point accepts equal contributions from different plugins") {
    def contributor(pluginId: String): Plugin =
      new Plugin:
        override def id: String                             = pluginId
        override def activate(context: PluginContext): Unit =
          context.contribute(RPCPlugin.routerPoint)(RPCRouter.of[NotesApi](NotesApiImpl()))
    val host = PluginTestHost.activateAll(contributor("p1"), contributor("p2"))
    host.rpcRouters.size shouldBe 2
  }

  test("deactivate runs onDeactivate hooks and clears contributions") {
    val plugin = NotesPlugin()
    val host   = PluginTestHost.activate(plugin)
    plugin.deactivated shouldBe false
    host.deactivate()
    plugin.deactivated shouldBe true
    host.commandIds shouldBe Set.empty[String]
    host.rpcRouters shouldBe Seq.empty[RPCRouter]
    host.activatedPlugins shouldBe Seq.empty[String]
  }

  test("registering a duplicate command id within a plugin is rejected") {
    val plugin =
      new Plugin:
        override def id: String                             = "dup"
        override def activate(context: PluginContext): Unit =
          context.registerCommand("x")(_ => 1)
          context.registerCommand("x")(_ => 2)
    intercept[IllegalArgumentException] {
      PluginTestHost.activate(plugin)
    }
  }

  test("activating two plugins with conflicting command ids is rejected") {
    val p1 =
      new Plugin:
        override def id: String                             = "p1"
        override def activate(context: PluginContext): Unit =
          context.registerCommand("shared")(_ => 1)
    val p2 =
      new Plugin:
        override def id: String                             = "p2"
        override def activate(context: PluginContext): Unit =
          context.registerCommand("shared")(_ => 2)
    val e = intercept[IllegalArgumentException] {
      PluginTestHost.activateAll(p1, p2)
    }
    e.getMessage shouldContain "p1"
    e.getMessage shouldContain "p2"
  }

  test("activating the same plugin id twice is rejected") {
    val host = PluginTestHost.activate(NotesPlugin())
    intercept[IllegalArgumentException] {
      host.activate(NotesPlugin())
    }
  }

end PluginHostTest
