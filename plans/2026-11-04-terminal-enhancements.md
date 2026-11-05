# Terminal enhancements after the first uni-harness Terminal tab

The emulator (`wvlet.uni.cli.term`), `dom.TerminalView`, and the uni-harness Terminal tab (#856, #857, #859, #862) cover xterm escape sequences, scrollback, OSC 8 links, the title, the bell, restart, and themes. This plan lists what comes next, in order. Each step is its own PR.

## 1. Rewrap on resize (this PR)

`Screen.resize` cut or padded lines to the new width. The uni-harness dock now resizes by dragging (#858), so narrowing it cut off long output for good.

- On a width change, rows joined by `Line.isWrapped` form one logical line, laid out again at the new width. Wide characters stay whole, and the blank a wide character left at the margin is dropped.
- The cursor and the saved cursor move with the character they were on. A pending wrap is kept, so the next character still goes after the last one.
- Rows that overflow the screen move into the scrollback, but the cursor's row stays visible. `scrollbackVersion` changes, so `TerminalView` redraws the scrollback.
- The alternate screen is cut or padded, not rewrapped: full-screen programs redraw it on SIGWINCH.
- A line that fits at the new width keeps its `Line` object, so a resize does not reallocate the whole scrollback.

## 2. Prompt marks (OSC 133) and chat hand-off

- Parse OSC 133 `A`/`B`/`C`/`D` into marks on lines (prompt start, command start, output start, end with exit code).
- `TerminalView`: jump between prompts, select one command's output, and show a marker for a failed exit code in the margin.
- uni-harness: "Send selection to chat" and, for a failed command, "Explain this error" with the command and its output; a command the assistant suggests runs with one click. Document the shell integration snippet for zsh and bash.

## 3. Find and plain-text links

- Cmd/Ctrl+F in `TerminalView`: highlight matches in the scrollback and the screen, and step through them.
- Detect URLs and `path:line` in plain text and open them with the same Cmd/Ctrl+click as OSC 8 links.
- Font zoom (Cmd + / − / 0) through the existing `withFontSize` and cell measurement.
- Emit OSC 7 changes through an `onWorkingDirectoryChange` listener and show the directory in the pane header.

## 4. Sessions

- Several terminal sessions (tabs or splits) in the Terminal pane, with `TerminalBridge` sessions keyed by a session id rather than the window.
- Keep the shell across a renderer reload: the main process keeps the pty and a buffer of recent output, and replays it when the page reconnects.
- Option to start in the workspace directory instead of `$HOME`; a shortcut to toggle the Terminal tab.
- Web build: a real shell over WebSocket (`NodeWebSocket` and `NodePty`), on localhost only and with a token, since it gives full shell access.

## 5. Rendering under heavy output

Measure first: a benchmark of `cat` of a large file and `yes` into a 5000-line scrollback. Then either draw only the scrollback rows near the viewport, or cap how much output is parsed per animation frame.

## Later

- OSC 52 clipboard writes, as an opt-in and write-only by default (the ADR explains why it is off).
