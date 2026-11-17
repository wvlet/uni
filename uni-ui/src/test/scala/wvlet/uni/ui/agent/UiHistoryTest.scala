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
package wvlet.uni.ui.agent

import org.scalajs.dom
import wvlet.uni.dom.SizeRange
import wvlet.uni.dom.all.div
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.*

import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions

class UiHistoryTest extends DomTestSupport:

  private def field(label: String): dom.HTMLInputElement = dom
    .document
    .querySelector(s"input[aria-label='${label}']")
    .asInstanceOf[dom.HTMLInputElement]

  // The human types into a field
  private def typeInto(label: String, text: String): Unit = fireEvent.input(field(label), text)

  private def install(): UiAgent = UiAgent.install(
    AgentPolicy.allowAll.withConfirm(_ => Rx.const(true))
  )

  private def form(region: RxVar[String], name: RxVar[String], note: RxVar[String]) = mount(
    div(
      Select(region, options = Seq("tokyo", "oregon", "paris"), optionLabel = s => s).withLabel(
        "Region"
      ),
      Input(name).withLabel("Name"),
      Input(note).withLabel("Note")
    )
  )

  private def statuses(rs: Seq[UndoResult]): Seq[(String, UndoStatus)] = rs.map(r =>
    (r.entry.ref.getOrElse(""), r.status)
  )

  test("records changes from every source and undoes only the agent's") {
    val (region, name, note) = (Rx.variable("tokyo"), Rx.variable(""), Rx.variable(""))
    form(region, name, note)
    val agent   = install()
    val history = UiHistory.record()
    agent.act("select:region", "select", Some("oregon"))
    typeInto("Name", "Ada")
    agent.act("textfield:note", "setText", Some("call back"))

    history.entries.map(e => (e.ref, e.source)) shouldBe
      Seq(
        (Some("select:region"), ChangeSource.Agent),
        (Some("textfield:name"), ChangeSource.Keyboard),
        (Some("textfield:note"), ChangeSource.Agent)
      )
    history.entries.head.describe shouldBe "Agent set Region to oregon"
    history.entries(1).describe shouldBe "User set Name to Ada"

    statuses(history.undoSource(ChangeSource.Agent)) shouldBe
      Seq("textfield:note" -> UndoStatus.Reverted, "select:region" -> UndoStatus.Reverted)
    (region.get, name.get, note.get) shouldBe ("tokyo", "Ada", "")
    field("Note").value shouldBe ""
    history.entries.map(_.state) shouldBe Seq(EntryState.Undone, EntryState.Live, EntryState.Undone)
    // Nothing left to undo
    history.undoSource(ChangeSource.Agent) shouldBe Nil
    history.close()
    agent.uninstall()
  }

  test("a human edit after the agent's change wins, as a conflict") {
    val (region, name, note) = (Rx.variable("tokyo"), Rx.variable(""), Rx.variable(""))
    form(region, name, note)
    val agent   = install()
    val history = UiHistory.record()
    agent.act("textfield:name", "setText", Some("Bob"))
    typeInto("Name", "Bobby")
    agent.act("textfield:name", "setText", Some("Robert"))

    val results = history.undoSource(ChangeSource.Agent)
    results.map(_.status) shouldBe Seq(UndoStatus.Reverted, UndoStatus.Conflict)
    // The newer agent change is gone; the human's text stays
    name.get shouldBe "Bobby"
    results(1).entry.value shouldBe "Bob"
    results(1).entry.state shouldBe EntryState.Live
    history.close()
    agent.uninstall()
  }

  test("successive agent changes to one field undo back to the original") {
    val (region, name, note) = (Rx.variable("tokyo"), Rx.variable("Ann"), Rx.variable(""))
    form(region, name, note)
    val agent   = install()
    val history = UiHistory.record()
    agent.act("textfield:name", "setText", Some("Bob"))
    agent.act("textfield:name", "setText", Some("Cy"))
    history.undoSource(ChangeSource.Agent).map(_.status) shouldBe
      Seq(UndoStatus.Reverted, UndoStatus.Reverted)
    name.get shouldBe "Ann"

    // Undoing an older entry while a newer one is in effect is a conflict, even with equal values
    agent.act("textfield:name", "setText", Some("Bob"))
    typeInto("Name", "Ann")
    typeInto("Name", "Bob")
    val older = history.entries.find(e => e.value == "Bob" && e.state == EntryState.Live).get
    history.undo(older).status shouldBe UndoStatus.Conflict
    name.get shouldBe "Bob"
    history.close()
    agent.uninstall()
  }

  test("a revert goes through onChange, which can cancel it, and is not recorded") {
    val name = Rx.variable("")
    val seen = ArrayBuffer.empty[ChangeDetails[Any]]
    var keep = true
    mount(
      Input(name)
        .withLabel("Name")
        .onChange { d =>
          seen += d
          if d.reason == ChangeReason.Undo && keep then
            d.cancel()
        }
    )
    val agent   = install()
    val history = UiHistory.record()
    val feed    = ArrayBuffer.empty[UiChange]
    val off     = agent.subscribe(feed += _)
    agent.act("textfield:name", "setText", Some("Bob"))

    history.undoSource(ChangeSource.Agent).map(_.status) shouldBe Seq(UndoStatus.Canceled)
    name.get shouldBe "Bob"
    seen.last.source shouldBe ChangeSource.Programmatic
    history.entries.map(_.state) shouldBe Seq(EntryState.Live)

    keep = false
    history.undoSource(ChangeSource.Agent).map(_.status) shouldBe Seq(UndoStatus.Reverted)
    name.get shouldBe ""
    // The revert is reported to others as an undo by app code, not a new human edit
    feed.last.reason shouldBe "undo"
    feed.last.source shouldBe ChangeSource.Programmatic
    agent.changes()._1.map(_.reason) shouldBe Seq("undo")
    history.entries.size shouldBe 1
    off.cancel
    history.close()
    agent.uninstall()
  }

  test("an onChange handler that undoes during a revert does nothing") {
    val name               = Rx.variable("")
    var history: UiHistory = null
    val nested             = ArrayBuffer.empty[Seq[UndoResult]]
    mount(
      Input(name)
        .withLabel("Name")
        .onChange { d =>
          if d.reason == ChangeReason.Undo then
            nested += history.undoSource(ChangeSource.Agent)
        }
    )
    val agent = install()
    history = UiHistory.record()
    agent.act("textfield:name", "setText", Some("Bob"))
    history.undoSource(ChangeSource.Agent).map(_.status) shouldBe Seq(UndoStatus.Reverted)
    nested shouldBe ArrayBuffer(Nil)
    history.close()
    agent.uninstall()
  }

  test("a stage collects the agent's changes, and revert keeps the human's edits") {
    val (region, name, note) = (Rx.variable("tokyo"), Rx.variable(""), Rx.variable(""))
    form(region, name, note)
    val agent   = install()
    val history = UiHistory.record()
    val stage   = history.stage("Suggested contact")
    agent.act("textfield:name", "setText", Some("Ada"))
    agent.act("textfield:note", "setText", Some("VIP"))
    typeInto("Note", "VIP customer")
    agent.act("select:region", "select", Some("paris"))

    stage.entries.map(_.ref) shouldBe
      Seq(Some("textfield:name"), Some("textfield:note"), Some("select:region"))
    stage.entries.flatMap(_.stage).distinct shouldBe Seq("Suggested contact")
    intercept[IllegalStateException] {
      history.stage("Another")
    }

    statuses(stage.revert()) shouldBe
      Seq(
        "select:region"  -> UndoStatus.Reverted,
        "textfield:note" -> UndoStatus.Conflict,
        "textfield:name" -> UndoStatus.Reverted
      )
    (region.get, name.get, note.get) shouldBe ("tokyo", "", "VIP customer")
    stage.state shouldBe StageState.Reverted
    stage.revert() shouldBe Nil
    history.close()
    agent.uninstall()
  }

  test("accepted changes stay when the agent's changes are undone") {
    val (region, name, note) = (Rx.variable("tokyo"), Rx.variable(""), Rx.variable(""))
    form(region, name, note)
    val agent   = install()
    val history = UiHistory.record()
    val stage   = history.stage("Region")
    agent.act("select:region", "select", Some("oregon"))
    stage.accept()
    stage.state shouldBe StageState.Accepted
    stage.entries.map(_.state) shouldBe Seq(EntryState.Accepted)
    // A change after the stage closed joins no stage
    agent.act("textfield:name", "setText", Some("Ada"))
    history.entries.last.stage shouldBe None

    statuses(history.undoSource(ChangeSource.Agent)) shouldBe
      Seq("textfield:name" -> UndoStatus.Reverted)
    region.get shouldBe "oregon"
    history.undo(stage.entries.head).status shouldBe UndoStatus.Skipped
    // A new stage can open once the last one closed
    history.stage("Next").isOpen shouldBe true
    history.close()
    agent.uninstall()
  }

  test("secret values are redacted and not kept, and hidden components are not recorded") {
    val token  = Rx.variable("sk-1")
    val pass   = Rx.variable("")
    val hidden = Rx.variable("")
    mount(
      div(
        Input(token).withLabel("Token")(UiAgent.secret),
        Input(pass).withLabel("Password").withType("password"),
        div(UiAgent.hidden, Input(hidden).withLabel("Internal"))
      )
    )
    val agent   = install()
    val history = UiHistory.record()
    agent.act("textfield:token", "setText", Some("sk-2"))
    typeInto("Password", "hunter2")
    typeInto("Internal", "x")

    history.entries.map(e => (e.ref, e.previous, e.value, e.reversible)) shouldBe
      Seq(
        (Some("textfield:token"), "<redacted>", "<redacted>", false),
        (Some("textfield:password"), "<redacted>", "<redacted>", false)
      )
    history.entries.exists(_.toString.contains("sk-")) shouldBe false
    history.entries.exists(_.describe.contains("hunter2")) shouldBe false
    history.undoSource(ChangeSource.Agent).map(_.status) shouldBe Seq(UndoStatus.Unsupported)
    token.get shouldBe "sk-2"
    history.close()
    agent.uninstall()
  }

  test("an unmounted component is missing, and its state is left alone") {
    val name    = Rx.variable("")
    val m       = mount(Input(name).withLabel("Name"))
    val agent   = install()
    val history = UiHistory.record()
    agent.act("textfield:name", "setText", Some("Ada"))
    m.unmount()
    history.undoSource(ChangeSource.Agent).map(r => (r.status, r.entry.state)) shouldBe
      Seq((UndoStatus.Missing, EntryState.Live))
    name.get shouldBe "Ada"
    history.close()
    agent.uninstall()
  }

  test("opening and closing overlays is recorded but not undone") {
    val open = Rx.variable(false)
    mount(Menu(open, trigger = Button("Actions"), entries = Seq(MenuItem("Rename"))))
    val agent   = install()
    val history = UiHistory.record()
    agent
      .act("menu:actions", "open")
      .map { _ =>
        open.get shouldBe true
        history.entries.map(e => (e.key, e.reversible)) shouldBe Seq(("open", false))
        history.undoSource(ChangeSource.Agent).map(_.status) shouldBe Seq(UndoStatus.Unsupported)
        open.get shouldBe true
        history.close()
        agent.uninstall()
      }
  }

  test("close stops recording, forgets changes, and closes an open stage") {
    val name = Rx.variable("")
    mount(Input(name).withLabel("Name"))
    val agent   = install()
    val history = UiHistory.record(limit = 2)
    val stage   = history.stage("s")
    agent.act("textfield:name", "setText", Some("a"))
    agent.act("textfield:name", "setText", Some("b"))
    agent.act("textfield:name", "setText", Some("c"))
    history.entries.map(_.value) shouldBe Seq("b", "c")
    history.close()
    stage.state shouldBe StageState.Closed
    stage.revert() shouldBe Nil
    history.entries shouldBe Nil
    agent.act("textfield:name", "setText", Some("d"))
    history.entries shouldBe Nil
    history.undoSource(ChangeSource.Agent) shouldBe Nil
    name.get shouldBe "d"
    intercept[IllegalStateException] {
      history.stage("again")
    }
    agent.uninstall()
  }

  test("a revert asks the handler of the component that owns the state now") {
    val name  = Rx.variable("")
    val first = mount(Input(name).withLabel("Name"))
    val agent = install()
    val hist  = UiHistory.record()
    agent.act("textfield:name", "setText", Some("Bob"))
    first.unmount()
    // A new field on the same state, whose handler refuses undo
    val seen   = ArrayBuffer.empty[ChangeReason]
    val second = mount(
      Input(name)
        .withLabel("Name")
        .onChange { d =>
          seen += d.reason
          if d.reason == ChangeReason.Undo then
            d.cancel()
        }
    )
    hist.undoSource(ChangeSource.Agent).map(_.status) shouldBe Seq(UndoStatus.Canceled)
    seen shouldBe Seq(ChangeReason.Undo)
    name.get shouldBe "Bob"
    second.unmount()
    hist.close()
    agent.uninstall()
  }

  test("a Dock layout change is undone through the dock's onChange") {
    val layout = Rx.variable(DockLayout.of("a", "b"))
    val seen   = ArrayBuffer.empty[ChangeReason]
    val d      = Dock(
      layout,
      label = "Workspace",
      panels = Seq(DockPanel("a", "A", div("a")), DockPanel("b", "B", div("b")))
    ).onChange(c => seen += c.reason)
    mount(div(d))
    val agent   = install()
    val history = UiHistory.record()
    d.close("b")
    layout.get shouldBe DockLayout.of("a")
    val entry = history.entries.head
    (entry.kind, entry.label, entry.key, entry.ref, entry.reversible) shouldBe
      (Some("dock"), "Workspace", "layout", None, true)

    history.undo(entry).status shouldBe UndoStatus.Reverted
    layout.get shouldBe DockLayout.of("a", "b")
    seen.last shouldBe ChangeReason.Undo
    // Agents still don't see the dock
    agent.snapshot().map(_.kind) shouldNotContain "dock"
    history.close()
    agent.uninstall()
  }

  test("a Dock layout changed again since is a conflict") {
    val layout = Rx.variable(DockLayout.of("a", "b"))
    val d      = Dock(
      layout,
      label = "Workspace",
      panels = Seq(DockPanel("a", "A", div("a")), DockPanel("b", "B", div("b")))
    )
    mount(div(d))
    val history = UiHistory.record()
    d.close("b")
    layout := DockLayout.of("a", "c")
    history.undo(history.entries.head).status shouldBe UndoStatus.Conflict
    layout.get shouldBe DockLayout.of("a", "c")
    history.close()
  }

  test("one splitter drag is one entry, undone in one step") {
    val width   = Rx.variable(300)
    val range   = SizeRange(min = 200, default = 300, max = 400)
    val m       = mount(Splitter(width, range, Edge.Right, "Resize sidebar"))
    val agent   = install()
    val history = UiHistory.record()
    val grip    = m.get("[role=separator]")
    fireEvent.pointerDown(grip, clientX = 300)
    fireEvent.pointerMove(dom.window, clientX = 320)
    fireEvent.pointerMove(dom.window, clientX = 350)
    fireEvent.pointerUp(dom.window, clientX = 350)
    // A second drag is an entry of its own
    fireEvent.pointerDown(grip, clientX = 350)
    fireEvent.pointerMove(dom.window, clientX = 370)
    fireEvent.pointerUp(dom.window, clientX = 370)
    width.get shouldBe 370
    // A drag back to where it started changes nothing, so it leaves no entry
    fireEvent.pointerDown(grip, clientX = 370)
    fireEvent.pointerMove(dom.window, clientX = 390)
    fireEvent.pointerMove(dom.window, clientX = 370)
    fireEvent.pointerUp(dom.window, clientX = 370)
    history.entries.map(e => (e.kind, e.label, e.previous, e.value)) shouldBe
      Seq(
        (Some("splitter"), "Resize sidebar", "300", "350"),
        (Some("splitter"), "Resize sidebar", "350", "370")
      )
    agent.snapshot().map(_.kind) shouldNotContain "splitter"

    history.undoSource(ChangeSource.Pointer).map(_.status) shouldBe
      Seq(UndoStatus.Reverted, UndoStatus.Reverted)
    width.get shouldBe 300
    // Keyboard steps stay one entry each
    fireEvent.keyDown(grip, "ArrowRight")
    fireEvent.keyDown(grip, "ArrowRight")
    history.entries.count(_.source == ChangeSource.Keyboard) shouldBe 2
    history.close()
    agent.uninstall()
  }

end UiHistoryTest
