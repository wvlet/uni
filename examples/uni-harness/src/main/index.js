import { app, BrowserWindow, ipcMain, Menu, nativeTheme, shell } from 'electron'
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
  // Detached dock panels of this window, so focus in a panel counts as focus in the window
  const docks = new Set()
  // The bridge lists this window and can target it by `win.id` until it closes
  if (agentBridge)
    attachAgentBridge(win, () => win.isFocused() || [...docks].some((d) => d.isFocused()))

  // Links go to the system browser through uniShell, so the page opens no other window, except a
  // blank same-origin one that the Dock moves a detached panel into (`Dock.withDetach`). Its frame
  // name starts with `uni-dock-`. It keeps this window's webPreferences (context isolation on, no
  // Node), and the renderer only moves its own DOM into it.
  win.webContents.setWindowOpenHandler(({ url, frameName }) =>
    url === 'about:blank' && frameName.startsWith('uni-dock-')
      ? {
          action: 'allow',
          overrideBrowserWindowOptions: {
            minWidth: 320,
            minHeight: 240,
            autoHideMenuBar: true,
            backgroundColor: nativeTheme.shouldUseDarkColors ? '#061114' : '#eef3f4'
          }
        }
      : { action: 'deny' }
  )
  // A dock window shows only the renderer's own DOM: it opens no windows and goes nowhere, so a
  // link inside a detached panel can't load a remote page with this window's preload
  win.webContents.on('did-create-window', (child) => {
    docks.add(child)
    child.on('closed', () => docks.delete(child))
    child.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
    child.webContents.on('will-navigate', (event) => event.preventDefault())
  })

  // electron-vite serves the renderer from a dev server in `dev`, and from a file in `build`.
  if (process.env.ELECTRON_RENDERER_URL) {
    win.loadURL(process.env.ELECTRON_RENDERER_URL)
  } else {
    win.loadFile(join(__dirname, '../renderer/index.html'))
  }
}

// Electron's default menu, plus File > New Window to open another harness window
function setMenu() {
  const isMac = process.platform === 'darwin'
  Menu.setApplicationMenu(
    Menu.buildFromTemplate([
      ...(isMac ? [{ role: 'appMenu' }] : []),
      {
        label: 'File',
        submenu: [
          { label: 'New Window', accelerator: 'CmdOrCtrl+N', click: () => createWindow() },
          { type: 'separator' },
          isMac ? { role: 'close' } : { role: 'quit' }
        ]
      },
      { role: 'editMenu' },
      { role: 'viewMenu' },
      { role: 'windowMenu' }
    ])
  )
}

app.whenReady().then(async () => {
  setMenu()
  // Register the ChatApi on Electron IPC before opening any window. Conversations are saved in
  // userData/conversations.json: ~/Library/Application Support/Uni Harness on macOS when packaged
  // (`uni-harness` under `pnpm dev`, which takes the name from package.json)
  wireMainProcess(ipcMain, app.getPath('userData'))
  // Run a shell for the renderer's Terminal tab
  wireTerminal(ipcMain, pty)
  // Open the Terminal tab's links in the system browser
  wireLinks(ipcMain, shell)

  if (agentBridge) {
    // A bridge that fails to start must not keep the window from opening
    try {
      const { url, token } = await startAgentBridge(ipcMain)
      console.log(
        `Agent bridge (MCP over HTTP): ${url}\n` +
          `  Authorization: Bearer ${token}\n` +
          `  claude mcp add --transport http uni-harness ${url} --header "Authorization: Bearer ${token}"`
      )
    } catch (e) {
      console.error('The agent bridge did not start:', e)
    }
  }

  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
