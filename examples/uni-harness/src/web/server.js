// Node entry for the browser app: serves the built UI and ChatApi over HTTP with Uni's Http.server.
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
// The Scala.js `server` module (the same one Electron's main process imports).
import { startWebServer } from 'scalajs:main.js'

const port = Number(process.env.PORT ?? 8080)
const publicDir = join(dirname(fileURLToPath(import.meta.url)), '../public')

startWebServer(port, publicDir)
console.log(`Uni Harness: http://127.0.0.1:${port}`)
