# PDF Editor

An offline desktop PDF editor inspired by [PDF Expert](https://pdfexpert.com/).
Electron shell, pdf.js rendering, pdf-lib writing — no network, no build step.

## Run

```bash
pdf-editor                # installed launcher (~/.local/bin)
pdf-editor some-file.pdf  # open a file directly
npm run app               # same thing, from the repo
```

Installed desktop integration:
- `~/.local/bin/pdf-editor` — CLI launcher
- `~/.local/share/applications/pdf-editor.desktop` — app launcher entry,
  registered for `application/pdf` (shows up in "Open With")
- `~/.local/share/icons/hicolor/scalable/apps/pdf-editor.svg`

Native mode gets real open/save dialogs, single-instance file opening, and an
unsaved-changes guard on close.

### Browser fallback

The same code also runs as a plain web app:

```bash
./run.sh          # serves on http://localhost:8123 and opens your browser
```

`?demo=1` loads the bundled sample document (regenerate it with
`node scripts/make-sample.cjs`).

## Features

**Annotate**
- Highlight / underline / strikeout — select text with the tool active
- Pen (freehand ink), shapes: rectangle, ellipse, line, arrow
- Text boxes — click to place, double-click to edit; pick the font
  (Helvetica / Times / Courier), size and colour from the toolbar. Style
  controls can be used mid-sentence: the box is kept and you are put back in
  it, so "place a box, set the size, then type" works too. Styling applies to
  the whole box, not to a selection inside it
- Edit the document's own text (E): click a line and retype it
- Move an image that is already in the document (I): click a highlighted
  image to lift it, then drag or resize it like any other object
- See "Editing existing content" below for what these do and do not do
- Sticky notes (real PDF `/Text` annotations)
- Insert images (PNG/JPEG)
- Signatures: draw once, stored locally, reuse across documents.
  Ctrl+Z (or Undo) inside the signature pad removes the last stroke only —
  it never touches the document

**Fill & sign**
- Interactive AcroForm filling: text fields, checkboxes, radios, dropdowns
- Values are written into the real form fields on save

**Organize pages**
- Sidebar thumbnails: drag to reorder, rotate, delete, extract single pages
- Insert blank pages, merge/append another PDF
- Document outline navigation

**View & find**
- Continuous scroll, zoom (Ctrl+wheel, buttons, fit width/page)
- Full-text search with per-page match highlighting
- Day / Night / Sepia reading modes

**Save**
- `Save` downloads an edited copy with all annotations, form values,
  rotations and page operations baked in (pdf-lib)
- If page structure is unchanged the original file is edited in place,
  preserving all document features; otherwise pages are copied into a fresh document

## Shortcuts

| Key | Action |
| --- | --- |
| Ctrl+O / Ctrl+S | Open / Save |
| Ctrl+F | Search |
| Ctrl+Z / Ctrl+Shift+Z | Undo / Redo (scoped: the signature pad and the text box you are typing in undo their own edits) |
| Ctrl+= / Ctrl+- / Ctrl+wheel | Zoom |
| V H U P T N E I | Select, Highlight, Underline, Pen, Text, Note, Edit text, Move image |
| Delete | Remove selected annotation |
| Esc | Deselect / cancel placement / close the signature pad |

## Architecture

```
electron/main.cjs   app window, native dialogs, argv/single-instance opening
electron/preload.cjs  contextBridge: window.native API for the renderer
index.html          UI shell (toolbar, sidebar, modals)
css/app.css         dark theme
js/state.js         shared state, undo stack, font table, RTL helpers
js/viewer.js        pdf.js rendering: canvas, text layer, custom form layer,
                    thumbnails, outline, search, zoom
js/tools.js         annotation engine: SVG overlay + DOM edit layer, pointer
                    interactions, signature pad, editing existing text/images
js/pages.js         page ops (rotate/reorder/delete/blank/merge)
js/save.js          pdf-lib export: draws annotations in PDF user space,
                    fills forms, handles page rebuilds & rotation math
vendor/             pdfjs-dist 3.11.174, pdf-lib 1.17.1, @pdf-lib/fontkit,
                    Noto Sans Hebrew as base64 (all vendored, fully offline)
```

Annotations are stored in PDF user-space coordinates, so they stay glued to
page content across zoom and page rotation, and can be drawn 1:1 by pdf-lib
on export.

## Editing existing content

Both of these work the same way — *cover and replace*. The page background
colour is sampled, the original content is masked with a filled rectangle, and
an ordinary annotation is put on top, which you can then edit, move, resize and
undo like anything else you added yourself.

### Text (E)

The original glyphs live in the page content stream and are drawn with subsetted
embedded fonts, so they cannot be re-encoded reliably — typing a character the
subset does not contain would simply fail. So the **E** tool samples the
background and ink colours, masks the run, and puts an editable text box on top,
pre-filled with the original words at the original size.

### Images (I)

Pick the tool and every image on the page is outlined; click one to lift it.
Images are found by replaying the page's graphics operators, so the outline is
the image's true placement. The pixels are taken from the decoded image at its
own resolution — not screengrabbed from the zoomed page — falling back to a crop
of the rendered page for image types that cannot be decoded directly (1-bit
stencil masks, for instance). The mask stays where the image was, so dragging
the copy leaves clean background behind. Images placed with a rotation or skew
are lifted upright, and the app says so when that happens.

What that means in practice:

- **The original content is still in the file, underneath the mask.** It is
  hidden from view, but text extraction and search will still find replaced
  text. This is *not* redaction — do not use it to remove sensitive content.
- Replacement text is drawn in the toolbar font, not the document's original
  typeface, so it will not match surrounding text exactly.
- Masking assumes a flat background. Content over a gradient, a photo or a
  texture will show a visible patch.
- Re-editing a line you already replaced picks up only the replacement: runs
  hidden beneath later-drawn ones are ignored.

### Right-to-left text

**Hebrew is supported. Arabic is not** — the only bundled Unicode face is Noto
Sans Hebrew, so Arabic exports as blank glyphs, and pdf-lib does no contextual
shaping, so even with an Arabic face the letters would come out isolated and
unjoined. Hebrew runs are drawn with the bundled font (subset into the saved
file); Latin, digits and punctuation in the same line keep the standard font,
which encodes far more than ASCII — curly quotes, dashes, the ellipsis and the
euro sign all come through as themselves.

PDF stores glyphs in painting order, so RTL text may be held in the file either
logically or visually depending on the producer, and pdf.js returns whichever it
finds. The editor guesses using Hebrew final forms (ך ם ן ף ץ occur only at the
end of a word), measured across the whole page, and flips the run to logical
order for editing when it looks reversed. If a line still comes up backwards,
just retype it — what you type is always stored correctly. Reordering for output
is a simplified bidi: segments are reversed (with paired brackets mirrored, and
numbers and Latin words kept upright inside them), which handles mixed
Hebrew/Latin/numbers, but is not the full Unicode bidirectional algorithm.

## Known limits (roadmap)

- Text boxes can use Helvetica, Times or Courier (the PDF standard 14, WinAnsi);
  non-Latin characters are still substituted on save
- No OCR, PDF↔Office conversion, or true content redaction (PDF Expert features
  that need heavy native/wasm machinery)
- No password-protected files
- No Arabic, Persian or Urdu: needs an Arabic face plus contextual shaping
