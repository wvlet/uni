package example.renderer

import wvlet.uni.dom.Keyboard
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.{Menu, MenuItem, Tooltip, VirtualList}
import wvlet.uni.ui.base.{InputHistory, Side}

import scala.language.implicitConversions

/**
  * Quick notes in the dock. Enter adds a note, ArrowUp brings back an earlier one, Alt+ArrowUp and
  * Alt+ArrowDown reorder the list, and the menu sorts or clears it. Detach the panel ("Open in new
  * window" on its tab) and these work the same in their own window.
  */
class NotesView extends RxElement:
  private val notes: RxVar[Seq[String]] = Rx.variable(
    Seq("Try Alt+ArrowUp on a note", "Detach this panel", "Read the Dock docs")
  )

  private val draft    = Rx.variable("")
  private val history  = InputHistory()
  private val menuOpen = Rx.variable(false)

  private def add(): Unit =
    val text = draft.get.trim
    if text.nonEmpty then
      history.add(text)
      notes.update(_ :+ text)
      draft := ""

  private def actions: RxElement = Tooltip(
    "Sort or clear notes",
    trigger = Menu(
      menuOpen,
      trigger = button(
        ariaLabel -> "Note actions",
        cls       ->
          "rounded-lg px-2 py-1 text-[12px] text-faint transition hover:bg-hover hover:text-fg",
        "Actions"
      ),
      entries = Seq(
        MenuItem("Sort A–Z").onSelect(() => notes.update(_.sorted)),
        MenuItem("Clear all").onSelect(() => notes := Seq.empty)
      )
    ).withSide(Side.Bottom)
  ).withSide(Side.Left)

  override def render: RxElement = div(
    cls -> "flex min-h-0 flex-1 flex-col gap-2 px-3 pb-4",
    div(cls -> "flex items-center justify-between", Ui.overline("Notes"), actions),
    textarea(
      rows        -> 1,
      placeholder -> "Add a note…",
      ariaLabel   -> "New note",
      cls         ->
        "block w-full resize-none rounded-lg border border-line bg-surface px-3 py-2 text-[13px] outline-none placeholder:text-faint",
      value.bind(draft),
      history.keys(draft),
      Keyboard.onEnter(() => add())
    ),
    VirtualList(notes, rowHeight = 32, key = identity, render = n => span(cls -> "text-[13px]", n))
      .withReorder(notes)(ariaLabel -> "Notes", cls += "min-h-0 flex-1")
  )

end NotesView
