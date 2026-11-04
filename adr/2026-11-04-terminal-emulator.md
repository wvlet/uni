# A pure-Scala terminal emulator: `wvlet.uni.cli.term` and `dom.TerminalView`

## Context

GUI apps built with Uni, such as the uni-harness and Electron desktop apps, need an embedded terminal pane like the xterm.js pane in Treasure Work. xterm.js is about 50k lines of TypeScript and runs only in a browser. A terminal has four parts: a byte parser, a screen model, an input encoder, and a renderer. Only the PTY that runs the shell needs operating-system support.

## Decision

- The emulator core lives in `wvlet.uni.cli.term`, in the shared part of `uni` (JVM, Scala.js, Native). An emulator speaks the other side of the escape-sequence protocol that `cli` already speaks, and it reuses `Key` and `DisplayWidth`. `term` names the terminal itself (as in `$TERM`). `tty` would name the kernel device, and `vt` is jargon to most readers. Class names avoid a bare `Terminal`, which is already the `cli.Terminal` helper.
- It is written fresh, not transliterated from xterm.js. `EscapeParser` follows Paul Williams' DEC ANSI parser state machine (as xterm, VTE, and Alacritty do) and decodes UTF-8 incrementally. xterm and xterm.js serve as behaviour references only.
- `Line` stores cells in parallel `Int` arrays (code point, foreground, background, flags), with links and combining marks allocated lazily. Colors are packed into an `Int` (default, palette index, or RGB). A wide character takes a `Wide` cell plus a `WideTail` cell, and overwriting either half blanks the other (`Line.splitAt`).
- `Screen` exposes three change signals for renderers:
  - per-row dirty flags, for incremental redraws;
  - `scrollbackTotal`, a running count of lines that entered the scrollback, so new lines can be appended;
  - two version counters: `generation` (redraw the visible rows after a resize, reset, or screen switch) and `scrollbackVersion` (redraw the scrollback, because it was cleared or a resize moved lines back onto the screen).
  
  Keeping the last two counters apart means a resize or a vim session does not redraw thousands of scrollback rows.
- Lines enter the scrollback only when the scroll region starts at row 0 of the normal screen, as in xterm. Resize does not reflow wrapped lines. Shrinking moves rows above the cursor into the scrollback after dropping empty rows below it, and growing brings rows back.
- `TerminalEmulator` replies to queries (DSR, DA) and sends encoded input through `onData`, which an app connects to a `PtyProcess`. While synchronized output (mode 2026) is on, `onChange` is held. OSC 52 (clipboard writes) is ignored on purpose, because it would let program output write the user's clipboard.
- `InputEncoder` encodes keys by their W3C `KeyboardEvent.key` names, so it is pure Scala and testable, and DOM events map onto it directly. It also accepts uni `Key` values.
- `PtyProcess` is an interface. On Scala.js, `NodePty.spawn` takes the loaded `node-pty` module from the app (the value-handoff pattern of `2026-06-24-electron-ipc-transport.md`). uni never `require`s the native addon and has no npm dependency.
- `dom.TerminalView` (Scala.js, in `uni` because it needs `cli.term`) renders one DOM row per line from runs of styled spans. It redraws only dirty rows, at most once per animation frame. Scrollback rows are static DOM in their own container, which is hidden while the alternate screen shows. Scrolling, selection, and copy are the browser's own: a selection is lost when its row redraws, which we accept to avoid writing a custom selection. Input goes through a hidden textarea: printable text arrives through `input`/`compositionend`, so IME and dead keys work, and `keydown` encodes only control keys. AltGr, and macOS Option unless `macOptionIsMeta`, type text rather than Meta sequences.

## Consequences

- The same emulator runs headless on the JVM and Native, so TUI output can be checked as screen text and attributes instead of raw escape bytes.
- Native `forkpty` and a JVM PTY backend can be added behind `PtyProcess` later. Reflow, OSC 52 (as an opt-in), graphics, and a canvas or WebGL renderer can be added without changing the core API.
- The uni-harness example shows the Electron wiring: a `uniPty` preload bridge, a main-process `TerminalBridge` with the `ElectronRPCServerConfig.isAllowedSender` check, and a postinstall script that restores the execute bit on node-pty's prebuilt `spawn-helper`, which pnpm drops.
