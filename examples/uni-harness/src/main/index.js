import { app, BrowserWindow, ipcMain } from 'electron'
import { join } from 'node:path'
// The Scala.js `server` module, imported via vite-plugin-scalajs.
import { wireMainProcess } from 'scalajs:main.js'

function createWindow() {
  const win = new BrowserWindow({
    width: 1200,
    height: 780,
    minWidth: 720,
    minHeight: 480,
    title: 'Uni Harness',
    backgroundColor: '#061114',
    // On macOS, hide the title bar and keep the window buttons over the icon rail
    ...(process.platform === 'darwin'
      ? { titleBarStyle: 'hiddenInset', trafficLightPosition: { x: 18, y: 16 } }
      : {}),
    webPreferences: {
      // The preload script exposes window.uniRPC via contextBridge.
      // electron-vite emits an ESM preload (.mjs) because package.json is "type": "module".
      preload: join(__dirname, '../preload/index.mjs'),
      contextIsolation: true,
      nodeIntegration: false,
      // ESM preloads require the sandbox off; the contextBridge boundary still isolates the renderer.
      sandbox: false
    }
  })

  // electron-vite serves the renderer from a dev server in `dev`, and from a file in `build`.
  if (process.env.ELECTRON_RENDERER_URL) {
    win.loadURL(process.env.ELECTRON_RENDERER_URL)
  } else {
    win.loadFile(join(__dirname, '../renderer/index.html'))
  }
}

app.whenReady().then(() => {
  // Register the ChatApi on Electron IPC before opening any window.
  wireMainProcess(ipcMain)

  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
