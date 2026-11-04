# A pure-Scala terminal emulator for Uni

## Goals

- Let Uni GUI apps (the uni-harness, Electron and web apps) embed a working terminal pane, like the xterm.js pane in Treasure Work, written in Scala.
- Keep the emulator core pure Scala and cross-platform, so the same code also runs headless on JVM and Native. Tests can then check the screen a TUI draws (`LineEditor`, `ProgressBar`, a REPL) instead of comparing raw escape bytes.
- Keep Uni's minimal-dependency rule: no new runtime library in `uni`.

## Background

- A terminal pane has four parts: a VT/ANSI byte parser, a screen model (cell grid, cursor, modes, scrollback), a key and mouse input encoder, and a renderer. Only the PTY that runs the shell is OS-specific.
- Uni already has some pieces: `cli.KeyDecoder`/`Key` (escape bytes to keys, the reverse of what a terminal sends), `cli.DisplayWidth` (wide characters and emoji), `cli.Color`/`Tint`, and the IME-safe input and `PaneResize` helpers in uni-dom.
- On Scala.js a PTY cannot be pure Scala: Node has no PTY API, so Electron apps use the native `node-pty` addon (xterm.js has the same constraint). On Native, `forkpty` from libc is enough. On the JVM it needs JNI or a library such as pty4j.
- xterm.js is MIT-licensed TypeScript, about 50k lines. Most of that is renderers (DOM, canvas, WebGL), addons, and years of compatibility fixes.

## Design

Lane: Standard — a new public API and a new module area with an unclear scope.

- **Write it, don't transliterate it.** Build the parser on Paul Williams' DEC state machine (used by xterm.js, VTE, and Alacritty) and use xterm.js and the xterm control-sequence spec only as behaviour references.
- **Package** `wvlet.uni.cli.term`**, in the shared part of** `uni`**.** An emulator speaks the other side of the protocol that `cli` already speaks, so it sits under `cli` next to `Key`, `KeyDecoder`, `DisplayWidth`, and `Color`, which it reuses (`private[cli]` included). It holds `EscapeParser` (bytes to actions), `Screen` (grid, alternate screen, scrollback ring, scroll regions, wide cells), `InputEncoder` (keys, paste, and mouse to bytes), and `TerminalEmulator` (ties them together and emits responses such as DSR and DA). `term` names the terminal itself (as in `$TERM`); `tty` would name the kernel device the PTY provides. The existing `cli.Terminal` helper is unchanged.
- **The other parts get their own scoped names.** The renderer is `wvlet.uni.dom.TerminalView`, beside the other uni-dom components. The PTY interface is `wvlet.uni.cli.term.PtyProcess`, with the `node-pty` facade in the Scala.js part of the same package, because it works in plain Node and not only in Electron.
- **Compact cell storage.** Store each row as packed `Int` arrays (code point, foreground, background, flags) with per-row dirty flags. Renderers redraw only changed rows, and scrollback stays cheap.
- **v1 target: what real TUIs need, measured as xterm-256color.** That means cursor and erase commands, scroll regions, SGR with 16, 256, and truecolor, the alternate screen (1049), application cursor keys, bracketed paste, SGR mouse (1000/1002/1006), focus events, synchronized output (2026), OSC 0/2 titles, and OSC 8 links. The bar is vim, less, htop, and Claude Code rendering correctly.
- **Out of v1:** sixel and Kitty graphics, ligatures, a WebGL renderer, and OSC 52 clipboard writes (a security boundary; it may come later as an opt-in).
- **DOM renderer in uni-dom (Scala.js).** Each row is an element made of styled span runs. A hidden textarea handles keyboard and IME input with the existing IME-safe helpers, and `PaneResize` plus character measurement sets cols and rows. Selection and copy use a grid-based selection, because native selection breaks on redraws.
- **The PTY is a small** `PtyProcess` **interface, not part of the emulator.** v1 ships a Scala.js facade for `node-pty` that the app passes in (the value-handoff pattern of the Electron ADR, so uni never `require`s it), and the renderer reaches it over the existing Electron IPC/RPC transport. Native `forkpty` and a JVM backend come in later PRs.
- **Prototype:** a Terminal tab in the uni-harness dock that runs the user's shell through `node-pty` in Electron main. In the web build the tab runs an in-memory echo terminal.
- **Tests:** shared golden tests feed byte streams into `TerminalEmulator` and assert the screen text, attributes, and cursor on all three platforms. Playwright DOM tests cover rendering, input, IME, and resize.

## Alternatives and Why Not?

- **Wrap xterm.js with a Scala.js facade.** This is the fastest way to a pane, but it is JS-only, adds a large npm dependency, and gives no headless emulator for JVM/Native tests. It remains a good choice for apps that only need a pane today.
- **Port xterm.js line by line.** That means 50k lines of TypeScript architecture, three renderers, and a long maintenance tail. A state-machine core plus one renderer reaches the v1 bar with a fraction of the code.
- **JediTerm (the IntelliJ terminal) or Lanterna.** They are solid JVM libraries, but they are Swing-oriented and do not run on Scala.js or Native.
- **Put PTY spawning in uni.** It needs native code on every platform, so it stays behind an interface. Only the opt-in backends touch native code.