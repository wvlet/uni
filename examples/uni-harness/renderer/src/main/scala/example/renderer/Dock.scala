package example.renderer

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx}
import wvlet.uni.ui.{Badge, Edge, FoldablePane, Splitter, Tab, TabList, TabPanel, VirtualList}
import wvlet.uni.ui.base.Side

import scala.language.implicitConversions

/**
  * The right dock: context next to the chat, resizable from its left edge. "Details" shows the
  * conversation and how a call travels; "Activity" lists every RPC call with its round-trip time;
  * "Terminal" runs a shell.
  */
class Dock(layout: Layout, store: ChatStore, log: RpcLog, transport: Transport) extends RxElement:
  // Set by a bell the user could not see; cleared once the Terminal tab is visible
  private[renderer] val terminalAttention = Rx.variable(false)
  private var attentionWatch: Cancelable  = Cancelable.empty

  private def terminalVisible: Boolean = Dock.isTerminalVisible(
    layout.dockOpen.get,
    layout.dockTab.get
  )

  // Created once, so the shell and its scrollback survive switching tabs
  private lazy val terminal = TerminalPane(
    layout.theme,
    onBell =
      () =>
        if !terminalVisible then
          terminalAttention := true,
    onAsk = text => store.sendOrStart(text)
  )

  private val handle = Splitter(layout.dockWidth, Layout.dockWidth, Edge.Left, "Resize dock")

  override def onMount(node: Any): Unit =
    attentionWatch = layout
      .dockOpen
      .join(layout.dockTab)
      .run { (open, tab) =>
        if Dock.isTerminalVisible(open, tab) then
          terminalAttention := false
      }

  override def beforeUnmount: Unit = attentionWatch.cancel

  private def tab(
      t: DockTab,
      icon: RxElement,
      badge: Rx[String],
      attention: Rx[Boolean] = Rx.const(false)
  ): Tab[DockTab] = Tab(
    t,
    icon,
    t.label,
    Badge(badge),
    attention.map { on =>
      if on then
        Badge.dot("Bell")
      else
        span()
    }
  )

  private lazy val tabs =
    TabList(layout.dockTab, "Dock views")(
      tab(DockTab.Details, Icons.info, Rx.const("")),
      tab(DockTab.Activity, Icons.activity, log.calls.map(_.size.toString)),
      tab(DockTab.Terminal, Icons.terminal, Rx.const(""), terminalAttention)
    )

  override def render: RxElement =
    FoldablePane(layout.dockOpen, layout.dockWidth, Edge.Left, Some(handle))(
      div(
        cls -> "app-drag flex h-14 shrink-0 items-center gap-2 pr-2 pl-3",
        tabs(cls += "flex-1"),
        Ui.iconButton("Close dock  ⌘⇧B", Icons.panelRight, () => layout.toggleDock(), Side.Left)
      ),
      TabPanel(tabs) {
        case DockTab.Terminal =>
          div(cls -> "min-h-0 flex-1 px-3 pb-3", terminal)
        case DockTab.Activity =>
          // The log scrolls inside its own list
          div(cls -> "flex min-h-0 flex-1 flex-col px-3 pb-4", ActivityLog(log))
        case DockTab.Details =>
          div(cls -> "min-h-0 flex-1 overflow-y-auto px-3 pb-4", DetailsView(store, transport))
      }
    )

end Dock

object Dock:
  /** True when the user can see the Terminal tab: the dock is open and shows it. */
  def isTerminalVisible(open: Boolean, tab: DockTab): Boolean = open && tab == DockTab.Terminal

/** The current conversation, the call path, and the Uni APIs the example uses. */
class DetailsView(store: ChatStore, transport: Transport) extends RxElement:

  private def card(title: String, body: RxElement*): RxElement = div(
    cls -> "mb-3 rounded-xl border border-line bg-surface/70 p-3.5",
    Ui.overline(title),
    body
  )

  private def stat(label: String, value: Rx[String]): RxElement = div(
    cls -> "rounded-lg bg-hover px-3 py-2",
    div(cls -> "text-[11px] text-faint", label),
    div(cls -> "text-[15px] font-semibold tabular-nums", value)
  )

  // One stop on the call path; a dot travels along the line between them
  private def stop(title: String, sub: String, accent: Boolean): RxElement =
    val dot =
      if accent then
        "bg-glow"
      else
        "bg-brand"
    div(
      cls -> "relative flex items-center gap-3",
      span(cls -> s"relative z-10 size-2.5 shrink-0 rounded-full ring-4 ring-surface ${dot}"),
      div(
        div(cls -> "text-[12px] font-medium", title),
        div(cls -> "font-mono text-[10.5px] text-faint", sub)
      )
    )

  override def render: RxElement = div(
    card(
      "Conversation",
      div(
        cls -> "mb-3 truncate text-[14px] font-semibold",
        store.selectedConversation.map(_.map(_.title).getOrElse("-"))
      ),
      div(
        cls -> "grid grid-cols-2 gap-2",
        stat("Messages", store.messageCount.map(_.toString)),
        stat("Updated", store.selectedConversation.map(_.map(c => ago(c.updatedAt)).getOrElse("-")))
      )
    ),
    card(
      "Call path",
      div(
        cls -> "relative space-y-4 pl-0.5",
        div(cls -> "absolute top-1.5 bottom-1.5 left-[5px] w-px bg-line"),
        div(
          cls ->
            "flow-dot absolute left-[3px] size-1.5 rounded-full bg-glow shadow-[0_0_10px_#08bdcb]"
        ),
        stop("Renderer", "ChatApiClient", accent = false),
        stop(transport.label, transport.detail, accent = true),
        stop("ChatService", "RPCRouter.of[ChatApi]", accent = false)
      )
    ),
    card(
      "Built with Uni",
      div(
        cls -> "flex flex-wrap gap-1.5",
        Seq(
          "RxElement",
          "Rx.variable",
          "Storage.local",
          "Keyboard",
          "Clipboard",
          "RPCClient",
          "RPCRouter",
          "Http.server",
          "ElectronRPCServer"
        ).map(t =>
          span(
            cls ->
              "rounded-md border border-line bg-hover px-2 py-0.5 font-mono text-[10.5px] text-muted",
            t
          )
        )
      )
    )
  )

end DetailsView

/**
  * Every RPC call, newest first, with its time relative to the slowest. The log keeps hundreds of
  * calls, so it renders through a [[VirtualList]]: only the rows in view are in the DOM.
  */
class ActivityLog(log: RpcLog) extends RxElement:
  // Each call with the slowest time, computed once per update. A row renders again only when its
  // pair changes, which is when a new slowest call arrives.
  private val rows: Rx[Seq[(RpcCall, Double)]] = log
    .calls
    .map { cs =>
      val slowest = math.max(1.0, cs.map(_.millis).maxOption.getOrElse(1.0))
      cs.map(c => (c, slowest))
    }

  private def row(c: RpcCall, slowest: Double): RxElement =
    val tone =
      if c.ok then
        "bg-glow"
      else
        "bg-red-500"
    div(
      cls -> "min-w-0 flex-1",
      div(
        cls -> "flex items-center gap-2",
        span(cls -> s"size-1.5 shrink-0 rounded-full ${tone}"),
        span(cls -> "flex-1 truncate font-mono text-[11.5px]", c.method),
        span(cls -> "font-mono text-[11px] text-muted tabular-nums", f"${c.millis}%.1f ms")
      ),
      div(
        cls -> "mt-1 ml-3.5 h-1 overflow-hidden rounded-full bg-hover",
        div(
          cls       -> "h-full rounded-full bg-gradient-to-r from-brand to-glow",
          styleAttr -> f"width: ${math.max(4.0, c.millis / slowest * 100)}%.0f%%"
        )
      )
    )

  override def render: RxElement = div(
    cls -> "flex min-h-0 flex-1 flex-col",
    log
      .calls
      .map { calls =>
        if calls.isEmpty then
          p(cls -> "px-2 py-8 text-center text-[12px] text-faint", "No calls yet")
        else
          div(
            cls -> "flex items-center justify-between px-1 pt-1 pb-2 text-[11px] text-faint",
            span(s"Last ${Ui.plural(calls.size, "call")}"),
            span(f"avg ${calls.map(_.millis).sum / calls.size}%.1f ms")
          )
      },
    VirtualList(rows, rowHeight = 44)(_._1.id)(row.tupled).withLabel("RPC calls")(
      cls += "min-h-0 flex-1"
    )
  )

end ActivityLog
