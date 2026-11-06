// vite-plugin-scalajs runs `sbt --batch` from PATH and can't be pointed elsewhere. Inside the Uni
// repository, run the repository's ./sbt instead, so `pnpm dev` needs no global sbt: put
// scripts/bin/sbt, which maps `--batch` to sbt-extras' `-batch`, first on PATH for this process.
// Outside the repository (a copied example) or on Windows, the sbt on PATH is used as before.
import { existsSync } from 'node:fs'
import { delimiter, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export function useRepoSbt(root) {
  const launcher = resolve(root, 'sbt')
  if (process.platform === 'win32' || !existsSync(launcher)) return
  process.env.UNI_HARNESS_SBT = launcher
  const bin = join(dirname(fileURLToPath(import.meta.url)), 'bin')
  if (!process.env.PATH?.split(delimiter).includes(bin)) {
    process.env.PATH = `${bin}${delimiter}${process.env.PATH ?? ''}`
  }
}
