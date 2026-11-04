# uni-harness Terminal tab: title, restart, bell

## Goals

- The Terminal tab should show what the shell is doing, recover after the shell exits, and not swallow the bell.
- Follow-up to #858; the dock width and resize behaviour stay as they are.

## Background

- `TerminalEmulator` already parses OSC 0/2 titles and BEL (`term.title`, `onTitleChange`, `onBell`), but the pane never subscribes.
- The main-process `TerminalBridge` already handles a second `pty-start`: it kills the old shell, spawns a new one, and drops output from the replaced one. So restart needs no bridge change.
- The preload `uniPty.onData` / `onExit` add an IPC listener per call with no way to remove it. The renderer must register them once and only call `start` again on restart, or output would be written twice.
- The dock re-renders its body on every tab switch, so `TerminalPane` unmounts while another tab is shown. Its emulator and shell live on (the pane is a `lazy val` in `Dock`).

## Design

Lane: **UI** — small, user-facing polish on an existing tab, no new screen, setting, or persisted state, no always-gate area.

- **Header strip:** a thin row above the terminal view shows the shell title, truncated with an ellipsis (full text in a tooltip), falling back to "Terminal". The tab button keeps its "Terminal" label, since three tabs share a narrow strip.
- **Exit:** on `pty-exit` the pane leaves the alternate screen and soft-resets modes (so a killed full-screen app doesn't leave mouse or paste modes on), then prints `[process exited with code N] Press Enter to restart`. The header shows a **Restart** button.
- **Restart:** Enter in the terminal or the Restart button calls `pty.start(cols, rows)` again; scrollback is kept and the title resets to the fallback. Other keys are ignored while the shell is down.
- **Bell flash:** BEL briefly rings the pane border in the accent color (about 150 ms, CSS transition), throttled so a burst of bells doesn't strobe.
- **Attention dot:** a BEL while the dock is closed or shows another tab sets a dot on the Terminal tab; it clears as soon as Terminal is visible.
- **Listener lifetime:** title and bell listeners live as long as the emulator and are not canceled in `beforeUnmount`. This departs from the stated decision: the pane unmounts on every tab switch, so mount-scoped bell listeners would miss exactly the bells the dot exists for. The emulator and pane share one lifetime, so nothing leaks. `themeWatch` keeps its mount scope.
- **Browser demo:** `DemoShell` gets `bell` and `title <text>` commands so the web build can show both; it never exits, so restart is Electron only.
- Tests cover the title fallback/truncation helper and the bell-attention rule (visible vs hidden).

## Alternatives and Why Not?

- Title in the tab label: the three tabs split a narrow strip, so a title there would truncate to a few characters.
- Clear the screen on restart (`term.reset()`): loses the output the user may want to read after a crash.
- Change `uniPty` to return unsubscribe handles: not needed if the renderer registers once, and it is outside this follow-up.
