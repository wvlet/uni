package example.gallery.stories

import example.gallery.{A11yCheck, ApiDoc, ComponentPage, Story}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.AriaPattern
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*
import wvlet.uni.ui.base.{Align, Side}

import scala.language.implicitConversions

object OverlayStories:

  // #region popover/basic
  inline def popoverBasic: RxElement =
    val open  = Rx.variable(false)
    val query = Rx.variable("")

    div(
      cls -> "demo-row",
      Popover(open, trigger = Button("Filter"), content = Input(query).withLabel("Filter"))
        .withAlign(Align.Start),
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
  end popoverBasic
  // #endregion

  // #region popover/placement
  inline def popoverPlacement: RxElement =
    def at(side: Side): RxElement = Popover(
      Rx.variable(false),
      trigger = Button(side.toString).ghost,
      content = p(s"On the ${side} side")
    ).withSide(side)

    div(cls -> "demo-row demo-center", at(Side.Top), at(Side.Right), at(Side.Bottom), at(Side.Left))
  end popoverPlacement
  // #endregion

  // #region popover/inline
  inline def popoverInline: RxElement =
    val open = Rx.variable(false)

    div(
      cls -> "demo-row",
      Popover(
        open,
        trigger = Button("Details"),
        content = p("Rendered right after its trigger, not at the end of <body>.")
      ).withInline(true)
    )
  end popoverInline
  // #endregion

  // #region tooltip/basic
  inline def tooltipBasic: RxElement = div(
    cls -> "demo-row",
    Tooltip(
      "Shows after 500 ms, or at once on keyboard focus",
      trigger = Button("Hover or focus me").ghost
    )
  )
  // #endregion

  // #region tooltip/sides
  inline def tooltipSides: RxElement = div(
    cls -> "demo-row demo-center",
    Tooltip("Top", trigger = Button("Top").ghost).withSide(Side.Top),
    Tooltip("Right", trigger = Button("Right").ghost).withSide(Side.Right),
    Tooltip("Bottom", trigger = Button("Bottom").ghost).withSide(Side.Bottom),
    Tooltip("Left", trigger = Button("Left").ghost).withSide(Side.Left)
  )
  // #endregion

  // #region tooltip/delay
  inline def tooltipDelay: RxElement = div(
    cls -> "demo-row",
    Tooltip("At once", trigger = Button("No delay").ghost).withDelay(0),
    Tooltip("After a second", trigger = Button("1 second").ghost).withDelay(1000)
  )
  // #endregion

  // #region menu/basic
  inline def menuBasic: RxElement =
    val open     = Rx.variable(false)
    val lastPick = Rx.variable("Nothing yet")

    div(
      cls -> "demo-row",
      Menu(
        open,
        trigger = Button("Edit ▾"),
        entries = Seq(
          MenuItem("Rename").withShortcut("F2").onSelect(() => lastPick := "Rename"),
          MenuItem("Duplicate").withShortcut("⌘D").onSelect(() => lastPick := "Duplicate"),
          MenuSeparator,
          MenuItem("Archive").withDisabled(true),
          MenuItem("Delete").onSelect(() => lastPick := "Delete")
        )
      ),
      span(cls -> "demo-note", lastPick.map(p => s"Picked: ${p}"))
    )
  end menuBasic
  // #endregion

  // #region menu/checked
  inline def menuChecked: RxElement =
    val open    = Rx.variable(false)
    val wrap    = Rx.variable(true)
    val minimap = Rx.variable(false)

    div(
      cls -> "demo-row",
      Menu(
        open,
        trigger = Button("View ▾").ghost,
        entries = Seq(
          MenuItem("Word wrap").withChecked(wrap).onSelect(() => wrap.update(!_)),
          MenuItem("Minimap").withChecked(minimap).onSelect(() => minimap.update(!_))
        )
      ).withAlign(Align.Start),
      span(cls -> "demo-note", Rx.join(wrap, minimap).map((w, m) => s"wrap=${w} minimap=${m}"))
    )
  end menuChecked
  // #endregion

  // #region menu/context
  inline def menuContext: RxElement =
    val picked = Rx.variable("Right-click the box")

    div(
      cls -> "demo-stack",
      ContextMenu(
        div(cls -> "demo-target", "Right-click here, or focus it and press Shift+F10"),
        entries = Seq(
          MenuItem("Copy").withShortcut("⌘C").onSelect(() => picked := "Copy"),
          MenuItem("Paste").withShortcut("⌘V").onSelect(() => picked := "Paste")
        )
      ),
      p(cls -> "demo-note", picked)
    )
  end menuContext
  // #endregion

  // #region dialog/basic
  inline def dialogBasic: RxElement =
    val open = Rx.variable(false)
    val name = Rx.variable("Untitled")

    div(
      // An inline dialog covers its nearest positioned ancestor instead of the whole page
      cls -> "demo-row demo-frame",
      Button("Rename…").onClick(() => open := true),
      span(cls -> "demo-note", name.map(n => s"Name: ${n}")),
      Dialog(open, content = Input(name).withLabel("Name"))
        .withTitle("Rename")
        .withDescription("Changes save as you type.")
        .withActions(Button("Done").primary.onClick(() => open := false))
        .withInline(true)
    )
  end dialogBasic
  // #endregion

  // #region dialog/form
  inline def dialogForm: RxElement =
    val open    = Rx.variable(false)
    val email   = Rx.variable("")
    val invited = Rx.variable(Seq.empty[String])
    val valid   = email.map(_.contains("@"))

    div(
      cls -> "demo-row demo-frame",
      Button("Invite…").primary.onClick(() => open := true),
      span(
        cls -> "demo-note",
        invited.map(xs =>
          if xs.isEmpty then
            "No invites yet"
          else
            xs.mkString(", ")
        )
      ),
      Dialog(
        open,
        content = Input(email)
          .withLabel("Email")
          .withInvalid(valid.map(v => !v && email.get.nonEmpty))
      ).withTitle("Invite a teammate")
        .withActions(
          Button("Cancel").ghost.onClick(() => open := false),
          Button("Send invite")
            .primary
            .withDisabled(valid.map(!_))
            .onClick { () =>
              invited.update(_ :+ email.get)
              email := ""
              open  := false
            }
        )
        .noDismissOnBackdrop()
        .withInline(true)
    )
  end dialogForm
  // #endregion

  // #region dialog/alert
  inline def dialogAlert: RxElement =
    val open   = Rx.variable(false)
    val status = Rx.variable("The file is here")

    div(
      cls -> "demo-row demo-frame",
      Button("Delete…").danger.onClick(() => open := true),
      span(cls -> "demo-note", status),
      AlertDialog(open, title = "Delete this file?")
        .withDescription("It can't be restored.")
        .withConfirmLabel("Delete")
        .danger
        .onConfirm(() => status := "Deleted")
        .onCancel(() => status := "Kept")
        .withInline(true)
    )
  end dialogAlert
  // #endregion

  // #region select/basic
  inline def selectBasic: RxElement =
    val size = Rx.variable("Medium")

    div(
      cls -> "demo-row",
      Select(size, options = Seq("Small", "Medium", "Large", "Extra large"), optionLabel = s => s)
        .withLabel("Size"),
      span(cls -> "demo-note", size.map(s => s"Size: ${s}"))
    )
  end selectBasic
  // #endregion

  // #region select/dynamic
  inline def selectDynamic: RxElement =
    val region   = Rx.variable("Tokyo")
    val showBeta = Rx.variable(false)
    val regions  = showBeta.map(b =>
      Seq("Tokyo", "Oregon", "Frankfurt") ++ (
        if b then
          Seq("Mumbai (beta)")
        else
          Nil
      )
    )

    div(
      cls -> "demo-row",
      Select(region, options = regions, optionLabel = r => r).withLabel("Region"),
      Button(
        showBeta.map(b =>
          if b then
            "Hide beta regions"
          else
            "Show beta regions"
        )
      ).ghost.small.onClick(() => showBeta.update(!_))
    )
  end selectDynamic
  // #endregion

  // #region select/disabled
  inline def selectDisabled: RxElement =
    val plan   = Rx.variable("Free")
    val locked = Rx.variable(true)

    div(
      cls -> "demo-row",
      Select(plan, options = Seq("Free", "Team", "Enterprise"), optionLabel = p => p)
        .withLabel("Plan")
        .withDisabled(locked),
      Button(
        locked.map(l =>
          if l then
            "Unlock"
          else
            "Lock"
        )
      ).ghost.small.onClick(() => locked.update(!_))
    )
  end selectDisabled
  // #endregion

  // #region toast/variants
  inline def toastVariants: RxElement =
    val toaster = Toaster()

    div(
      cls -> "demo-row",
      Button("Info").onClick(() => toaster.info("Sync started")),
      Button("Success").primary.onClick(() => toaster.success("Saved")),
      Button("Error").danger.onClick(() => toaster.error("Upload failed")),
      // Mount one Toaster per app; it shows its toasts in a corner of the window
      toaster
    )
  end toastVariants
  // #endregion

  // #region toast/duration
  inline def toastDuration: RxElement =
    val toaster = Toaster()

    div(
      cls -> "demo-row",
      Button("1.5 seconds").ghost.onClick(() => toaster.info("Gone soon", durationMs = 1500)),
      Button("10 seconds").ghost.onClick(() => toaster.info("Stays a while", durationMs = 10000)),
      Button("Clear").ghost.onClick(() => toaster.clear()),
      toaster
    )
  end toastDuration
  // #endregion

  val pages: Seq[ComponentPage] = Seq(
    ComponentPage(
      "popover",
      "Popover",
      "A non-modal panel next to its trigger, which flips and shifts to stay in the window.",
      Seq(
        Story(
          "popover/basic",
          "Basic",
          "Press Filter and type; Escape or a press outside closes it."
        )(popoverBasic),
        Story(
          "popover/placement",
          "Placement",
          "Each popover opens on its own side, and flips when there's no room."
        )(popoverPlacement),
        Story(
          "popover/inline",
          "In place",
          "Press Details: the panel renders next to its trigger in the DOM."
        )(popoverInline)
      ),
      ApiDoc.of[Popover],
      a11yNote =
        "No APG pattern: the trigger gets aria-expanded and aria-controls, focus moves into the panel, and Escape returns it."
    ),
    ComponentPage(
      "tooltip",
      "Tooltip",
      "A text label that shows on hover, after a delay, and at once on keyboard focus.",
      Seq(
        Story("tooltip/basic", "Basic", "Hover the button, or Tab to it.")(tooltipBasic),
        Story("tooltip/sides", "Sides", "Hover each button.")(tooltipSides),
        Story("tooltip/delay", "Delay", "Compare the two buttons.")(tooltipDelay)
      ),
      ApiDoc.of[Tooltip],
      Seq(
        A11yCheck(
          AriaPattern.Tooltip,
          "[role=tooltip]",
          "Hover or focus a trigger to show a tooltip first."
        )
      )
    ),
    ComponentPage(
      "menu",
      "Menu",
      "A menu button with items, shortcuts, checked items, separators, and a context menu.",
      Seq(
        Story(
          "menu/basic",
          "Basic",
          "Open the menu with a click, Enter, or ArrowDown; type a letter to jump."
        )(menuBasic),
        Story("menu/checked", "Checked items", "Toggle the options: the checks follow the RxVars.")(
          menuChecked
        ),
        Story("menu/context", "Context menu", "Right-click the box.")(menuContext)
      ),
      ApiDoc.of[Menu] ++ ApiDoc.of[MenuItem] ++ ApiDoc.of[ContextMenu],
      Seq(
        A11yCheck(AriaPattern.MenuButton, "[aria-haspopup=menu]"),
        A11yCheck(AriaPattern.Menu, "[role=menu]", "Open a menu first.")
      )
    ),
    ComponentPage(
      "dialog",
      "Dialog",
      "Modal dialogs with a focus trap, Escape stacking, and focus return; AlertDialog for confirm-or-cancel.",
      Seq(
        Story("dialog/basic", "Basic", "Press Rename…, edit, then press Done or Escape.")(
          dialogBasic
        ),
        Story(
          "dialog/form",
          "A form",
          "Send invite stays disabled until the email has an @; the backdrop doesn't close it."
        )(dialogForm),
        Story("dialog/alert", "Alert dialog", "Press Delete…, then confirm or cancel.")(dialogAlert)
      ),
      ApiDoc.of[Dialog] ++ ApiDoc.of[AlertDialog],
      Seq(
        A11yCheck(AriaPattern.Dialog, "[role=dialog]", "Open a dialog first."),
        A11yCheck(AriaPattern.AlertDialog, "[role=alertdialog]", "Open the alert dialog first.")
      )
    ),
    ComponentPage(
      "select",
      "Select",
      "A button with a list of options, bound to an RxVar; the options can be an Rx.",
      Seq(
        Story("select/basic", "Basic", "Open it, then use the arrow keys or type a letter.")(
          selectBasic
        ),
        Story(
          "select/dynamic",
          "Changing options",
          "Show the beta regions: the list follows the Rx."
        )(selectDynamic),
        Story("select/disabled", "Disabled", "Press Unlock to enable it.")(selectDisabled)
      ),
      ApiDoc.of[Select[?]],
      Seq(A11yCheck(AriaPattern.Listbox, "[role=listbox]", "Open a select first."))
    ),
    ComponentPage(
      "toast",
      "Toast",
      "Short messages in a corner of the window, from a Toaster mounted once per app.",
      Seq(
        Story("toast/variants", "Variants", "Press each button.")(toastVariants),
        Story("toast/duration", "Duration", "Compare the two; Clear removes them all.")(
          toastDuration
        )
      ),
      ApiDoc.of[Toaster],
      a11yNote =
        "The toaster is a live region, so screen readers announce each toast, and it stays usable while a dialog is open."
    )
  )

end OverlayStories
