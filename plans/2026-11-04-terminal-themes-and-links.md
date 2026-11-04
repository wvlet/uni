# Terminal theme presets and clickable links

## Goals

- Give `TerminalView` a polished look out of the box: a Uni theme drawn from Uni's brand palette, as the default, plus a small set of well-known presets.
- Make OSC 8 hyperlinks clickable without app code, with a rule that never opens a dangerous URI.
- Show the Electron way to open links: the system browser via `shell.openExternal` in the main process, never a new Electron window.
- Close the doc gap from #856: list `cli.term` and `TerminalView` in the AI-agent guide and the `uni` skill.

## Background

- Today there are only `TerminalTheme.dark` and `.light` (VS Code-like colors), and `TerminalViewConfig` fixes the theme at construction, so a pane cannot follow an app's light/dark switch.
- Links show a dotted underline, but a click does nothing unless the app registers `onLinkClick`. The harness handler calls `window.open`, which in Electron opens a bare app window instead of the browser.
- Uni's palette: deep teal-black surfaces (`#061114`, `#0a1a1e`), teal brand (`#2e7685` light, `#25a7c1` dark), a cyan glow `#08bdcb`, and silver `#b7b9b4` (harness `style.css`, docs `custom.css`).
- Program output controls OSC 8 URIs, so any URI scheme can appear, including `javascript:`, `file:`, and app-specific schemes.

## Design

Lane: Standard — new public API and default behavior, and the Electron link path crosses a security boundary.

**Themes**

- Add `TerminalTheme.uniDark` and `uniLight`: teal-black or near-white background, glow-cyan cursor, brand-tinted selection, and 16 ANSI colors tuned to sit with the teal (blue and cyan kept distinct). `uniDark` becomes the `TerminalViewConfig` default.
- Add presets from MIT-licensed palettes, credited in Scaladoc: `solarizedDark`, `solarizedLight`, `nord`, `dracula`, `gruvboxDark`, `oneDark`. Keep `dark` and `light`.
- Add `TerminalTheme.presets`, an ordered name-to-theme map, so apps can build a theme picker.
- Add `TerminalView.setTheme(theme)` to switch at runtime. Default colors and the 16 ANSI colors render as CSS custom properties on the pane, so a switch updates the variables without redrawing rows. 256-color cube and truecolor cells keep fixed values, as in xterm.

**Links**

- The rule: a link is active only when its scheme is in an allowlist, by default `http`, `https`, and `mailto` (case-insensitive, parsed up to the first `:`). URIs with other schemes render as plain text, with no underline or tooltip.
- Opening needs Cmd+click on macOS and Ctrl+click elsewhere, as in VS Code and iTerm2. A plain click keeps selecting text and focusing the pane, and the modifier click works even while a program tracks the mouse.
- Hover: active links keep the dotted underline. With the modifier held, a hovered link gets a solid underline and a pointer cursor, and its tooltip reads "Cmd+click to open <uri>" (Ctrl on other platforms).
- With no handler, the view opens the link with `window.open(uri, "_blank", "noopener,noreferrer")`. `onLinkClick` replaces that opener and only ever sees allowed URIs.
- Config: `withLinkSchemes(...)` changes the allowlist, and `noLinks()` turns links off.

**uni-harness**

- `TerminalPane` uses `uniDark` or `uniLight` and follows the app's System/Light/Dark setting, including OS changes under System, through `setTheme`. The pane's hard-coded `#1e1e1e` border background goes away.
- In Electron, links go to a new `uniShell.openExternal(uri)` preload bridge. The main process applies the same `isAllowedSender` check as `TerminalBridge`, re-checks the scheme allowlist, and calls `shell.openExternal`. The web build keeps the default `window.open`.
- Main also denies `window.open` from the renderer (`setWindowOpenHandler`), so no code path can open a bare app window.

**Docs**

- Add `cli.term` and `TerminalView` to `docs/guide/for-ai-agents.md` and a terminal section to the `uni` skill, with snippets compiled by `docSnippets` (JVM-headless snippets compile; DOM snippets are marked `skip`). Bump the plugin version.
- Update the ADR with the link rule and the theme defaults.
- Tests cover the scheme rule, the default opener versus a handler, theme switching without row redraws, and the preset palettes' sizes.

## Alternatives and Why Not?

- **Plain click opens links.** Conflicts with selecting link text and with focusing the pane; every major terminal requires a modifier.
- **Detect bare URLs in output (like xterm.js's web-links addon).** Useful, but it scans every row and guesses boundaries; it can come later on top of the same allowlist.
- **Keep the theme fixed and rebuild the view on a theme change.** Loses the screen and scrollback DOM, and re-renders thousands of rows.
- **Allow `file:` links by default.** Program output could point the user at any local file or app handler; apps can opt in with `withLinkSchemes`.
