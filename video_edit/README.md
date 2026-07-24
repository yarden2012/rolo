# Rolo Video Editor

A simple, self-contained, client-side video editor that runs entirely in the
browser — no backend, build step, or install required. Everything (decoding,
editing, and exporting) happens locally using standard Web APIs.

## Running it

From this directory, start any static file server and open it in a modern
Chromium-based browser (recommended for best `MediaRecorder`/codec support):

```bash
cd video_edit
python3 -m http.server 8000
# then open http://localhost:8000 in your browser
```

Opening `index.html` directly via `file://` also works in most browsers.

## Features

- **Upload** one or more video files (drag & drop or the "Add Video" button).
  Common formats supported by your browser's built-in decoders — MP4/H.264,
  WebM, OGG, MOV (when H.264-encoded) — will play back and preview correctly.
- **Timeline** with a single sequential track:
  - Drag a clip to **reorder** it.
  - Drag a clip's left/right edge to **trim** its in/out points.
  - Select a clip and click **Split at Playhead** (or press `S`) to **cut**
    it into two clips at the current playhead position.
  - **Duplicate** or **Delete** the selected clip.
  - Click/drag on the ruler or empty timeline area to **scrub/seek**.
  - Zoom the timeline in/out for fine-grained trimming.
- **Per-clip properties**: volume, mute, and playback speed (0.5×–2×).
- **Text overlays**: add timed text overlays with position, size, and color,
  rendered live in the preview and baked into the export.
- **Export** the whole composed timeline to a downloadable `.webm` file
  (video + mixed audio), with a progress indicator and cancel option.

## Keyboard shortcuts

- `Space` — play / pause
- `S` — split the clip under the playhead
- `Delete` / `Backspace` — delete the selected clip

## How it works

There's no server-side transcoding. The editor plays clips back-to-back on a
single hidden `<video>` element, draws each frame (plus any active text
overlays) onto a `<canvas>`, and routes audio through the Web Audio API so
per-clip volume/mute can be mixed live. Export reuses that exact same
rendering pipeline: it records the canvas (`canvas.captureStream`) and the
mixed audio graph with `MediaRecorder` while the timeline plays through once,
then downloads the result.

## Known limitations

- Export format is WebM (VP8/VP9 + Opus) — the format browsers can encode to
  natively. There's no client-side MP4 encoding without a much heavier
  dependency like ffmpeg.wasm.
- Single track only (no picture-in-picture/overlapping video layers).
- No crossfade/transition effects between clips.
- Playback/export format support depends entirely on your browser's codecs.
