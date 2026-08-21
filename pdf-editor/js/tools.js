// Annotation tools: SVG overlay + DOM edit layer, pointer interactions,
// text markup from selection, text boxes, notes, images, signatures, undo/redo.

import { S, uid, pushUndo, curColor, colorKey, clamp, fontCss, RTL_RE, flipBidi, rtlLooksVisual } from './state.js';
import { toast } from './ui.js';
import { views, refreshPage, els } from './viewer.js';

let drag = null; // active pointer interaction
// A text box whose editing was interrupted by the user reaching for the style
// controls. It is kept alive (even while empty) so size/colour can be chosen
// before typing, and editing resumes once the control is committed.
let parkedEdit = null;

function dropParked() {
  const id = parkedEdit;
  parkedEdit = null;
  if (!id) return;
  const a = findAnn(id);
  if (a && a.type === 'text' && !String(a.text || '').trim()) {
    if (a._fresh) rawRemove(a.id);
    else removeAnn(a.id);
  }
}

export function resumeTextEdit() {
  const id = parkedEdit;
  parkedEdit = null;
  if (!id) return;
  const a = findAnn(id);
  if (!a || a.type !== 'text') return;
  const v = views.get(a.pageId);
  const div = v && v.edit.querySelector(`.annText[data-ann="${a.id}"]`);
  if (div) startTextEdit(a, div);
}

// ---------- tool switching ----------
export function setTool(tool) {
  if (S.tool === tool) return;
  const prev = S.tool;
  if (S.tool === 'place' && tool !== 'place') S.pending = null;
  S.tool = tool;
  document.body.dataset.tool = tool;
  syncTextCtx();
  dropParked();
  if (tool === 'moveimg') primeImages();
  else if (prev === 'moveimg') for (const v of views.values()) refreshPage(v.entry.id);
  document.dispatchEvent(new CustomEvent('toolchange', { detail: tool }));
}

// ---------- helpers ----------
const findAnn = (id) => S.annots.find(a => a.id === id);

function viewFromEvent(ev) {
  const wrap = ev.target.closest?.('.pageWrap');
  return wrap ? views.get(wrap.dataset.entry) : null;
}

function pagePoint(v, ev) {
  const r = v.wrap.getBoundingClientRect();
  const px = ev.clientX - r.left, py = ev.clientY - r.top;
  return { px, py, pdf: v.viewport.convertToPdfPoint(px, py) };
}

// display-space bbox of a pdf-space rect (handles page rotation)
function dispRect(vp, x1, y1, x2, y2) {
  const pts = [vp.convertToViewportPoint(x1, y1), vp.convertToViewportPoint(x2, y1),
               vp.convertToViewportPoint(x1, y2), vp.convertToViewportPoint(x2, y2)];
  const xs = pts.map(p => p[0]), ys = pts.map(p => p[1]);
  return { x: Math.min(...xs), y: Math.min(...ys), w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys) };
}

function snapshotGeom(a) {
  return JSON.parse(JSON.stringify({ rects: a.rects, points: a.points, box: a.box }));
}
function restoreGeom(a, g) {
  if (g.rects) a.rects = JSON.parse(JSON.stringify(g.rects));
  if (g.points) a.points = JSON.parse(JSON.stringify(g.points));
  if (g.box) a.box = { ...g.box };
}

export function select(id) {
  if (S.selected === id) return;
  const prev = S.selected ? findAnn(S.selected) : null;
  S.selected = id;
  if (prev) refreshPage(prev.pageId);
  const cur = id ? findAnn(id) : null;
  if (cur) refreshPage(cur.pageId);
  syncTextCtx();
  document.dispatchEvent(new CustomEvent('annselect', { detail: id }));
}

export function addAnn(a, withUndo = true) {
  S.annots.push(a);
  if (withUndo) {
    pushUndo({
      undo: () => rawRemove(a.id),
      redo: () => { S.annots.push(a); refreshPage(a.pageId); },
    });
  }
  refreshPage(a.pageId);
  return a;
}

function rawRemove(id) {
  const i = S.annots.findIndex(a => a.id === id);
  if (i < 0) return null;
  const [a] = S.annots.splice(i, 1);
  if (S.selected === id) S.selected = null;
  closeNotePopup();
  refreshPage(a.pageId);
  return a;
}

export function removeAnn(id) {
  const a = findAnn(id);
  if (!a) return;
  rawRemove(id);
  pushUndo({
    undo: () => { S.annots.push(a); refreshPage(a.pageId); },
    redo: () => rawRemove(id),
  });
}

export function deleteSelected() {
  if (S.selected) removeAnn(S.selected);
}

export function undo() {
  const op = S.undoStack.pop();
  if (!op) return;
  op.undo();
  S.redoStack.push(op);
}

export function redo() {
  const op = S.redoStack.pop();
  if (!op) return;
  op.redo();
  S.undoStack.push(op);
}

export function recolorSelected(color) {
  const a = S.selected && findAnn(S.selected);
  if (!a) return;
  const old = a.color;
  a.color = color;
  pushUndo({
    undo: () => { a.color = old; refreshPage(a.pageId); },
    redo: () => { a.color = color; refreshPage(a.pageId); },
  });
  refreshPage(a.pageId);
}

// Restyle the selected text box (font size and/or family), undoably.
export function restyleSelected(props) {
  const a = S.selected && findAnn(S.selected);
  if (a?.type !== 'text') return;
  const before = {}, after = {};
  let changed = false;
  for (const [k, val] of Object.entries(props)) {
    if (a[k] === val) continue;
    before[k] = a[k];
    after[k] = val;
    a[k] = val;
    changed = true;
  }
  if (!changed) return;
  pushUndo({
    undo: () => { Object.assign(a, before); refreshPage(a.pageId); },
    redo: () => { Object.assign(a, after); refreshPage(a.pageId); },
  });
  refreshPage(a.pageId);
}

// ---------- SVG overlay rendering ----------
const NS = 'http://www.w3.org/2000/svg';
const mkEl = (tag, attrs) => {
  const el = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  return el;
};

export function renderOverlay(v) {
  const vp = v.viewport;
  const svg = v.svg;
  svg.setAttribute('width', vp.width);
  svg.setAttribute('height', vp.height);
  svg.setAttribute('viewBox', `0 0 ${vp.width} ${vp.height}`);
  svg.replaceChildren();
  for (const a of S.annots) {
    if (a.pageId !== v.entry.id) continue;
    drawAnnSvg(svg, vp, a);
  }
  if (S.tool === 'moveimg') drawImageHints(v);
  drawSelectionBox(v);
}

function drawAnnSvg(svg, vp, a) {
  const sw = (a.strokeW || 2) * vp.scale;
  // mask over the document content this annotation replaces or lifts
  if (a.cover) {
    const c = a.cover;
    const d = dispRect(vp, c.x, c.y, c.x + c.w, c.y + c.h);
    svg.appendChild(mkEl('rect', {
      x: d.x, y: d.y, width: d.w, height: d.h,
      fill: c.color || '#ffffff', 'data-ann': a.id, class: 'annCover',
    }));
  }
  // text, notes and images are drawn by the DOM edit layer
  if (a.type === 'text' || a.type === 'note' || a.type === 'image') return;
  if (a.type === 'highlight' || a.type === 'underline' || a.type === 'strikeout') {
    const g = mkEl('g', { 'data-ann': a.id });
    for (const r of a.rects) {
      if (a.type === 'highlight') {
        const d = dispRect(vp, r[0], r[1], r[2], r[3]);
        g.appendChild(mkEl('rect', { x: d.x, y: d.y, width: d.w, height: d.h, fill: a.color, 'fill-opacity': 0.38, class: 'hl', rx: 1 }));
      } else {
        const yy = a.type === 'underline' ? r[1] + (r[3] - r[1]) * 0.06 : (r[1] + r[3]) / 2;
        const p1 = vp.convertToViewportPoint(r[0], yy);
        const p2 = vp.convertToViewportPoint(r[2], yy);
        g.appendChild(mkEl('line', {
          x1: p1[0], y1: p1[1], x2: p2[0], y2: p2[1],
          stroke: a.color, 'stroke-width': Math.max(1.4, (r[3] - r[1]) * 0.075 * vp.scale), 'stroke-linecap': 'round',
        }));
        // invisible fat hit area for selection
        g.appendChild(mkEl('line', { x1: p1[0], y1: p1[1], x2: p2[0], y2: p2[1], stroke: 'rgba(0,0,0,0)', 'stroke-width': 10 }));
      }
    }
    svg.appendChild(g);
  } else if (a.type === 'ink') {
    const pts = a.points.map(p => vp.convertToViewportPoint(p[0], p[1]).join(',')).join(' ');
    svg.appendChild(mkEl('polyline', {
      points: pts, fill: 'none', stroke: a.color, 'stroke-width': sw,
      'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'data-ann': a.id,
    }));
  } else if (a.type === 'rect') {
    const b = a.box;
    const d = dispRect(vp, b.x, b.y, b.x + b.w, b.y + b.h);
    svg.appendChild(mkEl('rect', {
      x: d.x, y: d.y, width: d.w, height: d.h, rx: 1.5,
      fill: 'none', stroke: a.color, 'stroke-width': sw, 'data-ann': a.id,
    }));
  } else if (a.type === 'ellipse') {
    const b = a.box;
    const d = dispRect(vp, b.x, b.y, b.x + b.w, b.y + b.h);
    svg.appendChild(mkEl('ellipse', {
      cx: d.x + d.w / 2, cy: d.y + d.h / 2, rx: d.w / 2, ry: d.h / 2,
      fill: 'none', stroke: a.color, 'stroke-width': sw, 'data-ann': a.id,
    }));
  } else if (a.type === 'line' || a.type === 'arrow') {
    const p1 = vp.convertToViewportPoint(a.points[0][0], a.points[0][1]);
    const p2 = vp.convertToViewportPoint(a.points[1][0], a.points[1][1]);
    const g = mkEl('g', { 'data-ann': a.id, stroke: a.color, 'stroke-width': sw, 'stroke-linecap': 'round', fill: 'none' });
    g.appendChild(mkEl('line', { x1: p1[0], y1: p1[1], x2: p2[0], y2: p2[1] }));
    if (a.type === 'arrow') {
      const ang = Math.atan2(p2[1] - p1[1], p2[0] - p1[0]);
      const hl = 8 + sw * 2.2;
      for (const da of [Math.PI * 5 / 6, -Math.PI * 5 / 6]) {
        g.appendChild(mkEl('line', {
          x1: p2[0], y1: p2[1],
          x2: p2[0] + hl * Math.cos(ang + da), y2: p2[1] + hl * Math.sin(ang + da),
        }));
      }
    }
    g.appendChild(mkEl('line', { x1: p1[0], y1: p1[1], x2: p2[0], y2: p2[1], stroke: 'rgba(0,0,0,0)', 'stroke-width': 10 }));
    svg.appendChild(g);
  }
}

function drawSelectionBox(v) {
  const a = S.selected && findAnn(S.selected);
  if (!a || a.pageId !== v.entry.id) return;
  if (['text', 'note', 'image'].includes(a.type)) return; // DOM layer handles those
  const el = v.svg.querySelector(`[data-ann="${a.id}"]`);
  if (!el) return;
  try {
    const b = el.getBBox();
    v.svg.appendChild(mkEl('rect', {
      x: b.x - 4, y: b.y - 4, width: b.width + 8, height: b.height + 8, rx: 3, class: 'selBox',
    }));
    if (a.type === 'rect' || a.type === 'ellipse') {
      v.svg.appendChild(mkEl('circle', {
        cx: b.x + b.width + 4, cy: b.y + b.height + 4, r: 5.5,
        class: 'rzHandle', 'data-rz': a.id,
      }));
    }
  } catch { /* getBBox on empty element */ }
}

// ---------- DOM edit layer (text, notes, images) ----------
// Elements are reused across renders and keyed by annotation id. Rebuilding the
// layer from scratch used to swap the DOM node under the user mid-interaction,
// which broke double-click-to-edit and threw away in-progress typing.
// Removing a focused box fires blur synchronously, which commits the edit and
// calls straight back in here — mutating the layer while the previous pass is
// still walking it. Serialise like viewer.js does for page rendering: a nested
// call is remembered and replayed once the outer pass finishes.
const editBusy = new WeakSet();
const editAgain = new WeakSet();

export function renderEditLayer(v) {
  if (editBusy.has(v)) { editAgain.add(v); return; }
  editBusy.add(v);
  try {
    renderEditLayerNow(v);
  } finally {
    editBusy.delete(v);
  }
  if (editAgain.has(v)) {
    editAgain.delete(v);
    renderEditLayer(v);
  }
}

function renderEditLayerNow(v) {
  const vp = v.viewport;
  const live = new Set();
  for (const a of S.annots) {
    if (a.pageId !== v.entry.id) continue;
    live.add(a.id);
    if (a.type === 'text') syncTextDiv(v, vp, a);
    else if (a.type === 'note') syncNoteIcon(v, vp, a);
    else if (a.type === 'image') syncImageEl(v, vp, a);
  }
  // Drop anything whose annotation is gone (image handle holders carry no id).
  // Removing a focused box fires blur synchronously, which commits the edit and
  // can re-enter this function, so re-check each node is still attached here.
  for (const el of [...v.edit.children]) {
    if (el.parentNode !== v.edit) continue;
    if (!el.dataset.ann || !live.has(el.dataset.ann)) el.remove();
  }
  const sel = S.selected && findAnn(S.selected);
  if (sel && sel.pageId === v.entry.id && sel.type === 'image') addImageHandle(v, vp, sel);
}

function place(el, d) {
  el.style.left = `${d.x}px`;
  el.style.top = `${d.y}px`;
  el.style.width = `${d.w}px`;
  el.style.height = `${d.h}px`;
}

function addHandle(host, annId) {
  const h = document.createElement('div');
  h.className = 'rzHandleDom';
  h.dataset.rz = annId;
  host.appendChild(h);
}

function syncTextDiv(v, vp, a) {
  const b = a.box;
  const d = dispRect(vp, b.x, b.y, b.x + b.w, b.y + b.h);
  let div = v.edit.querySelector(`.annText[data-ann="${a.id}"]`);
  if (!div) {
    div = document.createElement('div');
    div.className = 'annText';
    div.dataset.ann = a.id;
    div.addEventListener('dblclick', (ev) => {
      ev.stopPropagation();
      startTextEdit(a, div, { x: ev.clientX, y: ev.clientY });
    });
    v.edit.appendChild(div);
  }
  const editing = div.classList.contains('editing');
  div.classList.toggle('selected', S.selected === a.id);
  div.style.cssText = `left:${d.x}px;top:${d.y}px;width:${d.w}px;min-height:${d.h}px;` +
    `font-size:${a.fontSize * vp.scale}px;color:${a.color};font-family:${fontCss(a.fontFamily)};` +
    (a.rtl ? 'direction:rtl;text-align:right;' : '');
  // never clobber what the user is currently typing
  if (!editing && div.textContent !== (a.text || '')) setDivText(div, a.text || '');
  const handle = div.querySelector('.rzHandleDom');
  if (S.selected === a.id && !editing && !handle) addHandle(div, a.id);
  else if (handle && (S.selected !== a.id || editing)) handle.remove();
}

// the resize handle lives inside the box so it tracks the rendered height
function setDivText(div, text) {
  const handle = div.querySelector('.rzHandleDom');
  div.textContent = text;
  if (handle) div.appendChild(handle);
}

export function startTextEdit(a, div, caretPt) {
  if (!div || div.classList.contains('editing')) return;
  div.querySelector('.rzHandleDom')?.remove();
  div.contentEditable = 'true';
  div.classList.add('editing');
  div.focus();
  setTimeout(() => div.focus(), 0); // win any focus tug-of-war from the placing click
  placeCaret(div, caretPt);
  syncTextCtx();

  const before = a.text;
  const beforeRtl = !!a.rtl;
  // mirror keystrokes into the annotation so a re-render can never lose them
  const onInput = () => {
    a.text = div.textContent;
    // a box typed straight into needs its direction too, or Hebrew exports reversed
    a.rtl = RTL_RE.test(a.text);
  };
  const onKeyDown = (ev) => {
    if (ev.key === 'Escape') { ev.preventDefault(); div.blur(); }
    ev.stopPropagation();
  };
  const done = (ev) => {
    // Reaching for the style controls is not "done typing": park the box so it
    // survives (even empty) and can be resumed once the control is committed.
    const parking = !!(ev && ev.relatedTarget && ev.relatedTarget.closest &&
      ev.relatedTarget.closest('#ctxControls'));
    div.removeEventListener('blur', done);
    div.removeEventListener('input', onInput);
    div.removeEventListener('keydown', onKeyDown);
    div.contentEditable = 'false';
    div.classList.remove('editing');
    if (parking) parkedEdit = a.id;
    const t = div.textContent.trim();
    if (!t) {
      // a box emptied after it was already committed is a deletion, and has to be
      // undoable; a never-committed one just goes away
      if (!parking) {
        if (a._fresh) {
          rawRemove(a.id);
        } else {
          // put back what was there before recording the deletion, or undo would
          // restore an empty box (onInput has already blanked a.text by now)
          a.text = before;
          a.rtl = beforeRtl;
          removeAnn(a.id);
        }
      }
      syncTextCtx();
      return;
    }
    if (!parking && a._fresh && a.cover && div.textContent === a._orig) {
      // Replacing document text with exactly the same string changes nothing.
      // Skipped while parking: an E-tool box still holds the original line until
      // the user types, and discarding it here would destroy the pending edit.
      rawRemove(a.id);
      syncTextCtx();
      return;
    }
    a.text = div.textContent;
    a.rtl = RTL_RE.test(a.text);
    if (before !== a.text) {
      if (a._fresh) {
        delete a._fresh;
        pushUndo({ undo: () => rawRemove(a.id), redo: () => { S.annots.push(a); refreshPage(a.pageId); } });
      } else {
        const after = a.text, afterRtl = !!a.rtl;
        pushUndo({
          undo: () => { a.text = before; a.rtl = beforeRtl; refreshPage(a.pageId); },
          redo: () => { a.text = after; a.rtl = afterRtl; refreshPage(a.pageId); },
        });
      }
    } else if (a._fresh) {
      delete a._fresh;
      pushUndo({ undo: () => rawRemove(a.id), redo: () => { S.annots.push(a); refreshPage(a.pageId); } });
    }
    refreshPage(a.pageId);
    syncTextCtx();
  };
  div.addEventListener('input', onInput);
  div.addEventListener('blur', done);
  div.addEventListener('keydown', onKeyDown);
}

// Caret goes where the user clicked. Selecting the whole box on entry (the old
// behaviour) meant the first keystroke wiped existing text.
function placeCaret(div, pt) {
  const sel = window.getSelection();
  if (!sel) return;
  let range = null;
  if (pt) {
    if (document.caretRangeFromPoint) {
      range = document.caretRangeFromPoint(pt.x, pt.y);
    } else if (document.caretPositionFromPoint) {
      const cp = document.caretPositionFromPoint(pt.x, pt.y);
      if (cp) { range = document.createRange(); range.setStart(cp.offsetNode, cp.offset); }
    }
    if (range && !div.contains(range.startContainer)) range = null;
  }
  if (range) {
    range.collapse(true);
  } else {
    range = document.createRange();
    range.selectNodeContents(div);
    range.collapse(false); // caret at the end
  }
  sel.removeAllRanges();
  sel.addRange(range);
}

// Keep the font controls visible whenever text is the active context.
export function syncTextCtx() {
  const a = S.selected && findAnn(S.selected);
  const on = S.tool === 'text' || S.tool === 'edittext' || a?.type === 'text' ||
    !!document.querySelector('.annText.editing');
  document.body.classList.toggle('textCtx', on);
}

function syncNoteIcon(v, vp, a) {
  const b = a.box;
  const d = dispRect(vp, b.x, b.y, b.x + b.w, b.y + b.h);
  let div = v.edit.querySelector(`.annNote[data-ann="${a.id}"]`);
  if (!div) {
    div = document.createElement('div');
    div.className = 'annNote';
    div.dataset.ann = a.id;
    div.innerHTML = '<svg viewBox="0 0 24 24">' +
      '<path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z"/></svg>';
    v.edit.appendChild(div);
  }
  div.classList.toggle('selected', S.selected === a.id);
  div.title = a.text || 'Note';
  div.querySelector('svg').setAttribute('style', `fill:${a.color};stroke:rgba(0,0,0,.35);stroke-width:1`);
  place(div, d);
}

function syncImageEl(v, vp, a) {
  const b = a.box;
  const d = dispRect(vp, b.x, b.y, b.x + b.w, b.y + b.h);
  let img = v.edit.querySelector(`.annImage[data-ann="${a.id}"]`);
  if (!img) {
    img = document.createElement('img');
    img.className = 'annImage';
    img.dataset.ann = a.id;
    img.draggable = false;
    v.edit.appendChild(img);
  }
  if (img.getAttribute('src') !== a.dataUrl) img.src = a.dataUrl;
  img.classList.toggle('selected', S.selected === a.id);
  place(img, d);
}

function addImageHandle(v, vp, a) {
  const b = a.box;
  const d = dispRect(vp, b.x, b.y, b.x + b.w, b.y + b.h);
  const holder = document.createElement('div');
  holder.style.cssText = `position:absolute;left:${d.x}px;top:${d.y}px;width:${d.w}px;height:${d.h}px;pointer-events:none;`;
  addHandle(holder, a.id);
  v.edit.appendChild(holder);
}

// ---------- note popup ----------
let noteState = null; // { ann, before }

export function openNotePopup(a, anchorEl) {
  const pop = document.getElementById('notePopup');
  const ta = document.getElementById('npText');
  closeNotePopup();
  noteState = { ann: a, before: a.text || '' };
  ta.value = a.text || '';
  pop.hidden = false;
  const r = anchorEl.getBoundingClientRect();
  let x = r.right + 10, y = r.top - 4;
  if (x + 250 > innerWidth) x = r.left - 250;
  if (y + 180 > innerHeight) y = innerHeight - 190;
  pop.style.left = `${Math.max(8, x)}px`;
  pop.style.top = `${Math.max(58, y)}px`;
  ta.focus();
}

export function closeNotePopup() {
  const pop = document.getElementById('notePopup');
  if (pop.hidden) return;
  pop.hidden = true;
  if (noteState) {
    const { ann, before } = noteState;
    const after = document.getElementById('npText').value;
    ann.text = after;
    if (ann._fresh) {
      delete ann._fresh;
      pushUndo({ undo: () => rawRemove(ann.id), redo: () => { S.annots.push(ann); refreshPage(ann.pageId); } });
    } else if (before !== after) {
      pushUndo({
        undo: () => { ann.text = before; refreshPage(ann.pageId); },
        redo: () => { ann.text = after; refreshPage(ann.pageId); },
      });
    }
    refreshPage(ann.pageId);
  }
  noteState = null;
}

// ---------- editing the document's own text ----------
// The original glyphs live in the page content stream and are drawn with
// subsetted embedded fonts, so they cannot be re-encoded reliably (typing a
// character the subset lacks would fail). Instead we mask the run with the page
// background colour and put an ordinary editable text box on top of it.

const rgbToHex = (s) => '#' + s.split(',').map(x => (+x).toString(16).padStart(2, '0')).join('');

function pdfBox(vp, d) {
  const p1 = vp.convertToPdfPoint(d.x, d.y);
  const p2 = vp.convertToPdfPoint(d.x + d.w, d.y + d.h);
  return {
    x: Math.min(p1[0], p2[0]), y: Math.min(p1[1], p2[1]),
    w: Math.abs(p2[0] - p1[0]), h: Math.abs(p2[1] - p1[1]),
  };
}

function canvasSampler(v) {
  const c = v.canvas;
  const dpr = c.width / (parseFloat(c.style.width) || c.width || 1);
  return { c, dpr, ctx: c.getContext('2d', { willReadFrequently: true }) };
}

// most common colour in the thin bands just above and below the run
function sampleBg(v, d) {
  try {
    const { c, dpr, ctx } = canvasSampler(v);
    const pad = Math.max(2, d.h * 0.35);
    const counts = new Map();
    for (const yy of [d.y - pad, d.y + d.h + pad]) {
      const py = Math.round(yy * dpr);
      if (py < 0 || py >= c.height) continue;
      const x0 = Math.max(0, Math.round(d.x * dpr));
      const w = Math.min(c.width - x0, Math.round(d.w * dpr));
      if (w <= 0) continue;
      const row = ctx.getImageData(x0, py, w, 1).data;
      for (let i = 0; i < w; i++) {
        const k = `${row[i * 4]},${row[i * 4 + 1]},${row[i * 4 + 2]}`;
        counts.set(k, (counts.get(k) || 0) + 1);
      }
    }
    let best = null, n = 0;
    for (const [k, cnt] of counts) if (cnt > n) { n = cnt; best = k; }
    return best ? rgbToHex(best) : '#ffffff';
  } catch { return '#ffffff'; }
}

const hexTriplet = (hex) => {
  const h = (hex || '#000000').replace('#', '');
  const n = parseInt(h.length === 3 ? h.split('').map(c => c + c).join('') : h, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
};

// The glyph colour: the commonest colour inside the run that is clearly not the
// background. Picking the *darkest* pixel instead would return the background
// for light-on-dark text (e.g. white on a coloured banner).
function sampleInk(v, d, bgHex) {
  try {
    const { c, dpr, ctx } = canvasSampler(v);
    const x0 = Math.max(0, Math.round(d.x * dpr));
    const y0 = Math.max(0, Math.round(d.y * dpr));
    const w = Math.min(c.width - x0, Math.round(d.w * dpr));
    const h = Math.min(c.height - y0, Math.round(d.h * dpr));
    if (w <= 0 || h <= 0) return S.colors.text;
    const bg = hexTriplet(bgHex);
    const px = ctx.getImageData(x0, y0, w, h).data;
    const counts = new Map();
    for (let i = 0; i < px.length; i += 4) {
      const dr = px[i] - bg[0], dg = px[i + 1] - bg[1], db = px[i + 2] - bg[2];
      if (dr * dr + dg * dg + db * db < 3000) continue; // effectively background
      const k = `${px[i]},${px[i + 1]},${px[i + 2]}`;
      counts.set(k, (counts.get(k) || 0) + 1);
    }
    let best = null, n = 0;
    for (const [k, cnt] of counts) if (cnt > n) { n = cnt; best = k; }
    return best ? rgbToHex(best) : S.colors.text;
  } catch { return S.colors.text; }
}

// how much of `a`'s width is covered by the union of `others` on the same line
function coveredFrac(a, others) {
  const segs = [];
  for (const b of others) {
    if (Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) <= a.height * 0.3) continue;
    const l = Math.max(a.left, b.left), r = Math.min(a.right, b.right);
    if (r > l) segs.push([l, r]);
  }
  if (!segs.length) return 0;
  segs.sort((x, y) => x[0] - y[0]);
  let total = 0, curL = segs[0][0], curR = segs[0][1];
  for (const [l, r] of segs.slice(1)) {
    if (l > curR) { total += curR - curL; curL = l; curR = r; }
    else curR = Math.max(curR, r);
  }
  return (total + curR - curL) / Math.max(1, a.width);
}

// every run sitting on the same baseline as `span`, contiguous with it
function lineSpans(span) {
  const layer = span.closest('.textLayer');
  if (!layer) return [span];
  const all = [...layer.querySelectorAll('span')];
  const r0 = span.getBoundingClientRect();
  const mid0 = r0.top + r0.height / 2;
  let row = all
    .map((s, i) => ({ s, i, r: s.getBoundingClientRect() }))
    .filter(o => o.s.textContent && o.r.height > 0 &&
      Math.abs((o.r.top + o.r.height / 2) - mid0) <= Math.max(3, r0.height * 0.5));
  // Text this tool has already replaced is still in the file, just painted over,
  // so a run sitting underneath a later-drawn one is invisible: drop it, or
  // re-editing a replaced line would pick up both copies.
  // Runs on one baseline normally sit side by side, so a run that a later-drawn
  // run overlaps is almost certainly hidden beneath it.
  row = row.filter((o, k) => o.s === span ||
    coveredFrac(o.r, row.filter((p, j) => j !== k && p.i > o.i).map(p => p.r)) <= 0.25);
  row.sort((a, b) => a.r.left - b.r.left);
  const i = row.findIndex(o => o.s === span);
  if (i < 0) return [span];
  const gap = Math.max(6, r0.height * 1.4);
  const group = [row[i]];
  for (let k = i - 1; k >= 0; k--) {
    if (group[0].r.left - row[k].r.right > gap) break;
    group.unshift(row[k]);
  }
  for (let k = i + 1; k < row.length; k++) {
    if (row[k].r.left - group[group.length - 1].r.right > gap) break;
    group.push(row[k]);
  }
  // return in DOM order: that is the order pdf.js emitted the runs, i.e. the
  // logical order, which is what an RTL string needs (visual order would reverse it)
  const set = new Set(group.map(o => o.s));
  return all.filter(s => set.has(s));
}

function editExistingText(v, ev) {
  const span = ev.target.closest?.('.textLayer span');
  if (!span || !span.textContent.trim()) return false;
  const spans = lineSpans(span);
  const wr = v.wrap.getBoundingClientRect();
  let left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity;
  for (const s of spans) {
    const r = s.getBoundingClientRect();
    left = Math.min(left, r.left);
    top = Math.min(top, r.top);
    right = Math.max(right, r.right);
    bottom = Math.max(bottom, r.bottom);
  }
  const d = { x: left - wr.left, y: top - wr.top, w: right - left, h: bottom - top };
  if (!(d.w > 0) || !(d.h > 0)) return false;

  let text = spans.map(s => s.textContent).join('');
  const rtl = RTL_RE.test(text);
  // pdf.js returns glyphs in content-stream order; if this page was produced in
  // visual order, flip the run back to logical so it reads correctly while editing
  if (rtl && rtlLooksVisual(v.entry._text || text)) text = flipBidi(text);
  const pad = Math.max(1, d.h * 0.15);
  const padded = { x: d.x - pad, y: d.y - pad, w: d.w + pad * 2, h: d.h + pad * 2 };
  const fs = (parseFloat(getComputedStyle(span).fontSize) || d.h) / v.viewport.scale;
  const bg = sampleBg(v, d);
  const a = {
    id: uid(), pageId: v.entry.id, type: 'text',
    box: pdfBox(v.viewport, padded),
    cover: { ...pdfBox(v.viewport, padded), color: bg },
    text,
    fontSize: Math.max(4, fs),
    fontFamily: S.fontFamily,
    color: sampleInk(v, d, bg),
    rtl,
    _fresh: true,
    _orig: text,
  };
  S.annots.push(a);
  refreshPage(a.pageId);
  select(a.id);
  setTool('select');
  startTextEdit(a, v.edit.querySelector(`.annText[data-ann="${a.id}"]`), { x: ev.clientX, y: ev.clientY });
  return true;
}

// ---------- moving images that are already in the document ----------
// Same idea as editing existing text: mask the original spot and hand the
// content to the ordinary annotation machinery, which already does move,
// resize, undo and export.
let OPS_NAME = null;
function opName(fn) {
  if (!OPS_NAME) {
    OPS_NAME = {};
    for (const [k, val] of Object.entries(globalThis.pdfjsLib?.OPS || {})) OPS_NAME[val] = k;
  }
  return OPS_NAME[fn];
}

const matMul = (a, b) => [
  a[0] * b[0] + a[2] * b[1], a[1] * b[0] + a[3] * b[1],
  a[0] * b[2] + a[2] * b[3], a[1] * b[2] + a[3] * b[3],
  a[0] * b[4] + a[2] * b[5] + a[4], a[1] * b[4] + a[3] * b[5] + a[5],
];

// bbox of the unit square under matrix m, in PDF user space
function unitBox(m) {
  const pt = (x, y) => [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];
  const c = [pt(0, 0), pt(1, 0), pt(0, 1), pt(1, 1)];
  const xs = c.map(p => p[0]), ys = c.map(p => p[1]);
  return {
    x: Math.min(...xs), y: Math.min(...ys),
    w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys),
  };
}

// Every image painted on the page plus the matrix that places it: an image
// XObject fills the unit square, so the matrix carries position, size and flips.
export function pageImages(entry) {
  // cache the promise, not a placeholder: a caller arriving mid-scan used to get
  // the empty array and conclude the page had no images
  if (!entry._imagesScan) entry._imagesScan = scanPageImages(entry);
  return entry._imagesScan;
}

async function scanPageImages(entry) {
  entry._images = [];
  if (!entry._page) return entry._images;
  try {
    const ops = await entry._page.getOperatorList();
    let ctm = [1, 0, 0, 1, 0, 0];
    const stack = [];
    const found = [];
    for (let i = 0; i < ops.fnArray.length; i++) {
      const name = opName(ops.fnArray[i]);
      const args = ops.argsArray[i];
      if (name === 'save') stack.push(ctm.slice());
      else if (name === 'restore') ctm = stack.pop() || [1, 0, 0, 1, 0, 0];
      else if (name === 'transform') ctm = matMul(ctm, args);
      else if (name === 'paintImageXObject' || name === 'paintImageMaskXObject') found.push({ objId: args[0], m: ctm.slice() });
      else if (name === 'paintInlineImageXObject') found.push({ inline: args[0], m: ctm.slice() });
    }
    entry._images = found
      .map(f => ({ ...f, box: unitBox(f.m) }))
      .filter(f => f.box.w > 1 && f.box.h > 1);
  } catch (err) { console.warn('image scan', err); }
  return entry._images;
}

// load the image list for everything on screen, then redraw the hints
function primeImages() {
  for (const v of views.values()) {
    pageImages(v.entry).then(() => { if (S.tool === 'moveimg') refreshPage(v.entry.id); });
  }
}

function drawImageHints(v) {
  for (const im of v.entry._images || []) {
    const d = dispRect(v.viewport, im.box.x, im.box.y, im.box.x + im.box.w, im.box.y + im.box.h);
    v.svg.appendChild(mkEl('rect', { x: d.x, y: d.y, width: d.w, height: d.h, class: 'imgHint' }));
  }
}

function bitmapToDataUrl(img, flipX, flipY) {
  const w = img.width, h = img.height;
  if (!w || !h) return null;
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  const ctx = c.getContext('2d');
  ctx.translate(flipX ? w : 0, flipY ? h : 0);
  ctx.scale(flipX ? -1 : 1, flipY ? -1 : 1);
  if (img.bitmap) {
    ctx.drawImage(img.bitmap, 0, 0);
  } else if (img.data) {
    const src = img.data;
    const tmp = document.createElement('canvas');
    tmp.width = w;
    tmp.height = h;
    const tctx = tmp.getContext('2d');
    const id = tctx.createImageData(w, h);
    if (src.length === w * h * 4) {
      id.data.set(src);
    } else if (src.length === w * h * 3) {
      for (let i = 0, j = 0; i < src.length; i += 3, j += 4) {
        id.data[j] = src[i];
        id.data[j + 1] = src[i + 1];
        id.data[j + 2] = src[i + 2];
        id.data[j + 3] = 255;
      }
    } else {
      return null; // 1bpp masks and friends: caller falls back to the canvas crop
    }
    tctx.putImageData(id, 0, 0);
    ctx.drawImage(tmp, 0, 0);
  } else {
    return null;
  }
  return c.toDataURL('image/png');
}

// last resort: take the pixels straight off the rendered page
function cropFromCanvas(v, d) {
  try {
    const src = v.canvas;
    const dpr = src.width / (parseFloat(src.style.width) || src.width || 1);
    const x = Math.max(0, Math.round(d.x * dpr));
    const y = Math.max(0, Math.round(d.y * dpr));
    const w = Math.min(src.width - x, Math.round(d.w * dpr));
    const h = Math.min(src.height - y, Math.round(d.h * dpr));
    if (w <= 0 || h <= 0) return null;
    const c = document.createElement('canvas');
    c.width = w;
    c.height = h;
    c.getContext('2d').drawImage(src, x, y, w, h, 0, 0, w, h);
    return c.toDataURL('image/png');
  } catch { return null; }
}

async function liftImage(v, p) {
  const imgs = await pageImages(v.entry);
  if (!imgs.length) { toast('No movable image on this page', 'info', 2600); return; }
  const [px, py] = p.pdf;
  // later-painted images sit on top, so search back to front
  const hit = [...imgs].reverse().find(im =>
    px >= im.box.x && px <= im.box.x + im.box.w && py >= im.box.y && py <= im.box.y + im.box.h);
  if (!hit) { toast('Click directly on an image', 'info', 2600); return; }

  const d = dispRect(v.viewport, hit.box.x, hit.box.y, hit.box.x + hit.box.w, hit.box.y + hit.box.h);
  let dataUrl = null;
  try {
    let img = null;
    if (hit.objId && v.entry._page.objs.has(hit.objId)) img = v.entry._page.objs.get(hit.objId);
    else if (hit.inline) img = hit.inline;
    if (img) dataUrl = bitmapToDataUrl(img, hit.m[0] < 0, hit.m[3] < 0);
  } catch (err) { console.warn('image extract', err); }
  if (!dataUrl) dataUrl = cropFromCanvas(v, d); // rasterised at the current zoom
  if (!dataUrl) { toast('Could not read that image', 'error'); return; }

  const a = addAnn({
    id: uid(), pageId: v.entry.id, type: 'image',
    box: { ...hit.box },
    cover: { ...hit.box, color: sampleBg(v, d) },
    dataUrl,
  });
  select(a.id);
  setTool('select');
  if (Math.abs(hit.m[1]) > 0.01 || Math.abs(hit.m[2]) > 0.01) {
    toast('That image is rotated or skewed in the page; the copy is placed upright', 'info', 5000);
  } else {
    toast('Drag to move it, or use the corner handle to resize');
  }
}

// ---------- creating annotations ----------
function createTextAnn(v, p) {
  const w = 220, h = S.fontSize * 1.6;
  const a = {
    id: uid(), pageId: v.entry.id, type: 'text',
    box: { x: p.pdf[0], y: p.pdf[1] - h, w, h },
    text: '', fontSize: S.fontSize, fontFamily: S.fontFamily, color: S.colors.text, _fresh: true,
  };
  S.annots.push(a);
  refreshPage(a.pageId);
  select(a.id);
  const div = v.edit.querySelector(`[data-ann="${a.id}"]`);
  if (div) startTextEdit(a, div);
  setTool('select');
}

function createNoteAnn(v, p) {
  const size = 22;
  const a = {
    id: uid(), pageId: v.entry.id, type: 'note',
    box: { x: p.pdf[0] - size / 2, y: p.pdf[1] - size / 2, w: size, h: size },
    text: '', color: S.colors.note, _fresh: true,
  };
  S.annots.push(a);
  refreshPage(a.pageId);
  select(a.id);
  setTool('select');
  const el = v.edit.querySelector(`[data-ann="${a.id}"]`);
  if (el) openNotePopup(a, el);
}

function placePending(v, p) {
  const { dataUrl, w, h, isSig } = S.pending;
  const maxW = isSig ? 150 : 260;
  const k = Math.min(1, maxW / w);
  const bw = w * k, bh = h * k;
  const a = addAnn({
    id: uid(), pageId: v.entry.id, type: 'image', isSig: !!isSig,
    box: { x: p.pdf[0] - bw / 2, y: p.pdf[1] - bh / 2, w: bw, h: bh },
    dataUrl,
  });
  S.pending = null;
  setTool('select');
  select(a.id);
}

// ---------- text markup from selection ----------
function commitMarkupSelection() {
  const sel = window.getSelection();
  if (!sel || sel.isCollapsed || !sel.rangeCount) return;
  const perPage = new Map();
  const pageRects = [...views.values()].map(v => ({ v, r: v.wrap.getBoundingClientRect() }));
  for (let i = 0; i < sel.rangeCount; i++) {
    for (const cr of sel.getRangeAt(i).getClientRects()) {
      if (cr.width < 1 || cr.height < 1) continue;
      const cx = cr.left + cr.width / 2, cy = cr.top + cr.height / 2;
      const hit = pageRects.find(({ r }) => cx >= r.left && cx <= r.right && cy >= r.top && cy <= r.bottom);
      if (!hit) continue;
      const { v, r } = hit;
      const p1 = v.viewport.convertToPdfPoint(cr.left - r.left, cr.top - r.top);
      const p2 = v.viewport.convertToPdfPoint(cr.right - r.left, cr.bottom - r.top);
      const rect = [Math.min(p1[0], p2[0]), Math.min(p1[1], p2[1]), Math.max(p1[0], p2[0]), Math.max(p1[1], p2[1])];
      if (!perPage.has(v.entry.id)) perPage.set(v.entry.id, []);
      perPage.get(v.entry.id).push(rect);
    }
  }
  sel.removeAllRanges();
  for (const [pageId, rects] of perPage) {
    const merged = mergeLineRects(rects);
    addAnn({ id: uid(), pageId, type: S.tool, rects: merged, color: S.colors[S.tool] });
  }
}

function mergeLineRects(rects) {
  rects.sort((a, b) => b[3] - a[3]);
  const merged = [];
  for (const r of rects) {
    const m = merged.find(m => {
      const ov = Math.min(m[3], r[3]) - Math.max(m[1], r[1]);
      return ov > 0.5 * Math.min(m[3] - m[1], r[3] - r[1]);
    });
    if (m) {
      m[0] = Math.min(m[0], r[0]); m[1] = Math.min(m[1], r[1]);
      m[2] = Math.max(m[2], r[2]); m[3] = Math.max(m[3], r[3]);
    } else merged.push([...r]);
  }
  return merged;
}

// ---------- pointer interactions ----------
// Double-click detection that survives the DOM node being swapped between the
// two clicks; a plain dblclick listener does not.
let lastClick = { id: null, t: 0, x: 0, y: 0 };
function isSecondClick(ev, id) {
  const now = performance.now();
  const hit = id !== null && lastClick.id === id && now - lastClick.t < 500 &&
    Math.abs(ev.clientX - lastClick.x) < 6 && Math.abs(ev.clientY - lastClick.y) < 6;
  lastClick = { id, t: now, x: ev.clientX, y: ev.clientY };
  return hit;
}

function onDown(ev) {
  if (ev.button !== 0) return;
  if (ev.target.closest('.formField') || ev.target.closest('.annText.editing')) return;
  if (ev.target.closest('#notePopup')) return;
  // commit any in-progress text edit before the layer gets rebuilt
  const editing = document.querySelector('.annText.editing');
  if (editing) editing.blur();
  // clicking anywhere but the parked box abandons it
  const clickedAnn = ev.target.closest?.('[data-ann]')?.dataset?.ann;
  if (parkedEdit === clickedAnn) parkedEdit = null;
  else dropParked();

  const v = viewFromEvent(ev);
  const t = S.tool;

  const rz = ev.target.closest?.('[data-rz]') || (ev.target.dataset?.rz ? ev.target : null);
  const rzId = rz?.dataset?.rz || (ev.target.getAttribute && ev.target.getAttribute('data-rz'));
  if (rzId && v) {
    const a = findAnn(rzId);
    if (a) {
      drag = { mode: 'resize', v, a, start: pagePoint(v, ev), orig: snapshotGeom(a) };
      ev.preventDefault();
      return;
    }
  }

  const annEl = ev.target.closest?.('[data-ann]');
  if (t === 'select') {
    if (annEl && v) {
      const a = findAnn(annEl.dataset.ann);
      if (!a) return;
      const second = isSecondClick(ev, a.id);
      select(a.id);
      if (a.type === 'text' && (second || ev.detail >= 2)) {
        ev.preventDefault();
        startTextEdit(a, v.edit.querySelector(`.annText[data-ann="${a.id}"]`), { x: ev.clientX, y: ev.clientY });
        return;
      }
      drag = { mode: 'move', v, a, start: pagePoint(v, ev), orig: snapshotGeom(a), moved: false, el: annEl };
      ev.preventDefault();
    } else {
      isSecondClick(ev, null);
      if (!ev.target.closest('.menu')) { select(null); closeNotePopup(); }
    }
    return;
  }

  if (!v) return;
  if (['highlight', 'underline', 'strikeout'].includes(t)) return; // native text selection

  const p = pagePoint(v, ev);
  if (t === 'pen') {
    drag = { mode: 'pen', v, pts: [p.pdf], el: null };
    ev.preventDefault();
  } else if (['rect', 'ellipse', 'line', 'arrow'].includes(t)) {
    drag = { mode: 'shape', v, shape: t, p0: p, el: null };
    ev.preventDefault();
  } else if (t === 'text') {
    ev.preventDefault(); // keep focus on the new text box (suppress default focus steal)
    const hit = annEl && findAnn(annEl.dataset.ann);
    if (hit?.type === 'text') {
      // edit the box that was clicked instead of stacking a new one on top of it
      select(hit.id);
      setTool('select');
      startTextEdit(hit, v.edit.querySelector(`.annText[data-ann="${hit.id}"]`), { x: ev.clientX, y: ev.clientY });
    } else {
      createTextAnn(v, p);
    }
  } else if (t === 'edittext') {
    ev.preventDefault();
    const prev = annEl && findAnn(annEl.dataset.ann);
    if (prev?.type === 'text') {
      select(prev.id);
      setTool('select');
      startTextEdit(prev, v.edit.querySelector(`.annText[data-ann="${prev.id}"]`), { x: ev.clientX, y: ev.clientY });
    } else if (!editExistingText(v, ev)) {
      toast('Click directly on the text you want to change', 'info', 2600);
    }
  } else if (t === 'moveimg') {
    ev.preventDefault();
    liftImage(v, p);
  } else if (t === 'note') {
    ev.preventDefault();
    createNoteAnn(v, p);
  } else if (t === 'place' && S.pending) {
    placePending(v, p);
  }
}

function onMove(ev) {
  if (!drag) return;
  const { v } = drag;
  const p = pagePoint(v, ev);
  const vp = v.viewport;

  if (drag.mode === 'pen') {
    drag.pts.push(p.pdf);
    if (!drag.el) {
      drag.el = mkEl('polyline', {
        fill: 'none', stroke: S.colors.pen, 'stroke-width': S.strokeW * vp.scale,
        'stroke-linecap': 'round', 'stroke-linejoin': 'round',
      });
      v.svg.appendChild(drag.el);
    }
    drag.el.setAttribute('points', drag.pts.map(pt => vp.convertToViewportPoint(pt[0], pt[1]).join(',')).join(' '));
  } else if (drag.mode === 'shape') {
    const a = tempShapeAnn(drag, p);
    if (drag.el) drag.el.remove();
    const g = mkEl('g', {});
    drawAnnSvg(g, vp, a);
    drag.el = g.firstChild || g;
    v.svg.appendChild(drag.el);
  } else if (drag.mode === 'move') {
    const dx = p.pdf[0] - drag.start.pdf[0];
    const dy = p.pdf[1] - drag.start.pdf[1];
    if (Math.abs(p.px - drag.start.px) + Math.abs(p.py - drag.start.py) > 3) drag.moved = true;
    applyDelta(drag.a, drag.orig, dx, dy);
    refreshPage(drag.a.pageId);
  } else if (drag.mode === 'resize') {
    const a = drag.a, o = drag.orig.box;
    const d0 = dispRect(vp, o.x, o.y, o.x + o.w, o.y + o.h);
    const nw = Math.max(14, d0.w + (p.px - drag.start.px));
    const nh = Math.max(14, d0.h + (p.py - drag.start.py));
    const c1 = vp.convertToPdfPoint(d0.x, d0.y);
    const c2 = vp.convertToPdfPoint(d0.x + nw, d0.y + nh);
    a.box = {
      x: Math.min(c1[0], c2[0]), y: Math.min(c1[1], c2[1]),
      w: Math.abs(c2[0] - c1[0]), h: Math.abs(c2[1] - c1[1]),
    };
    refreshPage(a.pageId);
  }
}

function tempShapeAnn(drag, p) {
  const t = drag.shape;
  const p0 = drag.p0.pdf, p1 = p.pdf;
  drag.last = p;
  if (t === 'line' || t === 'arrow') {
    return { id: '_tmp', pageId: drag.v.entry.id, type: t, points: [p0, p1], color: S.colors.shape, strokeW: S.strokeW };
  }
  return {
    id: '_tmp', pageId: drag.v.entry.id, type: t,
    box: {
      x: Math.min(p0[0], p1[0]), y: Math.min(p0[1], p1[1]),
      w: Math.abs(p1[0] - p0[0]), h: Math.abs(p1[1] - p0[1]),
    },
    color: S.colors.shape, strokeW: S.strokeW,
  };
}

function applyDelta(a, orig, dx, dy) {
  if (orig.box) a.box = { ...orig.box, x: orig.box.x + dx, y: orig.box.y + dy };
  if (orig.points) a.points = orig.points.map(pt => [pt[0] + dx, pt[1] + dy]);
  if (orig.rects) a.rects = orig.rects.map(r => [r[0] + dx, r[1] + dy, r[2] + dx, r[3] + dy]);
}

function onUp(ev) {
  if (['highlight', 'underline', 'strikeout'].includes(S.tool)) {
    setTimeout(commitMarkupSelection, 10);
  }
  if (!drag) return;
  const d = drag;
  drag = null;
  // only the temporary pen/shape preview is disposable here; in 'move' mode
  // d.el is the live annotation node and must survive
  if (d.el && d.el.remove && (d.mode === 'pen' || d.mode === 'shape')) d.el.remove();

  if (d.mode === 'pen') {
    if (d.pts.length > 2) {
      addAnn({ id: uid(), pageId: d.v.entry.id, type: 'ink', points: d.pts, color: S.colors.pen, strokeW: S.strokeW });
    } else refreshPage(d.v.entry.id);
  } else if (d.mode === 'shape') {
    const p = d.last || d.p0;
    const dist = Math.abs(p.px - d.p0.px) + Math.abs(p.py - d.p0.py);
    if (dist > 6) {
      const a = tempShapeAnn(d, p);
      a.id = uid();
      addAnn(a);
      select(a.id);
    } else refreshPage(d.v.entry.id);
  } else if (d.mode === 'move') {
    if (d.moved) {
      const before = d.orig, after = snapshotGeom(d.a), a = d.a;
      pushUndo({
        undo: () => { restoreGeom(a, before); refreshPage(a.pageId); },
        redo: () => { restoreGeom(a, after); refreshPage(a.pageId); },
      });
    } else if (d.a.type === 'note') {
      const el = d.v.edit.querySelector(`[data-ann="${d.a.id}"]`);
      if (el) openNotePopup(d.a, el);
    }
  } else if (d.mode === 'resize') {
    const before = d.orig, after = snapshotGeom(d.a), a = d.a;
    pushUndo({
      undo: () => { restoreGeom(a, before); refreshPage(a.pageId); },
      redo: () => { restoreGeom(a, after); refreshPage(a.pageId); },
    });
  }
}

function onKey(ev) {
  if (modalOpen()) {
    // the signature pad owns its keys; nothing here may reach the document
    if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === 'z') {
      ev.preventDefault();
      ev.stopImmediatePropagation();
      sigApi?.undoStroke();
      return;
    }
    if (ev.key === 'Escape') { ev.preventDefault(); ev.stopImmediatePropagation(); closeSigModal(); }
    return;
  }
  const typing = ev.target.matches?.('input, textarea, select, [contenteditable="true"]');
  if (typing) return;
  if ((ev.key === 'Delete' || ev.key === 'Backspace') && S.selected) {
    ev.preventDefault();
    deleteSelected();
  } else if (ev.key === 'Escape') {
    select(null);
    closeNotePopup();
    if (S.tool === 'place') { S.pending = null; }
    setTool('select');
  }
}

// ---------- image / signature ----------
export function startImagePlacement(dataUrl, w, h, isSig = false) {
  S.pending = { dataUrl, w: w * 0.75, h: h * 0.75, isSig }; // px -> pt-ish
  setTool('place');
  toast(isSig ? 'Click on the page to place your signature' : 'Click on the page to place the image');
}

const SIG_KEY = 'pdfeditor.signatures';

// Stroke-level undo for the signature pad. Without it Ctrl+Z inside the modal
// fell through to the document and undid the last edit to the PDF.
let sigApi = null;
export const modalOpen = () => !document.getElementById('sigModal')?.hidden;
export function closeSigModal() {
  const m = document.getElementById('sigModal');
  if (m) m.hidden = true;
}

export function initSigModal() {
  const canvas = document.getElementById('sigCanvas');
  const ctx = canvas.getContext('2d');
  let strokes = [], cur = null;

  const pos = (ev) => {
    const r = canvas.getBoundingClientRect();
    return [(ev.clientX - r.left) * (canvas.width / r.width), (ev.clientY - r.top) * (canvas.height / r.height)];
  };
  const style = () => {
    ctx.strokeStyle = '#101828';
    ctx.fillStyle = '#101828';
    ctx.lineWidth = 2.6;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
  };
  const drawStroke = (s) => {
    style();
    if (s.length === 1) {
      ctx.beginPath();
      ctx.arc(s[0][0], s[0][1], 1.3, 0, Math.PI * 2);
      ctx.fill();
      return;
    }
    ctx.beginPath();
    ctx.moveTo(s[0][0], s[0][1]);
    for (let i = 1; i < s.length; i++) ctx.lineTo(s[i][0], s[i][1]);
    ctx.stroke();
  };
  const redraw = () => {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    for (const s of strokes) drawStroke(s);
  };
  // draw incrementally while the pen is down; full redraw only on undo/clear
  const segment = (a, b) => {
    style();
    ctx.beginPath();
    ctx.moveTo(a[0], a[1]);
    ctx.lineTo(b[0], b[1]);
    ctx.stroke();
  };

  canvas.addEventListener('pointerdown', (ev) => {
    cur = [pos(ev)];
    strokes.push(cur);
    drawStroke(cur);
    canvas.setPointerCapture(ev.pointerId);
  });
  canvas.addEventListener('pointermove', (ev) => {
    if (!cur) return;
    const p = pos(ev);
    segment(cur[cur.length - 1], p);
    cur.push(p);
  });
  canvas.addEventListener('pointerup', () => { cur = null; });

  const clear = () => { strokes = []; cur = null; redraw(); };
  const undoStroke = () => {
    if (!strokes.length) return false;
    strokes.pop();
    cur = null;
    redraw();
    return true;
  };
  sigApi = { clear, undoStroke };

  document.getElementById('sigClear').addEventListener('click', clear);
  document.getElementById('sigUndo').addEventListener('click', undoStroke);
  document.getElementById('sigCancel').addEventListener('click', closeSigModal);
  document.getElementById('sigClose').addEventListener('click', closeSigModal);
  document.getElementById('sigUse').addEventListener('click', () => {
    if (!strokes.length) { toast('Draw a signature first', 'error'); return; }
    const trimmed = trimCanvas(canvas);
    if (!trimmed) { toast('Draw a signature first', 'error'); return; }
    const list = loadSigs();
    list.unshift(trimmed.url);
    localStorage.setItem(SIG_KEY, JSON.stringify(list.slice(0, 6)));
    closeSigModal();
    startImagePlacement(trimmed.url, trimmed.w, trimmed.h, true);
  });
}

function loadSigs() {
  try { return JSON.parse(localStorage.getItem(SIG_KEY)) || []; } catch { return []; }
}

export function openSigModal() {
  sigApi?.clear();
  renderSigList();
  document.getElementById('sigModal').hidden = false;
}

function renderSigList() {
  const host = document.getElementById('sigSaved');
  host.replaceChildren();
  const list = loadSigs();
  list.forEach((url, i) => {
    const d = document.createElement('div');
    d.className = 'sigThumb';
    d.title = 'Use this signature';
    const img = document.createElement('img');
    img.src = url;
    const del = document.createElement('button');
    del.className = 'sigDel';
    del.innerHTML = '<svg viewBox="0 0 24 24"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>';
    del.addEventListener('click', (ev) => {
      ev.stopPropagation();
      const l = loadSigs(); l.splice(i, 1);
      localStorage.setItem(SIG_KEY, JSON.stringify(l));
      renderSigList();
    });
    d.append(img, del);
    d.addEventListener('click', () => {
      const im = new Image();
      im.onload = () => {
        document.getElementById('sigModal').hidden = true;
        startImagePlacement(url, im.naturalWidth, im.naturalHeight, true);
      };
      im.src = url;
    });
    host.appendChild(d);
  });
}

function trimCanvas(canvas) {
  const ctx = canvas.getContext('2d');
  const { width, height } = canvas;
  const data = ctx.getImageData(0, 0, width, height).data;
  let minX = width, minY = height, maxX = -1, maxY = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (data[(y * width + x) * 4 + 3] > 8) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
  }
  if (maxX < 0) return null;
  const pad = 6;
  minX = Math.max(0, minX - pad); minY = Math.max(0, minY - pad);
  maxX = Math.min(width - 1, maxX + pad); maxY = Math.min(height - 1, maxY + pad);
  const w = maxX - minX + 1, h = maxY - minY + 1;
  const out = document.createElement('canvas');
  out.width = w; out.height = h;
  out.getContext('2d').drawImage(canvas, minX, minY, w, h, 0, 0, w, h);
  return { url: out.toDataURL('image/png'), w, h };
}

// ---------- init ----------
export function initTools() {
  const viewer = document.getElementById('viewer');
  viewer.addEventListener('pointerdown', onDown);
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp);
  document.addEventListener('keydown', onKey);
  document.getElementById('npClose').addEventListener('click', closeNotePopup);
  document.getElementById('npDelete').addEventListener('click', () => {
    if (noteState) {
      const id = noteState.ann.id;
      noteState = null;
      document.getElementById('notePopup').hidden = true;
      removeAnn(id);
    }
  });
  initSigModal();
}
