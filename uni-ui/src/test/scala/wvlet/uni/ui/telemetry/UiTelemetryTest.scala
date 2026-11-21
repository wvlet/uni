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
package wvlet.uni.ui.telemetry

import org.scalajs.dom
import wvlet.uni.dom.all.{div, span}
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.rx.{Rx, RxTimeProvider}
import wvlet.uni.ui.*
import wvlet.uni.ui.agent.{AgentPolicy, UiAgent}

import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions
import scala.scalajs.js

class UiTelemetryTest extends DomTestSupport:

  private class Recorder:
    val batches                           = ArrayBuffer.empty[UiEventBatch]
    val sink                              = UiTelemetrySink(b => batches += b)
    def events: Seq[UiEvent]              = batches.toSeq.flatMap(_.events)
    def named(name: String): Seq[UiEvent] = events.filter(_.name == name)

  private def find(selector: String): dom.HTMLElement = dom
    .document
    .querySelector(selector)
    .asInstanceOf[dom.HTMLElement]

  // `detail` counts pointer clicks; a click from Enter or Space on a focused button has 0
  private def clickWithDetail(el: dom.Element, detail: Int): Unit =
    val init = js.Dynamic.literal(bubbles = true, cancelable = true, detail = detail)
    el.dispatchEvent(new dom.MouseEvent("click", init.asInstanceOf[dom.MouseEventInit]))

  private def pointerClick(el: dom.Element): Unit = clickWithDetail(el, 1)

  private def install(r: Recorder, time: RxTimeProvider = RxTimeProvider.manual()): UiTelemetry =
    UiTelemetryConfig(r.sink).withTimeProvider(time).withAttribute("app.version", "1.2").install()

  test("record presses on buttons by their ref and input source") {
    val r = Recorder()
    mount(
      div(Button("Save"), Button("Off").withDisabled(true), div(UiAgent.hidden, Button("Internal")))
    )
    val t = install(r)
    pointerClick(find("button"))
    clickWithDetail(find("button"), 0)
    dom.document.querySelectorAll("button").foreach(b => pointerClick(b.asInstanceOf[dom.Element]))
    t.flush()
    val presses = r.named("ui.press")
    presses.map(_.ref) shouldBe Seq(Some("button:save"), Some("button:save"), Some("button:save"))
    presses.map(_.attributes("ui.source")) shouldBe Seq("pointer", "keyboard", "pointer")
    presses.head.attributes("ui.kind") shouldBe "button"
    presses.head.attributes("app.version") shouldBe "1.2"
    r.batches.head.sessionId.length shouldBe 32
    t.uninstall()
  }

  test("record a tab change with its value, and how long the previous tab was viewed") {
    val r    = Recorder()
    val tab  = Rx.variable("a")
    val time = RxTimeProvider.manual()
    mount(TabList(tab, label = "Views", tabs = Seq(Tab("a", "Alpha"), Tab("b", "Beta"))))
    val t = install(r, time)
    time.advance(0)
    pointerClick(dom.document.querySelectorAll("[role='tab']")(1))
    time.advance(0)
    t.flush()
    val change = r.named("ui.change").head
    change.ref shouldBe Some("tabs:views")
    change.attributes("ui.key") shouldBe "selected"
    change.attributes("ui.source") shouldBe "pointer"
    change.attributes.contains("ui.value") shouldBe true
    val view = r.named("ui.view").head
    view.attributes("ui.value") shouldBe "Alpha"
    (view.attributes("duration.ms").asInstanceOf[Long] >= 0L) shouldBe true
    t.uninstall()
  }

  test("merge a typing burst into one change and leave text values out") {
    val r    = Recorder()
    val name = Rx.variable("")
    val time = RxTimeProvider.manual()
    mount(Input(name).withLabel("Name"))
    val t     = install(r, time)
    val field = find("input")
    fireEvent.input(field, "a")
    fireEvent.input(field, "ab")
    fireEvent.input(field, "abc")
    time.advance(1000)
    t.flush()
    val changes = r.named("ui.change")
    changes.size shouldBe 1
    changes.head.ref shouldBe Some("textfield:name")
    changes.head.attributes.contains("ui.value") shouldBe false
    t.uninstall()
  }

  test("leave out changes the app's own code makes") {
    val r    = Recorder()
    val name = Rx.variable("")
    mount(Input(name).withLabel("Name"))
    val t = install(r)
    ChangeGate.write(name, "value", "set by app", ChangeCause.current, ChangeGate.noHandler)
    t.flush()
    r.named("ui.change") shouldBe Seq.empty
    t.uninstall()
  }

  test("record when a dialog opens and closes") {
    val r    = Recorder()
    val open = Rx.variable(false)
    val time = RxTimeProvider.manual()
    mount(div(Dialog(open, content = Seq(Button("Confirm")))))
    val t = install(r, time)
    open := true
    settle()
      .flatMap { _ =>
        time.advance(0)
        open := false
        settle()
      }
      .map { _ =>
        time.advance(0)
        t.flush()
        r.named("ui.open").map(_.attributes("ui.kind")) shouldBe Seq("dialog")
        val close = r.named("ui.close").head
        close.attributes("ui.kind") shouldBe "dialog"
        close.ref shouldBe r.named("ui.open").head.ref
        close.attributes.contains("duration.ms") shouldBe true
        t.uninstall()
      }
  }

  test("record a command palette command by its id") {
    val r        = Recorder()
    val open     = Rx.variable(false)
    val time     = RxTimeProvider.manual()
    var ran      = false
    val commands = Seq(Command("toggle-dock", "Toggle dock").onRun(() => ran = true))
    mount(div(CommandPalette(open, Rx.const(commands))))
    val t     = install(r, time)
    val agent = UiAgent.install(AgentPolicy.allowAll)
    open := true
    settle()
      .flatMap { _ =>
        agent.act("commandpalette:command-palette", "run", Some("Toggle dock"))
      }
      .map { _ =>
        ran shouldBe true
        t.flush()
        val run = r.named("command.run").head
        run.attributes("command.id") shouldBe "toggle-dock"
        run.attributes("ui.source") shouldBe "agent"
        agent.uninstall()
        t.uninstall()
      }
  }

  test("an unsampled session records nothing") {
    val r = Recorder()
    mount(Button("Save"))
    val t = UiTelemetryConfig(r.sink).withSampleRatio(0.0).install()
    t.isSampled shouldBe false
    pointerClick(find("button"))
    t.flush()
    r.events shouldBe Seq.empty
  }

  test("record nothing after uninstall, and send what was queued") {
    val r = Recorder()
    mount(Button("Save"))
    val t = install(r)
    pointerClick(find("button"))
    t.uninstall()
    r.named("ui.press").size shouldBe 1
    pointerClick(find("button"))
    t.flush()
    r.named("ui.press").size shouldBe 1
  }

  test("encode batches as the JSON UiTelemetryRelay accepts") {
    val batch = UiEventBatch(
      "abcd",
      Seq(UiEvent("ui.press", 1700000000000L, Map("ui.ref" -> "button:save", "duration.ms" -> 5L)))
    )
    val json = JSON.parse(UiTelemetrySink.toJson(batch).toJSON).asInstanceOf[JSONObject]
    json.get("session") shouldBe Some(JSONString("abcd"))
    val e = json.get("events").get.asInstanceOf[JSONArray].v.head.asInstanceOf[JSONObject]
    e.get("name") shouldBe Some(JSONString("ui.press"))
    e.get("time") shouldBe Some(JSONLong(1700000000000L))
    val attrs = e.get("attributes").get.asInstanceOf[JSONObject]
    attrs.get("ui.ref") shouldBe Some(JSONString("button:save"))
    attrs.get("duration.ms") shouldBe Some(JSONLong(5))
  }

  test("report events to Datadog RUM as custom actions") {
    val calls = ArrayBuffer.empty[(String, js.Dictionary[js.Any])]
    val rum   = js
      .Dynamic
      .literal(addAction =
        (name: String, context: js.Dictionary[js.Any]) => calls += (name -> context)
      )
    val sink = UiTelemetrySink.datadogRum(rum)
    sink.send(
      UiEventBatch(
        "s1",
        Seq(
          UiEvent("ui.press", 1L, Map("ui.ref" -> "button:save")),
          UiEvent("command.run", 2L, Map("command.id" -> "toggle-dock"))
        )
      ),
      unloading = false
    )
    calls.map(_._1).toSeq shouldBe Seq("ui.press button:save", "command.run toggle-dock")
    calls.head._2("session.id") shouldBe "s1"
    // Without the SDK on the page, nothing happens
    UiTelemetrySink.datadogRum(js.undefined).send(UiEventBatch("s", Seq.empty), unloading = false)
  }

end UiTelemetryTest
