package example.renderer

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx}
import wvlet.uni.ui.{
  Badge,
  Column,
  DataTable,
  DockLayout,
  DockPanel,
  Edge,
  FoldablePane,
  SortBy,
  Splitter,
  VirtualList
}
import wvlet.uni.ui.base.Side

import scala.language.implicitConversions

/**
  * The right dock: context next to the chat, resizable from its left edge. "Details" shows the
  * conversation and how a call travels; "Activity" lists every RPC call with its round-trip time;
  * "Terminal" runs a shell. The panels sit in a [[wvlet.uni.ui.Dock]], so the user can drag a tab
  * to an edge to split the dock, such as Terminal under Details.
  */
class Dock(layout: Layout, store: ChatStore, log: RpcLog, transport: Transport) extends RxElement:
  // Set by a bell the user could not see; cleared once the Terminal panel is visible
  private[renderer] val terminalAttention = Rx.variable(false)
  private var attentionWatch: Cancelable  = Cancelable.empty

  private def terminalVisible: Boolean = Dock.isTerminalVisible(
    layout.dockOpen.get,
    layout.dockLayout.get
  )

  // Created once, so the shell and its scrollback survive switching tabs and moving the panel
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
      .join(layout.dockLayout)
      .run { (open, l) =>
        if Dock.isTerminalVisible(open, l) then
          terminalAttention := false
      }

  override def beforeUnmount: Unit = attentionWatch.cancel

  private def panel(
      t: DockTab,
      icon: RxElement,
      badge: Rx[String],
      attention: Rx[Boolean] = Rx.const(false)
  )(content: => RxElement): DockPanel = DockPanel(t.id, t.label)(content)
    .withTab(
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
    // The dock has only these three; closing one would leave no way back but the palette
    .withClosable(false)

  private lazy val panels = wvlet
    .uni
    .ui
    .Dock(layout.dockLayout, "Dock views")(
      panel(DockTab.Details, Icons.info, Rx.const(""))(
        div(cls -> "min-h-0 flex-1 overflow-y-auto px-3 pb-4", DetailsView(store, transport))
      ),
      panel(DockTab.Activity, Icons.activity, log.calls.map(_.size.toString))(
        // The log scrolls inside its own list
        div(cls -> "flex min-h-0 flex-1 flex-col px-3 pb-4", ActivityLog(log))
      ),
      panel(DockTab.Terminal, Icons.terminal, Rx.const(""), terminalAttention)(
        div(cls -> "min-h-0 flex-1 px-3 pb-3", terminal)
      )
    )
    .withActions(
      Ui.iconButton("Close dock  ⌘⇧B", Icons.panelRight, () => layout.toggleDock(), Side.Left)
    )

  override def render: RxElement =
    FoldablePane(layout.dockOpen, layout.dockWidth, Edge.Left, Some(handle))(
      panels(cls += "harness-dock")
    )

end Dock

object Dock:
  /** True when the user can see the Terminal panel: the dock is open and it is a visible tab. */
  def isTerminalVisible(open: Boolean, layout: DockLayout): Boolean =
    open && layout.visible.contains(DockTab.Terminal.id)

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
  * calls, so it renders through a [[VirtualList]]: only the rows in view are in the DOM. The
  * "Table" view shows the same calls in a sortable [[DataTable]], which moves by cell.
  */
class ActivityLog(log: RpcLog) extends RxElement:
  private val asTable = Rx.variable(false)
  private val sort    = Rx.variable(Option.empty[SortBy])

  private def viewButton(label: String, table: Boolean): RxElement = button(
    tpe         -> "button",
    ariaPressed -> asTable.map(t => (t == table).toString),
    cls         ->
      asTable.map { t =>
        val tone =
          if t == table then
            "bg-hover text-fg"
          else
            "text-faint hover:text-fg"
        s"rounded px-1.5 py-0.5 ${tone}"
      },
    onclick -> (() => asTable := table),
    label
  )

  private def mono(s: String): RxElement = span(cls -> "font-mono text-[11.5px] tabular-nums", s)

  // Wider than a narrow dock on purpose, so the table also scrolls sideways to the active cell
  private def table: RxElement =
    DataTable(log.calls)(_.id)(
      Column[RpcCall]("id", "#")(c => mono(c.id.toString)).sortBy(_.id).withWidth("44px").alignEnd,
      Column[RpcCall]("method", "Method")(c =>
        span(cls -> "truncate font-mono text-[11.5px]", c.method)
      ).sortBy(_.method).withWidth("minmax(140px, 1fr)"),
      Column[RpcCall]("millis", "Time")(c => mono(f"${c.millis}%.1f ms"))
        .sortBy(_.millis)
        .withWidth("80px")
        .alignEnd,
      Column[RpcCall]("first", "First")(c => mono(c.firstMillis.fold("–")(f => f"${f}%.0f ms")))
        .sortBy(_.firstMillis.getOrElse(-1.0))
        .withWidth("72px")
        .alignEnd,
      Column[RpcCall]("events", "Events")(c => mono(c.events.toString))
        .sortBy(_.events)
        .withWidth("64px")
        .alignEnd,
      Column[RpcCall]("ok", "Status") { c =>
        val (tone, text) =
          if c.ok then
            ("text-muted", "ok")
          else
            ("text-red-500", "failed")
        span(cls -> tone, text)
      }.sortBy(_.ok).withWidth("64px")
    ).withLabel("RPC calls").withSort(sort).withRowHeight(30)(cls += "min-h-0 flex-1 text-[12px]")

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
        // A stream: the time to its first event, and how many events it delivered
        c.firstMillis
          .fold(DomNode.empty)(first =>
            span(
              titleAttr -> f"First event after ${first}%.1f ms, ${c.events} events in all",
              cls       -> "font-mono text-[11px] text-faint tabular-nums",
              f"${first}%.0f ms → ${c.events}×"
            )
          ),
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

  end row

  override def render: RxElement = div(
    cls -> "flex min-h-0 flex-1 flex-col",
    log
      .calls
      .map { calls =>
        if calls.isEmpty then
          p(cls -> "px-2 py-8 text-center text-[12px] text-faint", "No calls yet")
        else
          div(
            cls -> "flex items-center gap-2 px-1 pt-1 pb-2 text-[11px] text-faint",
            span(cls -> "flex-1", s"Last ${Ui.plural(calls.size, "call")}"),
            span(f"avg ${calls.map(_.millis).sum / calls.size}%.1f ms"),
            div(
              role      -> "group",
              ariaLabel -> "Activity view",
              cls       -> "flex gap-0.5",
              viewButton("List", table = false),
              viewButton("Table", table = true)
            )
          )
      },
    asTable.map { t =>
      if t then
        table
      else
        VirtualList(rows, rowHeight = 44)(_._1.id)(row.tupled).withLabel("RPC calls")(
          cls += "min-h-0 flex-1"
        )
    }
  )

end ActivityLog
