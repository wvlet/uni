package example.renderer

import example.api.Conversation
import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx, RxVar}

import scala.language.implicitConversions
import scala.scalajs.js

/**
  * The application shell: icon rail | foldable sidebar | chat | right dock.
  *
  * Each pane is a small RxElement that reads [[HarnessState]]. Folding is a width transition, so a
  * pane's contents keep a fixed width and never reflow while it opens or closes.
  */
class Shell(state: HarnessState) extends RxElement:
  private val searchRef                 = DomRef[dom.html.Input]()
  private var subscriptions: Cancelable = Cancelable.empty

  private def focusSearch(): Unit =
    state.view        := View.Chats.toString
    state.sidebarOpen := true
    dom.window.setTimeout(() => searchRef.foreach(_.focus()), 220)

  override def onMount(node: Any): Unit =
    // ⌘ on macOS, Ctrl elsewhere
    val shortcuts = Keyboard.bindAll(
      "meta+b"       -> (() => state.toggleSidebar()),
      "ctrl+b"       -> (() => state.toggleSidebar()),
      "meta+shift+b" -> (() => state.toggleDock()),
      "ctrl+shift+b" -> (() => state.toggleDock()),
      "meta+k"       -> (() => focusSearch()),
      "ctrl+k"       -> (() => focusSearch())
    )
    // Light / Dark / System: style.css reads the data-theme attribute
    val themes = state
      .theme
      .run { _ =>
        val root = dom.document.documentElement
        state.currentTheme match
          case Theme.System =>
            root.removeAttribute("data-theme")
          case t =>
            root.setAttribute("data-theme", t.toString.toLowerCase)
      }
    // The Seq overload: the two-argument `Cancelable.merge` takes its arguments by name, so it
    // would not subscribe until cancel time
    subscriptions = Cancelable.merge(Seq(shortcuts, themes))
    state.load()

  override def beforeUnmount: Unit = subscriptions.cancel

  override def render: RxElement = div(
    cls -> "flex h-screen w-screen overflow-hidden bg-canvas font-sans text-fg",
    IconRail(state),
    Sidebar(state, searchRef),
    ChatView(state),
    Dock(state)
  )

end Shell

/** A ghost icon button with a tooltip, used in panel headers. */
def iconButton(
    label: String,
    icon: RxElement,
    action: () => Unit,
    tipSide: String = "bottom"
): RxElement = button(
  data("tip")      -> label,
  data("tip-side") -> tipSide,
  ariaLabel        -> label,
  cls              ->
    "grid size-8 place-items-center rounded-lg text-muted transition hover:bg-hover hover:text-fg active:scale-95",
  onclick -> { () =>
    action()
  },
  icon
)

/** A keyboard key cap. */
def keyCap(k: String): RxElement = span(
  cls ->
    "inline-grid min-w-[18px] place-items-center rounded-[5px] border border-line bg-surface px-1 font-mono text-[10px] leading-4 text-muted shadow-[0_1px_0_var(--h-line)]",
  k
)

/** The deep-teal strip on the far left: logo, views, and global toggles. */
class IconRail(state: HarnessState) extends RxElement:

  private def railButton(v: View, icon: RxElement): RxElement =
    val active = Rx
      .join(state.view, state.sidebarOpen)
      .map((cur, open) => cur == v.toString && open)
    button(
      data("tip") -> v.label,
      ariaLabel   -> v.label,
      cls         ->
        active.map { on =>
          val look =
            if on then
              "bg-white/10 text-white shadow-[inset_0_0_0_1px_rgb(255_255_255/0.08)]"
            else
              "text-rail-fg hover:bg-white/5 hover:text-white"
          s"relative grid size-10 place-items-center rounded-xl transition active:scale-95 ${look}"
        },
      onclick -> { () =>
        state.openView(v)
      },
      active.map(on =>
        when(
          on,
          span(
            cls ->
              "absolute -left-[10px] top-2 bottom-2 w-[3px] rounded-r-full bg-glow shadow-[0_0_12px_#08bdcb]"
          )
        )
      ),
      icon
    )

  end railButton

  private def railToggle(label: String, icon: Rx[RxElement], action: () => Unit): RxElement =
    button(
      data("tip") -> label,
      ariaLabel   -> label,
      cls         ->
        "grid size-10 place-items-center rounded-xl text-rail-fg transition hover:bg-white/5 hover:text-white active:scale-95",
      onclick -> { () =>
        action()
      },
      icon
    )

  override def render: RxElement = nav(
    cls ->
      "app-drag rail-top flex w-[60px] shrink-0 flex-col items-center gap-1.5 bg-rail pt-3 pb-3",
    div(
      cls ->
        "mb-4 grid size-11 place-items-center rounded-2xl bg-gradient-to-b from-white/10 to-white/0 ring-1 ring-white/10",
      Icons.logo("size-8 drop-shadow-[0_2px_8px_rgb(8_189_203/0.45)]")
    ),
    railButton(View.Chats, Icons.chat),
    railButton(View.Files, Icons.folder),
    railButton(View.Settings, Icons.sliders),
    div(cls -> "flex-1"),
    railToggle(
      "Theme",
      state
        .theme
        .map { _ =>
          state.currentTheme match
            case Theme.System =>
              Icons.monitor
            case Theme.Light =>
              Icons.sun
            case Theme.Dark =>
              Icons.moon
        },
      () => state.cycleTheme()
    ),
    railToggle("Right dock  ⌘⇧B", Rx.variable(Icons.panelRight), () => state.toggleDock()),
    div(
      cls ->
        "mt-2 grid size-8 place-items-center rounded-full bg-gradient-to-br from-silver to-[#6e7b75] text-[10px] font-semibold text-[#0e2a30] ring-2 ring-white/10",
      "You"
    )
  )

end IconRail

/** The left sidebar. Its content follows the view selected on the rail. */
class Sidebar(state: HarnessState, searchRef: DomRef[dom.html.Input]) extends RxElement:

  override def render: RxElement = aside(
    cls ->
      state
        .sidebarOpen
        .map { open =>
          val width =
            if open then
              "w-[272px] border-r"
            else
              "w-0 border-r-0"
          s"shrink-0 overflow-hidden border-line bg-panel backdrop-blur-xl transition-[width] duration-300 ease-out ${width}"
        },
    div(
      cls -> "flex h-full w-[272px] flex-col",
      state
        .view
        .map { _ =>
          val v = state.currentView
          div(
            cls -> "flex h-full flex-col",
            div(
              cls -> "app-drag flex h-14 shrink-0 items-center gap-1 pr-2 pl-4",
              div(
                cls -> "flex-1",
                div(cls -> "text-[13px] font-semibold tracking-tight", v.label),
                div(cls -> "text-[11px] text-faint", subtitle(v))
              ),
              iconButton("Fold sidebar  ⌘B", Icons.panelLeft, () => state.toggleSidebar())
            ),
            v match
              case View.Chats =>
                chats
              case View.Files =>
                files
              case View.Settings =>
                settings
          )
        }
    )
  )

  private def subtitle(v: View): RxElement =
    v match
      case View.Chats =>
        span(
          state
            .conversations
            .map(cs =>
              s"${cs.size} conversation${
                  if cs.size == 1 then
                    ""
                  else
                    "s"
                }"
            )
        )
      case View.Files =>
        span("examples/uni-harness")
      case View.Settings =>
        span("Saved in this browser")

  // --- Chats -------------------------------------------------------------------------------

  private def chats: RxElement = div(
    cls -> "flex min-h-0 flex-1 flex-col",
    div(
      cls -> "space-y-2 px-3 pb-3",
      button(
        cls ->
          "flex h-9 w-full items-center gap-2 rounded-lg btn-primary px-3 text-[13px] font-medium text-white shadow-[0_8px_20px_-12px_var(--h-brand)] transition hover:brightness-110 active:scale-[0.99]",
        onclick -> { () =>
          state.newChat()
        },
        Icons.plus,
        span(cls -> "flex-1 text-left", "New chat")
      ),
      label(
        cls ->
          "flex h-9 items-center gap-2 rounded-lg border border-line bg-surface/70 px-2.5 text-muted transition focus-within:border-brand/50 focus-within:ring-4 focus-within:ring-brand-soft",
        Icons.search,
        input(
          ref         -> searchRef,
          tpe         -> "search",
          placeholder -> "Search chats",
          cls         ->
            "min-w-0 flex-1 bg-transparent text-[13px] text-fg outline-none placeholder:text-faint",
          value.bind(state.query)
        ),
        keyCap("⌘K")
      )
    ),
    div(
      cls -> "min-h-0 flex-1 overflow-y-auto px-2 pb-3",
      Rx.join(state.conversations, state.selected, state.query)
        .map { (cs, sel, q) =>
          val hits = cs.filter(_.title.toLowerCase.contains(q.trim.toLowerCase))
          if hits.isEmpty then
            p(cls -> "px-3 py-6 text-center text-[12px] text-faint", "No chats match")
          else
            // `new`: JS constructors need it (`js.Date()` alone returns a string)
            val now        = new js.Date()
            val startOfDay =
              new js.Date(now.getFullYear().toInt, now.getMonth().toInt, now.getDate().toInt)
                .getTime()
            val (today, earlier) = hits.partition(_.updatedAt >= startOfDay)
            div(group("Today", today, sel), group("Earlier", earlier, sel))
        }
    )
  )

  private def group(title: String, cs: Seq[Conversation], sel: Option[String]): RxElement = div(
    when(
      cs.nonEmpty,
      div(
        cls -> "mb-2",
        div(
          cls -> "px-3 pt-2 pb-1 text-[10px] font-semibold tracking-[0.12em] text-faint uppercase",
          title
        ),
        cs.map(c => conversationItem(c, sel.contains(c.id)))
      )
    )
  )

  private def conversationItem(c: Conversation, active: Boolean): RxElement =
    val look =
      if active then
        "bg-raised shadow-[0_6px_18px_-12px_rgb(6_17_20/0.5)] ring-1 ring-line"
      else
        "hover:bg-hover"
    val weight =
      if active then
        "font-semibold"
      else
        "font-medium"
    button(
      cls ->
        s"group relative flex w-full flex-col rounded-lg px-3 py-2 text-left transition ${look}",
      onclick -> { () =>
        state.select(c.id)
      },
      when(
        active,
        span(cls -> "absolute top-2.5 bottom-2.5 left-0 w-[3px] rounded-r-full bg-brand")
      ),
      span(cls -> s"truncate text-[13px] ${weight}", c.title),
      span(cls -> "text-[11px] text-faint", ago(c.updatedAt))
    )

  // --- Files -------------------------------------------------------------------------------

  private val tree: Seq[(String, Seq[String])] = Seq(
    "api"      -> Seq("ChatApi.scala"),
    "server"   -> Seq("ChatService.scala", "HarnessServer.scala"),
    "renderer" -> Seq("HarnessApp.scala", "Shell.scala", "Chat.scala", "Dock.scala", "Icons.scala"),
    "src"      -> Seq("main/index.js", "preload/index.js", "web/server.js")
  )

  private def files: RxElement = div(
    cls -> "min-h-0 flex-1 overflow-y-auto px-2 pb-3 text-[13px]",
    tree.map { (dir, fs) =>
      div(
        cls -> "mb-1",
        div(
          cls -> "flex items-center gap-1.5 rounded-md px-2 py-1.5 font-medium text-fg",
          span(cls -> "rotate-90 text-faint", Icons.chevron),
          span(cls -> "text-brand", Icons.folder),
          dir
        ),
        fs.map(f =>
          div(
            cls ->
              "ml-5 flex cursor-default items-center gap-2 border-l border-line py-1.5 pl-3 text-muted transition hover:bg-hover hover:text-fg",
            span(cls -> "text-faint", Icons.code),
            f
          )
        )
      )
    },
    div(
      cls ->
        "mx-2 mt-4 rounded-lg border border-dashed border-line p-3 text-[12px] leading-5 text-muted",
      "A static tree. Back this view with your own RPC API to browse real files."
    )
  )

  // --- Settings ----------------------------------------------------------------------------

  private def section(title: String, body: RxElement*): RxElement = div(
    cls -> "mb-5",
    div(cls -> "px-1 pb-2 text-[10px] font-semibold tracking-[0.12em] text-faint uppercase", title),
    body
  )

  private def switchRow(text: String, hint: String, v: RxVar[Boolean]): RxElement = label(
    cls -> "flex cursor-pointer items-center gap-3 rounded-lg px-2 py-2 transition hover:bg-hover",
    div(
      cls -> "flex-1",
      div(cls -> "text-[13px] font-medium", text),
      div(cls -> "text-[11px] text-faint", hint)
    ),
    input(tpe -> "checkbox", cls -> "peer sr-only", checked.bind(v)),
    span(
      cls ->
        "relative h-5 w-9 shrink-0 rounded-full bg-line transition peer-checked:bg-brand after:absolute after:top-0.5 after:left-0.5 after:size-4 after:rounded-full after:bg-white after:shadow after:transition peer-checked:after:translate-x-4"
    )
  )

  private def themePicker: RxElement = div(
    cls -> "grid grid-cols-3 gap-1 rounded-xl bg-hover p-1",
    state
      .theme
      .map { _ =>
        Seq(Theme.System -> Icons.monitor, Theme.Light -> Icons.sun, Theme.Dark -> Icons.moon).map {
          (t, icon) =>
            val look =
              if state.currentTheme == t then
                "bg-raised text-fg shadow-sm ring-1 ring-line"
              else
                "text-muted hover:text-fg"
            button(
              cls ->
                s"flex flex-col items-center gap-1 rounded-lg py-2 text-[11px] font-medium transition ${look}",
              onclick -> { () =>
                state.theme := t.toString
              },
              icon,
              t.label
            )
        }
      }
  )

  private def shortcut(text: String, keys: String*): RxElement = div(
    cls -> "flex items-center justify-between px-2 py-1.5 text-[12px] text-muted",
    span(text),
    span(cls -> "flex gap-1", keys.map(keyCap))
  )

  private def settings: RxElement = div(
    cls -> "min-h-0 flex-1 overflow-y-auto px-3 pb-4",
    section("Appearance", themePicker),
    section(
      "Layout",
      switchRow("Right dock", "Details and RPC activity", state.dockOpen),
      switchRow("Sidebar", "Fold it from the rail, too", state.sidebarOpen)
    ),
    section(
      "Keyboard",
      shortcut("Fold sidebar", "⌘", "B"),
      shortcut("Toggle dock", "⌘", "⇧", "B"),
      shortcut("Search chats", "⌘", "K"),
      shortcut("New line", "⇧", "⏎")
    ),
    section(
      "Connection",
      div(
        cls -> "flex items-center gap-3 rounded-lg border border-line bg-surface/60 p-3",
        span(cls -> "status-dot relative size-2 shrink-0 rounded-full bg-glow"),
        div(
          div(cls -> "text-[13px] font-medium", state.transport.label),
          div(cls -> "font-mono text-[11px] text-faint", state.transport.detail)
        )
      )
    )
  )

end Sidebar
