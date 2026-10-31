# Electron app size with Uni: keep Chromium, ship less of everything else

## Goals

- Keep Electron (bundled Chromium) as Uni's desktop shell, for the consistent engine it buys.
- Show Uni users exactly where an Electron app's bytes go, which parts they can safely drop, and which they can't.
- Make `examples/electron-app` ship the smallest correct package by default, with the numbers in its README.

## Background

- Uni already builds Electron apps: `wvlet.uni.electron` tunnels RPC over IPC (ADR `2026-06-24`), with `examples/electron-app` and the guide pages `docs/http/electron*.md`.
- The example's `electron-builder.yml` uses defaults: every Chromium locale, default compression, and no notes on architecture.

## Electron's size: where it comes from and what it buys

- **Measured on the packaged example** (Electron 45, macOS arm64, default config): **262 MiB** app, **121 MB** DMG.
  - Chromium core binary (`Electron Framework`): 197 MB (~75%). Fixed.
  - Locale `.lproj` folders: ~9 MB. Trimmable. (Electron 43 measured 47 MB; the share depends on the release.)
  - SwiftShader (software GPU fallback): 16 MB. Trimmable, but only if every user has a GPU.
  - ICU data, `.pak` resources, V8 snapshot: ~25 MB. Fixed.
- A **universal** macOS build (arm64 + x64) roughly doubles the core. Per-architecture builds avoid that.
- **Memory is closer than its reputation**: one Linux test measured ~600 MiB RSS for Electron vs ~417 MiB for Tauri, but nearly identical PSS (~265 MiB each).
- **What the size buys**: one known, up-to-date engine on every OS, with the same rendering, Chromium APIs (WebGPU, WebRTC, codecs), DevTools, and no dependency on what the OS has installed.
- **Takeaway**: about three quarters of the size is the engine itself, which is the price of that consistency. The rest is where Uni can help.

## Design

**Lane: Standard.** It changes the example's packaging config (an always-gate area) and redirects the direction you approved earlier.

- **Trim the example's package** in `electron-builder.yml`:
  - Keep only `en` locales (`electronLanguages`), which saves ~9 MB.
  - Set `compression: maximum` and keep asar on.
  - Build per-architecture targets, not universal.
- **Keep SwiftShader by default.** Dropping it breaks rendering on VMs and machines without a GPU. The docs present removing it as an opt-in step with that warning.
- **Measure, don't guess**: package before and after on macOS and record app, DMG, and `app.asar` (Scala.js) sizes in the example README.
- **Check Uni's own share**: verify that the Scala.js output is linked with `fullLinkJS` and minified, and that no `node_modules` are shipped (Scala.js bundles everything through Vite). If Uni's JS is unexpectedly large, file it as a follow-up rather than fixing it here.
- **Docs**: add an "App size" section to `docs/http/electron.md`.
  - Contents: the measured breakdown, what to trim and its risks, and the rest of what the size buys.
  - Framing: present Electron respectfully as the consistent-engine choice. Mention system-WebView shells such as Tauri as good alternatives when disk size matters more.
- **No library code changes** in this PR.

## Alternatives and Why Not?

- **System WebView shell (Tauri-style) in Scala Native**: deferred. It trades rendering consistency for size, and you chose to keep Chromium.
- **Scala Native sidecar for the main process**: it moves backend work off Node, but adds bytes rather than removing them. It's worth a separate PR if performance is the goal.
- **CEF + Scala Native instead of Electron**: saves only Node's small share, at a large integration cost.
- **Shared Electron runtime across apps**: no supported mechanism exists today.
