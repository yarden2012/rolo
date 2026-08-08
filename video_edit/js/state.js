// Central application state, shared by all modules via VE.state.
window.VE = window.VE || {};

VE.MIN_CLIP_DURATION = 0.15; // seconds, in source-time

VE.state = {
  clips: [],           // { id, file, url, name, srcDuration, inPoint, outPoint, volume, muted, speed, thumb }
  overlays: [],         // { id, text, start, end, x, y, fontSize, color }
  selectedClipId: null,
  selectedOverlayId: null,
  playhead: 0,           // seconds, position on the overall timeline
  isPlaying: false,
  exporting: false,
  pxPerSecond: 80,
};
