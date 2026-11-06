package example.renderer

import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.{ColorScheme, Command, CommandPalette}

/**
  * The ⌘K palette: one search over the app's commands and its conversations. While the query is
  * empty it lists the commands, then the conversations, newest first.
  */
object Palette:

  def apply(
      open: RxVar[Boolean],
      layout: Layout,
      store: ChatStore,
      onNewChat: () => Unit
  ): CommandPalette = CommandPalette(open, commands(layout, store, onNewChat))
    .withPlaceholder("Search conversations or run a command")
    .withLabel("Search or run a command")

  def commands(layout: Layout, store: ChatStore, onNewChat: () => Unit): Rx[Seq[Command]] =
    def showDock(t: DockTab): Unit =
      layout.dockTab  := t
      layout.dockOpen := true
    def showView(v: View): Unit =
      layout.view        := v
      layout.sidebarOpen := true

    val actions =
      Seq(
        Command("new-chat", "New chat").withGroup("Chat").onRun(onNewChat),
        Command("toggle-sidebar", "Toggle sidebar")
          .withGroup("View")
          .withShortcut("⌘B")
          .onRun(() => layout.toggleSidebar()),
        Command("toggle-dock", "Toggle dock")
          .withGroup("View")
          .withShortcut("⌘⇧B")
          .onRun(() => layout.toggleDock())
      ) ++
        DockTab
          .values
          .map(t =>
            Command(s"dock-${t}", s"Show ${t.label}")
              .withGroup("View")
              .withKeywords("dock")
              .onRun(() => showDock(t))
          ) ++
        Seq(View.Files, View.Settings).map(v =>
          Command(s"view-${v}", s"Show ${v.label}")
            .withGroup("View")
            .withKeywords("sidebar")
            .onRun(() => showView(v))
        ) ++
        Seq(ColorScheme.System, ColorScheme.Light, ColorScheme.Dark).map(t =>
          Command(s"theme-${t}", s"Theme: ${t.label}")
            .withGroup("Appearance")
            .withKeywords("appearance", "color scheme")
            .onRun(() => layout.theme := t)
        )

    store
      .conversations
      .map { cs =>
        actions ++
          cs.map(c =>
            Command(s"chat-${c.id}", c.title)
              .withGroup("Conversations")
              .withHint(ago(c.updatedAt))
              .onRun { () =>
                showView(View.Chats)
                store.select(c.id)
              }
          )
      }

  end commands

end Palette
