package example.renderer

import example.api.ChatMessage
import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.HtmlTags
import wvlet.uni.rx.{Cancelable, Rx}

import scala.language.implicitConversions
import scala.scalajs.js

/**
  * The application shell: icon rail | foldable sidebar | chat | right dock.
  *
  * Each pane is a small RxElement that reads [[HarnessState]]. Folding is a width transition, so
  * the pane contents keep a fixed width and never reflow while the pane opens or closes.
  */
class Shell(state: HarnessState) extends RxElement:
  private var shortcuts: Cancelable = Cancelable.empty

  override def onMount(node: Any): Unit =
    // Cmd+B on macOS, Ctrl+B elsewhere: fold the sidebar. Add Shift for the right dock.
    shortcuts = Keyboard.bindAll(
      "meta+b"       -> (() => state.toggleSidebar()),
      "ctrl+b"       -> (() => state.toggleSidebar()),
      "meta+shift+b" -> (() => state.toggleDock()),
      "ctrl+shift+b" -> (() => state.toggleDock())
    )
    state.load()

  override def beforeUnmount: Unit = shortcuts.cancel

  override def render: RxElement = div(
    cls -> "flex h-screen w-screen overflow-hidden bg-surface text-fg",
    IconRail(state),
    Sidebar(state),
    ChatView(state),
    Dock(state)
  )

end Shell

/** A vertical strip of view icons, always visible. */
class IconRail(state: HarnessState) extends RxElement:
  private def railButton(v: View, icon: RxElement): RxElement = button(
    titleAttr -> v.label,
    ariaLabel -> v.label,
    cls       ->
      Rx.join(state.view, state.sidebarOpen)
        .map { (current, open) =>
          val active =
            if current == v.toString && open then
              "bg-accent-soft text-accent"
            else
              "text-muted hover:bg-hover hover:text-fg"
          s"flex size-9 items-center justify-center rounded-lg transition-colors ${active}"
        },
    onclick -> { () =>
      state.openView(v)
    },
    icon
  )

  override def render: RxElement = nav(
    cls -> "flex w-12 shrink-0 flex-col items-center gap-1 border-r border-line bg-rail py-3",
    div(
      cls ->
        "mb-3 flex size-8 items-center justify-center rounded-lg bg-accent text-sm font-bold text-white",
      "U"
    ),
    railButton(View.Chats, Icons.chat),
    railButton(View.Files, Icons.folder),
    railButton(View.Settings, Icons.sliders),
    div(cls -> "flex-1"),
    iconButton("Toggle right dock (⌘⇧B)", Icons.panelRight, () => state.toggleDock())
  )

end IconRail

def iconButton(label: String, icon: RxElement, action: () => Unit): RxElement = button(
  titleAttr -> label,
  ariaLabel -> label,
  cls       ->
    "flex size-8 items-center justify-center rounded-md text-muted transition-colors hover:bg-hover hover:text-fg",
  onclick -> { () =>
    action()
  },
  icon
)

/** The left sidebar. Its content follows the view selected on the rail. */
class Sidebar(state: HarnessState) extends RxElement:
  override def render: RxElement = aside(
    cls ->
      state
        .sidebarOpen
        .map { open =>
          val width =
            if open then
              "w-64 border-r"
            else
              "w-0"
          s"shrink-0 overflow-hidden border-line bg-panel transition-[width] duration-200 ${width}"
        },
    div(
      cls -> "flex h-full w-64 flex-col",
      state
        .view
        .map { _ =>
          val v = state.currentView
          div(
            cls -> "flex h-full flex-col",
            div(
              cls -> "flex h-12 items-center gap-2 border-b border-line px-3",
              span(cls -> "flex-1 text-sm font-semibold", v.label),
              when(v == View.Chats, iconButton("New chat", Icons.plus, () => state.newChat())),
              iconButton("Fold sidebar (⌘B)", Icons.panelLeft, () => state.toggleSidebar())
            ),
            v match
              case View.Chats =>
                conversationList
              case View.Files =>
                filesView
              case View.Settings =>
                settingsView
          )
        }
    )
  )

  private def conversationList: RxElement = div(
    cls -> "flex-1 space-y-0.5 overflow-y-auto p-2",
    Rx.join(state.conversations, state.selected)
      .map { (cs, sel) =>
        cs.map { c =>
          val active =
            if sel.contains(c.id) then
              "bg-hover text-fg"
            else
              "text-muted hover:bg-hover hover:text-fg"
          button(
            cls     -> s"block w-full truncate rounded-md px-3 py-2 text-left text-sm ${active}",
            onclick -> { () =>
              state.select(c.id)
            },
            c.title
          )
        }
      }
  )

  private def filesView: RxElement = div(
    cls -> "flex-1 space-y-1 p-3 text-sm text-muted",
    Seq("README.md", "build.sbt", "server/", "renderer/").map(f =>
      div(cls -> "flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-hover", Icons.file, f)
    ),
    p(cls -> "pt-3 text-xs", "Sample entries. Back this view with your own RPC API.")
  )

  private def settingsView: RxElement =
    def toggle(text: String, v: wvlet.uni.rx.RxVar[Boolean]): RxElement = label(
      cls -> "flex cursor-pointer items-center justify-between rounded-md px-2 py-2 hover:bg-hover",
      span(text),
      input(tpe -> "checkbox", cls -> "size-4 accent-accent", checked.bind(v))
    )
    div(
      cls -> "flex-1 space-y-1 p-3 text-sm",
      toggle("Show right dock", state.dockOpen),
      p(cls -> "px-2 pt-3 text-xs text-muted", s"Transport: ${state.transport.label}")
    )

end Sidebar

/** The main column: a header, the message thread, and the composer. */
class ChatView(state: HarnessState) extends RxElement:
  private val threadRef = DomRef[dom.html.Div]()

  // Keep the newest message in view after the thread re-renders
  private def scrollToBottom(): Unit = dom
    .window
    .setTimeout(
      () =>
        threadRef.foreach { el =>
          el.scrollTop = el.scrollHeight
        },
      0
    )

  override def render: RxElement = main(
    cls -> "flex min-w-0 flex-1 flex-col",
    header(
      cls -> "flex h-12 shrink-0 items-center gap-2 border-b border-line px-3",
      state
        .sidebarOpen
        .map { open =>
          unless(
            open,
            iconButton("Open sidebar (⌘B)", Icons.panelLeft, () => state.toggleSidebar())
          )
        },
      h1(
        cls -> "flex-1 truncate text-sm font-semibold",
        state.selectedConversation.map(_.map(_.title).getOrElse("Uni Harness"))
      ),
      span(
        cls -> "rounded-full border border-line px-2 py-0.5 text-xs text-muted",
        state.transport.label
      ),
      state
        .dockOpen
        .map { open =>
          unless(
            open,
            iconButton("Open right dock (⌘⇧B)", Icons.panelRight, () => state.toggleDock())
          )
        }
    ),
    div(
      ref -> threadRef,
      cls -> "flex-1 overflow-y-auto",
      div(
        cls -> "mx-auto flex max-w-3xl flex-col gap-4 px-6 py-6",
        state
          .messages
          .map { ms =>
            scrollToBottom()
            ms.map(bubble)
          },
        state
          .pending
          .map { p =>
            when(p, div(cls -> "flex gap-1 px-1 py-2", dot("0ms"), dot("150ms"), dot("300ms")))
          }
      )
    ),
    state
      .error
      .map {
        case Some(msg) =>
          div(cls -> "mx-auto w-full max-w-3xl px-6 pb-2 text-sm text-red-500", msg)
        case None =>
          span()
      },
    Composer(state)
  )

  private def dot(delay: String): RxElement = span(
    cls       -> "size-2 animate-bounce rounded-full bg-muted",
    styleAttr -> s"animation-delay: ${delay}"
  )

  private def bubble(m: ChatMessage): RxElement =
    if m.role == "user" then
      div(
        cls ->
          "self-end max-w-[80%] whitespace-pre-wrap rounded-2xl bg-accent px-4 py-2 text-sm text-white",
        m.text
      )
    else
      div(
        cls -> "flex gap-3",
        div(
          cls ->
            "flex size-7 shrink-0 items-center justify-center rounded-full bg-accent-soft text-xs font-bold text-accent",
          "AI"
        ),
        div(cls -> "whitespace-pre-wrap pt-1 text-sm leading-6", m.text)
      )

end ChatView

/**
  * The message composer. Enter sends, Shift+Enter inserts a newline, and Enter during IME
  * composition (e.g. Japanese input) confirms the conversion instead of sending.
  */
class Composer(state: HarnessState) extends RxElement:
  private val draft    = Rx.variable("")
  private val inputRef = DomRef[dom.html.TextArea]()

  private var growing = Cancelable.empty

  // Grow the textarea with its content, up to max-h-48. This follows the draft instead of using
  // `oninput`, because `value.bind` already owns the element's input handler.
  override def onMount(node: Any): Unit =
    growing = draft.run { _ =>
      inputRef.foreach { el =>
        el.style.height = "auto"
        el.style.height = s"${el.scrollHeight}px"
      }
    }

  override def beforeUnmount: Unit = growing.cancel

  private def submit(): Unit =
    val text = draft.get.trim
    if text.nonEmpty && !state.pending.get then
      draft := ""
      state.send(text)

  private def onKeyDown(e: dom.KeyboardEvent): Unit =
    val composing = e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[Boolean] || e.keyCode == 229
    if e.key == "Enter" && !e.shiftKey && !composing then
      e.preventDefault()
      submit()

  override def render: RxElement = div(
    cls -> "shrink-0 px-6 pb-5",
    div(
      cls ->
        "mx-auto flex max-w-3xl items-end gap-2 rounded-2xl border border-line bg-panel p-2 shadow-sm focus-within:border-accent",
      textarea(
        ref         -> inputRef,
        rows        -> 1,
        placeholder -> "Send a message (Enter to send, Shift+Enter for a newline)",
        cls         ->
          "max-h-48 flex-1 resize-none bg-transparent px-2 py-1.5 text-sm outline-none placeholder:text-muted",
        value.bind(draft),
        onkeydown -> onKeyDown,
        autofocus
      ),
      button(
        ariaLabel -> "Send",
        cls       ->
          "flex size-8 shrink-0 items-center justify-center rounded-lg bg-accent text-white transition-opacity disabled:opacity-40",
        HtmlTags.attr("disabled") ->
          Rx.join(draft, state.pending).map((d, p) => d.trim.isEmpty || p),
        onclick -> { () =>
          submit()
        },
        Icons.arrowUp
      )
    )
  )

end Composer

/** The right dock: details about the current conversation and the runtime. */
class Dock(state: HarnessState) extends RxElement:
  private def row(name: String, value: Rx[String]): RxElement = div(
    cls -> "flex justify-between gap-3 py-1.5 text-sm",
    span(cls -> "text-muted", name),
    span(cls -> "truncate", value)
  )

  private def row(name: String, value: String): RxElement = div(
    cls -> "flex justify-between gap-3 py-1.5 text-sm",
    span(cls -> "text-muted", name),
    span(cls -> "truncate", value)
  )

  override def render: RxElement = aside(
    cls ->
      state
        .dockOpen
        .map { open =>
          val width =
            if open then
              "w-72 border-l"
            else
              "w-0"
          s"shrink-0 overflow-hidden border-line bg-panel transition-[width] duration-200 ${width}"
        },
    div(
      cls -> "flex h-full w-72 flex-col",
      div(
        cls -> "flex h-12 items-center border-b border-line px-3",
        span(cls -> "flex-1 text-sm font-semibold", "Details"),
        iconButton("Close dock (⌘⇧B)", Icons.panelRight, () => state.toggleDock())
      ),
      div(
        cls -> "space-y-4 p-4",
        div(
          h2(
            cls -> "pb-1 text-xs font-semibold uppercase tracking-wide text-muted",
            "Conversation"
          ),
          row("Title", state.selectedConversation.map(_.map(_.title).getOrElse("-"))),
          row("Messages", state.messages.map(_.count(_.id != "pending").toString))
        ),
        div(
          h2(cls -> "pb-1 text-xs font-semibold uppercase tracking-wide text-muted", "Runtime"),
          row("Transport", state.transport.label),
          row("Service", "example.api.ChatApi")
        ),
        p(
          cls -> "text-xs leading-5 text-muted",
          "The dock is a place for context next to the chat: tool output, files, or a preview."
        )
      )
    )
  )

end Dock
