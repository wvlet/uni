package example.renderer

import example.server.ChatService
import org.scalajs.dom
import wvlet.uni.dom.SizeRange
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx
import wvlet.uni.ui.{ColorScheme, DockLayout, DockSide, Edge, FoldablePane, Splitter}
import wvlet.uni.ui.base.DockModel

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

/** The composer, the panes, and the rail, rendered into a real DOM with no server. */
class ComponentTest extends DomTestSupport:

  test("the composer sends trimmed text on Enter and clears itself") {
    val sent = mutable.Buffer.empty[String]
    val m    = mount(Composer(sent += _, Rx.variable(false)))
    val box  = m.get("textarea")
    fireEvent.input(box, "  hello  ")
    fireEvent.keyDown(box, "Enter")
    sent.toSeq shouldBe Seq("hello")
    box.asInstanceOf[dom.html.TextArea].value shouldBe ""
  }

  test("the composer keeps Shift+Enter and IME Enter as text input") {
    val sent = mutable.Buffer.empty[String]
    val m    = mount(Composer(sent += _, Rx.variable(false)))
    val box  = m.get("textarea")
    fireEvent.input(box, "こんにちは")
    fireEvent.keyDown(box, "Enter", shiftKey = true)
    fireEvent.keyDown(box, "Enter", isComposing = true)
    sent.toSeq shouldBe Seq.empty
  }

  test("the composer waits while a reply is on its way") {
    val sent = mutable.Buffer.empty[String]
    val busy = Rx.variable(true)
    val m    = mount(Composer(sent += _, busy))
    // onMount wiring runs a step after mount
    settle().map { _ =>
      val box = m.get("textarea")
      fireEvent.input(box, "later")
      fireEvent.keyDown(box, "Enter")
      sent.toSeq shouldBe Seq.empty
      m.get("[aria-label=Send]").hasAttribute("disabled") shouldBe true
      busy := false
      fireEvent.keyDown(box, "Enter")
      sent.toSeq shouldBe Seq("later")
    }
  }

  private def paneWidth(m: Mounted): String =
    m.get("aside").asInstanceOf[dom.html.Element].style.width

  test("a foldable pane collapses to zero width and back") {
    val open = Rx.variable(true)
    val m    = mount(FoldablePane(open, Rx.variable(300), Edge.Left)())
    paneWidth(m) shouldBe "300px"
    open := false
    paneWidth(m) shouldBe "0px"
  }

  test("dragging the resize handle changes the width within its range") {
    val width  = Rx.variable(272)
    val handle = Splitter(width, Layout.sidebarWidth, Edge.Right, "Resize")
    val m      = mount(FoldablePane(Rx.variable(true), width, Edge.Right, Some(handle))())
    val grip   = m.get("[role=separator]")
    fireEvent.pointerDown(grip, clientX = 300)
    fireEvent.pointerMove(dom.window, clientX = 350)
    width.get shouldBe 322
    fireEvent.pointerMove(dom.window, clientX = 1000)
    width.get shouldBe Layout.sidebarWidth.max
    fireEvent.pointerUp(dom.window, clientX = 1000)
    // After pointerup, moves no longer resize
    fireEvent.pointerMove(dom.window, clientX = 0)
    width.get shouldBe Layout.sidebarWidth.max
    fireEvent.dblclick(grip)
    width.get shouldBe Layout.sidebarWidth.default
  }

  test("a handle on the left edge grows the pane as it moves left") {
    val width  = Rx.variable(300)
    val handle = Splitter(width, SizeRange(200, 300, 400), Edge.Left, "Resize")
    val m      = mount(handle)
    fireEvent.pointerDown(m.get("[role=separator]"), clientX = 500)
    fireEvent.pointerMove(dom.window, clientX = 450)
    fireEvent.pointerUp(dom.window, clientX = 450)
    width.get shouldBe 350
  }

  test("the dock's left-edge handle resizes the shared dock width and resets it") {
    val layout = Layout.inMemory()
    val handle = Splitter(layout.dockWidth, Layout.dockWidth, Edge.Left, "Resize dock")
    val m      = mount(FoldablePane(layout.dockOpen, layout.dockWidth, Edge.Left, Some(handle))())
    val grip   = m.get("[role=separator]")
    fireEvent.pointerDown(grip, clientX = 900)
    fireEvent.pointerMove(dom.window, clientX = 0)
    fireEvent.pointerUp(dom.window, clientX = 0)
    layout.dockWidth.get shouldBe Layout.dockWidth.max
    paneWidth(m) shouldBe s"${Layout.dockWidth.max}px"
    fireEvent.dblclick(grip)
    layout.dockWidth.get shouldBe Layout.dockWidth.default
  }

  test("a dock width stored under an older, larger max loads clamped and is saved back") {
    val prefix = "component-test.clamp."
    val key    = s"${prefix}dockWidth"
    dom.window.localStorage.setItem(key, "960")
    try
      val layout = Layout.persistent(prefix)
      Splitter(layout.dockWidth, Layout.dockWidth, Edge.Left, "Resize dock")
      layout.dockWidth.get shouldBe Layout.dockWidth.max
      dom.window.localStorage.getItem(key) shouldBe s"${Layout.dockWidth.max}"
    finally
      dom.window.localStorage.removeItem(key)
  }

  test("clicking the active rail icon folds the sidebar; another icon switches views") {
    val layout = Layout.inMemory()
    val m      = mount(IconRail(layout))
    fireEvent.click(m.get("[aria-label=Files]"))
    layout.view.get shouldBe View.Files
    layout.sidebarOpen.get shouldBe true
    fireEvent.click(m.get("[aria-label=Files]"))
    layout.sidebarOpen.get shouldBe false
  }

  test("the theme menu checks the current scheme, picks another, and sets data-theme") {
    val layout                               = Layout.inMemory()
    val root                                 = dom.document.createElement("div")
    val sub                                  = layout.theme.run(t => ColorScheme.applyTo(root, t))
    val m                                    = mount(IconRail(layout))
    def item(label: String): dom.HTMLElement = dom
      .document
      .querySelectorAll(".uni-menu [role=menuitemcheckbox]")
      .find(_.textContent == label)
      .get
      .asInstanceOf[dom.HTMLElement]
    val trigger = m.get("[aria-label=Theme]")
    trigger.getAttribute("aria-haspopup") shouldBe "menu"
    fireEvent.click(trigger)
    item("System").getAttribute("aria-checked") shouldBe "true"
    fireEvent.click(item("Dark"))
    layout.theme.get shouldBe ColorScheme.Dark
    root.getAttribute("data-theme") shouldBe "dark"
    // Choosing closes the menu
    dom.document.querySelector(".uni-menu") shouldBe null
    fireEvent.click(trigger)
    fireEvent.click(item("Light"))
    root.getAttribute("data-theme") shouldBe "light"
    sub.cancel
  }

  test("the palette lists commands, then conversations, and runs what it finds") {
    val layout  = Layout.inMemory()
    val store   = ChatStore(ChatService(respond = p => Future.successful(s"echo: ${p}")))
    var newChat = 0
    val open    = Rx.variable(false)
    Rx.future(store.load())
      .flatMap { _ =>
        mount(Palette(open, layout, store, () => newChat += 1))
        open := true
        settle()
      }
      .map { _ =>
        def labels =
          dom
            .document
            .querySelectorAll(".uni-palette__item .uni-palette__label")
            .map(_.textContent)
            .toSeq
        val input = dom.document.querySelector(".uni-palette__input")
        labels.head shouldBe "New chat"
        // The conversation the store loaded comes last, under its own group
        labels.last shouldBe store.conversations.get.head.title
        dom.document.querySelectorAll(".uni-palette__group").map(_.textContent).toSeq shouldBe
          Seq("Chat", "View", "Appearance", "Conversations")

        fireEvent.input(input, "show activity")
        labels.head shouldBe "Show Activity"
        layout.dockOpen := false
        fireEvent.keyDown(input, "Enter")
        layout.dockOpen.get shouldBe true
        layout.dockLayout.get.visible shouldBe Set(DockTab.Activity.id)
        open.get shouldBe false
      }
  }

  test("the activity log renders its calls through a virtual list, newest first") {
    val log = RpcLog()
    Seq("listConversations", "createConversation", "sendMessage").foreach(m =>
      log.track(m)(Rx.const(())).run(_ => ())
    )
    val m = mount(ActivityLog(log))
    m.get("[role=listbox]").getAttribute("aria-label") shouldBe "RPC calls"
    m.queryAll(".uni-list__row").map(_.textContent.takeWhile(!_.isDigit)).toSeq shouldBe
      Seq("sendMessage", "createConversation", "listConversations")
    log.calls.get.map(_.id) shouldBe Seq(3L, 2L, 1L)
    m.text shouldContain "Last 3 calls"
  }

  test("the file tree opens folders from the keyboard") {
    val m     = mount(FileTree())
    val tree  = m.get("[role=tree]").asInstanceOf[dom.HTMLElement]
    def names = m.queryAll(".uni-list__row").map(_.textContent).toSeq
    names shouldBe
      Seq(
        "api",
        "server",
        "renderer",
        "HarnessApp.scala",
        "ChatStore.scala",
        "Shell.scala",
        "Chat.scala",
        "Dock.scala",
        "Palette.scala",
        "src"
      )
    tree.focus()
    fireEvent.keyDown(tree, "End")
    fireEvent.keyDown(tree, "ArrowRight")
    names.takeRight(4) shouldBe Seq("src", "main", "preload", "web")
    m.queryAll(".uni-list__row").last.getAttribute("aria-level") shouldBe "2"
  }

  test("the terminal title falls back to Terminal and is shortened") {
    TerminalPane.displayTitle("") shouldBe "Terminal"
    TerminalPane.displayTitle("  vim   main.scala ") shouldBe "vim main.scala"
    val long = TerminalPane.displayTitle("x" * 200)
    long.length shouldBe 120
    long.endsWith("…") shouldBe true
  }

  test("the Terminal panel is visible only while the dock is open and shows it") {
    Dock.isTerminalVisible(true, Layout.defaultDock(DockTab.Terminal)) shouldBe true
    Dock.isTerminalVisible(true, Layout.defaultDock(DockTab.Activity)) shouldBe false
    Dock.isTerminalVisible(false, Layout.defaultDock(DockTab.Terminal)) shouldBe false
    // Split off under Details, Terminal is visible beside it
    val split = DockModel.split(Layout.defaultDock(), DockTab.Terminal.id, Nil, DockSide.Bottom)
    Dock.isTerminalVisible(true, split) shouldBe true
  }

  test("a dock saved before layouts opens on its saved tab, and a layout is saved after") {
    val prefix = "component-test.dock."
    dom.window.localStorage.setItem(s"${prefix}dockTab", "Terminal")
    try
      val layout = Layout.persistent(prefix)
      layout.dockLayout.get shouldBe Layout.defaultDock(DockTab.Terminal)
      layout.showDock(DockTab.Activity)
      Option(dom.window.localStorage.getItem(s"${prefix}dockLayout")).flatMap(
        DockLayout.fromJSON
      ) shouldBe Some(Layout.defaultDock(DockTab.Activity))
      // A saved layout wins over the old tab
      Layout.persistent(prefix).dockLayout.get shouldBe Layout.defaultDock(DockTab.Activity)
    finally
      dom.window.localStorage.removeItem(s"${prefix}dockTab")
      dom.window.localStorage.removeItem(s"${prefix}dockLayout")
  }

  test("the dock splits Terminal under Details") {
    val layout = Layout.inMemory()
    val m      = mount(
      Dock(
        layout,
        ChatStore(ChatService(respond = p => Future.successful(p))),
        RpcLog(),
        Transport.HttpFetch
      )
    )
    m.queryAll("[role=tab]").map(_.getAttribute("data-panel")).toSeq shouldBe
      Seq("details", "activity", "terminal")
    // The fold button sits in the dock's top-right tab bar
    m.queryAll(".uni-dock__actions [aria-label^='Close dock']").size shouldBe 1
    layout.dockLayout := DockModel.split(layout.dockLayout.get, "terminal", Nil, DockSide.Bottom)
    m.queryAll(".uni-dock__group").size shouldBe 2
    m.queryAll(".uni-dock__group[data-top='true']").size shouldBe 1
    // Both groups show a panel: Details on top, the terminal below
    m.queryAll("[role=tabpanel]").size shouldBe 2
    m.queryAll("[role=tab][aria-selected='true']").map(_.getAttribute("data-panel")).toSeq shouldBe
      Seq("details", "terminal")
  }

  test("the terminal header shows the program's title and a bell reaches onBell") {
    var bells = 0
    val pane  = TerminalPane(Rx.variable(ColorScheme.Light), () => bells += 1)
    val m     = mount(pane)
    m.get("span.truncate").textContent shouldBe "Terminal"
    pane.term.write("\u001b]2;~/work/uni\u0007")
    m.get("span.truncate").textContent shouldBe "~/work/uni"
    pane.term.write("\u0007")
    bells shouldBe 1
  }

  test("offer to explain a failed command, and pass a prompt with its output to the chat") {
    var asked         = Seq.empty[String]
    val pane          = TerminalPane(Rx.variable(ColorScheme.Light), onAsk = text => asked :+= text)
    val m             = mount(pane)
    def explainButton = m.queryAll("button").find(_.textContent == "Explain error")
    val prompt        = "\u001b]133;A\u0007$ \u001b]133;B\u0007"
    pane
      .term
      .write(prompt + "make\r\n\u001b]133;C\u0007error: no target\r\n\u001b]133;D;2\u0007" + prompt)
    explainButton.isDefined shouldBe true
    explainButton.foreach(fireEvent.click(_))
    asked.size shouldBe 1
    asked.head shouldContain "`make` failed with exit code 2"
    asked.head shouldContain "error: no target"
    explainButton shouldBe None
    // A command that succeeds clears the offer
    pane.term.write("false\r\n\u001b]133;C\u0007\u001b]133;D;1\u0007" + prompt)
    explainButton.isDefined shouldBe true
    pane.term.write("true\r\n\u001b]133;C\u0007\u001b]133;D;0\u0007" + prompt)
    explainButton shouldBe None
  }

  test("cut long output from the top in the explain prompt") {
    val output = (1 to 200).map(i => s"line ${i}").mkString("\n")
    val text   = TerminalPane.explainPrompt(
      wvlet.uni.cli.term.TerminalCommand(0, "build", output, Some(1))
    )
    text shouldContain "line 200"
    text shouldNotContain "line 100\n"
    text shouldContain "(earlier output cut)"
  }

  test("the demo shell marks commands and their exit codes") {
    val term = wvlet.uni.cli.term.TerminalEmulator()
    DemoShell(term).start()
    term.sendText("echo hi\r")
    term.sendText("nope\r")
    term.commands.map(c => (c.command, c.exitCode)) shouldBe
      Seq(("echo hi", Some(0)), ("nope", Some(127)))
  }

  test("a sent prompt keeps its bubble when the reply confirms it, so its entrance plays once") {
    val reply = Promise[String]()
    val store = ChatStore(ChatService(respond = _ => reply.future))
    Rx.future(store.load())
      .flatMap { _ =>
        mount(ChatView(store, Layout.inMemory(), Transport.HttpFetch))
        val sent  = store.send("Hello Uni")
        val draft = dom.document.querySelector(".bubble-user").parentNode
        draft.textContent shouldContain "Sending…"
        reply.success("echo: Hello Uni")
        Rx.future(sent).map(_ => draft)
      }
      .map { draft =>
        val prompts = dom.document.querySelectorAll(".bubble-user")
        prompts.length shouldBe 1
        // The same node, now labelled with the stored prompt's time
        (prompts(0).parentNode eq draft) shouldBe true
        draft.textContent.contains("Sending…") shouldBe false
        store.messages.get.count(m => !ChatStore.isDraft(m)) shouldBe 2
      }
  }

  test("renaming a conversation keeps the other rows in the chat list") {
    val store = ChatStore(ChatService(respond = p => Future.successful(s"echo: ${p}")))
    def rows  = dom.document.querySelectorAll("button.group").toSeq
    Rx.future(store.load().flatMap(_ => store.newChat()))
      .flatMap { _ =>
        mount(ChatList(store, () => (), () => ()))
        rows.size shouldBe 2
        // The conversation that is not selected
        val other = rows(1)
        Rx.future(store.send("Hello Uni")).map(_ => other)
      }
      .map { other =>
        rows.head.textContent shouldContain "Hello Uni"
        rows.exists(_ eq other) shouldBe true
      }
  }

end ComponentTest
