// Shared helpers used across the editor modules.
window.VE = window.VE || {};

VE.utils = {
  uid() {
    return Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
  },

  clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
  },

  // Formats seconds as M:SS.d (or H:MM:SS.d for long clips).
  formatTime(seconds) {
    if (!isFinite(seconds) || seconds < 0) seconds = 0;
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    const sStr = s.toFixed(1).padStart(4, '0');
    if (h > 0) {
      return `${h}:${String(m).padStart(2, '0')}:${sStr}`;
    }
    return `${m}:${sStr}`;
  },

  // Some blob/webm sources report Infinity for duration until seeked once.
  // This resolves the real duration and leaves the video paused at time 0.
  resolveVideoDuration(video) {
    return new Promise((resolve) => {
      if (isFinite(video.duration) && video.duration > 0) {
        resolve(video.duration);
        return;
      }
      const onTimeUpdate = () => {
        video.removeEventListener('timeupdate', onTimeUpdate);
        const dur = video.duration;
        video.currentTime = 0;
        resolve(isFinite(dur) ? dur : 0);
      };
      video.addEventListener('timeupdate', onTimeUpdate);
      video.currentTime = Number.MAX_SAFE_INTEGER > 1e7 ? 1e7 : video.duration;
    });
  },

  // Computes a "contain" rectangle fitting srcW/srcH inside dstW/dstH, centered.
  containRect(srcW, srcH, dstW, dstH) {
    if (!srcW || !srcH) return { x: 0, y: 0, w: dstW, h: dstH };
    const scale = Math.min(dstW / srcW, dstH / srcH);
    const w = srcW * scale;
    const h = srcH * scale;
    return { x: (dstW - w) / 2, y: (dstH - h) / 2, w, h };
  },
};
