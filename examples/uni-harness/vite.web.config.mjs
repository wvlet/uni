import { defineConfig } from 'vite'
import scalaJSPlugin from '@scala-js/vite-plugin-scalajs'
import tailwindcss from '@tailwindcss/vite'
import { spawn } from 'node:child_process'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

// The browser app. The renderer is the same Scala.js module the Electron window loads; the
// ChatApi it calls is served over HTTP by the same Scala.js `server` module Electron's main
// process runs, started with Uni's `Http.server` on Node.
//
//   vite --config vite.web.config.mjs                 dev server; RPC is proxied to the Uni server
//   vite build --config vite.web.config.mjs           the static UI   -> out/web/public
//   vite build --config vite.web.config.mjs --mode server   the Node server -> out/web/server

// Ports apart from Vite's default 5173, which `pnpm dev` (Electron) uses for its renderer. That
// server has no HTTP ChatApi (Electron calls it over IPC), so a browser must not land on it.
const WEB_PORT = Number(process.env.UNI_HARNESS_WEB_PORT ?? 5180)
const RPC_PORT = Number(process.env.UNI_HARNESS_RPC_PORT ?? 5181)
const RPC_PREFIX = '/example.api.'

// Links an sbt project with Scala.js and returns its output directory. The renderer is linked
// by vite-plugin-scalajs; the server is linked here, after it, because two sbt processes in one
// build would conflict.
function linkScalaJS(projectID) {
  return new Promise((ok, fail) => {
    const args = ['--batch', '-no-colors', '-Dsbt.supershell=false', `print ${projectID}/fastLinkJSOutput`]
    const sbt = spawn(process.platform === 'win32' ? 'sbt.bat' : 'sbt', args, {
      shell: process.platform === 'win32',
      stdio: ['ignore', 'pipe', 'inherit']
    })
    let out = ''
    sbt.stdout.on('data', (d) => (out += d))
    sbt.on('error', fail)
    sbt.on('close', (code) =>
      code === 0 ? ok(out.trim().split('\n').at(-1).trim()) : fail(new Error(`sbt exited with ${code}`))
    )
  })
}

// Starts the Scala.js ChatApi server next to the Vite dev server. Restart `pnpm dev:web` after
// changing server code; renderer code reloads on save.
function uniRpcServer() {
  let httpServer
  return {
    name: 'uni-harness:rpc-server',
    apply: 'serve',
    configureServer(server) {
      // Vite runs the renderer's sbt link (buildStart) before it starts listening
      server.httpServer?.once('listening', async () => {
        const dir = await linkScalaJS('server')
        const mod = await import(pathToFileURL(join(dir, 'main.js')).href)
        httpServer = mod.startWebServer(RPC_PORT, undefined)
        server.config.logger.info(`  Uni ChatApi server: http://127.0.0.1:${RPC_PORT}${RPC_PREFIX}ChatApi/*`)
      })
      server.httpServer?.once('close', () => httpServer?.stop())
    }
  }
}

export default defineConfig(({ mode }) => {
  if (mode === 'server') {
    return {
      plugins: [scalaJSPlugin({ cwd: '.', projectID: 'server' })],
      build: {
        ssr: resolve('src/web/server.js'),
        outDir: 'out/web/server',
        emptyOutDir: true,
        minify: true
      }
    }
  }
  return {
    root: 'src/renderer',
    plugins: [
      scalaJSPlugin({ cwd: '.', projectID: 'renderer' }),
      tailwindcss(),
      uniRpcServer()
    ],
    server: {
      port: WEB_PORT,
      // Fail on a busy port instead of moving to another one the instructions don't mention
      strictPort: true,
      proxy: { [RPC_PREFIX]: `http://127.0.0.1:${RPC_PORT}` }
    },
    build: {
      outDir: resolve('out/web/public'),
      emptyOutDir: true,
      minify: true
    }
  }
})
