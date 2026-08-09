const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('native', {
  ready: () => ipcRenderer.invoke('ready'),
  openDialog: () => ipcRenderer.invoke('open-dialog'),
  savePdf: (bytes, suggestedName) => ipcRenderer.invoke('save-pdf', { bytes, suggestedName }),
  openSample: () => ipcRenderer.invoke('open-sample'),
  onOpenFile: (cb) => ipcRenderer.on('open-file', (ev, payload) => cb(payload)),
});
