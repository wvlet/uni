// node-pty's macOS and Linux prebuilds include `spawn-helper`, which pnpm installs without the
// execute bit; spawning then fails with "posix_spawnp failed". Restore the bit after install.
import { chmodSync, existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const prebuilds = join('node_modules', 'node-pty', 'prebuilds')
if (existsSync(prebuilds)) {
  for (const dir of readdirSync(prebuilds)) {
    const helper = join(prebuilds, dir, 'spawn-helper')
    if (existsSync(helper)) chmodSync(helper, 0o755)
  }
}
