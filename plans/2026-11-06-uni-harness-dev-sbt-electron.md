# uni-harness: `pnpm dev` works out of the box

## Goals

- `cd examples/uni-harness && pnpm install && pnpm dev` opens the Electron window when the only sbt is the repo's `./sbt`.
- `pnpm dev:web` keeps working.

## Background

- `@scala-js/vite-plugin-scalajs` 1.1.0 always runs `sbt --batch ...` from `PATH` and has no option for the command. `vite.web.config.mjs` repeats the same call to link the server.
- The repo's `./sbt` is sbt-extras, which accepts `-batch` but not `--batch`. With no `sbt` on `PATH`, the plugin can't start at all.
- Electron 45 dropped its `postinstall` script. It downloads its binary on the first `require('electron')`, but electron-vite 6 reads `node_modules/electron/path.txt` itself and throws "Electron uninstall" first. So `allowBuilds: electron: true` no longer does anything.

## Design

Lane: **Express**. A dev-tooling fix with a clear diagnosis in one example. It touches no always-gate area (app packaging is unchanged).

- Add a small `sbt` shim under `examples/uni-harness/scripts/bin/`. It rewrites `--batch` to `-batch` and runs the repo's `./sbt`.
- A shared config helper puts that shim first on `PATH`, for the Vite process only, when `${ROOT}/sbt` exists. The plugin and `linkScalaJS` then run the repo's launcher and sbt version, which match what `./sbt` gives in CI and in AGENTS.md.
- The helper does nothing on Windows, or when the example is copied out of the repo (no `${ROOT}/sbt`). The `sbt` on `PATH` is used as before, and the README says so.
- `postinstall` also runs Electron's `install-electron` bin, which downloads the binary into `node_modules/electron/dist`. It exits right away when the binary is already there.

## Alternatives and Why Not?

- Accept `--batch` in `./sbt`: this edits vendored sbt-extras (lost when it is regenerated), and still needs `sbt` on `PATH`.
- Fork or patch vite-plugin-scalajs (pnpm patch): more upkeep than a 5-line shim, for one flag.
- Only document "install sbt": this leaves the documented `./sbt` workflow broken.
