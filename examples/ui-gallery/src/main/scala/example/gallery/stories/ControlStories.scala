package example.gallery.stories

import example.gallery.{A11yCheck, ApiDoc, ComponentPage, Control, Story}
import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*

import scala.language.implicitConversions

/**
  * Each example is one `inline def` inside a `// #region <page>/<example>` block. The gallery shows
  * that block as the example's code, so keep everything the demo needs inside it.
  */
object ControlStories:

  // #region button/variants
  inline def buttonVariants: RxElement =
    val clicks = Rx.variable(0)

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-row",
        Button("Default").onClick(() => clicks.update(_ + 1)),
        Button("Primary").primary.onClick(() => clicks.update(_ + 1)),
        Button("Ghost").ghost.onClick(() => clicks.update(_ + 1)),
        Button("Danger").danger.onClick(() => clicks.update(_ + 1))
      ),
      div(
        cls -> "demo-row",
        Button("Small").small.onClick(() => clicks.update(_ + 1)),
        Button("Small primary").primary.small.onClick(() => clicks.update(_ + 1))
      ),
      p(cls -> "demo-note", clicks.map(n => s"Clicked ${n} times"))
    )
  end buttonVariants
  // #endregion

  // #region button/disabled
  inline def buttonDisabled: RxElement =
    val agreed = Rx.variable(false)

    div(
      cls -> "demo-row",
      Button("Continue").primary.withDisabled(agreed.map(!_)),
      Button(
        agreed.map(a =>
          if a then
            "Withdraw"
          else
            "I agree"
        )
      ).ghost.onClick(() => agreed.update(!_))
    )
  end buttonDisabled
  // #endregion

  // #region button/busy
  inline def buttonBusy: RxElement =
    val saving = Rx.variable(false)
    val saved  = Rx.variable(0)

    def save(): Unit =
      saving := true
      dom
        .window
        .setTimeout(
          () =>
            saving := false
            saved.update(_ + 1)
          ,
          1500
        )

    div(
      cls -> "demo-row",
      Button(
        saving.map(s =>
          if s then
            "Saving…"
          else
            "Save"
        )
      ).primary.withDisabled(saving).onClick(() => save()),
      span(cls -> "demo-note", saved.map(n => s"Saved ${n} times"))
    )
  end buttonBusy
  // #endregion

  // #region button/icon
  inline def buttonIcon: RxElement =
    val starred = Rx.variable(false)

    div(
      cls -> "demo-row",
      Tooltip(
        starred.map(s =>
          if s then
            "Unstar"
          else
            "Star"
        ),
        trigger = IconButton(
          "Star",
          span(
            starred.map(s =>
              if s then
                "★"
              else
                "☆"
            )
          )
        ).onClick(() => starred.update(!_))
      ),
      Tooltip("Close", trigger = IconButton("Close", span("✕"))),
      span(cls -> "demo-note", "An IconButton's label is its accessible name and its tooltip.")
    )
  end buttonIcon
  // #endregion

  // #region button/props
  case class ButtonProps(
      label: String = "Save",
      variant: ButtonVariant = ButtonVariant.Primary,
      size: ButtonSize = ButtonSize.Medium,
      disabled: Boolean = false
  )

  inline def buttonProps(p: ButtonProps): RxElement = Button(p.label)
    .withVariant(p.variant)
    .withSize(p.size)
    .withDisabled(p.disabled)
  // #endregion

  // #region input/basic
  inline def inputBasic: RxElement =
    val name = Rx.variable("")

    div(
      cls -> "demo-stack demo-narrow",
      Input(name).withLabel("Name").withPlaceholder("Ada Lovelace"),
      p(
        cls -> "demo-note",
        name.map(n =>
          if n.isEmpty then
            "Type a name"
          else
            s"Hello, ${n}!"
        )
      )
    )
  end inputBasic
  // #endregion

  // #region input/validation
  inline def inputValidation: RxElement =
    val email   = Rx.variable("")
    val invalid = email.map(e => e.nonEmpty && !e.matches("""[^@\s]+@[^@\s]+\.[^@\s]+"""))

    div(
      cls -> "demo-stack demo-narrow",
      Input(email)
        .withType("email")
        .withLabel("Email")
        .withPlaceholder("ada@example.com")
        .withInvalid(invalid),
      p(
        cls -> "demo-note",
        invalid.map(bad =>
          if bad then
            "That doesn't look like an email address"
          else
            "aria-invalid follows the check"
        )
      )
    )
  end inputValidation
  // #endregion

  // #region input/types
  inline def inputTypes: RxElement =
    val password = Rx.variable("")
    val quantity = Rx.variable("1")

    div(
      cls -> "demo-stack demo-narrow",
      Input(password).withType("password").withLabel("Password"),
      Input(quantity).withType("number").withLabel("Quantity"),
      p(
        cls -> "demo-note",
        Rx.join(password, quantity).map((pw, q) => s"${pw.length} characters · quantity ${q}")
      )
    )
  end inputTypes
  // #endregion

  // #region input/states
  inline def inputStates: RxElement =
    val locked = Rx.variable(true)

    div(
      cls -> "demo-stack demo-narrow",
      Input(Rx.variable("ada@example.com"))
        .withLabel("Read-only: select and copy, not edit")
        .withReadOnly(true),
      Input(Rx.variable("Workspace")).withLabel("Disabled until unlocked").withDisabled(locked),
      Button(
        locked.map(l =>
          if l then
            "Unlock"
          else
            "Lock"
        )
      ).ghost.small.onClick(() => locked.update(!_))
    )
  end inputStates
  // #endregion

  // #region input/shared
  inline def inputShared: RxElement =
    val title = Rx.variable("Untitled")

    div(
      cls -> "demo-stack demo-narrow",
      Input(title).withLabel("Title"),
      Input(title).withLabel("The same RxVar"),
      p(cls -> "demo-note", "Both fields write to one RxVar, so each follows the other.")
    )
  end inputShared
  // #endregion

  // #region input/props
  case class InputProps(
      label: String = "Email",
      placeholder: String = "ada@example.com",
      invalid: Boolean = false,
      disabled: Boolean = false,
      readOnly: Boolean = false
  )

  inline def inputProps(p: InputProps): RxElement = Input(Rx.variable(""))
    .withLabel(p.label)
    .withPlaceholder(p.placeholder)
    .withInvalid(Rx.const(p.invalid))
    .withDisabled(p.disabled)
    .withReadOnly(p.readOnly)
  // #endregion

  // #region textarea/counter
  inline def textareaCounter: RxElement =
    val text  = Rx.variable("")
    val limit = 140

    div(
      cls -> "demo-stack demo-narrow",
      Textarea(text)
        .withLabel("Status")
        .withRows(4)
        .withPlaceholder("What's new?")
        .withInvalid(text.map(_.length > limit)),
      p(cls -> "demo-note", text.map(t => s"${t.length} / ${limit}"))
    )
  end textareaCounter
  // #endregion

  // #region textarea/read-only
  inline def textareaReadOnly: RxElement = div(
    cls -> "demo-stack demo-narrow",
    Textarea(Rx.variable("Release notes are generated from merged pull requests."))
      .withLabel("Generated notes")
      .withRows(3)
      .withReadOnly(true)
  )
  // #endregion

  // #region tabs/basic
  enum Pane:
    case Overview
    case Activity
    case Settings

  inline def tabsBasic: RxElement =
    val selected = Rx.variable(Pane.Overview)
    val list     = TabList(
      selected,
      label = "Project",
      tabs = Seq(
        Tab(Pane.Overview, "Overview"),
        Tab(Pane.Activity, "Activity", Badge("3").withTone(BadgeTone.Accent)),
        Tab(Pane.Settings, "Settings")
      )
    )

    div(
      cls -> "demo-stack",
      list,
      TabPanel(list, render = pane => div(cls -> "demo-panel", s"The ${pane} tab's content"))
    )
  end tabsBasic
  // #endregion

  // #region tabs/controlled
  inline def tabsControlled: RxElement =
    val steps = Seq("Account", "Profile", "Review")
    val step  = Rx.variable("Account")
    val list  = TabList(step, label = "Steps", tabs = steps.map(s => Tab(s, s)))

    def move(by: Int): Unit =
      val i = (steps.indexOf(step.get) + by).max(0).min(steps.size - 1)
      step := steps(i)

    div(
      cls -> "demo-stack",
      list,
      TabPanel(list, render = s => div(cls -> "demo-panel", s"Step: ${s}")),
      div(
        cls -> "demo-row",
        Button("Back").ghost.onClick(() => move(-1)),
        Button("Next").primary.onClick(() => move(1))
      )
    )
  end tabsControlled
  // #endregion

  // #region tabs/guard
  inline def tabsGuard: RxElement =
    val tab     = Rx.variable("Draft")
    val unsaved = Rx.variable(true)
    val list    = TabList(
      tab,
      label = "Editor",
      tabs = Seq(Tab("Draft", "Draft"), Tab("Preview", "Preview"))
    ).onChange(d =>
      if unsaved.get && d.value == "Preview" then
        d.cancel()
    )

    div(
      cls -> "demo-stack",
      list,
      TabPanel(list, render = t => div(cls -> "demo-panel", s"${t}")),
      div(
        cls -> "demo-row",
        Button(
          unsaved.map(u =>
            if u then
              "Save draft"
            else
              "Saved"
          )
        ).primary.withDisabled(unsaved.map(!_)).onClick(() => unsaved := false),
        span(
          cls -> "demo-note",
          unsaved.map(u =>
            if u then
              "Preview is blocked until you save"
            else
              "Preview is open"
          )
        )
      )
    )
  end tabsGuard
  // #endregion

  // #region badge/tones
  inline def badgeTones: RxElement = div(
    cls -> "demo-row",
    Badge("Neutral"),
    Badge("Accent").withTone(BadgeTone.Accent),
    Badge("Danger").withTone(BadgeTone.Danger),
    span(
      cls -> "demo-row",
      Badge.dot("New output"),
      span(cls -> "demo-note", "a dot, named for screen readers")
    )
  )
  // #endregion

  // #region badge/count
  inline def badgeCount: RxElement =
    val unread = Rx.variable(2)

    div(
      cls -> "demo-row",
      span("Inbox"),
      Badge(unread.map(_.toString)).withTone(BadgeTone.Accent),
      Button("New message").ghost.small.onClick(() => unread.update(_ + 1)),
      Button("Mark all read").ghost.small.onClick(() => unread := 0)
    )
  end badgeCount
  // #endregion

  // #region badge/kbd
  inline def badgeKbd: RxElement = div(
    cls -> "demo-stack",
    div(cls -> "demo-row", Kbd("⌘"), Kbd("K"), span(cls -> "demo-note", "Search")),
    div(cls -> "demo-row", Kbd("⌘"), Kbd("B"), span(cls -> "demo-note", "Toggle the sidebar")),
    div(cls -> "demo-row", Kbd("Esc"), span(cls -> "demo-note", "Close the top overlay"))
  )
  // #endregion

  val pages: Seq[ComponentPage] = Seq(
    ComponentPage(
      "button",
      "Button",
      "A <button type=\"button\"> with variants, sizes, a disabled state bound to an Rx, and an icon-only form.",
      Seq(
        Story("button/variants", "Variants and sizes", "Press any button: the count goes up.")(
          buttonVariants
        ),
        Story("button/disabled", "Disabled", "Continue stays disabled until you press I agree.")(
          buttonDisabled
        ),
        Story(
          "button/busy",
          "Busy",
          "Press Save. It disables itself for 1.5 seconds while it saves."
        )(buttonBusy),
        Story(
          "button/icon",
          "Icon buttons",
          "Hover or focus a button for its tooltip; press the star to toggle it."
        )(buttonIcon),
        Story.playground(
          "button/props",
          "Props",
          "Change the props above the preview; the code follows.",
          ButtonProps(),
          Seq(
            Control.text("label", _.label, (p, v) => p.copy(label = v)),
            Control.choice(
              "variant",
              ButtonVariant.values.toSeq,
              _.variant,
              (p, v) => p.copy(variant = v),
              v => s"ButtonVariant.${v}"
            ),
            Control.choice(
              "size",
              ButtonSize.values.toSeq,
              _.size,
              (p, v) => p.copy(size = v),
              v => s"ButtonSize.${v}"
            ),
            Control.toggle("disabled", _.disabled, (p, v) => p.copy(disabled = v))
          )
        )((p: ButtonProps) => buttonProps(p))
      ),
      ApiDoc.of[Button],
      a11yNote =
        "A native <button>: Enter and Space press it. IconButton needs its label, which becomes aria-label."
    ),
    ComponentPage(
      "input",
      "Input",
      "A text field bound both ways to an RxVar[String], with a label, validation, types, and read-only and disabled states.",
      Seq(
        Story("input/basic", "Basic", "Type a name.")(inputBasic),
        Story(
          "input/validation",
          "Validation",
          "Type an address without an @: the field turns invalid."
        )(inputValidation),
        Story("input/types", "Types", "withType sets the input type: password and number here.")(
          inputTypes
        ),
        Story("input/states", "Read-only and disabled", "Press Unlock to enable the second field.")(
          inputStates
        ),
        Story("input/shared", "One value, two fields", "Type in either field.")(inputShared),
        Story.playground(
          "input/props",
          "Props",
          "Change the props above the preview; the code follows.",
          InputProps(),
          Seq(
            Control.text("label", _.label, (p, v) => p.copy(label = v)),
            Control.text("placeholder", _.placeholder, (p, v) => p.copy(placeholder = v)),
            Control.toggle("invalid", _.invalid, (p, v) => p.copy(invalid = v)),
            Control.toggle("disabled", _.disabled, (p, v) => p.copy(disabled = v)),
            Control.toggle("readOnly", _.readOnly, (p, v) => p.copy(readOnly = v))
          )
        )((p: InputProps) => inputProps(p))
      ),
      ApiDoc.of[Input],
      a11yNote = "withLabel renders a <label> tied to the field; withInvalid sets aria-invalid."
    ),
    ComponentPage(
      "textarea",
      "Textarea",
      "A multi-line text field with the same options as Input, plus rows.",
      Seq(
        Story(
          "textarea/counter",
          "Character count",
          "Type more than 140 characters: the field turns invalid."
        )(textareaCounter),
        Story("textarea/read-only", "Read-only", "Select the text: it copies, but doesn't change.")(
          textareaReadOnly
        )
      ),
      ApiDoc.of[Textarea]
    ),
    ComponentPage(
      "tabs",
      "Tabs",
      "TabList and TabPanel over any value, bound to an RxVar, with arrow-key navigation and an onChange that can refuse a switch.",
      Seq(
        Story("tabs/basic", "Basic", "Focus a tab, then press ← →, Home, or End.")(tabsBasic),
        Story(
          "tabs/controlled",
          "Controlled",
          "Press Next and Back: the app sets the RxVar, and the tabs follow."
        )(tabsControlled),
        Story(
          "tabs/guard",
          "Refusing a switch",
          "Try Preview: onChange cancels it until you save."
        )(tabsGuard)
      ),
      ApiDoc.of[TabList[?]] ++ ApiDoc.of[TabPanel[?]] ++ ApiDoc.of[Tab[?]],
      Seq(A11yCheck(wvlet.uni.dom.testing.AriaPattern.Tabs, "[role=tablist]"))
    ),
    ComponentPage(
      "badge",
      "Badge & Kbd",
      "Small labels for counts and status, and key caps for shortcuts.",
      Seq(
        Story("badge/tones", "Tones", "Neutral, accent, danger, and a status dot.")(badgeTones),
        Story("badge/count", "A live count", "Press New message, then Mark all read.")(badgeCount),
        Story("badge/kbd", "Key caps", "Kbd renders <kbd>, for shortcuts in menus and hints.")(
          badgeKbd
        )
      ),
      ApiDoc.of[Badge] ++ ApiDoc.of[Kbd]
    )
  )

end ControlStories
