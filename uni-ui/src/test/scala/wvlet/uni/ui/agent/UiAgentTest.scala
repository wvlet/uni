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
import wvlet.uni.agent.UiAgentApi
import wvlet.uni.dom.all.{div, span}
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.{JSONArray, JSONObject, JSONString}
import wvlet.uni.mcp.MCPServer
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*

import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions
import scala.scalajs.js

class UiAgentTest extends DomTestSupport:

  private def find(selector: String): dom.HTMLElement = dom
    .document
    .querySelector(selector)
    .asInstanceOf[dom.HTMLElement]

  // An agent that answers confirmations with `answer` instead of a dialog
  private def install(policy: AgentPolicy = AgentPolicy.allowAll, answer: Boolean = true): UiAgent =
    UiAgent.install(policy.withConfirm(_ => Rx.const(answer)))

  private def node(agent: UiAgent, kind: String): UiNode = agent.query(kind = Some(kind)).head

  test("a snapshot lists components with their kind, label, value, items, and actions") {
    val region = Rx.variable("tokyo")
    val query  = Rx.variable("")
    mount(
      div(
        Button("Save"),
        Select(region, options = Seq("tokyo", "oregon"), optionLabel = _.capitalize).withLabel(
          "Region"
        ),
        Input(query).withLabel("Search")
      )
    )
    val agent = install()
    val nodes = agent.snapshot()
    nodes.map(_.kind) shouldBe Seq("button", "select", "textfield")
    nodes.map(_.ref) shouldBe Seq("button:save", "select:region", "textfield:search")

    val select = nodes(1)
    select.label shouldBe "Region"
    select.value shouldBe Some("Tokyo")
    select.items shouldBe Seq(UiItem("Tokyo", selected = true), UiItem("Oregon"))
    val schema = select.action("select").flatMap(_.input).get
    schema.get("enum") shouldBe
      Some(JSONArray(IndexedSeq(JSONString("Tokyo"), JSONString("Oregon"))))
    nodes(2).action("setText").isDefined shouldBe true
    agent.uninstall()
  }

  test("an agent's change goes through onChange with the Agent source") {
    val region = Rx.variable("tokyo")
    val seen   = ArrayBuffer.empty[ChangeDetails[Any]]
    mount(
      Select(region, options = Seq("tokyo", "oregon"), optionLabel = s => s)
        .withLabel("Region")
        .onChange(d => seen += d)
    )
    val agent = install()
    agent
      .act("select:region", "select", Some("oregon"))
      .map { r =>
        r.status shouldBe ActStatus.Applied
        r.node.flatMap(_.value) shouldBe Some("oregon")
        region.get shouldBe "oregon"
        seen.last.source shouldBe ChangeSource.Agent
        seen.last.reason shouldBe ChangeReason.ItemPress
        agent.uninstall()
      }
  }

  test("onChange can cancel an agent's change") {
    val region = Rx.variable("tokyo")
    mount(
      Select(region, options = Seq("tokyo", "oregon"), optionLabel = s => s)
        .withLabel("Region")
        .onChange(d =>
          if d.source == ChangeSource.Agent then
            d.cancel()
        )
    )
    val agent = install()
    agent
      .act("select:region", "select", Some("oregon"))
      .map { r =>
        r.status shouldBe ActStatus.Canceled
        region.get shouldBe "tokyo"
        agent.uninstall()
      }
  }

  test("a bad value, an unknown action, and an unknown ref are refused") {
    val region = Rx.variable("tokyo")
    mount(
      Select(region, options = Seq("tokyo", "oregon"), optionLabel = s => s).withLabel("Region")
    )
    val agent = install()
    Rx.join(
        agent.act("select:region", "select", Some("paris")),
        agent.act("select:region", "press"),
        agent.act("select:nowhere", "select", Some("tokyo"))
      )
      .map { (bad, unknown, missing) =>
        bad.status shouldBe ActStatus.Refused
        bad.message shouldContain "Oregon".toLowerCase
        unknown.status shouldBe ActStatus.Refused
        missing.status shouldBe ActStatus.NotFound
        region.get shouldBe "tokyo"
        agent.uninstall()
      }
  }

  test("an agent types into a text field, and a password reads as redacted") {
    val name = Rx.variable("")
    val pass = Rx.variable("hunter2")
    mount(
      div(Input(name).withLabel("Name"), Input(pass).withLabel("Password").withType("password"))
    )
    val agent = install()
    node(agent, "textfield").ref shouldBe "textfield:name"
    agent.query(text = Some("password")).head.value shouldBe Some("<redacted>")
    agent
      .act("textfield:name", "setText", Some("Ada"))
      .map { r =>
        r.status shouldBe ActStatus.Applied
        name.get shouldBe "Ada"
        find("input").asInstanceOf[dom.HTMLInputElement].value shouldBe "Ada"
        agent.uninstall()
      }
  }

  test("hidden, secret, and ref modifiers change what an agent sees") {
    val token = Rx.variable("sk-123")
    mount(
      div(
        div(UiAgent.hidden, Button("Delete everything")),
        Input(token).withLabel("Token")(UiAgent.secret),
        Button("Go")(UiAgent.ref("go"))
      )
    )
    val agent = install()
    agent.snapshot().map(_.ref) shouldBe Seq("textfield:token", "go")
    node(agent, "textfield").value shouldBe Some("<redacted>")
    agent.uninstall()
  }

  test("a disabled component and a read-only policy list no actions and refuse them") {
    var pressed = 0
    mount(
      div(
        Button("Off").withDisabled(true).onClick(() => pressed += 1),
        Button("On").onClick(() => pressed += 1)
      )
    )
    val readOnly = install(AgentPolicy.readOnly)
    readOnly.snapshot().flatMap(_.actions) shouldBe Nil
    val agent = install()
    agent.query(text = Some("off")).head.disabled shouldBe true
    Rx.join(readOnly.act("button:on", "press"), agent.act("button:off", "press"))
      .map { (policy, off) =>
        policy.status shouldBe ActStatus.Refused
        off.status shouldBe ActStatus.Refused
        pressed shouldBe 0
        readOnly.uninstall()
        agent.uninstall()
      }
  }

  test("an action marked confirm runs only when the human allows it") {
    var deleted = 0
    mount(Button("Delete").onClick(() => deleted += 1)(UiAgent.confirm("Delete the project?")))
    val no  = install(answer = false)
    val yes = install(answer = true)
    no.act("button:delete", "press")
      .flatMap { r =>
        r.status shouldBe ActStatus.Declined
        deleted shouldBe 0
        yes.act("button:delete", "press")
      }
      .map { r =>
        r.status shouldBe ActStatus.Applied
        deleted shouldBe 1
        no.uninstall()
        yes.uninstall()
      }
  }

  test("the default confirmation shows a dialog the agent cannot answer itself") {
    var deleted = 0
    mount(Button("Delete").onClick(() => deleted += 1)(UiAgent.confirm("Delete the project?")))
    val agent  = UiAgent.install()
    val result = agent.act("button:delete", "press")
    var status = Option.empty[ActStatus]
    result.run(r => status = Some(r.status))
    settle().flatMap { _ =>
      find("[role=alertdialog]").textContent shouldContain "Delete the project?"
      // Another agent action waits for the human's answer
      agent
        .act("button:allow", "press")
        .flatMap { r =>
          r.status shouldBe ActStatus.Refused
          status shouldBe None
          fireEvent.click(
            dom.document.querySelectorAll("[role=alertdialog] button")(1).asInstanceOf[dom.Element]
          )
          status shouldBe Some(ActStatus.Applied)
          deleted shouldBe 1
          agent.uninstall()
          settle()
        }
    }
  }

  test("changes lists what people changed since a cursor, without the agent's own") {
    val region = Rx.variable("tokyo")
    val m      = mount(
      Select(region, options = Seq("tokyo", "oregon", "paris"), optionLabel = s => s).withLabel(
        "Region"
      )
    )
    val agent         = install()
    val (none, start) = agent.changes()
    none shouldBe Nil
    fireEvent.click(m.get("button"))
    fireEvent.click(find("[role=option]:nth-child(2)"))
    val (human, cursor) = agent.changes(start)
    human.map(c => (c.ref, c.key, c.previous, c.value, c.source)) shouldBe
      Seq((Some("select:region"), "selected", "tokyo", "oregon", ChangeSource.Pointer))
    agent
      .act("select:region", "select", Some("paris"))
      .map { _ =>
        agent.changes(cursor)._1 shouldBe Nil
        agent.uninstall()
      }
  }

  test("an agent opens a menu, then presses an item") {
    val open    = Rx.variable(false)
    var renamed = 0
    mount(
      Menu(
        open,
        trigger = Button("Actions"),
        entries = Seq(MenuItem("Rename").onSelect(() => renamed += 1))
      )
    )
    val agent = install()
    node(agent, "menu").action("open").isDefined shouldBe true
    agent
      .act("menu:actions", "open")
      .flatMap { _ =>
        open.get shouldBe true
        settle()
      }
      .flatMap { _ =>
        val item = node(agent, "menuitem")
        item.label shouldBe "Rename"
        agent.act(item.ref, "press")
      }
      .map { r =>
        r.status shouldBe ActStatus.Applied
        renamed shouldBe 1
        open.get shouldBe false
        agent.uninstall()
      }
  }

  test("an agent selects a tab, a list row, and sorts a table") {
    val tab   = Rx.variable("chat")
    val row   = Rx.variable(Option.empty[Int])
    val sort  = Rx.variable(Option.empty[SortBy])
    val items = Rx.const(Seq(1, 2, 3))
    mount(
      div(
        TabList(tab, label = "Views", tabs = Seq(Tab("chat", "Chat"), Tab("logs", "Logs"))),
        VirtualList(items, rowHeight = 24, key = identity, render = i => span(s"Item ${i}"))
          .withLabel("Numbers")
          .withSelected(row)
          .withTypeahead(i => s"Item ${i}"),
        DataTable(
          items,
          key = identity,
          columns = Seq(Column[Int]("n", "N", cell = i => span(i.toString)).sortBy(identity))
        ).withLabel("Table").withSort(sort)
      )
    )
    val agent = install()
    node(agent, "tabs").items.map(_.label) shouldBe Seq("Chat", "Logs")
    Rx.join(
        agent.act("tabs:views", "select", Some("Logs")),
        agent.act("list:numbers", "select", Some("Item 2")),
        agent.act("table:table", "sort", Some("n descending"))
      )
      .map { (t, l, s) =>
        Seq(t, l, s).map(_.status) shouldBe Seq.fill(3)(ActStatus.Applied)
        tab.get shouldBe "logs"
        row.get shouldBe Some(2)
        sort.get shouldBe Some(SortBy("n", SortDirection.Descending))
        agent.uninstall()
      }
  }

  test("an agent moves a row of a reorderable list, through onChange") {
    val items   = Rx.variable(Seq(1, 2, 3))
    val sources = scala.collection.mutable.ArrayBuffer.empty[ChangeSource]
    mount(
      VirtualList(items, rowHeight = 24, key = identity, render = i => span(s"Item ${i}"))
        .withLabel("Numbers")
        .withTypeahead(i => s"Item ${i}")
        .withReorder(items)
        .onChange(d => sources += d.source)
    )
    val agent = install()
    node(agent, "list").actions.map(_.name) shouldContain "move"
    // A person's move shows in the change feed, with row labels
    val start = agent.changes()._2
    val list  = find(".uni-virtual-list")
    list.focus()
    fireEvent.keyDown(list, "ArrowDown", altKey = true)
    agent.changes(start)._1.map(c => (c.ref, c.key, c.previous, c.value, c.source)) shouldBe
      Seq(
        (
          Some("list:numbers"),
          "order",
          "Item 1, Item 2, Item 3",
          "Item 2, Item 1, Item 3",
          ChangeSource.Keyboard
        )
      )
    sources.clear()
    Rx.join(
        agent.act("list:numbers", "move", Some("Item 3 to 1")),
        agent.act("list:numbers", "move", Some("Item 9 to 1")),
        agent.act("list:numbers", "move", Some("Item 1 to 7"))
      )
      .map { (ok, unknown, outOfRange) =>
        ok.status shouldBe ActStatus.Applied
        items.get shouldBe Seq(3, 2, 1)
        sources.toSeq shouldBe Seq(ChangeSource.Agent)
        unknown.status shouldNotBe ActStatus.Applied
        outOfRange.message shouldContain "Position must be 1 to 3"
        agent.uninstall()
      }
  }

  test("the JSON API serves as MCP tools") {
    val region = Rx.variable("tokyo")
    mount(
      Select(region, options = Seq("tokyo", "oregon"), optionLabel = s => s).withLabel("Region")
    )
    val agent  = install()
    val server = MCPServer().withTools[UiAgentApi](agent.api)
    server.tools.map(_.name) shouldBe Seq("act", "changes", "query", "snapshot")
    val call =
      """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"act",""" +
        """"arguments":{"ref":"select:region","action":"select","value":"oregon"}}}"""
    server
      .handleMessage(call)
      .flatMap { line =>
        line.get shouldContain "applied"
        region.get shouldBe "oregon"
        agent.api.snapshot()
      }
      .map { snapshot =>
        JSON.parse(snapshot) shouldMatch { case _: JSONArray =>
        }
        agent.uninstall()
      }
  }

  test("installWebMcp registers the tools with document.modelContext and unregisters them") {
    val region                                               = Rx.variable("tokyo")
    val registered                                           = ArrayBuffer.empty[js.Dynamic]
    var signal                                               = Option.empty[js.Dynamic]
    val register: js.Function2[js.Dynamic, js.Dynamic, Unit] =
      (tool, options) =>
        registered += tool
        signal = Some(options.signal)
    dom.document.asInstanceOf[js.Dynamic].modelContext = js.Dynamic.literal(registerTool = register)
    mount(
      Select(region, options = Seq("tokyo", "oregon"), optionLabel = s => s).withLabel("Region")
    )
    val agent = install()
    val off   = agent.installWebMcp()
    registered.map(_.name.asInstanceOf[String]) shouldBe Seq("act", "changes", "query", "snapshot")
    val act = registered.head
    act.inputSchema.required.asInstanceOf[js.Array[String]].toSeq shouldBe Seq("ref", "action")
    val promise = act
      .execute(js.Dynamic.literal(ref = "select:region", action = "select", value = "oregon"))
      .asInstanceOf[js.Promise[js.Dynamic]]
    Rx.future(promise.toFuture)(using scala.concurrent.ExecutionContext.parasitic)
      .map { result =>
        result.content.asInstanceOf[js.Array[js.Dynamic]](0).text.asInstanceOf[String] shouldContain
          "applied"
        region.get shouldBe "oregon"
        off.cancel
        signal.get.aborted.asInstanceOf[Boolean] shouldBe true
        js.special.delete(dom.document, "modelContext")
        agent.uninstall()
      }
  }

  test("a confirmation that ends without an answer declines and frees later actions") {
    var pressed = 0
    mount(
      div(
        Button("Delete").onClick(() => pressed += 1)(UiAgent.confirm("Delete?")),
        Button("Save").onClick(() => pressed += 1)
      )
    )
    val agent = UiAgent.install(AgentPolicy.allowAll.withConfirm(_ => Rx.empty))
    agent
      .act("button:delete", "press")
      .flatMap { r =>
        r.status shouldBe ActStatus.Declined
        agent.act("button:save", "press")
      }
      .map { r =>
        r.status shouldBe ActStatus.Applied
        pressed shouldBe 1
        agent.uninstall()
      }
  }

  test("changes inside a hidden subtree stay out of the feed") {
    val plan = Rx.variable("free")
    val m    = mount(
      div(UiAgent.hidden, Select(plan, options = Seq("free", "team"), optionLabel = s => s))
    )
    val agent = install()
    fireEvent.click(m.get("button"))
    fireEvent.click(find("[role=option]:nth-child(2)"))
    plan.get shouldBe "team"
    agent.changes()._1 shouldBe Nil
    agent.uninstall()
  }

  test("a long list takes a row label as text instead of listing every row in the schema") {
    val rows = Rx.const((1 to 150).toSeq)
    val row  = Rx.variable(Option.empty[Int])
    mount(
      VirtualList(rows, rowHeight = 24, key = identity, render = i => span(i.toString))
        .withLabel("Rows")
        .withSelected(row)
    )
    val agent = install()
    val list  = node(agent, "list")
    list.items.size shouldBe 100
    list.moreItems shouldBe 50
    list.action("select").flatMap(_.input).flatMap(_.get("enum")) shouldBe None
    agent
      .act("list:rows", "select", Some("140"))
      .map { r =>
        r.status shouldBe ActStatus.Applied
        row.get shouldBe Some(140)
        agent.uninstall()
      }
  }

  test("a table names rows by withRowLabel, and a selection change reuses the labels") {
    case class User(id: Int, name: String)
    val rows  = Rx.const((1 to 200).map(i => User(i, s"User ${i}")))
    val row   = Rx.variable(Option.empty[Int])
    var calls = 0
    mount(
      DataTable(
        rows,
        key = _.id,
        columns = Seq(Column[User]("name", "Name", cell = u => span(u.name)))
      ).withLabel("Users")
        .withSelected(row)
        .withRowLabel { u =>
          calls += 1
          u.name
        }
    )
    val agent = install()
    node(agent, "table").items.take(2).map(_.label) shouldBe Seq("User 1", "User 2")
    // A second snapshot of the same rows reuses the labels
    val afterSnapshot = calls
    node(agent, "table")
    calls shouldBe afterSnapshot
    val (_, start) = agent.changes()
    fireEvent.click(find("[data-slot=data-table-row]:nth-child(2)"))
    row.get shouldBe Some(2)
    agent.changes(start)._1.map(c => (c.previous, c.value)) shouldBe Seq(("", "User 2"))
    calls shouldBe afterSnapshot
    agent
      .act("table:users", "select", Some("User 5"))
      .map { r =>
        r.status shouldBe ActStatus.Applied
        row.get shouldBe Some(5)
        agent.uninstall()
      }
  }

  test("a table without withRowLabel names rows by key") {
    mount(
      DataTable(
        Rx.const(Seq(7, 8)),
        key = identity,
        columns = Seq(Column[Int]("n", "N", cell = i => span(i.toString)))
      )
    )
    val agent = install()
    node(agent, "table").items.map(_.label) shouldBe Seq("7", "8")
    agent.uninstall()
  }

  test("a list names rows by withRowLabel, apart from its typeahead text") {
    case class Task(id: Int, title: String)
    val items = Rx.variable(Seq(Task(1, "apple"), Task(2, "banana"), Task(3, "cherry")))
    val row   = Rx.variable(Option.empty[Int])
    mount(
      VirtualList(items, rowHeight = 24, key = _.id, render = t => span(t.title))
        .withLabel("Tasks")
        .withSelected(row)
        .withTypeahead(_.title)
        .withRowLabel(t => s"Task ${t.id}: ${t.title}")
        .withReorder(items)
    )
    val agent = install()
    node(agent, "list").items.map(_.label) shouldBe
      Seq("Task 1: apple", "Task 2: banana", "Task 3: cherry")
    // Typing still matches the typeahead text, not the row label
    val list = find(".uni-virtual-list")
    list.focus()
    fireEvent.keyDown(list, "c")
    find("[data-active=true]").textContent shouldBe "cherry"
    // A click, a move, and its announcement name rows by the row label
    val start = agent.changes()._2
    fireEvent.click(find(".uni-list__row"))
    fireEvent.keyDown(list, "ArrowDown", altKey = true)
    agent.changes(start)._1.map(c => (c.key, c.previous, c.value)) shouldBe
      Seq(
        ("selected", "", "Task 1: apple"),
        (
          "order",
          "Task 1: apple, Task 2: banana, Task 3: cherry",
          "Task 2: banana, Task 1: apple, Task 3: cherry"
        )
      )
    find("[data-slot=announcer][aria-live=polite]").textContent shouldContain
      "Moved Task 1: apple to position 2"
    // New rows get new labels
    items := Seq(Task(1, "apricot"), Task(4, "date"))
    node(agent, "list").items.map(_.label) shouldBe Seq("Task 1: apricot", "Task 4: date")
    agent
      .act("list:tasks", "select", Some("Task 4: date"))
      .map { r =>
        r.status shouldBe ActStatus.Applied
        row.get shouldBe Some(4)
        agent.uninstall()
      }
  }

  test("a tree names rows by withRowLabel, and newly opened rows get labels") {
    case class File(path: String, kids: Seq[File] = Nil)
    val roots    = Seq(File("src", Seq(File("src/Main.scala"))), File("build.sbt"))
    val expanded = Rx.variable(Set.empty[String])
    val selected = Rx.variable(Option.empty[String])
    mount(
      Tree(Rx.const(roots), children = _.kids, key = _.path, render = f => span(f.path))
        .withLabel("Files")
        .withExpanded(expanded)
        .withSelected(selected)
        .withRowLabel(f => f.path.split('/').last)
    )
    val agent = install()
    node(agent, "tree").items.map(_.label) shouldBe Seq("src", "build.sbt")
    expanded := Set("src")
    node(agent, "tree").items.map(_.label) shouldBe Seq("src", "Main.scala", "build.sbt")
    val start = agent.changes()._2
    fireEvent.click(find(".uni-list__row:nth-child(3)"))
    agent.changes(start)._1.map(c => (c.key, c.value)) shouldBe Seq(("selected", "build.sbt"))
    agent
      .act("tree:files", "select", Some("Main.scala"))
      .map { r =>
        r.status shouldBe ActStatus.Applied
        selected.get shouldBe Some("src/Main.scala")
        agent.uninstall()
      }
  }

  test("an agent can't reach controls behind a modal dialog") {
    val open    = Rx.variable(false)
    var pressed = 0
    mount(
      div(
        Button("Save").onClick(() => pressed += 1),
        Dialog(open, content = Seq(Button("Confirm")))
      )
    )
    val agent = install()
    agent.snapshot().map(_.ref) shouldContain "button:save"
    open := true
    settle()
      .flatMap { _ =>
        val refs = agent.snapshot().map(_.ref)
        refs.exists(_.startsWith("dialog")) shouldBe true
        refs.contains("button:save") shouldBe false
        agent.act("button:save", "press")
      }
      .map { r =>
        r.status shouldBe ActStatus.NotFound
        pressed shouldBe 0
        open := false
        agent.snapshot().map(_.ref) shouldContain "button:save"
        agent.uninstall()
      }
  }

  test("a read-only field lists no actions and refuses an agent's edit") {
    val id = Rx.variable("ord-42")
    mount(Input(id).withLabel("Order id").withReadOnly(true))
    val agent = install()
    val n     = node(agent, "textfield")
    n.readOnly shouldBe true
    n.actions shouldBe Nil
    n.toJSON.get("readOnly") shouldBe Some(JSON.JSONBoolean(true))
    find("input").hasAttribute("readonly") shouldBe true
    agent
      .act("textfield:order-id", "setText", Some("ord-1"))
      .map { r =>
        r.status shouldBe ActStatus.Refused
        r.message shouldContain "read-only"
        id.get shouldBe "ord-42"
        agent.uninstall()
      }
  }

  test("a number field refuses text that isn't a number, such as grouped digits") {
    val price = Rx.variable("")
    mount(Input(price).withLabel("Price").withType("number"))
    val agent = install()
    Rx.join(
        agent.act("textfield:price", "setText", Some("1,499.5")),
        agent.act("textfield:price", "setText", Some("1e3"))
      )
      .map { (grouped, plain) =>
        grouped.status shouldBe ActStatus.Refused
        grouped.message shouldContain "not a number"
        plain.status shouldBe ActStatus.Applied
        price.get shouldBe "1e3"
        agent.uninstall()
      }
  }

  test("an agent asks before it adds or drops a row marked by withAgentConfirm") {
    val roles  = Rx.variable(Set("admin"))
    val single = Rx.variable(Option("viewer"))
    val asked  = ArrayBuffer.empty[String]
    val guard  = (r: String) => Option.when(r == "admin")("Change the admin role?")
    val rows   = Rx.const(Seq("viewer", "editor", "admin"))
    mount(
      div(
        VirtualList(rows, rowHeight = 24, key = identity, render = r => span(r))
          .withLabel("Roles")
          .withRowLabel(identity)
          .withMultiSelect(roles)
          .withAgentConfirm(guard),
        VirtualList(rows, rowHeight = 24, key = identity, render = r => span(r))
          .withLabel("Role")
          .withRowLabel(identity)
          .withSelected(single)
          .withAgentConfirm(guard)
      )
    )
    val no = UiAgent.install(
      AgentPolicy
        .allowAll
        .withConfirm { m =>
          asked += m
          Rx.const(false)
        }
    )
    val yes = install(answer = true)
    no.act("list:roles", "select", Some("editor"))
      .flatMap { dropped =>
        dropped.status shouldBe ActStatus.Declined
        roles.get shouldBe Set("admin")
        no.act("list:role", "select", Some("admin"))
      }
      .flatMap { added =>
        added.status shouldBe ActStatus.Declined
        single.get shouldBe Some("viewer")
        asked.toSeq shouldBe Seq("Change the admin role?", "Change the admin role?")
        // A step that touches no marked row doesn't ask
        no.act("list:role", "select", Some("editor"))
      }
      .flatMap { plain =>
        plain.status shouldBe ActStatus.Applied
        single.get shouldBe Some("editor")
        asked.size shouldBe 2
        yes.act("list:roles", "select", Some("viewer"))
      }
      .map { allowed =>
        allowed.status shouldBe ActStatus.Applied
        roles.get shouldBe Set("viewer")
        no.uninstall()
        yes.uninstall()
      }
  }

  test("a toast is listed by its message and an agent dismisses it") {
    val toasts = Toaster()
    mount(div(toasts))
    val agent = install()
    // An empty region isn't listed
    agent.snapshot() shouldBe Nil
    toasts.success("Session saved", durationMs = 0)
    agent.snapshot().map(n => (n.kind, n.label, n.value)) shouldBe
      Seq(("toast", "Session saved", Some("success")))
    agent.snapshot().head.children shouldBe Nil
    agent
      .act("toast:session-saved", "dismiss")
      .map { r =>
        r.status shouldBe ActStatus.Applied
        dom.document.querySelectorAll(".uni-toast").length shouldBe 0
        agent.uninstall()
      }
  }

  test("an agent runs a command from the open palette, through onChange, and undoes it") {
    val open     = Rx.variable(false)
    val region   = Rx.variable("tokyo")
    val sources  = ArrayBuffer.empty[ChangeSource]
    var cancel   = false
    val commands = Seq(
      Command("help", "Show help"),
      Command("oregon", "Switch to Oregon").onRun(() =>
        ChangeGate.write(region, "selected", "oregon", ChangeCause.current, ChangeGate.noHandler)
      )
    )
    mount(
      div(
        Select(region, options = Seq("tokyo", "oregon"), optionLabel = s => s).withLabel("Region"),
        CommandPalette(open, Rx.const(commands))
          .withLimit(1)
          .onChange { d =>
            sources += d.source
            if cancel then
              d.cancel()
          }
      )
    )
    val agent   = install()
    val history = UiHistory.record()
    open := true
    settle()
      .flatMap { _ =>
        val palette = node(agent, "commandpalette")
        palette.ref shouldBe "commandpalette:command-palette"
        // Every command, not only the one the limit lists
        palette.items.map(_.label) shouldBe Seq("Show help", "Switch to Oregon")
        cancel = true
        agent.act(palette.ref, "run", Some("Switch to Oregon"))
      }
      .flatMap { canceled =>
        canceled.status shouldBe ActStatus.Canceled
        region.get shouldBe "tokyo"
        cancel = false
        agent.act("commandpalette:command-palette", "run", Some("Switch to Oregon"))
      }
      .map { ran =>
        ran.status shouldBe ActStatus.Applied
        sources.toSeq shouldBe Seq(ChangeSource.Agent, ChangeSource.Agent)
        (open.get, region.get) shouldBe (false, "oregon")
        // The command's own write reverts; the palette closing is recorded but not undone
        history.undoSource(ChangeSource.Agent).map(_.status) shouldBe
          Seq(UndoStatus.Reverted, UndoStatus.Unsupported)
        region.get shouldBe "tokyo"
        history.close()
        agent.uninstall()
      }
  }

  test("an unlabeled list or select is named by its kind, not by the text inside it") {
    val region = Rx.variable("tokyo")
    mount(
      div(
        VirtualList(
          Rx.const(Seq(1, 2)),
          rowHeight = 24,
          key = identity,
          render = i => span(s"Row ${i}")
        ),
        Select(region, options = Seq("tokyo", "oregon"), optionLabel = s => s)
      )
    )
    val agent = install()
    agent.snapshot().map(n => (n.ref, n.label)) shouldBe Seq(("list", ""), ("select", ""))
    agent.uninstall()
  }

end UiAgentTest
