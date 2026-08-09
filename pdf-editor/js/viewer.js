// Document loading, page rendering (canvas + text layer + form layer),
// thumbnails, outline, zoom, search, scroll tracking.

import { S, uid, clamp } from './state.js';
import { toast, I } from './ui.js';
import { renderOverlay, renderEditLayer, select } from './tools.js';
import { rotateEntry, deleteEntry, moveEntryBefore } from './pages.js';
import { extractEntries } from './save.js';

const pdfjsLib = globalThis.pdfjsLib;
pdfjsLib.GlobalWorkerOptions.workerSrc = 'vendor/pdf.worker.min.js';

export const views = new Map(); // entryId -> view
export const els = {};

// ---------- blank-page viewport (mimics pdf.js PageViewport) ----------
class BlankViewport {
  constructor(w, h, scale, rotation) {
    this.scale = scale;
    this.rotation = ((rotation % 360) + 360) % 360;
    const s = scale;
    let m;
    switch (this.rotation) {
      case 90: m = [0, s, s, 0, 0, 0]; break;
      case 180: m = [-s, 0, 0, s, s * w, 0]; break;
      case 270: m = [0, -s, -s, 0, s * h, s * w]; break;
      default: m = [s, 0, 0, -s, 0, s * h];
    }
    this.transform = m;
    this.width = (this.rotation % 180) ? h * s : w * s;
    this.height = (this.rotation % 180) ? w * s : h * s;
  }
  convertToViewportPoint(x, y) {
    const [a, b, c, d, e, f] = this.transform;
    return [a * x + c * y + e, b * x + d * y + f];
  }
  convertToPdfPoint(vx, vy) {
    const [a, b, c, d, e, f] = this.transform;
    const det = a * d - b * c;
    return [(d * (vx - e) - c * (vy - f)) / det, (-b * (vx - e) + a * (vy - f)) / det];
  }
  convertToViewportRectangle(r) {
    const p1 = this.convertToViewportPoint(r[0], r[1]);
    const p2 = this.convertToViewportPoint(r[2], r[3]);
    return [p1[0], p1[1], p2[0], p2[1]];
  }
}

export function makeViewport(entry, scale) {
  if (entry._page) {
    return entry._page.getViewport({ scale, rotation: (entry._page.rotate + entry.rotation) % 360 });
  }
  return new BlankViewport(entry.w, entry.h, scale, entry.rotation);
}

// ---------- init ----------
export function initViewer() {
  for (const id of ['viewer', 'viewerWrap', 'thumbList', 'outlineList', 'hitCount', 'zoomPct', 'pageNow', 'pageTotal', 'docName']) {
    els[id] = document.getElementById(id);
  }
  els.scroller = els.viewerWrap;
  let raf = null;
  els.scroller.addEventListener('scroll', () => {
    if (raf) return;
    raf = requestAnimationFrame(() => { raf = null; renderVisible(); updateIndicator(); });
  });
  window.addEventListener('resize', () => renderVisible());
}

// ---------- loading ----------
async function destroyAll() {
  for (const s of S.srcDocs) { try { await s.pdf.destroy(); } catch { /* ignore */ } }
  S.srcDocs = []; S.entries = []; S.annots = [];
  S.formValues.clear(); S.undoStack = []; S.redoStack = [];
  S.selected = null; S.pending = null; S.search = { q: '', hits: [], pos: -1 };
  views.clear();
  els.viewer.replaceChildren();
  els.thumbList.replaceChildren();
  els.outlineList.replaceChildren();
}

export async function addSource(buffer, name) {
  const bytes = new Uint8Array(buffer.slice(0)); // keep pristine copy: pdf.js consumes the buffer
  const pdf = await pdfjsLib.getDocument({ data: buffer }).promise;
  const srcIdx = S.srcDocs.length;
  S.srcDocs.push({ bytes, pdf, name });
  for (let i = 0; i < pdf.numPages; i++) {
    const page = await pdf.getPage(i + 1);
    const vp1 = page.getViewport({ scale: 1, rotation: 0 });
    S.entries.push({ id: uid(), src: srcIdx, srcPage: i, rotation: 0, w: vp1.width, h: vp1.height, _page: page });
  }
}

export async function loadDocument(buffer, name) {
  await destroyAll();
  await addSource(buffer, name);
  S.fileName = name;
  document.title = `${name} — PDF Editor`;
  els.docName.textContent = name;
  document.body.classList.remove('noDoc');
  layout();
  renderThumbs();
  buildOutline();
  renderVisible();
  updateIndicator();
}

// ---------- layout & rendering ----------
function ensureView(entry) {
  let v = views.get(entry.id);
  if (v) return v;
  const wrap = document.createElement('div');
  wrap.className = 'pageWrap';
  wrap.dataset.entry = entry.id;
  const canvas = document.createElement('canvas');
  canvas.className = 'pageCanvas';
  const tl = document.createElement('div');
  tl.className = 'textLayer';
  const form = document.createElement('div');
  form.className = 'formLayer';
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'overlay');
  const edit = document.createElement('div');
  edit.className = 'editLayer';
  wrap.append(canvas, tl, form, svg, edit);
  v = { entry, wrap, canvas, tl, form, svg, edit, viewport: null, key: null, busy: false, again: false, task: null };
  views.set(entry.id, v);
  return v;
}

export function layout() {
  const live = new Set(S.entries.map(e => e.id));
  for (const [id, v] of views) {
    if (!live.has(id)) { v.wrap.remove(); views.delete(id); }
  }
  for (const e of S.entries) {
    const v = ensureView(e);
    v.viewport = makeViewport(e, S.scale);
    v.wrap.style.width = `${v.viewport.width}px`;
    v.wrap.style.height = `${v.viewport.height}px`;
    els.viewer.appendChild(v.wrap); // appending in order also reorders
  }
  els.pageTotal.textContent = S.entries.length;
}

export function invalidate(entryId) {
  const v = views.get(entryId);
  if (v) v.key = null;
}

export function renderVisible() {
  const sc = els.scroller;
  const top = sc.scrollTop - 900;
  const bot = sc.scrollTop + sc.clientHeight + 900;
  for (const e of S.entries) {
    const v = views.get(e.id);
    if (!v) continue;
    const y0 = v.wrap.offsetTop, y1 = y0 + v.wrap.offsetHeight;
    if (y1 > top && y0 < bot) ensureRendered(v);
  }
}

async function ensureRendered(v) {
  const e = v.entry;
  const key = `${S.scale}|${e.rotation}`;
  if (v.key === key) return;
  if (v.busy) { v.again = true; return; }
  v.busy = true;
  try {
    const vp = v.viewport = makeViewport(e, S.scale);
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const c = v.canvas;
    c.width = Math.floor(vp.width * dpr);
    c.height = Math.floor(vp.height * dpr);
    c.style.width = `${vp.width}px`;
    c.style.height = `${vp.height}px`;
    const ctx = c.getContext('2d', { alpha: false });
    if (!e._page) {
      ctx.fillStyle = '#fff';
      ctx.fillRect(0, 0, c.width, c.height);
    } else {
      if (v.task) { try { v.task.cancel(); } catch { /* ignore */ } }
      v.task = e._page.render({
        canvasContext: ctx, viewport: vp,
        transform: dpr !== 1 ? [dpr, 0, 0, dpr, 0, 0] : null,
      });
      await v.task.promise;
      v.task = null;
      await buildTextLayer(v, vp);
      await buildFormLayer(v, vp);
    }
    renderOverlay(v);
    renderEditLayer(v);
    v.key = key;
  } catch (err) {
    if (err?.name !== 'RenderingCancelledException') console.error('render', err);
  }
  v.busy = false;
  if (v.again) { v.again = false; ensureRendered(v); }
}

async function buildTextLayer(v, vp) {
  v.tl.replaceChildren();
  v.tl.style.setProperty('--scale-factor', vp.scale);
  const tc = await v.entry._page.getTextContent();
  if (v.entry._text == null) {
    v.entry._text = tc.items.map(i => i.str + (i.hasEOL ? '\n' : '')).join('');
  }
  const task = pdfjsLib.renderTextLayer({ textContentSource: tc, container: v.tl, viewport: vp, textDivs: [] });
  await (task.promise ?? task);
  applySearchMarks(v);
}

function applySearchMarks(v) {
  v.tl.querySelectorAll('.searchHit').forEach(el => el.classList.remove('searchHit'));
  const q = S.search.q.toLowerCase();
  if (!q) return;
  const spans = [...v.tl.querySelectorAll('span')];
  let full = '';
  const ranges = spans.map(sp => {
    const start = full.length;
    full += sp.textContent.toLowerCase();
    return [start, full.length, sp];
  });
  const hits = [];
  let idx = 0;
  while ((idx = full.indexOf(q, idx)) !== -1) { hits.push([idx, idx + q.length]); idx += Math.max(1, q.length); }
  if (!hits.length) return;
  for (const [a, b, sp] of ranges) {
    if (hits.some(([h0, h1]) => h0 < b && h1 > a)) sp.classList.add('searchHit');
  }
}

async function buildFormLayer(v, vp) {
  v.form.replaceChildren();
  const annots = (v.entry._annotsW ??= await v.entry._page.getAnnotations());
  for (const a of annots) {
    if (a.subtype !== 'Widget' || !a.fieldName) continue;
    const r = vp.convertToViewportRectangle(a.rect);
    const x = Math.min(r[0], r[2]), y = Math.min(r[1], r[3]);
    const w = Math.abs(r[2] - r[0]), h = Math.abs(r[3] - r[1]);
    if (w < 2 || h < 2) continue;
    const key = `${v.entry.src}#${a.fieldName}`;
    const cur = S.formValues.get(key);
    let el;
    if (a.fieldType === 'Tx') {
      el = document.createElement(a.multiLine ? 'textarea' : 'input');
      el.value = cur ? cur.value : (a.fieldValue || '');
      el.addEventListener('input', () => S.formValues.set(key, { type: 'text', value: el.value }));
    } else if (a.fieldType === 'Btn' && a.checkBox) {
      el = document.createElement('input');
      el.type = 'checkbox';
      el.checked = cur ? !!cur.value : !!(a.fieldValue && a.fieldValue !== 'Off');
      el.addEventListener('change', () => S.formValues.set(key, { type: 'check', value: el.checked }));
    } else if (a.fieldType === 'Btn' && a.radioButton) {
      el = document.createElement('input');
      el.type = 'radio';
      el.name = key;
      const exp = a.buttonValue ?? a.exportValue ?? 'On';
      el.checked = cur ? cur.value === exp : a.fieldValue === exp;
      el.addEventListener('change', () => { if (el.checked) S.formValues.set(key, { type: 'radio', value: exp }); });
    } else if (a.fieldType === 'Ch') {
      el = document.createElement('select');
      for (const o of (a.options || [])) {
        const op = document.createElement('option');
        op.value = o.exportValue;
        op.textContent = o.displayValue;
        el.appendChild(op);
      }
      const val = cur ? cur.value : (Array.isArray(a.fieldValue) ? a.fieldValue[0] : a.fieldValue);
      if (val != null) el.value = val;
      el.addEventListener('change', () => S.formValues.set(key, { type: 'dropdown', value: el.value }));
    } else continue;
    el.className = 'formField';
    el.style.cssText += `left:${x}px;top:${y}px;width:${w}px;height:${h}px;font-size:${Math.max(9, Math.min(h * 0.62, 18 * S.scale))}px;`;
    v.form.appendChild(el);
  }
}

export function refreshPage(entryId) {
  const v = views.get(entryId);
  if (v) { renderOverlay(v); renderEditLayer(v); }
}

// ---------- thumbnails ----------
let thumbChain = Promise.resolve();

export function renderThumbs() {
  els.thumbList.replaceChildren();
  S.entries.forEach((e, i) => {
    const li = document.createElement('div');
    li.className = 'thumb';
    li.draggable = true;
    li.dataset.entry = e.id;
    const cv = document.createElement('canvas');
    cv.className = 'thumbCv';
    const num = document.createElement('div');
    num.className = 'thumbNum';
    num.textContent = i + 1;
    const bar = document.createElement('div');
    bar.className = 'thumbBar';
    const mk = (icon, title, fn, danger) => {
      const b = document.createElement('button');
      b.className = 'miniBtn' + (danger ? ' danger' : '');
      b.title = title;
      b.innerHTML = icon;
      b.addEventListener('click', (ev) => { ev.stopPropagation(); fn(); });
      return b;
    };
    bar.append(
      mk(I.rotate, 'Rotate 90°', () => rotateEntry(e.id)),
      mk(I.extract, 'Extract page as PDF', () => extractEntries([e.id])),
      mk(I.trash, 'Delete page', () => deleteEntry(e.id), true),
    );
    li.append(cv, num, bar);
    li.addEventListener('click', () => scrollToEntry(e.id));
    li.addEventListener('dragstart', (ev) => {
      ev.dataTransfer.setData('text/plain', e.id);
      ev.dataTransfer.effectAllowed = 'move';
    });
    li.addEventListener('dragover', (ev) => { ev.preventDefault(); li.classList.add('dragOver'); });
    li.addEventListener('dragleave', () => li.classList.remove('dragOver'));
    li.addEventListener('drop', (ev) => {
      ev.preventDefault();
      li.classList.remove('dragOver');
      const dragId = ev.dataTransfer.getData('text/plain');
      if (dragId && dragId !== e.id) moveEntryBefore(dragId, e.id);
    });
    els.thumbList.appendChild(li);
    thumbChain = thumbChain.then(() => drawThumb(e, cv)).catch(() => { /* ignore */ });
  });
  // drop at end of the list moves page to the end
  els.thumbList.addEventListener('dragover', (ev) => ev.preventDefault());
  els.thumbList.addEventListener('drop', (ev) => {
    if (ev.target !== els.thumbList) return;
    const dragId = ev.dataTransfer.getData('text/plain');
    if (dragId) moveEntryBefore(dragId, null);
  });
  markCurrentThumb();
}

async function drawThumb(e, cv) {
  if (!cv.isConnected) return;
  const vp0 = makeViewport(e, 1);
  const scale = 104 / vp0.width;
  const vp = makeViewport(e, scale);
  cv.width = Math.floor(vp.width * 2);
  cv.height = Math.floor(vp.height * 2);
  cv.style.width = `${vp.width}px`;
  cv.style.height = `${vp.height}px`;
  const ctx = cv.getContext('2d', { alpha: false });
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, cv.width, cv.height);
  if (e._page) {
    await e._page.render({ canvasContext: ctx, viewport: vp, transform: [2, 0, 0, 2, 0, 0] }).promise;
  }
}

function markCurrentThumb() {
  els.thumbList.querySelectorAll('.thumb').forEach(t =>
    t.classList.toggle('current', t.dataset.entry === S.currentEntry));
}

// ---------- navigation ----------
export function scrollToEntry(id, flash = false) {
  const v = views.get(id);
  if (!v) return;
  els.scroller.scrollTo({ top: Math.max(0, v.wrap.offsetTop - 16), behavior: 'smooth' });
  if (flash) {
    v.wrap.classList.add('flash');
    setTimeout(() => v.wrap.classList.remove('flash'), 900);
  }
}

export function gotoPage(n) {
  const e = S.entries[clamp(n, 1, S.entries.length) - 1];
  if (e) scrollToEntry(e.id);
}

export function currentEntryId() {
  return S.currentEntry ?? S.entries[0]?.id ?? null;
}

export function updateIndicator() {
  const sc = els.scroller;
  const mid = sc.scrollTop + sc.clientHeight * 0.4;
  let cur = S.entries[0];
  let idx = 0;
  S.entries.forEach((e, i) => {
    const v = views.get(e.id);
    if (v && v.wrap.offsetTop <= mid) { cur = e; idx = i; }
  });
  if (!cur) return;
  S.currentEntry = cur.id;
  if (document.activeElement !== els.pageNow) els.pageNow.value = idx + 1;
  markCurrentThumb();
}

// ---------- zoom ----------
export function setScale(s) {
  s = clamp(s, 0.3, 5);
  if (Math.abs(s - S.scale) < 0.001) return;
  const sc = els.scroller;
  const ratio = (sc.scrollTop + sc.clientHeight / 2) / Math.max(1, sc.scrollHeight);
  S.scale = s;
  for (const v of views.values()) v.key = null;
  layout();
  sc.scrollTop = ratio * sc.scrollHeight - sc.clientHeight / 2;
  renderVisible();
  els.zoomPct.textContent = `${Math.round(s * 100)}%`;
}

export function fitWidth() {
  const id = currentEntryId();
  const e = S.entries.find(en => en.id === id);
  if (!e) return;
  const vp1 = makeViewport(e, 1);
  setScale((els.scroller.clientWidth - 96) / vp1.width);
}

export function fitPage() {
  const id = currentEntryId();
  const e = S.entries.find(en => en.id === id);
  if (!e) return;
  const vp1 = makeViewport(e, 1);
  setScale(Math.min(
    (els.scroller.clientWidth - 96) / vp1.width,
    (els.scroller.clientHeight - 56) / vp1.height,
  ));
}

// ---------- outline ----------
export async function buildOutline() {
  els.outlineList.replaceChildren();
  const src = S.srcDocs[0];
  if (!src) return;
  let outline = null;
  try { outline = await src.pdf.getOutline(); } catch { /* ignore */ }
  if (!outline?.length) {
    els.outlineList.innerHTML = '<div class="emptyNote">This document has no outline.</div>';
    return;
  }
  const add = (items, depth) => {
    for (const it of items) {
      const d = document.createElement('div');
      d.className = 'olItem';
      d.style.paddingLeft = `${10 + depth * 14}px`;
      d.textContent = it.title || '…';
      d.addEventListener('click', () => gotoDest(it.dest));
      els.outlineList.appendChild(d);
      if (it.items?.length) add(it.items, depth + 1);
    }
  };
  add(outline, 0);
}

async function gotoDest(dest) {
  try {
    const pdf = S.srcDocs[0].pdf;
    const d = typeof dest === 'string' ? await pdf.getDestination(dest) : dest;
    if (!d?.[0]) return;
    const idx = await pdf.getPageIndex(d[0]);
    const e = S.entries.find(en => en.src === 0 && en.srcPage === idx);
    if (e) scrollToEntry(e.id, true);
  } catch (err) { console.warn('outline dest', err); }
}

// ---------- search ----------
export async function runSearch(q) {
  S.search = { q: q.trim(), hits: [], pos: -1 };
  document.querySelectorAll('.searchHit').forEach(el => el.classList.remove('searchHit'));
  if (!S.search.q) { els.hitCount.textContent = ''; return; }
  const ql = S.search.q.toLowerCase();
  for (const e of S.entries) {
    if (!e._page) continue;
    if (e._text == null) {
      const tc = await e._page.getTextContent();
      e._text = tc.items.map(i => i.str + (i.hasEOL ? '\n' : '')).join('');
    }
    const t = e._text.toLowerCase();
    let i = 0;
    while ((i = t.indexOf(ql, i)) !== -1) { S.search.hits.push(e.id); i += Math.max(1, ql.length); }
  }
  for (const v of views.values()) if (v.key) applySearchMarks(v);
  els.hitCount.textContent = S.search.hits.length ? `0/${S.search.hits.length}` : '0';
  if (S.search.hits.length) searchNext(1);
  else toast('No matches found');
}

export function searchNext(dir) {
  const h = S.search.hits;
  if (!h.length) return;
  S.search.pos = ((S.search.pos + dir) % h.length + h.length) % h.length;
  els.hitCount.textContent = `${S.search.pos + 1}/${h.length}`;
  scrollToEntry(h[S.search.pos], true);
}

export function setViewMode(mode) {
  els.viewerWrap.dataset.view = mode;
}
