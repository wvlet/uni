import { contextBridge, ipcRenderer } from 'electron'

// The single, minimal bridge Uni's renderer channel expects: `window.uniRPC.request(payload)`
// forwards an RPC request payload to the main process and returns the response payload as a Promise.
// Context isolation stays ON — the renderer never gets direct access to Node or ipcRenderer.
contextBridge.exposeInMainWorld('uniRPC', {
  request: (payload) => ipcRenderer.invoke('uni-rpc', payload)
})
