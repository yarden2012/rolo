// Page management: rotate, delete, reorder, insert blank, merge.

import { S, uid, pushUndo } from './state.js';
import { toast } from './ui.js';
import * as V from './viewer.js';

function relayout() {
  V.layout();
  V.renderThumbs();
  V.renderVisible();
  V.updateIndicator();
}

export function rotateEntry(id) {
  const e = S.entries.find(en => en.id === id);
  if (!e) return;
  e.rotation = (e.rotation + 90) % 360;
  V.invalidate(id);
  relayout();
}

export function deleteEntry(id) {
  if (S.entries.length <= 1) { toast('Cannot delete the only page', 'error'); return; }
  const i = S.entries.findIndex(en => en.id === id);
  if (i < 0) return;
  const [e] = S.entries.splice(i, 1);
  const removedAnns = S.annots.filter(a => a.pageId === id);
  S.annots = S.annots.filter(a => a.pageId !== id);
  relayout();
  pushUndo({
    undo: () => { S.entries.splice(i, 0, e); S.annots.push(...removedAnns); V.invalidate(id); relayout(); },
    redo: () => {
      const j = S.entries.findIndex(en => en.id === id);
      if (j >= 0) S.entries.splice(j, 1);
      S.annots = S.annots.filter(a => a.pageId !== id);
      relayout();
    },
  });
}

export function moveEntryBefore(dragId, beforeId) {
  const from = S.entries.findIndex(e => e.id === dragId);
  if (from < 0) return;
  const fromIdx = from;
  const [e] = S.entries.splice(from, 1);
  let to = beforeId ? S.entries.findIndex(en => en.id === beforeId) : S.entries.length;
  if (to < 0) to = S.entries.length;
  S.entries.splice(to, 0, e);
  relayout();
  const toIdx = to;
  pushUndo({
    undo: () => {
      const cur = S.entries.findIndex(en => en.id === dragId);
      if (cur >= 0) { S.entries.splice(cur, 1); S.entries.splice(fromIdx, 0, e); relayout(); }
    },
    redo: () => {
      const cur = S.entries.findIndex(en => en.id === dragId);
      if (cur >= 0) { S.entries.splice(cur, 1); S.entries.splice(toIdx, 0, e); relayout(); }
    },
  });
}

export function insertBlankAfter(afterId) {
  const i = afterId ? S.entries.findIndex(en => en.id === afterId) : S.entries.length - 1;
  const ref = S.entries[Math.max(0, i)] || null;
  const e = {
    id: uid(), src: -1, srcPage: -1, rotation: 0,
    w: ref?.w || 612, h: ref?.h || 792, _page: null,
  };
  S.entries.splice(i + 1, 0, e);
  relayout();
  pushUndo({
    undo: () => {
      const j = S.entries.findIndex(en => en.id === e.id);
      if (j >= 0) { S.entries.splice(j, 1); S.annots = S.annots.filter(a => a.pageId !== e.id); relayout(); }
    },
    redo: () => { S.entries.splice(i + 1, 0, e); relayout(); },
  });
  toast('Blank page added');
}

export async function mergePdf(file) {
  try {
    const buf = await file.arrayBuffer();
    const before = S.entries.length;
    await V.addSource(buf, file.name);
    relayout();
    toast(`Appended ${S.entries.length - before} page(s) from “${file.name}”`, 'ok');
  } catch (err) {
    console.error(err);
    toast(`Could not merge: ${err.message}`, 'error');
  }
}
