package example.gallery.stories

import example.gallery.Story
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*
import wvlet.uni.ui.base.{Align, Side}

import scala.language.implicitConversions

object OverlayStories:

  // #region overlays/menu
  inline def menu: RxElement =
    val open     = Rx.variable(false)
    val wrap     = Rx.variable(true)
    val lastPick = Rx.variable("Nothing yet")

    div(
      cls -> "demo-row",
      Menu(
        open,
        trigger = Button("Edit ▾"),
        entries = Seq(
          MenuItem("Rename").withShortcut("F2").onSelect(() => lastPick := "Rename"),
          MenuItem("Duplicate").withShortcut("⌘D").onSelect(() => lastPick := "Duplicate"),
          MenuItem("Word wrap").withChecked(wrap).onSelect(() => wrap.update(!_)),
          MenuSeparator,
          MenuItem("Archive").withDisabled(true),
          MenuItem("Delete").onSelect(() => lastPick := "Delete")
        )
      ),
      span(cls -> "demo-note", lastPick.map(p => s"Picked: ${p}"))
    )
  // #endregion

  // #region overlays/dialog
  inline def dialog: RxElement =
    val renameOpen = Rx.variable(false)
    val deleteOpen = Rx.variable(false)
    val name       = Rx.variable("Untitled")
    val status     = Rx.variable("")

    div(
      // An inline dialog covers its nearest positioned ancestor instead of the whole page
      cls -> "demo-row demo-frame",
      Button("Rename…").onClick(() => renameOpen := true),
      Button("Delete…").danger.onClick(() => deleteOpen := true),
      span(cls -> "demo-note", name.map(n => s"Name: ${n}"), status),
      Dialog(renameOpen, content = Input(name).withLabel("Name"))
        .withTitle("Rename")
        .withDescription("Changes save as you type.")
        .withActions(Button("Done").primary.onClick(() => renameOpen := false))
        .withInline(true),
      AlertDialog(deleteOpen, title = "Delete this file?")
        .withDescription("It can't be restored.")
        .withConfirmLabel("Delete")
        .danger
        .onConfirm(() => status := " · deleted")
        .withInline(true)
    )
  // #endregion

  // #region overlays/select
  inline def select: RxElement =
    val sizes    = Seq("Small", "Medium", "Large", "Extra large")
    val selected = Rx.variable("Medium")

    div(
      cls -> "demo-row",
      Select(selected, options = sizes, optionLabel = s => s).withLabel("Size"),
      span(cls -> "demo-note", selected.map(s => s"Size: ${s}"))
    )
  // #endregion

  // #region overlays/popover-tooltip
  inline def popoverTooltip: RxElement =
    val filterOpen = Rx.variable(false)
    val query      = Rx.variable("")

    div(
      cls -> "demo-row",
      Popover(filterOpen, trigger = Button("Filter"), content = Input(query).withLabel("Filter"))
        .withAlign(Align.Start),
      Tooltip(
        "Shows after 500 ms, or at once on keyboard focus",
        trigger = Button("Hover me").ghost
      ).withSide(Side.Top),
      span(
        cls -> "demo-note",
        query.map(q =>
          if q.isEmpty then
            "No filter"
          else
            s"Filter: ${q}"
        )
      )
    )
  // #endregion

  val all: Seq[Story] = Seq(
    Story(
      "overlays/menu",
      "Menu",
      "Items with shortcuts, a checked item, a separator, and a disabled item."
    )(menu),
    Story(
      "overlays/dialog",
      "Dialog",
      "A Dialog and an AlertDialog rendered in place, with focus trapping and Escape."
    )(dialog),
    Story("overlays/select", "Select", "A button with a list of options, bound to an RxVar.")(
      select
    ),
    Story(
      "overlays/popover-tooltip",
      "Popover & Tooltip",
      "A non-modal panel next to its trigger, and a text label on hover or focus."
    )(popoverTooltip)
  )

end OverlayStories
