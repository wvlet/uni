import { contextBridge, ipcRenderer } from 'electron'

// The single, minimal bridge Uni's renderer channel expects: `window.uniRPC.request(payload)`
// forwards an RPC request payload to the main process and returns the response payload as a Promise.
// Context isolation stays ON — the renderer never gets direct access to Node or ipcRenderer.
contextBridge.exposeInMainWorld('uniRPC', {
  request: (payload) => ipcRenderer.invoke('uni-rpc', payload)
})

// The OS name, so the renderer can make room for the macOS window buttons.
contextBridge.exposeInMainWorld('uniHarness', { platform: process.platform })

// The Terminal tab's shell, running in the main process. Output arrives as `pty-data` messages.
contextBridge.exposeInMainWorld('uniPty', {
  start: (cols, rows) => ipcRenderer.send('pty-start', cols, rows),
  write: (data) => ipcRenderer.send('pty-input', data),
  resize: (cols, rows) => ipcRenderer.send('pty-resize', cols, rows),
  onData: (callback) => ipcRenderer.on('pty-data', (_event, data) => callback(data)),
  onExit: (callback) => ipcRenderer.on('pty-exit', (_event, code) => callback(code))
})
