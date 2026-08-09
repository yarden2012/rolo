// Electron main process: window, native file dialogs, argv/second-instance
// file opening, unsaved-changes close guard.
const { app, BrowserWindow, Menu, dialog, ipcMain } = require('electron');
const fs = require('fs');
const path = require('path');

app.setName('PDF Editor');

let win = null;
let rendererReady = false;
let pendingFile = null;

const ROOT = path.join(__dirname, '..');

function argvPdf(argv) {
  return argv.slice(1).find(a => /\.pdf$/i.test(a) && !a.startsWith('-') && fs.existsSync(a)) || null;
}

function payloadFor(file) {
  return { name: path.basename(file), bytes: fs.readFileSync(file) };
}

function sendOpen(file) {
  try {
    if (rendererReady && win) win.webContents.send('open-file', payloadFor(file));
    else pendingFile = file;
  } catch (err) {
    dialog.showErrorBox('Could not open file', `${file}\n${err.message}`);
  }
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', (ev, argv) => {
    const f = argvPdf(argv);
    if (f) sendOpen(f);
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });

  app.whenReady().then(() => {
    Menu.setApplicationMenu(null);
    win = new BrowserWindow({
      width: 1440,
      height: 920,
      minWidth: 880,
      minHeight: 560,
      backgroundColor: '#141619',
      autoHideMenuBar: true,
      title: 'PDF Editor',
      webPreferences: {
        preload: path.join(__dirname, 'preload.cjs'),
        contextIsolation: true,
      },
    });

    // beforeunload (unsaved edits) would otherwise silently block closing
    win.webContents.on('will-prevent-unload', (event) => {
      const choice = dialog.showMessageBoxSync(win, {
        type: 'question',
        buttons: ['Discard changes', 'Cancel'],
        defaultId: 1,
        cancelId: 1,
        message: 'You have unsaved changes.',
        detail: 'Discard them and close?',
      });
      if (choice === 0) event.preventDefault();
    });

    if (process.env.PDFED_DEBUG || process.env.PDFED_TEST) {
      win.webContents.on('console-message', (ev, level, msg) => {
        console.log(`[renderer:${level}] ${String(msg).slice(0, 300)}`);
      });
    }

    win.on('closed', () => { win = null; });
    win.loadFile(path.join(ROOT, 'index.html'));

    if (process.env.PDFED_TEST) {
      win.webContents.once('did-finish-load', () => {
        sendOpen(path.join(ROOT, 'sample.pdf'));
        setTimeout(async () => {
          try {
            const n = await win.webContents.executeJavaScript(
              'window.__pdfed ? window.__pdfed.S.entries.length : -1');
            const spans = await win.webContents.executeJavaScript(
              "document.querySelectorAll('.textLayer span').length");
            console.log(`PDFED_TEST pages=${n} textSpans=${spans}`);
          } catch (err) { console.log('PDFED_TEST error', err.message); }
          app.quit();
        }, 7000);
      });
    }
  });
}

app.on('window-all-closed', () => app.quit());

const initialFile = argvPdf(process.argv);
if (initialFile) pendingFile = initialFile;

// ---------- IPC ----------
ipcMain.handle('ready', () => {
  rendererReady = true;
  if (pendingFile) {
    const f = pendingFile;
    pendingFile = null;
    try { return payloadFor(f); } catch { return null; }
  }
  return null;
});

ipcMain.handle('open-dialog', async () => {
  const r = await dialog.showOpenDialog(win, {
    title: 'Open PDF',
    filters: [{ name: 'PDF documents', extensions: ['pdf'] }],
    properties: ['openFile'],
  });
  if (r.canceled || !r.filePaths[0]) return null;
  try { return payloadFor(r.filePaths[0]); }
  catch (err) { dialog.showErrorBox('Could not open file', err.message); return null; }
});

ipcMain.handle('save-pdf', async (ev, { bytes, suggestedName }) => {
  const r = await dialog.showSaveDialog(win, {
    title: 'Save PDF',
    defaultPath: path.join(app.getPath('documents'), suggestedName || 'document.pdf'),
    filters: [{ name: 'PDF documents', extensions: ['pdf'] }],
  });
  if (r.canceled || !r.filePath) return null;
  try {
    fs.writeFileSync(r.filePath, Buffer.from(bytes));
    return r.filePath;
  } catch (err) {
    dialog.showErrorBox('Save failed', err.message);
    return null;
  }
});

ipcMain.handle('open-sample', () => {
  try { return payloadFor(path.join(ROOT, 'sample.pdf')); } catch { return null; }
});
