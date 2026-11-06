package example.renderer

import example.api.Conversation
import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx, RxVar}
import wvlet.uni.ui.{ColorScheme, Edge, FoldablePane, Menu, MenuItem, Splitter, Tooltip, Tree}
import wvlet.uni.ui.base.{Align, Side}

import scala.language.implicitConversions
import scala.scalajs.js

/**
  * The application shell: title bar, icon rail | sidebar | chat | dock. It wires the panes to the
  * state and owns the app-wide effects (shortcuts, theme); each pane takes only what it uses.
  */
class Shell(layout: Layout, store: ChatStore, log: RpcLog, transport: Transport) extends RxElement:
  private val paletteOpen               = Rx.variable(false)
  private var subscriptions: Cancelable = Cancelable.empty

  private def openPalette(): Unit = paletteOpen := true

  private def newChat(): Unit =
    layout.view        := View.Chats
    layout.sidebarOpen := true
    store.newChat()

  override def onMount(node: Any): Unit =
    // ⌘ on macOS, Ctrl elsewhere
    val shortcuts = Keyboard.bindAll(
      "meta+b"       -> (() => layout.toggleSidebar()),
      "ctrl+b"       -> (() => layout.toggleSidebar()),
      "meta+shift+b" -> (() => layout.toggleDock()),
      "ctrl+shift+b" -> (() => layout.toggleDock()),
      "meta+k"       -> (() => openPalette()),
      "ctrl+k"       -> (() => openPalette())
    )
    val themes = ColorScheme.bind(layout.theme)
    subscriptions = Cancelable.merge(shortcuts, themes)
    store.load()

  override def beforeUnmount: Unit = subscriptions.cancel

  override def render: RxElement = div(
    cls -> "flex h-screen w-screen flex-col overflow-hidden bg-canvas font-sans text-fg",
    TitleBar(store.selectedConversation.map(_.map(_.title).getOrElse("New chat"))),
    div(
      cls -> "flex min-h-0 flex-1",
      IconRail(layout),
      Sidebar(layout, store, transport, () => openPalette(), () => newChat()),
      ChatView(store, layout, transport),
      Dock(layout, store, log, transport)
    ),
    Palette(paletteOpen, layout, store, () => newChat())
  )

end Shell

/**
  * A 40px strip across the top of a frameless macOS window. The window buttons sit at its left
  * (`trafficLightPosition` in `src/main/index.js`), so no pane content runs under them, and the
  * strip drags the window. Browsers and other platforms keep their own title bar, so the strip is
  * hidden there (`.title-bar` in style.css).
  */
class TitleBar(title: Rx[String]) extends RxElement:
  override def render: RxElement = div(
    cls ->
      "title-bar app-drag h-10 shrink-0 items-center justify-center border-b border-line bg-rail px-20",
    div(
      cls -> "flex min-w-0 items-center gap-2 text-[12px] font-medium text-muted",
      Icons.logo("size-4"),
      span("Uni Harness"),
      span(cls -> "text-faint", "/"),
      span(cls -> "truncate text-fg", title)
    )
  )

/** The strip on the far left: logo, views, theme, and account. It follows the theme. */
class IconRail(layout: Layout) extends RxElement:

  private def railButton(v: View, icon: RxElement): RxElement =
    val active = Rx.join(layout.view, layout.sidebarOpen).map((cur, open) => cur == v && open)
    Tooltip(v.label)(
      button(
        ariaLabel -> v.label,
        cls       ->
          active.map { on =>
            val look =
              if on then
                "bg-brand-soft text-brand shadow-[inset_0_0_0_1px_var(--h-line)]"
              else
                "text-rail-fg hover:bg-hover hover:text-fg"
            s"relative grid size-9 place-items-center rounded-lg transition active:scale-95 ${look}"
          },
        onclick -> { () =>
          layout.openView(v)
        },
        active.map(on =>
          when(
            on,
            span(
              cls ->
                "absolute -left-[6px] top-2 bottom-2 w-[3px] rounded-r-full bg-brand shadow-[0_0_12px_var(--h-brand)]"
            )
          )
        ),
        icon
      )
    ).withSide(Side.Right)

  private val themeMenuOpen = Rx.variable(false)

  // The theme button opens a menu of the three schemes, with a check on the current one
  private def themeButton: RxElement = Tooltip("Theme")(
    Menu(themeMenuOpen)(themeIcon)(
      ColorScheme
        .values
        .toSeq
        .map { s =>
          MenuItem(s.label).withChecked(layout.theme.map(_ == s)).onSelect(() => layout.theme := s)
        }*
    ).withSide(Side.Right).withAlign(Align.End)
  ).withSide(Side.Right)

  private def themeIcon: RxElement = button(
    ariaLabel -> "Theme",
    cls       ->
      "grid size-9 place-items-center rounded-lg text-rail-fg transition hover:bg-hover hover:text-fg active:scale-95",
    layout
      .theme
      .map {
        case ColorScheme.System =>
          Icons.monitor
        case ColorScheme.Light =>
          Icons.sun
        case ColorScheme.Dark =>
          Icons.moon
      }
  )

  // The account slot: a rail button like the others, holding an avatar with a status dot
  private def account: RxElement = Tooltip("Account (local)")(
    button(
      ariaLabel -> "Account",
      cls       ->
        "mt-1 grid size-9 place-items-center rounded-lg transition hover:bg-hover active:scale-95",
      span(
        cls ->
          "relative grid size-6 place-items-center rounded-full bg-gradient-to-br from-brand-2 to-brand text-white shadow-[0_4px_12px_-6px_var(--h-brand)] ring-1 ring-white/25",
        Icons.user,
        span(
          cls ->
            "absolute -right-px -bottom-px size-2 rounded-full bg-glow ring-2 ring-[var(--h-rail)]"
        )
      )
    )
  ).withSide(Side.Right)

  override def render: RxElement = nav(
    cls ->
      "app-drag flex w-12 shrink-0 flex-col items-center gap-1 border-r border-line bg-rail pt-3 pb-2.5",
    div(cls -> "mb-3 grid size-9 place-items-center", Icons.logo("size-7")),
    railButton(View.Chats, Icons.chat),
    railButton(View.Files, Icons.folder),
    railButton(View.Settings, Icons.sliders),
    div(cls -> "flex-1"),
    themeButton,
    account
  )

end IconRail

/** The left sidebar: a resizable, foldable pane whose content follows the rail's view. */
class Sidebar(
    layout: Layout,
    store: ChatStore,
    transport: Transport,
    onSearch: () => Unit,
    onNewChat: () => Unit
) extends RxElement:
  private val handle = Splitter(
    layout.sidebarWidth,
    Layout.sidebarWidth,
    Edge.Right,
    "Resize sidebar"
  )

  private def subtitle(v: View): RxElement =
    v match
      case View.Chats =>
        span(store.conversations.map(cs => Ui.plural(cs.size, "conversation")))
      case View.Files =>
        span("examples/uni-harness")
      case View.Settings =>
        span("Saved in this browser")

  override def render: RxElement =
    FoldablePane(layout.sidebarOpen, layout.sidebarWidth, Edge.Right, Some(handle))(
      layout
        .view
        .map { v =>
          div(
            cls -> "flex h-full flex-col",
            div(
              cls -> "app-drag flex h-14 shrink-0 items-center gap-1 pr-2 pl-4",
              div(
                cls -> "flex-1",
                div(cls -> "text-[13px] font-semibold tracking-tight", v.label),
                div(cls -> "text-[11px] text-faint", subtitle(v))
              ),
              Ui.iconButton("Fold sidebar  ⌘B", Icons.panelLeft, () => layout.toggleSidebar())
            ),
            v match
              case View.Chats =>
                ChatList(store, onSearch, onNewChat)
              case View.Files =>
                FileTree()
              case View.Settings =>
                SettingsView(layout, transport)
          )
        }
    )

end Sidebar

/** New chat, the search button that opens the palette, and the conversations grouped by day. */
class ChatList(store: ChatStore, onSearch: () => Unit, onNewChat: () => Unit) extends RxElement:

  // Rows render only when their conversation changes, so their "ago" labels follow this clock
  private val now   = Rx.variable(js.Date.now())
  private var timer = 0

  override def onMount(node: Any): Unit =
    timer = dom.window.setInterval(() => now := js.Date.now(), 30000)

  override def beforeUnmount: Unit = dom.window.clearInterval(timer)

  // Each conversation with whether it is selected, split into Today and Earlier
  private val rows: Rx[(Seq[(Conversation, Boolean)], Seq[(Conversation, Boolean)])] = Rx
    .join(store.conversations, store.selected)
    .map { (cs, sel) =>
      // `new`: JS constructors need it (`js.Date()` alone returns a string)
      val now        = new js.Date()
      val startOfDay =
        new js.Date(now.getFullYear().toInt, now.getMonth().toInt, now.getDate().toInt).getTime()
      cs.map(c => (c, sel.contains(c.id))).partition(_._1.updatedAt >= startOfDay)
    }

  // Keyed by id: a rename or a new selection renders only the rows that changed
  private def group(title: String, cs: Rx[Seq[(Conversation, Boolean)]]): RxElement = div(
    cls    -> "mb-2",
    hidden -> cs.map(_.isEmpty),
    div(
      cls -> "px-3 pt-2 pb-1 text-[10px] font-semibold tracking-[0.12em] text-faint uppercase",
      title
    ),
    each(cs)(_._1.id)((c, active) => item(c, active))
  )

  private def item(c: Conversation, active: Boolean): RxElement =
    val look =
      if active then
        "bg-raised font-semibold shadow-[0_6px_18px_-12px_rgb(6_17_20/0.5)] ring-1 ring-line"
      else
        "font-medium hover:bg-hover"
    button(
      cls ->
        s"group relative flex w-full flex-col rounded-lg px-3 py-2 text-left transition ${look}",
      onclick -> { () =>
        store.select(c.id)
      },
      when(
        active,
        span(cls -> "absolute top-2.5 bottom-2.5 left-0 w-[3px] rounded-r-full bg-brand")
      ),
      span(cls -> "truncate text-[13px]", c.title),
      span(cls -> "text-[11px] font-normal text-faint", now.map(t => ago(c.updatedAt, t)))
    )

  override def render: RxElement = div(
    cls -> "flex min-h-0 flex-1 flex-col",
    div(
      cls -> "space-y-2 px-3 pb-3",
      button(
        cls ->
          "btn-primary flex h-9 w-full items-center gap-2 rounded-lg px-3 text-[13px] font-medium text-white shadow-[0_8px_20px_-12px_var(--h-brand)] transition hover:brightness-110 active:scale-[0.99]",
        onclick -> { () =>
          onNewChat()
        },
        Icons.plus,
        span(cls -> "flex-1 text-left", "New chat")
      ),
      // One search for conversations and commands: the ⌘K palette
      button(
        cls ->
          "flex h-9 w-full items-center gap-2 rounded-lg border border-line bg-surface/70 px-2.5 text-muted transition hover:border-brand/50 focus-visible:border-brand/50 focus-visible:ring-4 focus-visible:ring-brand-soft focus-visible:outline-none",
        onclick -> { () =>
          onSearch()
        },
        Icons.search,
        span(cls -> "min-w-0 flex-1 text-left text-[13px] text-faint", "Search or run a command"),
        Ui.keyCap("⌘K")
      )
    ),
    div(
      cls -> "min-h-0 flex-1 overflow-y-auto px-2 pb-3",
      p(
        cls    -> "px-3 py-6 text-center text-[12px] text-faint",
        hidden -> store.conversations.map(_.nonEmpty),
        "No chats yet"
      ),
      group("Today", rows.map(_._1)),
      group("Earlier", rows.map(_._2))
    )
  )

end ChatList

/** A file or folder in [[FileTree]]. `path` is unique and keys the tree's rows. */
case class FileEntry(path: String, name: String, children: Seq[FileEntry] = Seq.empty)

object FileEntry:
  def dir(name: String, children: FileEntry*): FileEntry = FileEntry(
    name,
    name,
    children.map(under(name, _))
  )

  private def under(parent: String, e: FileEntry): FileEntry = e.copy(
    path = s"${parent}/${e.path}",
    children = e.children.map(under(parent, _))
  )

  def file(name: String): FileEntry = FileEntry(name, name)

/** A static file tree, as a placeholder for your own file API. */
class FileTree extends RxElement:
  import FileEntry.{dir, file}

  private val roots = Seq(
    dir("api", file("ChatApi.scala")),
    dir("server", file("ChatService.scala"), file("HarnessServer.scala")),
    dir(
      "renderer",
      file("HarnessApp.scala"),
      file("ChatStore.scala"),
      file("Shell.scala"),
      file("Chat.scala"),
      file("Dock.scala"),
      file("Palette.scala")
    ),
    dir(
      "src",
      dir("main", file("index.js")),
      dir("preload", file("index.js")),
      dir("web", file("server.js"))
    )
  )

  private val expanded = Rx.variable(Set("renderer"))

  override def render: RxElement = div(
    cls -> "flex min-h-0 flex-1 flex-col px-2 pb-3",
    Tree(Rx.const(roots), (e: FileEntry) => e.children)(_.path)(e =>
      Seq[DomNode](
        if e.children.nonEmpty then
          span(cls -> "text-brand", Icons.folder)
        else
          span(cls -> "text-faint", Icons.code)
        ,
        span(cls -> "truncate", e.name)
      )
    ).withLabel("Files")
      .withExpanded(expanded)
      .withTypeahead(_.name)
      .withRowHeight(30)(cls += "min-h-0 flex-1 text-[13px]"),
    div(
      cls ->
        "mx-2 mt-4 shrink-0 rounded-lg border border-dashed border-line p-3 text-[12px] leading-5 text-muted",
      "A static tree. Back this view with your own RPC API to browse real files."
    )
  )

end FileTree

/** Appearance, layout switches, shortcuts, and the connection. */
class SettingsView(layout: Layout, transport: Transport) extends RxElement:

  private def section(title: String, body: RxElement*): RxElement = div(
    cls -> "mb-5",
    Ui.overline(title),
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
    layout
      .theme
      .map { current =>
        Seq(
          ColorScheme.System -> Icons.monitor,
          ColorScheme.Light  -> Icons.sun,
          ColorScheme.Dark   -> Icons.moon
        ).map { (t, icon) =>
          val look =
            if current == t then
              "bg-raised text-fg shadow-sm ring-1 ring-line"
            else
              "text-muted hover:text-fg"
          button(
            cls ->
              s"flex flex-col items-center gap-1 rounded-lg py-2 text-[11px] font-medium transition ${look}",
            onclick -> { () =>
              layout.theme := t
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
    span(cls -> "flex gap-1", keys.map(Ui.keyCap))
  )

  override def render: RxElement = div(
    cls -> "min-h-0 flex-1 overflow-y-auto px-3 pb-4",
    section("Appearance", themePicker),
    section(
      "Layout",
      switchRow("Right dock", "Details and RPC activity", layout.dockOpen),
      switchRow("Sidebar", "Fold it from the rail, too", layout.sidebarOpen)
    ),
    section(
      "Keyboard",
      shortcut("Fold sidebar", "⌘", "B"),
      shortcut("Toggle dock", "⌘", "⇧", "B"),
      shortcut("Search or run a command", "⌘", "K"),
      shortcut("New line", "⇧", "⏎")
    ),
    section(
      "Connection",
      div(
        cls -> "flex items-center gap-3 rounded-lg border border-line bg-surface/60 p-3",
        span(cls -> "status-dot relative size-2 shrink-0 rounded-full bg-glow"),
        div(
          div(cls -> "text-[13px] font-medium", transport.label),
          div(cls -> "font-mono text-[11px] text-faint", transport.detail)
        )
      )
    )
  )

end SettingsView
