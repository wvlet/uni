import { contextBridge, ipcRenderer } from 'electron'

// The bridge Uni's renderer channel expects: `window.uniRPC.request(payload)` forwards an RPC request
// payload to the main process and returns the response payload as a Promise.
// Context isolation stays ON — the renderer never gets direct access to Node or ipcRenderer.
//
// `stream(payload, onMessage)` carries streaming calls (`rpc.stream`, such as ChatApi.streamMessage):
// the main process answers on 'uni-rpc-stream-event' with (id, message) until an `end` or `error`.
// One listener serves every call, keyed by a per-call id. The returned function cancels the call:
// the main process then stops the server stream.
const streams = new Map()
// Unique per page load: a reload keeps the webContents, and a stream from the old page may still run
const pageId = Math.random().toString(36).slice(2)
let nextStreamId = 0
ipcRenderer.on('uni-rpc-stream-event', (_event, id, message) => {
  const onMessage = streams.get(id)
  if (!onMessage) return
  if (message.kind === 'end' || message.kind === 'error') streams.delete(id)
  onMessage(message)
})

// `serve(handler)` answers calls from the main process (the agent bridge): the main process sends
// (id, payload) on 'uni-rpc-reverse', and the handler's response goes back on 'uni-rpc-reverse-reply'.
// Until the renderer serves, calls get 503 (UNAVAILABLE) at once.
let reverseHandler = null
ipcRenderer.on('uni-rpc-reverse', async (_event, id, payload) => {
  let response = { status: 503, headers: {}, body: '' }
  try {
    if (reverseHandler) response = await reverseHandler(payload)
  } catch (e) {
    // Always reply, so the main process never waits for an answer that won't come
    response = { status: 500, headers: {}, body: '' }
  }
  ipcRenderer.send('uni-rpc-reverse-reply', id, response)
})

contextBridge.exposeInMainWorld('uniRPC', {
  request: (payload) => ipcRenderer.invoke('uni-rpc', payload),
  serve: (handler) => {
    reverseHandler = handler
  },
  stream: (payload, onMessage) => {
    const id = `${pageId}-${++nextStreamId}`
    streams.set(id, onMessage)
    ipcRenderer.send('uni-rpc-stream', id, payload)
    return () => {
      if (streams.delete(id)) ipcRenderer.send('uni-rpc-stream-cancel', id)
    }
  }
})

// The OS name, so the renderer can make room for the macOS window buttons.
// `agentBridge` is true when the app started with --agent-bridge, so the renderer serves UiAgent.
contextBridge.exposeInMainWorld('uniHarness', {
  platform: process.platform,
  agentBridge: process.argv.includes('--uni-agent-bridge')
})

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
