// Shared application state + tiny helpers.

export const S = {
  srcDocs: [],            // { bytes: Uint8Array, pdf: pdfjs doc, name }
  entries: [],            // page list: { id, src, srcPage, rotation, w, h, _page, _text, _annotsW }
  annots: [],             // user annotations (coords in PDF user space of their page)
  formValues: new Map(),  // "srcIdx#fieldName" -> { type, value }
  scale: 1.25,
  tool: 'select',
  colors: {
    highlight: '#ffd60a', underline: '#3b82f6', strikeout: '#ef4444',
    pen: '#ef4444', shape: '#3b82f6', text: '#111827', note: '#f59e0b',
  },
  strokeW: 2,
  fontSize: 14,
  selected: null,
  pending: null,          // image/signature waiting for placement: { dataUrl, w, h, isSig }
  undoStack: [],
  redoStack: [],
  fileName: null,
  search: { q: '', hits: [], pos: -1 },
  currentEntry: null,
};

let n = 0;
export const uid = () => `a${Date.now().toString(36)}_${(n++).toString(36)}`;

export function colorKey(tool = S.tool) {
  if (['highlight', 'underline', 'strikeout', 'pen'].includes(tool)) return tool;
  if (tool === 'text') return 'text';
  if (tool === 'note') return 'note';
  return 'shape';
}
export const curColor = () => S.colors[colorKey()];

export function pushUndo(op) {
  S.undoStack.push(op);
  if (S.undoStack.length > 100) S.undoStack.shift();
  S.redoStack.length = 0;
}

export function hexToRgb(hex) {
  let h = (hex || '#000').replace('#', '');
  if (h.length === 3) h = h.split('').map(c => c + c).join('');
  const v = parseInt(h, 16);
  return { r: ((v >> 16) & 255) / 255, g: ((v >> 8) & 255) / 255, b: (v & 255) / 255 };
}

export const clamp = (v, a, b) => Math.min(b, Math.max(a, v));

export const hasEdits = () =>
  S.annots.length > 0 || S.formValues.size > 0 ||
  S.entries.some((e, i) => e.rotation !== 0 || e.src !== 0 || e.srcPage !== i);
