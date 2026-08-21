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
  fontFamily: 'Helvetica',
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

// Which stored colour the picker reads and writes. A selected annotation wins
// over the active tool: after placing a text box the tool flips to 'select', and
// keying off the tool alone would point the picker at the shape colour.
export function colorKey(tool = S.tool) {
  // Only while the select tool is active — otherwise a lingering selection would
  // hijack the colour of whatever tool the user just picked up.
  const sel = tool === 'select' && S.selected && S.annots.find(a => a.id === S.selected);
  if (sel) {
    if (['highlight', 'underline', 'strikeout'].includes(sel.type)) return sel.type;
    if (sel.type === 'ink') return 'pen';
    if (sel.type === 'text') return 'text';
    if (sel.type === 'note') return 'note';
    if (sel.type !== 'image') return 'shape';
  }
  if (['highlight', 'underline', 'strikeout', 'pen'].includes(tool)) return tool;
  if (tool === 'text' || tool === 'edittext') return 'text';
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

// Text-box fonts. Limited to the PDF standard 14 so saved files need no font
// embedding, and the on-screen CSS stack matches what pdf-lib will draw.
export const FONTS = {
  Helvetica: { label: 'Helvetica', css: 'Helvetica, Arial, sans-serif', pdf: 'Helvetica' },
  Times: { label: 'Times', css: '"Times New Roman", Times, serif', pdf: 'TimesRoman' },
  Courier: { label: 'Courier', css: '"Courier New", Courier, monospace', pdf: 'Courier' },
};
export const fontKey = (k) => (FONTS[k] ? k : 'Helvetica');
export const fontCss = (k) => FONTS[fontKey(k)].css;

// ---------- right-to-left text ----------
// PDF content streams store glyphs in the order they are painted. Producers that
// do their own bidi emit RTL text in VISUAL order, others emit logical order, and
// pdf.js hands back whichever it finds. Hebrew final forms tell them apart: they
// only ever end a word, so seeing them start words means the run is reversed.
export const RTL_RE = /[\u0590-\u05FF\u0600-\u06FF\u0700-\u074F\u0780-\u07BF\uFB1D-\uFDFF\uFE70-\uFEFF]/;
const BIDI_SPLIT = /[\u0590-\u08FF\uFB1D-\uFEFF]+|[^\u0590-\u08FF\uFB1D-\uFEFF]+/g;
const HEB_FINALS = /[\u05DA\u05DD\u05DF\u05E3\u05E5]/; // final kaf, mem, nun, pe, tsadi
const HEB_WORD_SPLIT = /[^\u0590-\u05FF]+/;

// Reverses RTL segments and the order of segments. Its own inverse, so it maps
// logical <-> visual either way.
// A neutral/Latin segment has to be reversed too — otherwise its spaces and
// punctuation end up on the wrong side ("עמוד 12" would render with the space
// glued to the wrong word) — but numbers and Latin words stay upright inside it.
const LTR_TOKEN = /[0-9A-Za-zÀ-ɏ]+|[\s\S]/g;
// Paired characters have to be swapped for their mirror when the run is flipped,
// or "(12)" in a Hebrew line comes out as ")12(".
const MIRROR = { '(': ')', ')': '(', '[': ']', ']': '[', '{': '}', '}': '{', '<': '>', '>': '<' };
const flipNeutral = (seg) => (seg.match(LTR_TOKEN) || [])
  .map(tok => (tok.length === 1 && MIRROR[tok]) || tok)
  .reverse()
  .join('');

export function flipBidi(line) {
  const s = String(line ?? '');
  if (!RTL_RE.test(s)) return s;   // nothing bidirectional here, leave it alone
  const parts = s.match(BIDI_SPLIT) || [];
  return parts
    .map(p => (RTL_RE.test(p) ? [...p].reverse().join('') : flipNeutral(p)))
    .reverse()
    .join('');
}

// Feed this as much text as possible (a whole page) — per-line samples are noisy.
export function rtlLooksVisual(text) {
  let atStart = 0, atEnd = 0;
  for (const w of String(text ?? '').split(HEB_WORD_SPLIT)) {
    if (w.length < 2) continue;
    if (HEB_FINALS.test(w[0])) atStart++;
    if (HEB_FINALS.test(w[w.length - 1])) atEnd++;
  }
  return atStart > atEnd;
}

export const hasEdits = () =>
  S.annots.length > 0 || S.formValues.size > 0 ||
  S.entries.some((e, i) => e.rotation !== 0 || e.src !== 0 || e.srcPage !== i);
