# uni-harness: Markdown replies and saved conversations

## Goals

- **Markdown replies.** Assistant replies render headings, lists, code, tables, and links instead of raw `**text**`, so the harness looks like a real chat tool once a model is plugged in.
- **Saved conversations.** Chats survive an app restart; today only the Dock layout does (README: "Conversations live in memory").
- Both use Uni's own pieces (the Markdown parser, `SettingStore`), so the example shows them working together and adds no npm dependency.

## Background

- `wvlet.uni.text.markdown` already parses CommonMark + GFM on Scala.js and exposes the AST (`Block`, `Inline`). Its HTML output is safe by default, but it would need `innerHTML` (`RawHtml`).
- In Electron, a plain `<a href>` click would navigate the main window, which keeps the preload bridge. The Terminal tab already opens links through `uniShell.openExternal`, which sends them to the system browser.
- `ChatService` runs in Node (Electron main, or the web server), not in the renderer. `SettingStore` on Node writes atomic JSON files with Weaver, with versioning and corrupt-file recovery.
- Electron's `app.getPath('userData')` is `~/Library/Application Support/Uni Harness/` when packaged. In `pnpm dev` it is `…/uni-harness/`, because dev runs take the app name from package.json.

## Design

Lane: Standard. Both items add user-facing behavior, and persistence touches an always-gate area (a data storage path).

**User flow:** ask something, and the reply streams in as formatted Markdown. Quit and relaunch, and the sidebar lists the same chats with their messages. To start over, delete one file (named in the README).

Markdown rendering (renderer):
- A small `MarkdownView` in the example walks the parsed AST into uni-dom elements, with no `innerHTML`. Raw HTML in a reply shows as text.
- Links: only `http`, `https`, and `mailto` become links; other schemes render as plain text. A click opens the system browser through `uniShell` in Electron, and a new tab (`rel=noopener`) on the web.
- Images render as a link with their alt text. Nothing is loaded from remote URLs, which would leak the reader's IP.
- Each streamed part re-parses the whole reply, so a half-written list or code fence settles as more text arrives. Chat-sized text parses in well under a millisecond.
- User prompts stay plain text (`pre-wrap`), as people type them. **Copy** still copies the Markdown source.
- Styling: compact prose CSS for code blocks, lists, tables, and quotes, in both themes. The mock model's reply uses a little Markdown so the feature shows without a real model.

Persistence (server):
- `ChatService` takes an optional storage directory. With one, its conversations and messages live in a `SettingStore` file, `conversations.json`, in that directory. Without one, it stays in memory, as tests and the web build do today.
- **Path (needs your approval):** Electron passes `app.getPath('userData')`, so the file is `~/Library/Application Support/Uni Harness/conversations.json` (`…/uni-harness/` in dev). It sits next to Electron's own localStorage data, which already holds the Dock layout.
- Web build: memory only by default. Set `UNI_HARNESS_DATA_DIR` to save chats to a directory you choose.
- Writes happen when a chat is created, when a prompt is stored (`Started`), and when a reply finishes (`Done`), never on each streamed part. A reply cut off by quitting keeps its prompt but has no reply.
- On load, ids continue after the highest saved id. An empty "New chat" left from the last run is reused, so relaunching doesn't pile up blank chats.
- A corrupt file is renamed aside and the app starts fresh (`SettingStore` does this already). The file starts at schema version 0 (`SettingStore`'s default), with no migrations yet.
- No delete-chat UI and no size cap (out of scope). The README names the file to remove.
- Tests: `MarkdownView` rendering and link safety, in ComponentTest. `ChatService` reloading from a temporary directory, in ChatServiceTest.

**Two PRs** (user decision): Markdown rendering first, then persistence, each based on origin/main.

## Alternatives and Why Not?

- **`Markdown.toHtml` + `RawHtml`.** Less code, but it uses `innerHTML` and can't route link clicks through `uniShell` without a delegated listener; walking the AST keeps it typed and safe.
- **An npm renderer (marked, markdown-it).** Adds a dependency and a sanitizer, and doesn't show Uni's parser.
- **A reusable `Markdown` component in uni-ui.** Worth doing later, but it widens scope to library API, docs, and the plugin skill; prove it in the example first.
- **Persist in the renderer (localStorage).** The server owns the data and serves both transports; a renderer cache would diverge from it.
- **`~/.config/uni-harness` (SettingStore default).** It doesn't follow the macOS and Windows conventions for app data, which `userData` does.
