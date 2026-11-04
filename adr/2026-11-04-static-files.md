# StaticFiles: serving a directory from Http.server

## Context

`examples/uni-harness` served its built renderer with its own Node-only `StaticFiles` class. Any Uni app with a web frontend next to its API needs the same thing, and a hand-written handler easily gets path traversal, symlinks, or content types wrong. Serving files is a security boundary, so the library version must be safe without configuration, in the spirit of `2026-10-14-http-server-secure-defaults.md`.

## Decision

- **A filter, not a server setting.** `StaticFiles(root)` is an `RxHttpFilter`: a `GET` for a servable file returns it, and everything else goes to `next`. It composes with `Http.server`, `Netty.server`, `RouterHandler`, and auth filters, and their order decides whether files are public. `HttpServerConfig.withStaticFiles(dir)` was rejected because it would add a field for what a filter already does.
- **Shared code on uni-core `FileSystem`.** Its `info` uses `lstat` on the JVM, Node.js, and Native, so one implementation covers every server backend. In a browser the constructor fails, since there is no file system.
- **Validate the URL before touching the file system.** The path is split on `/`, and each segment is percent-decoded once. A segment that is empty, `.`, or `..`, or that contains `/` (from `%2F`), `\`, `:`, NUL, or another control character, makes the request a miss. `:` covers Windows drive letters and NTFS streams (`file::$DATA`). Names Windows cannot open or silently aliases are refused on every platform, so behaviour is the same everywhere: `<>"|?*`, a trailing dot or space (Win32 strips them, so `index.html.` would open `index.html`), and `~` followed by a digit (8.3 short names such as `GIT~1` alias `.git`). The decoded segments are appended to the root as path segments and never reparsed as a path string.
- **Refuse every symbolic link below the root.** Each component is `lstat`ed: every intermediate one must be a directory, and the last one a regular file. On the JVM, `FileSystem.info` now classifies NTFS junctions and other reparse points as `Other` before checking `isDirectory`, since a junction reports both, so they are refused too. Following links with a `realpath` containment check was rejected: uni-core has no cross-platform `realpath`, and bundlers emit real files. The root itself may be a link, because the operator chose it. The check and the read are not atomic, so an attacker who can write inside the root could swap in a link between them. That attacker already controls the served content, so this is accepted.
- **Hide dotfiles except `.well-known/`** (RFC 8615), so a stray `.env` or `.git/` in a build directory is never served. `withDotfiles(true)` opts out.
- **No directory listings.** A directory serves its index file only when the URL ends with `/`. Without the slash, relative URLs in the index would resolve against the parent, so the request is a miss instead (and the SPA fallback can pick it up).
- **A miss looks like any unknown route**, including a file that can't be read (permissions, or deleted after the check). The filter never answers 403 or 400 itself. It passes the request to `next`, which usually answers 404, so a response reveals nothing about which files exist outside the root or why a path was refused.
- **Explicit content types and `nosniff`.** A built-in table maps the extensions of a typical web build, with `charset=utf-8` for text. Unknown extensions get `application/octet-stream`, and every file response sets `X-Content-Type-Options: nosniff`, so a browser never runs an uploaded `.txt` as script.
- **`GET` only.** Other methods, `HEAD` included, go to `next`, so an API on the same paths keeps working. `HEAD` can be added later. It needs the `GET` headers with no body, and this change did not verify how each backend handles that.
- **SPA fallback on the next handler's 404.** `withSpaFallback(true)` serves the index file only when `next` answers 404 to a `GET` whose `Accept` includes `text/html`. API routes therefore keep their own 404s, and so do script, image, and JSON requests, so a missing asset never comes back as HTML.

## Consequences

- Files are read whole on each request: no `Range`, ETag, caching headers, or streaming. That fits app bundles. Large media and cache control can be added later without changing the defaults.
- A missing root behaves as an empty directory, so a dev server can start before the first build.
- uni-harness uses `StaticFiles(dir).withSpaFallback(true)` and no longer has its own class.
