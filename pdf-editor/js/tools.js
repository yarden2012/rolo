// Annotation tools: SVG overlay + DOM edit layer, pointer interactions,
// text markup from selection, text boxes, notes, images, signatures, undo/redo.

import { S, uid, pushUndo, curColor, colorKey, clamp } from './state.js';
import { toast } from './ui.js';
import { views, refreshPage, els } from './viewer.js';

let drag = null; // active pointer interaction

// ---------- tool switching ----------
export function setTool(tool) {
  if (S.tool === tool) return;
  if (S.tool === 'place' && tool !== 'place') S.pending = null;
  S.tool = tool;
  document.body.dataset.tool = tool;
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

export function restyleSelectedFont(size) {
  const a = S.selected && findAnn(S.selected);
  if (a?.type === 'text') { a.fontSize = size; refreshPage(a.pageId); }
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
  drawSelectionBox(v);
}

function drawAnnSvg(svg, vp, a) {
  const sw = (a.strokeW || 2) * vp.scale;
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
export function renderEditLayer(v) {
  v.edit.replaceChildren();
  const vp = v.viewport;
  for (const a of S.annots) {
    if (a.pageId !== v.entry.id) continue;
    if (a.type === 'text') addTextDiv(v, vp, a);
    else if (a.type === 'note') addNoteIcon(v, vp, a);
    else if (a.type === 'image') addImageEl(v, vp, a);
  }
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

function addTextDiv(v, vp, a) {
  const b = a.box;
  const d = dispRect(vp, b.x, b.y, b.x + b.w, b.y + b.h);
  const div = document.createElement('div');
  div.className = 'annText' + (S.selected === a.id ? ' selected' : '');
  div.dataset.ann = a.id;
  div.textContent = a.text || '';
  div.style.cssText = `left:${d.x}px;top:${d.y}px;width:${d.w}px;min-height:${d.h}px;` +
    `font-size:${a.fontSize * vp.scale}px;color:${a.color};`;
  div.addEventListener('dblclick', (ev) => { ev.stopPropagation(); startTextEdit(a, div); });
  if (S.selected === a.id) addHandle(div, a.id);
  v.edit.appendChild(div);
}

export function startTextEdit(a, div) {
  div.contentEditable = 'true';
  div.classList.add('editing');
  div.focus();
  setTimeout(() => div.focus(), 0); // win any focus tug-of-war from the placing click
  const range = document.createRange();
  range.selectNodeContents(div);
  const sel = window.getSelection();
  sel.removeAllRanges();
  sel.addRange(range);
  const before = a.text;
  const done = () => {
    div.removeEventListener('blur', done);
    div.contentEditable = 'false';
    div.classList.remove('editing');
    const t = div.textContent.trim();
    if (!t) { rawRemove(a.id); return; }
    a.text = div.textContent;
    if (before !== a.text) {
      if (a._fresh) {
        delete a._fresh;
        pushUndo({ undo: () => rawRemove(a.id), redo: () => { S.annots.push(a); refreshPage(a.pageId); } });
      } else {
        const after = a.text;
        pushUndo({
          undo: () => { a.text = before; refreshPage(a.pageId); },
          redo: () => { a.text = after; refreshPage(a.pageId); },
        });
      }
    } else if (a._fresh) {
      delete a._fresh;
      pushUndo({ undo: () => rawRemove(a.id), redo: () => { S.annots.push(a); refreshPage(a.pageId); } });
    }
    refreshPage(a.pageId);
  };
  div.addEventListener('blur', done);
  div.addEventListener('keydown', (ev) => {
    if (ev.key === 'Escape') { ev.preventDefault(); div.blur(); }
    ev.stopPropagation();
  });
}

function addNoteIcon(v, vp, a) {
  const b = a.box;
  const d = dispRect(vp, b.x, b.y, b.x + b.w, b.y + b.h);
  const div = document.createElement('div');
  div.className = 'annNote' + (S.selected === a.id ? ' selected' : '');
  div.dataset.ann = a.id;
  div.title = a.text || 'Note';
  place(div, d);
  div.innerHTML = `<svg viewBox="0 0 24 24" style="fill:${a.color};stroke:rgba(0,0,0,.35);stroke-width:1">` +
    `<path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z"/></svg>`;
  v.edit.appendChild(div);
}

function addImageEl(v, vp, a) {
  const b = a.box;
  const d = dispRect(vp, b.x, b.y, b.x + b.w, b.y + b.h);
  const img = document.createElement('img');
  img.className = 'annImage' + (S.selected === a.id ? ' selected' : '');
  img.dataset.ann = a.id;
  img.src = a.dataUrl;
  img.draggable = false;
  place(img, d);
  v.edit.appendChild(img);
  if (S.selected === a.id) {
    const holder = document.createElement('div');
    holder.style.cssText = `position:absolute;left:${d.x}px;top:${d.y}px;width:${d.w}px;height:${d.h}px;pointer-events:none;`;
    addHandle(holder, a.id);
    v.edit.appendChild(holder);
  }
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

// ---------- creating annotations ----------
function createTextAnn(v, p) {
  const w = 220, h = S.fontSize * 1.6;
  const a = {
    id: uid(), pageId: v.entry.id, type: 'text',
    box: { x: p.pdf[0], y: p.pdf[1] - h, w, h },
    text: '', fontSize: S.fontSize, color: S.colors.text, _fresh: true,
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
function onDown(ev) {
  if (ev.button !== 0) return;
  if (ev.target.closest('.formField') || ev.target.closest('.annText.editing')) return;
  if (ev.target.closest('#notePopup')) return;
  // commit any in-progress text edit before the layer gets rebuilt
  const editing = document.querySelector('.annText.editing');
  if (editing) editing.blur();

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
      select(a.id);
      drag = { mode: 'move', v, a, start: pagePoint(v, ev), orig: snapshotGeom(a), moved: false, el: annEl };
      ev.preventDefault();
    } else {
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
    createTextAnn(v, p);
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
  if (d.el && d.el.remove) d.el.remove();

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
  const typing = ev.target.matches('input, textarea, select, [contenteditable="true"]');
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

export function initSigModal() {
  const modal = document.getElementById('sigModal');
  const canvas = document.getElementById('sigCanvas');
  const ctx = canvas.getContext('2d');
  let drawing = false, last = null, empty = true;

  const pos = (ev) => {
    const r = canvas.getBoundingClientRect();
    return [(ev.clientX - r.left) * (canvas.width / r.width), (ev.clientY - r.top) * (canvas.height / r.height)];
  };
  canvas.addEventListener('pointerdown', (ev) => {
    drawing = true; empty = false; last = pos(ev);
    canvas.setPointerCapture(ev.pointerId);
  });
  canvas.addEventListener('pointermove', (ev) => {
    if (!drawing) return;
    const p = pos(ev);
    ctx.strokeStyle = '#101828';
    ctx.lineWidth = 2.6;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(last[0], last[1]);
    ctx.lineTo(p[0], p[1]);
    ctx.stroke();
    last = p;
  });
  canvas.addEventListener('pointerup', () => { drawing = false; });

  const clear = () => { ctx.clearRect(0, 0, canvas.width, canvas.height); empty = true; };
  document.getElementById('sigClear').addEventListener('click', clear);
  document.getElementById('sigCancel').addEventListener('click', () => { modal.hidden = true; });
  document.getElementById('sigClose').addEventListener('click', () => { modal.hidden = true; });
  document.getElementById('sigUse').addEventListener('click', () => {
    if (empty) { toast('Draw a signature first', 'error'); return; }
    const trimmed = trimCanvas(canvas);
    if (!trimmed) { toast('Draw a signature first', 'error'); return; }
    const list = loadSigs();
    list.unshift(trimmed.url);
    localStorage.setItem(SIG_KEY, JSON.stringify(list.slice(0, 6)));
    modal.hidden = true;
    startImagePlacement(trimmed.url, trimmed.w, trimmed.h, true);
  });
}

function loadSigs() {
  try { return JSON.parse(localStorage.getItem(SIG_KEY)) || []; } catch { return []; }
}

export function openSigModal() {
  const modal = document.getElementById('sigModal');
  const canvas = document.getElementById('sigCanvas');
  canvas.getContext('2d').clearRect(0, 0, canvas.width, canvas.height);
  renderSigList();
  modal.hidden = false;
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
