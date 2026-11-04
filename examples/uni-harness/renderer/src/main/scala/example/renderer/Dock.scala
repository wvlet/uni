package example.renderer

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx

import scala.language.implicitConversions

/**
  * The right dock: context next to the chat. "Details" shows the conversation and how the call
  * travels; "Activity" lists every RPC call with its round-trip time.
  */
class Dock(state: HarnessState) extends RxElement:

  private def tab(id: String, text: String, icon: RxElement, badge: Rx[String]): RxElement = button(
    cls ->
      state
        .dockTab
        .map { cur =>
          val look =
            if cur == id then
              "bg-raised text-fg shadow-sm ring-1 ring-line"
            else
              "text-muted hover:text-fg"
          s"flex flex-1 items-center justify-center gap-1.5 rounded-lg py-1.5 text-[12px] font-medium transition ${look}"
        },
    onclick -> { () =>
      state.dockTab := id
    },
    icon,
    text,
    span(cls -> "font-mono text-[10px] text-faint", badge)
  )

  override def render: RxElement = aside(
    cls ->
      state
        .dockOpen
        .map { open =>
          val width =
            if open then
              "w-[320px] border-l"
            else
              "w-0 border-l-0"
          s"shrink-0 overflow-hidden border-line bg-panel backdrop-blur-xl transition-[width] duration-300 ease-out ${width}"
        },
    div(
      cls -> "flex h-full w-[320px] flex-col",
      div(
        cls -> "app-drag flex h-14 shrink-0 items-center gap-2 pr-2 pl-3",
        div(
          cls -> "flex flex-1 gap-1 rounded-xl bg-hover p-1",
          tab("details", "Details", Icons.info, Rx.variable("")),
          tab("activity", "Activity", Icons.activity, state.activity.map(_.size.toString))
        ),
        iconButton("Close dock  ⌘⇧B", Icons.panelRight, () => state.toggleDock(), "left")
      ),
      div(
        cls -> "min-h-0 flex-1 overflow-y-auto px-3 pb-4",
        state
          .dockTab
          .map {
            case "activity" =>
              activityView
            case _ =>
              detailsView
          }
      )
    )
  )

  private def card(title: String, body: RxElement*): RxElement = div(
    cls -> "mb-3 rounded-xl border border-line bg-surface/70 p-3.5",
    div(cls -> "mb-2.5 text-[10px] font-semibold tracking-[0.12em] text-faint uppercase", title),
    body
  )

  private def stat(label: String, value: Rx[String]): RxElement = div(
    cls -> "rounded-lg bg-hover px-3 py-2",
    div(cls -> "text-[11px] text-faint", label),
    div(cls -> "text-[15px] font-semibold tabular-nums", value)
  )

  // A three-stop diagram of the call path, with a dot that travels along it
  private def node(title: String, sub: String, accent: Boolean): RxElement = div(
    cls -> "relative flex items-center gap-3",
    span(
      cls ->
        s"relative z-10 size-2.5 shrink-0 rounded-full ring-4 ring-surface ${
            if accent then
              "bg-glow"
            else
              "bg-brand"
          }"
    ),
    div(
      div(cls -> "text-[12px] font-medium", title),
      div(cls -> "font-mono text-[10.5px] text-faint", sub)
    )
  )

  private def detailsView: RxElement = div(
    card(
      "Conversation",
      div(
        cls -> "mb-3 truncate text-[14px] font-semibold",
        state.selectedConversation.map(_.map(_.title).getOrElse("-"))
      ),
      div(
        cls -> "grid grid-cols-2 gap-2",
        stat("Messages", state.messages.map(_.count(_.id != "pending").toString)),
        stat("Updated", state.selectedConversation.map(_.map(c => ago(c.updatedAt)).getOrElse("-")))
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
        node("Renderer", "RPCClient.of[ChatApi]", accent = false),
        node(state.transport.label, state.transport.detail, accent = true),
        node("ChatService", "RPCRouter.of[ChatApi]", accent = false)
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

  private def activityView: RxElement = div(
    state
      .activity
      .map { calls =>
        if calls.isEmpty then
          p(cls -> "px-2 py-8 text-center text-[12px] text-faint", "No calls yet")
        else
          val slowest = math.max(1.0, calls.map(_.millis).max)
          div(
            div(
              cls -> "flex items-center justify-between px-1 pt-1 pb-2 text-[11px] text-faint",
              span(s"Last ${calls.size} calls"),
              span(f"avg ${calls.map(_.millis).sum / calls.size}%.1f ms")
            ),
            calls.map { c =>
              val tone =
                if c.ok then
                  "bg-glow"
                else
                  "bg-red-500"
              div(
                cls -> "rise rounded-lg px-2 py-1.5 transition hover:bg-hover",
                div(
                  cls -> "flex items-center gap-2",
                  span(cls -> s"size-1.5 shrink-0 rounded-full ${tone}"),
                  span(cls -> "flex-1 truncate font-mono text-[11.5px]", c.method),
                  span(
                    cls -> "font-mono text-[11px] tabular-nums text-muted",
                    f"${c.millis}%.1f ms"
                  )
                ),
                div(
                  cls -> "mt-1 ml-3.5 h-1 overflow-hidden rounded-full bg-hover",
                  div(
                    cls       -> "h-full rounded-full bg-gradient-to-r from-brand to-glow",
                    styleAttr -> f"width: ${math.max(4.0, c.millis / slowest * 100)}%.0f%%"
                  )
                )
              )
            }
          )
      }
  )

end Dock
