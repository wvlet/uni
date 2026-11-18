# TerminalView: open bare URLs, keep the selection on redraw

## Goals

- Let users open plain `https://…` URLs that programs print (login and device-code URLs, build and PR links), not only OSC 8 hyperlinks.
- Stop losing a text selection when the row it sits on redraws, e.g. selecting text on the prompt line or next to a progress bar while output streams.
- Both are follow-ups listed as known limitations in #857 (links) and #856 (selection).

## Background

- `TerminalView` (uni-dom, Scala.js) draws each terminal row as a `div` of text runs and redraws only dirty rows, plus the cursor row, once per animation frame.
- A redraw clears the row (`textContent = ""`) and rebuilds its nodes. The browser drops any selection whose endpoints were in the removed nodes, so selecting text on a row that keeps redrawing does not stick.
- OSC 8 links already work: cells carry a hyperlink id, `TerminalLink.isAllowed` gates schemes (`http`, `https`, `mailto` by default), and Cmd+click (Ctrl elsewhere) opens through `view.onLinkClick`.
- uni-harness wires `onLinkClick` to `Ui.openLink`, which sends the URI to the Electron main process; `TerminalBridge.wireLinks` checks `TerminalLink.isAllowed` again and calls `shell.openExternal`.
- Since #863, long lines soft-wrap and rewrap on resize, and each `Line` knows whether it continues the previous one (`isWrapped`).

## Design

Lane: **Standard** — new user-facing terminal behavior (a new kind of clickable text and a new config option).

User flow, links:
- A program prints a bare URL such as `https://github.com/login/device`.
- The URL looks like an OSC 8 link: dotted underline, solid with a pointer while Cmd (Ctrl) is held, and a tooltip "⌘+click to open …".
- Cmd+click opens it in the system browser through the same path as OSC 8 links (`onLinkClick` → main process → `shell.openExternal`). A plain click still focuses the terminal or keeps a selection.

Decisions, links:
- Only `http://` and `https://` are detected in plain text. No `www.` without a scheme, no `mailto:`, no `file:`; OSC 8 keeps its own allowlist.
- Detected URLs still pass `TerminalLink.isAllowed(uri, linkSchemes)`, so `noLinks()` or a narrower `withLinkSchemes` also turns them off.
- An OSC 8 link wins over detection on the same cells.
- The URL ends at whitespace or characters that cannot appear in a URL. Trailing sentence punctuation (`.`, `,`, `;`, `:`, `!`, `?`) and unbalanced closing `)`, `]`, `}`, `'`, `"` are trimmed, so `(see https://x.org/a).` links `https://x.org/a`.
- Detection runs over the logical line: a URL that soft-wraps across rows links in full, and Cmd+click on any part opens the whole URL. Lines the program broke itself with a newline are not joined.
- The underline is drawn with the row, but the URI is worked out from the emulator's lines at click time. A row drawn while a long URL was still streaming (or already in the scrollback) then still opens the complete URL.
- The matcher is a pure function in `wvlet.uni.cli.term.TerminalLink` (JVM, Scala.js, Native), so it is unit-tested without a DOM.
- New config option on `TerminalViewConfig`, following the existing `withXXX`/`noXXX` shape: `withUrlDetection(enabled)` and `noUrlDetection()`. On by default.

User flow, selection:
- The user drags to select text on a row that keeps redrawing (the prompt row with a blinking or moving cursor, a spinner line, a row a TUI repaints).
- The selection stays where it was, by row and column, and Cmd+C copies it.
- Clicking elsewhere or starting a new drag clears it as usual.

Decisions, selection:
- Before redrawing rows, the view records the selection's anchor and focus as (absolute line, cell column) when they are inside the terminal; after the redraw it maps them back to DOM offsets (wide and combining characters make offsets differ from columns). The cost applies only while a selection exists in the pane.
- The absolute line counts from the first line ever written, so output that scrolls the screen moves the selection up with its text, and a selection whose line the scrollback evicted is dropped.
- A drag still in progress holds row redraws until mouseup: the prototype showed Chromium restarts a drag whose text node was replaced, even after the selection is put back.
- The selection is kept by position, like xterm: if the text under it changes, the selection covers the new text at the same cells.
- A full rebuild (resize reflow, switch to or from the alternate screen) still clears the selection, since positions no longer map to the same text.

Tests and docs:
- Unit tests cover the URL matcher (trimming, brackets, wrapped lines, OSC 8 precedence) and the DOM view (detected link markup, selection kept across a cursor-row redraw).
- Update `adr/2026-11-04-terminal-emulator.md`: it accepted losing the selection on redraw and covers only OSC 8 links.
- Update `docs/guide/for-ai-agents.md`, the `uni` skill digest, and bump the plugin version.
- Prototype check in uni-harness: run `echo https://github.com/wvlet/uni`, a long wrapping URL, and a selection on the prompt row while a background loop prints.

## Alternatives and Why Not?

- Detect URLs in the emulator and store them as hyperlink ids on cells: changes the emulator's model and its `hyperlink` API for a display concern; detection on draw keeps the emulator unchanged.
- Detect URLs only on hover (xterm.js web-links style): no visible affordance until the user hovers, and the dotted underline is already the view's link convention.
- Skip redrawing rows whose content did not change: helps the cursor row but not rows a program actually rewrites, so the selection would still drop.
- Single-row detection only: simpler, but long login URLs, the most common case, wrap and would open truncated.
