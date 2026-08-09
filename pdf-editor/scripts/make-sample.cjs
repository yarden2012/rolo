// Generates sample.pdf (3 pages: cover, text, interactive form) using the
// vendored pdf-lib build. Run: node scripts/make-sample.cjs
const fs = require('fs');
const path = require('path');
// The vendored UMD build attaches to `self` when loaded outside CJS.
globalThis.self = globalThis;
const req = require(path.join(__dirname, '..', 'vendor', 'pdf-lib.min.js'));
const { PDFDocument, StandardFonts, rgb } = (req && req.PDFDocument) ? req : globalThis.PDFLib;

const LOREM = 'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.';

async function main() {
  const doc = await PDFDocument.create();
  const helv = await doc.embedFont(StandardFonts.Helvetica);
  const helvB = await doc.embedFont(StandardFonts.HelveticaBold);

  // ---- page 1: cover ----
  const p1 = doc.addPage([612, 792]);
  p1.drawRectangle({ x: 0, y: 632, width: 612, height: 160, color: rgb(0.23, 0.51, 0.96) });
  p1.drawText('PDF Editor', { x: 60, y: 700, size: 42, font: helvB, color: rgb(1, 1, 1) });
  p1.drawText('Sample document — open with ?demo=1', { x: 60, y: 668, size: 14, font: helv, color: rgb(0.9, 0.95, 1) });
  p1.drawText('Things to try', { x: 60, y: 560, size: 20, font: helvB, color: rgb(0.1, 0.1, 0.15) });
  const tips = [
    'Highlight, underline or strike out the text on page 2',
    'Draw with the pen tool, add shapes and arrows',
    'Add a text box, a sticky note, or your signature',
    'Fill the form on page 3, then hit Save',
    'Rotate / reorder / delete pages from the sidebar',
    'Search the document from the toolbar',
  ];
  tips.forEach((t, i) => {
    p1.drawCircle({ x: 68, y: 524 - i * 30, size: 3.4, color: rgb(0.23, 0.51, 0.96) });
    p1.drawText(t, { x: 82, y: 519 - i * 30, size: 13, font: helv, color: rgb(0.22, 0.24, 0.3) });
  });
  p1.drawRectangle({ x: 60, y: 120, width: 200, height: 120, borderColor: rgb(0.23, 0.51, 0.96), borderWidth: 2 });
  p1.drawEllipse({ x: 400, y: 180, xScale: 90, yScale: 60, borderColor: rgb(0.94, 0.27, 0.27), borderWidth: 2 });

  // ---- page 2: text ----
  const p2 = doc.addPage([612, 792]);
  p2.drawText('Chapter 1 — Annotate me', { x: 60, y: 720, size: 24, font: helvB, color: rgb(0.1, 0.1, 0.15) });
  for (let i = 0; i < 5; i++) {
    p2.drawText(LOREM, {
      x: 60, y: 660 - i * 118, size: 11.5, font: helv,
      color: rgb(0.2, 0.22, 0.27), maxWidth: 492, lineHeight: 16,
    });
  }

  // ---- page 3: form ----
  const p3 = doc.addPage([612, 792]);
  p3.drawText('Registration form', { x: 60, y: 720, size: 24, font: helvB, color: rgb(0.1, 0.1, 0.15) });
  p3.drawText('Fill me out, then press Save — values are written into the PDF.', { x: 60, y: 692, size: 12, font: helv, color: rgb(0.4, 0.42, 0.48) });

  const form = doc.getForm();

  p3.drawText('Full name', { x: 60, y: 640, size: 12, font: helv, color: rgb(0.25, 0.27, 0.32) });
  const nameF = form.createTextField('fullName');
  nameF.addToPage(p3, { x: 180, y: 626, width: 280, height: 26, borderColor: rgb(0.7, 0.73, 0.78), borderWidth: 1 });

  p3.drawText('Email', { x: 60, y: 596, size: 12, font: helv, color: rgb(0.25, 0.27, 0.32) });
  const mailF = form.createTextField('email');
  mailF.addToPage(p3, { x: 180, y: 582, width: 280, height: 26, borderColor: rgb(0.7, 0.73, 0.78), borderWidth: 1 });

  p3.drawText('Country', { x: 60, y: 552, size: 12, font: helv, color: rgb(0.25, 0.27, 0.32) });
  const dd = form.createDropdown('country');
  dd.addOptions(['Israel', 'United States', 'Germany', 'Japan', 'Other']);
  dd.addToPage(p3, { x: 180, y: 538, width: 200, height: 26, borderColor: rgb(0.7, 0.73, 0.78), borderWidth: 1 });

  p3.drawText('Subscribe to newsletter', { x: 84, y: 500, size: 12, font: helv, color: rgb(0.25, 0.27, 0.32) });
  const cb = form.createCheckBox('subscribe');
  cb.addToPage(p3, { x: 60, y: 496, width: 16, height: 16, borderColor: rgb(0.7, 0.73, 0.78), borderWidth: 1 });

  p3.drawText('Plan:', { x: 60, y: 460, size: 12, font: helv, color: rgb(0.25, 0.27, 0.32) });
  const radio = form.createRadioGroup('plan');
  radio.addOptionToPage('free', p3, { x: 110, y: 454, width: 16, height: 16 });
  p3.drawText('Free', { x: 132, y: 458, size: 12, font: helv, color: rgb(0.25, 0.27, 0.32) });
  radio.addOptionToPage('pro', p3, { x: 190, y: 454, width: 16, height: 16 });
  p3.drawText('Pro', { x: 212, y: 458, size: 12, font: helv, color: rgb(0.25, 0.27, 0.32) });

  const bytes = await doc.save();
  const outPath = path.join(__dirname, '..', 'sample.pdf');
  fs.writeFileSync(outPath, bytes);
  console.log(`Wrote ${outPath} (${bytes.length} bytes, ${doc.getPageCount()} pages)`);
}

main().catch((e) => { console.error(e); process.exit(1); });
