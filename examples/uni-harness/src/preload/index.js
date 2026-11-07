import { contextBridge, ipcRenderer } from 'electron'

// The bridge Uni's renderer channel expects: `window.uniRPC.request(payload)` forwards an RPC request
// payload to the main process and returns the response payload as a Promise.
// Context isolation stays ON — the renderer never gets direct access to Node or ipcRenderer.
//
// `stream(payload, onMessage)` carries streaming calls (`rpc.stream`, such as ChatApi.streamMessage):
// the main process answers on 'uni-rpc-stream-event' with (id, message) until an `end` or `error`.
// One listener serves every call, keyed by a per-call id.
const streams = new Map()
let nextStreamId = 0
ipcRenderer.on('uni-rpc-stream-event', (_event, id, message) => {
  const onMessage = streams.get(id)
  if (!onMessage) return
  if (message.kind === 'end' || message.kind === 'error') streams.delete(id)
  onMessage(message)
})

contextBridge.exposeInMainWorld('uniRPC', {
  request: (payload) => ipcRenderer.invoke('uni-rpc', payload),
  stream: (payload, onMessage) => {
    const id = ++nextStreamId
    streams.set(id, onMessage)
    ipcRenderer.send('uni-rpc-stream', id, payload)
  }
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

// Opens a terminal link in the system browser. The main process checks the sender and the URI scheme.
contextBridge.exposeInMainWorld('uniShell', {
  openExternal: (uri) => ipcRenderer.send('open-external', uri)
})
