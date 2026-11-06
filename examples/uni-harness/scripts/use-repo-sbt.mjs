// vite-plugin-scalajs runs `sbt --batch` from PATH and can't be pointed elsewhere. Inside the Uni
// repository, run the repository's ./sbt instead, so `pnpm dev` needs no global sbt: put
// scripts/bin/sbt, which maps `--batch` to sbt-extras' `-batch`, first on PATH. Only the sbt
// processes see it; the dev servers, Electron, and the shells in its terminal keep your PATH.
// Outside the repository (a copied example) or on Windows, the sbt on PATH is used as before.
import scalaJSPlugin from '@scala-js/vite-plugin-scalajs'
import { existsSync } from 'node:fs'
import { delimiter, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const BIN = join(dirname(fileURLToPath(import.meta.url)), 'bin')

// The environment for an sbt process run in `root`: process.env, plus the shim when `root` has ./sbt
export function sbtEnv(root) {
  const launcher = resolve(root, 'sbt')
  if (process.platform === 'win32' || !existsSync(launcher)) return process.env
  return { ...process.env, UNI_HARNESS_SBT: launcher, PATH: `${BIN}${delimiter}${process.env.PATH ?? ''}` }
}

// scalaJSPlugin({ cwd: root, ...options }), with its sbt run under sbtEnv(root)
export function scalaJS(root, options) {
  const plugin = scalaJSPlugin({ cwd: root, ...options })
  const buildStart = plugin.buildStart
  plugin.buildStart = function (...args) {
    // The plugin spawns sbt synchronously, before its first await, and spawn copies process.env
    // then, so the change can be undone as soon as the call returns.
    const saved = process.env
    process.env = sbtEnv(root)
    try {
      return buildStart.apply(this, args)
    } finally {
      process.env = saved
    }
  }
  return plugin
}
