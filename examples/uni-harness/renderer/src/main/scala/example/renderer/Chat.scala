package example.renderer

import example.api.ChatMessage
import org.scalajs.dom
import wvlet.uni.dom.HtmlTags
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions
import scala.scalajs.js

/** The main column: a header, the thread (or the welcome screen), and the composer. */
class ChatView(store: ChatStore, layout: Layout, transport: Transport) extends RxElement:
  private val threadRef = DomRef[dom.html.Div]()

  // Keep the newest message in view after the thread re-renders
  private def scrollToBottom(): Unit = dom
    .window
    .setTimeout(
      () =>
        threadRef.foreach { el =>
          el.asInstanceOf[js.Dynamic]
            .scrollTo(js.Dynamic.literal(top = el.scrollHeight, behavior = "smooth"))
        },
      0
    )

  private def header: RxElement = div(
    cls ->
      "app-drag relative z-10 flex h-14 shrink-0 items-center gap-2 border-b border-line bg-surface/40 px-4 backdrop-blur-md",
    layout
      .sidebarOpen
      .map(open =>
        unless(
          open,
          Ui.iconButton("Open sidebar  ⌘B", Icons.panelLeft, () => layout.toggleSidebar())
        )
      ),
    div(
      cls -> "min-w-0 flex-1 px-1",
      h1(
        cls -> "truncate text-[14px] font-semibold tracking-tight",
        store.selectedConversation.map(_.map(_.title).getOrElse("Uni Harness"))
      ),
      div(
        cls -> "text-[11px] text-faint",
        store.messageCount.map(n => s"${Ui.plural(n, "message")} · Echo model (mock)")
      )
    ),
    span(
      cls ->
        "flex items-center gap-2 rounded-full border border-line bg-raised/70 px-3 py-1 text-[11px] font-medium text-muted shadow-sm",
      span(cls -> "status-dot relative size-1.5 rounded-full bg-glow"),
      transport.label
    ),
    layout
      .dockOpen
      .map(open =>
        unless(
          open,
          Ui.iconButton("Open dock  ⌘⇧B", Icons.panelRight, () => layout.toggleDock(), "left")
        )
      )
  )

  override def render: RxElement = main(
    cls -> "aurora relative flex min-w-0 flex-1 flex-col",
    header,
    div(
      ref -> threadRef,
      cls -> "relative z-10 min-h-0 flex-1 overflow-y-auto",
      store
        .messages
        .map { ms =>
          if ms.isEmpty then
            Welcome(transport, prompt => store.send(prompt))
          else
            scrollToBottom()
            div(
              cls -> "mx-auto flex max-w-3xl flex-col gap-7 px-6 pt-8 pb-6",
              ms.map(m => Bubble(m, animate = store.takeFresh(m.id))),
              store
                .pending
                .map { p =>
                  if p then
                    scrollToBottom()
                  when(p, Thinking())
                }
            )
        }
    ),
    store
      .error
      .map {
        case Some(msg) =>
          div(
            cls ->
              "relative z-10 mx-auto mb-2 w-full max-w-3xl rounded-lg border border-red-500/30 bg-red-500/10 px-4 py-2 text-[12px] text-red-500",
            msg
          )
        case None =>
          span()
      },
    Composer(text => store.send(text), store.pending)
  )

end ChatView

/** The first screen of an empty conversation: the logo, a line about Uni, and prompt ideas. */
class Welcome(transport: Transport, onPick: String => Unit) extends RxElement:
  private val ideas = Seq(
    (Icons.bolt, "Wire a real model", "How do I replace the mock with a real LLM call?"),
    (Icons.layers, "One API, two transports", "Explain how ChatApi runs over IPC and over HTTP."),
    (Icons.code, "Write a component", "Show me an RxElement for a tab bar."),
    (Icons.sparkle, "Say hello", "こんにちは、Uni!")
  )

  override def render: RxElement = div(
    cls ->
      "mx-auto flex min-h-full max-w-2xl flex-col items-center justify-center px-6 py-10 text-center",
    div(
      cls -> "relative mb-7 grid place-items-center",
      div(cls -> "halo absolute size-48 rounded-full"),
      Icons.logo("relative size-24 drop-shadow-[0_12px_28px_rgb(14_42_48/0.35)]")
    ),
    h2(
      cls -> "text-gradient text-[36px] leading-tight font-semibold tracking-tight",
      "Uni Harness"
    ),
    p(
      cls -> "mt-3 max-w-md text-[14px] leading-6 text-muted",
      "One Scala.js codebase for the desktop and the web. Each message is a typed RPC call to ",
      span(cls -> "font-mono text-[12.5px] text-fg", "ChatApi"),
      " over ",
      span(cls -> "font-medium text-brand", transport.label),
      "."
    ),
    div(
      cls -> "mt-9 grid w-full grid-cols-1 gap-3 text-left sm:grid-cols-2",
      ideas.map { (icon, title, prompt) =>
        button(
          cls ->
            "group rise flex flex-col items-start rounded-2xl border border-line bg-raised/70 p-4 text-left backdrop-blur transition duration-200 hover:-translate-y-0.5 hover:border-brand/40 hover:shadow-[0_16px_36px_-22px_var(--h-brand)]",
          onclick -> { () =>
            onPick(prompt)
          },
          div(
            cls ->
              "mb-3 grid size-9 place-items-center rounded-xl bg-brand-soft text-brand transition group-hover:bg-brand group-hover:text-white",
            icon
          ),
          div(cls -> "text-[13px] font-semibold", title),
          div(cls -> "mt-1 text-[12px] leading-5 text-muted", prompt)
        )
      }
    )
  )

end Welcome

/**
  * One message. With `animate`, the text types itself in, which stands in for token streaming;
  * replace it with a streaming RPC when your model streams.
  */
class Bubble(m: ChatMessage, animate: Boolean = false) extends RxElement:
  private val shown = Rx.variable(
    if animate then
      ""
    else
      m.text
  )

  private val copied = Rx.variable(false)
  private var timer  = 0

  override def onMount(node: Any): Unit =
    if animate then
      var i = 0
      timer = dom
        .window
        .setInterval(
          () =>
            i = math.min(m.text.length, i + 3)
            shown := m.text.take(i)
            // Keep the growing reply in view, as a streamed answer would be
            node.asInstanceOf[dom.Element].scrollIntoView(false)
            if i >= m.text.length then
              dom.window.clearInterval(timer)
          ,
          16
        )

  override def beforeUnmount: Unit = dom.window.clearInterval(timer)

  private def copy(): Unit = Clipboard
    .writeText(m.text)
    .foreach { _ =>
      copied := true
      dom.window.setTimeout(() => copied := false, 1200)
    }

  override def render: RxElement =
    if m.role == "user" then
      div(
        cls -> "rise group flex flex-col items-end gap-1",
        div(
          cls ->
            "bubble-user max-w-[78%] rounded-2xl rounded-br-md px-4 py-2.5 text-[14px] leading-6 whitespace-pre-wrap text-white",
          m.text
        ),
        span(
          cls -> "px-1 text-[11px] text-faint opacity-0 transition group-hover:opacity-100",
          if ChatStore.isDraft(m) then
            "Sending…"
          else
            clock(m.createdAt)
        )
      )
    else
      div(
        cls -> "rise group flex gap-3.5",
        div(
          cls ->
            "mt-0.5 grid size-8 shrink-0 place-items-center rounded-full bg-raised shadow-sm ring-1 ring-line",
          Icons.logo("size-6")
        ),
        div(
          cls -> "min-w-0 flex-1",
          div(
            cls -> "mb-1 flex items-center gap-2 text-[12px]",
            span(cls -> "font-semibold", "Uni"),
            span(cls -> "text-faint", clock(m.createdAt))
          ),
          div(cls -> "text-[14px] leading-7 whitespace-pre-wrap text-fg/90", shown),
          div(
            cls -> "mt-1 -ml-1.5 flex gap-0.5 opacity-0 transition group-hover:opacity-100",
            button(
              ariaLabel -> "Copy",
              cls       ->
                "flex items-center gap-1 rounded-md px-1.5 py-1 text-[11px] text-faint transition hover:bg-hover hover:text-fg",
              onclick -> { () =>
                copy()
              },
              copied.map(c =>
                if c then
                  span(cls -> "flex items-center gap-1 text-brand", Icons.check, "Copied")
                else
                  span(cls -> "flex items-center gap-1", Icons.copy, "Copy")
              )
            )
          )
        )
      )

end Bubble

/** Shown while a reply is on its way. */
class Thinking extends RxElement:
  override def render: RxElement = div(
    cls -> "rise flex items-center gap-3.5",
    div(
      cls ->
        "grid size-8 shrink-0 place-items-center rounded-full bg-raised shadow-sm ring-1 ring-line",
      Icons.logo("size-6 animate-pulse")
    ),
    span(cls -> "shimmer text-[13px] font-medium", "Thinking…")
  )

/**
  * The message composer. Enter sends, Shift+Enter inserts a newline, and Enter during IME
  * composition (e.g. Japanese input) confirms the conversion instead of sending.
  *
  * @param busy
  *   true while a reply is on its way; sending waits until it arrives
  */
class Composer(onSend: String => Unit, busy: Rx[Boolean]) extends RxElement:
  private val draft    = Rx.variable("")
  private val inputRef = DomRef[dom.html.TextArea]()
  private var isBusy   = false
  private var watching = Cancelable.empty

  override def onMount(node: Any): Unit =
    // Grow the textarea with its content, up to max-h-52. This follows the draft instead of using
    // `oninput`, because `value.bind` already owns the element's input handler.
    val growing = draft.run { _ =>
      inputRef.foreach { el =>
        el.style.height = "auto"
        el.style.height = s"${el.scrollHeight}px"
      }
    }
    val busyState = busy.run(b => isBusy = b)
    watching = Cancelable.merge(Seq(growing, busyState))

  override def beforeUnmount: Unit = watching.cancel

  private def submit(): Unit =
    val text = draft.get.trim
    if text.nonEmpty && !isBusy then
      draft := ""
      onSend(text)

  private def onKeyDown(e: dom.KeyboardEvent): Unit =
    val composing = e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[Boolean] || e.keyCode == 229
    if e.key == "Enter" && !e.shiftKey && !composing then
      e.preventDefault()
      submit()

  override def render: RxElement = div(
    cls -> "relative z-10 shrink-0 px-6 pt-2 pb-5",
    div(
      cls -> "composer mx-auto max-w-3xl rounded-2xl",
      textarea(
        ref         -> inputRef,
        rows        -> 1,
        placeholder -> "Ask Uni anything…",
        cls         ->
          "block max-h-52 w-full resize-none bg-transparent px-4 pt-3.5 pb-1 text-[14px] leading-6 outline-none placeholder:text-faint",
        value.bind(draft),
        onkeydown -> onKeyDown,
        autofocus
      ),
      div(
        cls -> "flex items-center gap-1.5 px-2.5 pb-2.5",
        button(
          data("tip")      -> "Attachments: add your own",
          data("tip-side") -> "bottom",
          ariaLabel        -> "Attach",
          cls              ->
            "grid size-8 place-items-center rounded-lg text-faint transition hover:bg-hover hover:text-fg",
          Icons.clip
        ),
        span(
          cls ->
            "flex items-center gap-1.5 rounded-full bg-brand-soft px-2.5 py-1 text-[11px] font-medium text-brand",
          Icons.sparkle,
          "Echo · mock"
        ),
        div(cls -> "flex-1"),
        span(
          cls -> "hidden items-center gap-1 pr-1 text-[11px] text-faint sm:flex",
          Ui.keyCap("⏎"),
          "send",
          span(cls -> "w-1.5"),
          Ui.keyCap("⇧⏎"),
          "newline"
        ),
        button(
          ariaLabel -> "Send",
          cls       ->
            "btn-primary grid size-8 place-items-center rounded-full text-white shadow-[0_6px_16px_-6px_var(--h-brand)] transition hover:scale-105 active:scale-95 disabled:scale-100 disabled:opacity-30 disabled:shadow-none",
          HtmlTags.attr("disabled") -> Rx.join(draft, busy).map((d, b) => d.trim.isEmpty || b),
          onclick                   -> { () =>
            submit()
          },
          Icons.arrowUp
        )
      )
    ),
    p(
      cls -> "mx-auto mt-2 max-w-3xl text-center text-[11px] text-faint",
      "Replies come from the mock model in ChatService. Swap in your model there."
    )
  )

end Composer
