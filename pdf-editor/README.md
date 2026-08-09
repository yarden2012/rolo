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
- Text boxes (double-click to edit), sticky notes (real PDF `/Text` annotations)
- Insert images (PNG/JPEG)
- Signatures: draw once, stored locally, reuse across documents

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
| Ctrl+Z / Ctrl+Shift+Z | Undo / Redo |
| Ctrl+= / Ctrl+- / Ctrl+wheel | Zoom |
| V H U P T N | Select, Highlight, Underline, Pen, Text, Note |
| Delete | Remove selected annotation |
| Esc | Deselect / cancel placement |

## Architecture

```
electron/main.cjs   app window, native dialogs, argv/single-instance opening
electron/preload.cjs  contextBridge: window.native API for the renderer
index.html          UI shell (toolbar, sidebar, modals)
css/app.css         dark theme
js/state.js         shared state + undo stack
js/viewer.js        pdf.js rendering: canvas, text layer, custom form layer,
                    thumbnails, outline, search, zoom
js/tools.js         annotation engine: SVG overlay + DOM edit layer,
                    pointer interactions, signature pad
js/pages.js         page ops (rotate/reorder/delete/blank/merge)
js/save.js          pdf-lib export: draws annotations in PDF user space,
                    fills forms, handles page rebuilds & rotation math
vendor/             pdfjs-dist 3.11.174 + pdf-lib 1.17.1 (vendored, offline)
```

Annotations are stored in PDF user-space coordinates, so they stay glued to
page content across zoom and page rotation, and can be drawn 1:1 by pdf-lib
on export.

## Known limits (roadmap)

- Text box font is Helvetica (WinAnsi); non-Latin characters are substituted on save
- No OCR, PDF↔Office conversion, or true content redaction (PDF Expert features
  that need heavy native/wasm machinery)
- No editing of existing PDF text, no password-protected files
