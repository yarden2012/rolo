// App bootstrap: toolbar wiring, keyboard shortcuts, file open/drop, menus.

import { S, colorKey, hasEdits, clamp } from './state.js';
import { toast, showMenu, I } from './ui.js';
import * as V from './viewer.js';
import * as T from './tools.js';
import * as P from './pages.js';
import { exportPdf, extractEntries, downloadOriginal } from './save.js';

const $ = (id) => document.getElementById(id);

const TOOL_HINTS = {
  select: 'Click to select · drag to move · double-click text boxes to edit',
  highlight: 'Drag over text to highlight it',
  underline: 'Drag over text to underline it',
  strikeout: 'Drag over text to strike it out',
  pen: 'Draw freely on the page',
  rect: 'Drag to draw a rectangle',
  ellipse: 'Drag to draw an ellipse',
  line: 'Drag to draw a line',
  arrow: 'Drag to draw an arrow',
  text: 'Click where you want to add text',
  note: 'Click to place a sticky note',
  place: 'Click on the page to place it · Esc to cancel',
};

function openFile(file) {
  if (!file) return;
  file.arrayBuffer().then(buf => V.loadDocument(buf, file.name)).catch(err => {
    console.error(err);
    toast(`Could not open “${file.name}”: ${err.message}`, 'error');
  });
}

// payload from the Electron main process: { name, bytes: Uint8Array }
function openNative(payload) {
  if (!payload) return;
  V.loadDocument(payload.bytes, payload.name).catch(err => {
    console.error(err);
    toast(`Could not open “${payload.name}”: ${err.message}`, 'error');
  });
}

function openViaDialog() {
  if (window.native) window.native.openDialog().then(openNative);
  else $('fileInput').click();
}

function syncToolUI() {
  document.querySelectorAll('#toolSeg [data-tool]').forEach(b =>
    b.classList.toggle('active', b.dataset.tool === S.tool));
  $('btnShapes').classList.toggle('active', ['rect', 'ellipse', 'line', 'arrow'].includes(S.tool));
  $('colorIn').value = S.colors[colorKey()];
  $('toolHint').textContent = TOOL_HINTS[S.tool] || '';
}

function init() {
  V.initViewer();
  T.initTools();

  // ---- tools ----
  document.querySelectorAll('#toolSeg [data-tool]').forEach(b =>
    b.addEventListener('click', () => T.setTool(b.dataset.tool)));
  document.addEventListener('toolchange', syncToolUI);

  $('btnShapes').addEventListener('click', () => showMenu($('btnShapes'), [
    { label: 'Rectangle', icon: I.square, checked: S.tool === 'rect', action: () => T.setTool('rect') },
    { label: 'Ellipse', icon: I.circle, checked: S.tool === 'ellipse', action: () => T.setTool('ellipse') },
    { label: 'Line', icon: I.line, checked: S.tool === 'line', action: () => T.setTool('line') },
    { label: 'Arrow', icon: I.arrow, checked: S.tool === 'arrow', action: () => T.setTool('arrow') },
  ]));

  $('btnImage').addEventListener('click', () => $('imageInput').click());
  $('imageInput').addEventListener('change', (ev) => {
    const f = ev.target.files[0];
    ev.target.value = '';
    if (!f) return;
    const rd = new FileReader();
    rd.onload = () => {
      const im = new Image();
      im.onload = () => T.startImagePlacement(rd.result, im.naturalWidth, im.naturalHeight, false);
      im.src = rd.result;
    };
    rd.readAsDataURL(f);
  });

  $('btnSign').addEventListener('click', T.openSigModal);

  // ---- style controls ----
  $('colorIn').addEventListener('input', (ev) => {
    S.colors[colorKey()] = ev.target.value;
    T.recolorSelected(ev.target.value);
  });
  $('strokeIn').addEventListener('input', (ev) => { S.strokeW = +ev.target.value; });
  $('fontIn').addEventListener('change', (ev) => {
    S.fontSize = clamp(+ev.target.value || 14, 8, 96);
    ev.target.value = S.fontSize;
    T.restyleSelectedFont(S.fontSize);
  });
  $('btnUndo').addEventListener('click', T.undo);
  $('btnRedo').addEventListener('click', T.redo);

  // ---- files ----
  $('btnOpen').addEventListener('click', openViaDialog);
  $('btnOpenEmpty').addEventListener('click', openViaDialog);
  $('fileInput').addEventListener('change', (ev) => { openFile(ev.target.files[0]); ev.target.value = ''; });
  $('btnSave').addEventListener('click', exportPdf);
  $('btnSaveMenu').addEventListener('click', () => showMenu($('btnSaveMenu'), [
    { label: 'Save edited copy', icon: I.extract, action: exportPdf },
    { label: 'Extract current page', action: () => { const id = V.currentEntryId(); if (id) extractEntries([id]); } },
    { sep: true },
    { label: 'Download original', action: downloadOriginal },
  ]));

  $('btnMerge').addEventListener('click', () => $('mergeInput').click());
  $('mergeInput').addEventListener('change', (ev) => {
    const f = ev.target.files[0];
    ev.target.value = '';
    if (f) P.mergePdf(f);
  });
  $('btnAddPage').addEventListener('click', () => P.insertBlankAfter(V.currentEntryId()));

  // ---- sidebar ----
  $('btnSidebar').addEventListener('click', () => document.body.classList.toggle('sideHidden'));
  $('tabPages').addEventListener('click', () => setTab('pages'));
  $('tabOutline').addEventListener('click', () => setTab('outline'));
  function setTab(t) {
    $('tabPages').classList.toggle('active', t === 'pages');
    $('tabOutline').classList.toggle('active', t === 'outline');
    $('thumbList').hidden = t !== 'pages';
    $('outlineList').hidden = t !== 'outline';
  }

  // ---- zoom & view ----
  $('btnZoomIn').addEventListener('click', () => V.setScale(S.scale * 1.2));
  $('btnZoomOut').addEventListener('click', () => V.setScale(S.scale / 1.2));
  $('zoomPct').addEventListener('click', () => V.setScale(1));
  $('btnView').addEventListener('click', () => {
    const cur = $('viewerWrap').dataset.view;
    showMenu($('btnView'), [
      { label: 'Fit width', action: V.fitWidth },
      { label: 'Fit page', action: V.fitPage },
      { sep: true },
      { label: 'Day', checked: cur === 'day', action: () => V.setViewMode('day') },
      { label: 'Night', checked: cur === 'night', action: () => V.setViewMode('night') },
      { label: 'Sepia', checked: cur === 'sepia', action: () => V.setViewMode('sepia') },
    ]);
  });

  // ---- search ----
  $('searchIn').addEventListener('keydown', (ev) => {
    if (ev.key === 'Enter') {
      const q = ev.target.value;
      if (q === S.search.q && S.search.hits.length) V.searchNext(ev.shiftKey ? -1 : 1);
      else V.runSearch(q);
    } else if (ev.key === 'Escape') {
      ev.target.value = '';
      V.runSearch('');
      ev.target.blur();
    }
  });
  $('btnNextHit').addEventListener('click', () => V.searchNext(1));
  $('btnPrevHit').addEventListener('click', () => V.searchNext(-1));

  // ---- page nav ----
  $('pageNow').addEventListener('change', (ev) => {
    V.gotoPage(+ev.target.value || 1);
    ev.target.blur();
  });

  // ---- keyboard ----
  document.addEventListener('keydown', (ev) => {
    const typing = ev.target.matches('input, textarea, select, [contenteditable="true"]');
    const mod = ev.ctrlKey || ev.metaKey;
    if (mod && ev.key === 's') { ev.preventDefault(); exportPdf(); return; }
    if (mod && ev.key === 'o') { ev.preventDefault(); openViaDialog(); return; }
    if (mod && ev.key === 'f') { ev.preventDefault(); $('searchIn').focus(); $('searchIn').select(); return; }
    if (typing) return;
    if (mod && !ev.shiftKey && ev.key === 'z') { ev.preventDefault(); T.undo(); return; }
    if (mod && (ev.key === 'y' || (ev.shiftKey && ev.key.toLowerCase() === 'z'))) { ev.preventDefault(); T.redo(); return; }
    if (mod && (ev.key === '=' || ev.key === '+')) { ev.preventDefault(); V.setScale(S.scale * 1.2); return; }
    if (mod && ev.key === '-') { ev.preventDefault(); V.setScale(S.scale / 1.2); return; }
    const keyTools = { v: 'select', h: 'highlight', u: 'underline', p: 'pen', t: 'text', n: 'note' };
    if (!mod && keyTools[ev.key.toLowerCase()] && !document.body.classList.contains('noDoc')) {
      T.setTool(keyTools[ev.key.toLowerCase()]);
    }
  });

  // ---- drag & drop ----
  let dragDepth = 0;
  window.addEventListener('dragenter', (ev) => {
    if ([...(ev.dataTransfer?.types || [])].includes('Files')) {
      dragDepth++;
      $('dropOver').hidden = false;
    }
  });
  window.addEventListener('dragleave', () => {
    if (--dragDepth <= 0) { dragDepth = 0; $('dropOver').hidden = true; }
  });
  window.addEventListener('dragover', (ev) => ev.preventDefault());
  window.addEventListener('drop', (ev) => {
    ev.preventDefault();
    dragDepth = 0;
    $('dropOver').hidden = true;
    const f = [...(ev.dataTransfer?.files || [])].find(f => /\.pdf$/i.test(f.name) || f.type === 'application/pdf');
    if (f) openFile(f);
  });

  // ---- ctrl+wheel zoom ----
  $('viewerWrap').addEventListener('wheel', (ev) => {
    if (!ev.ctrlKey) return;
    ev.preventDefault();
    V.setScale(S.scale * (ev.deltaY < 0 ? 1.1 : 1 / 1.1));
  }, { passive: false });

  // ---- guards & errors ----
  window.addEventListener('beforeunload', (ev) => {
    if (hasEdits()) { ev.preventDefault(); ev.returnValue = ''; }
  });
  const errSink = (msg) => {
    toast(`Error: ${msg}`, 'error', 6000);
    document.title = `ERR: ${msg}`; // aids headless debugging, harmless otherwise
  };
  window.addEventListener('error', (ev) => errSink(ev.message));
  window.addEventListener('unhandledrejection', (ev) => errSink(ev.reason?.stack || ev.reason?.message || ev.reason));

  syncToolUI();

  // debug/scripting handle (also used by e2e tests)
  window.__pdfed = { S, V, T, P, exportPdf, extractEntries };

  // ---- native app mode (Electron) ----
  if (window.native) {
    window.native.onOpenFile(openNative);
    window.native.ready().then(openNative);
    $('sampleLink').addEventListener('click', (ev) => {
      ev.preventDefault();
      window.native.openSample().then(openNative);
    });
  }

  // ---- demo mode ----
  if (new URLSearchParams(location.search).has('demo')) {
    fetch('sample.pdf')
      .then(r => { if (!r.ok) throw new Error('sample.pdf not found'); return r.arrayBuffer(); })
      .then(buf => V.loadDocument(buf, 'sample.pdf'))
      .catch(err => { document.title = `ERR: ${err.stack || err.message}`; toast(err.message, 'error'); });
  }
}

document.addEventListener('DOMContentLoaded', init);
