import { app, BrowserWindow, ipcMain, nativeTheme, shell } from 'electron'
import { join } from 'node:path'
// The Scala.js `server` module, imported via vite-plugin-scalajs.
import { wireMainProcess, wireTerminal, wireLinks, startAgentBridge, attachAgentBridge } from 'scalajs:main.js'
// node-pty runs the shell for the Terminal tab. It is a native addon, so the app (not Uni) installs it.
import * as pty from 'node-pty'

// `--agent-bridge` (`pnpm dev:agent`) starts a local MCP endpoint that lets an AI agent read and
// operate the UI. Without it there is no endpoint, and the renderer does not install UiAgent.
const agentBridge = process.argv.includes('--agent-bridge')

function createWindow() {
  const win = new BrowserWindow({
    width: 1200,
    height: 780,
    minWidth: 720,
    minHeight: 480,
    title: 'Uni Harness',
    // The canvas color of the OS theme, so the window doesn't flash before the first paint
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#061114' : '#eef3f4',
    // On macOS, hide the title bar; the renderer draws a 40px strip for the window buttons
    ...(process.platform === 'darwin'
      ? { titleBarStyle: 'hiddenInset', trafficLightPosition: { x: 16, y: 13 } }
      : {}),
    webPreferences: {
      // The preload script exposes window.uniRPC via contextBridge.
      // electron-vite emits an ESM preload (.mjs) because package.json is "type": "module".
      preload: join(__dirname, '../preload/index.mjs'),
      contextIsolation: true,
      nodeIntegration: false,
      // ESM preloads require the sandbox off; the contextBridge boundary still isolates the renderer.
      sandbox: false,
      // Tells the preload to expose `uniHarness.agentBridge`, so the renderer serves UiAgent
      additionalArguments: agentBridge ? ['--uni-agent-bridge'] : []
    }
  })
  if (agentBridge) attachAgentBridge(win.webContents)

  // Never open a bare app window from the page; links go to the system browser through uniShell
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))

  // electron-vite serves the renderer from a dev server in `dev`, and from a file in `build`.
  if (process.env.ELECTRON_RENDERER_URL) {
    win.loadURL(process.env.ELECTRON_RENDERER_URL)
  } else {
    win.loadFile(join(__dirname, '../renderer/index.html'))
  }
}

app.whenReady().then(async () => {
  // Register the ChatApi on Electron IPC before opening any window.
  wireMainProcess(ipcMain)
  // Run a shell for the renderer's Terminal tab
  wireTerminal(ipcMain, pty)
  // Open the Terminal tab's links in the system browser
  wireLinks(ipcMain, shell)

  if (agentBridge) {
    const { url, token } = await startAgentBridge(ipcMain)
    console.log(
      `Agent bridge (MCP over HTTP): ${url}\n` +
        `  Authorization: Bearer ${token}\n` +
        `  claude mcp add --transport http uni-harness ${url} --header "Authorization: Bearer ${token}"`
    )
  }

  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
