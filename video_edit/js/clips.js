// Clip model: creation from uploaded files, and structural operations
// (split, duplicate, delete, reorder, trim) on VE.state.clips.
window.VE = window.VE || {};

VE.clips = (() => {
  const { uid, clamp, resolveVideoDuration } = VE.utils;

  function createClipStub(file) {
    return {
      id: uid(),
      file,
      url: URL.createObjectURL(file),
      name: file.name,
      srcDuration: 0,
      inPoint: 0,
      outPoint: 0,
      volume: 1,
      muted: false,
      speed: 1,
      // Visual effects, applied identically in the preview and the export.
      brightness: 1,
      contrast: 1,
      saturation: 1,
      filterPreset: 'none',
      fadeIn: 0,
      fadeOut: 0,
      thumb: null,
      thumbs: [],   // [{ time, src }] filmstrip frames, in source-time order
      ready: false,
    };
  }

  // True when the clip differs from the "no effects applied" defaults.
  function hasEffects(clip) {
    return clip.brightness !== 1 || clip.contrast !== 1 || clip.saturation !== 1
      || clip.filterPreset !== 'none' || clip.fadeIn > 0 || clip.fadeOut > 0;
  }

  function resetEffects(clip) {
    clip.brightness = 1;
    clip.contrast = 1;
    clip.saturation = 1;
    clip.filterPreset = 'none';
    clip.fadeIn = 0;
    clip.fadeOut = 0;
  }

  // Filmstrip frames are captured at roughly the size they're displayed at
  // on the timeline, so tiling them looks sharp instead of upscaling one
  // small thumbnail across the whole clip block.
  const THUMB_W = 320;
  const THUMB_H = 180;

  function grabFrame(probe) {
    try {
      const canvas = document.createElement('canvas');
      canvas.width = THUMB_W;
      canvas.height = THUMB_H;
      const ctx = canvas.getContext('2d');
      const rect = VE.utils.containRect(probe.videoWidth, probe.videoHeight, THUMB_W, THUMB_H);
      ctx.fillStyle = '#000';
      ctx.fillRect(0, 0, THUMB_W, THUMB_H);
      ctx.drawImage(probe, rect.x, rect.y, rect.w, rect.h);
      return canvas.toDataURL('image/jpeg', 0.72);
    } catch (e) {
      // Frame capture is best-effort; ignore failures (e.g. tainted canvas).
      return null;
    }
  }

  // Seeks the probe element, resolving on 'seeked' but never hanging: a
  // decoder that refuses to seek would otherwise stall filmstrip capture.
  function seekProbe(probe, time) {
    return new Promise((resolve) => {
      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        probe.removeEventListener('seeked', finish);
        resolve();
      };
      const timer = setTimeout(finish, 3000);
      probe.addEventListener('seeked', finish, { once: true });
      probe.currentTime = time;
    });
  }

  async function captureFilmstrip(probe, clip, onUpdate) {
    const duration = clip.srcDuration;
    if (!duration) return;
    const count = Math.min(16, Math.max(4, Math.ceil(duration / 2)));
    const frames = [];
    for (let i = 0; i < count; i++) {
      const time = Math.min(((i + 0.5) * duration) / count, Math.max(duration - 0.05, 0));
      await seekProbe(probe, time);
      const src = grabFrame(probe);
      if (!src) break;
      frames.push({ time, src });
      // Publish progressively so the timeline sharpens up as frames land.
      clip.thumbs = frames.slice();
      if (!clip.thumb) clip.thumb = src;
      onUpdate();
    }
  }

  // Loads metadata (duration) and filmstrip frames for a clip using a
  // detached <video> element, so it never interferes with playback.
  function hydrateClip(clip, onUpdate) {
    const probe = document.createElement('video');
    probe.preload = 'auto';
    probe.muted = true;
    probe.playsInline = true;
    probe.src = clip.url;

    probe.addEventListener('loadedmetadata', async () => {
      // resolveVideoDuration may itself perform seeks (Infinity-duration
      // workaround), so it must fully settle before filmstrip capture
      // starts issuing its own seeks.
      const duration = await resolveVideoDuration(probe);
      clip.srcDuration = duration;
      clip.outPoint = duration;
      clip.ready = true;
      onUpdate();

      await captureFilmstrip(probe, clip, onUpdate);
    }, { once: true });

    probe.addEventListener('error', () => {
      console.warn('Failed to load video metadata for', clip.name);
      onUpdate();
    }, { once: true });
  }

  // Picks the captured frame closest to a given source timestamp.
  function nearestThumb(clip, srcTime) {
    const thumbs = clip.thumbs || [];
    if (thumbs.length === 0) return null;
    let best = thumbs[0];
    let bestDelta = Math.abs(best.time - srcTime);
    for (let i = 1; i < thumbs.length; i++) {
      const delta = Math.abs(thumbs[i].time - srcTime);
      if (delta < bestDelta) {
        best = thumbs[i];
        bestDelta = delta;
      }
    }
    return best;
  }

  function addFiles(fileList, onUpdate) {
    const files = Array.from(fileList).filter((f) => f.type.startsWith('video/'));
    if (files.length === 0 && fileList.length > 0) {
      alert('Please choose video files (mp4, webm, mov, ogg, ...).');
    }
    files.forEach((file) => {
      const clip = createClipStub(file);
      VE.state.clips.push(clip);
      hydrateClip(clip, onUpdate);
    });
    onUpdate();
  }

  function clipDuration(clip) {
    return Math.max(0, (clip.outPoint - clip.inPoint) / clip.speed);
  }

  // Returns { items: [{clip, start, duration}], totalDuration }
  function computeLayout() {
    let cursor = 0;
    const items = VE.state.clips.map((clip) => {
      const duration = clipDuration(clip);
      const item = { clip, start: cursor, duration };
      cursor += duration;
      return item;
    });
    return { items, totalDuration: cursor };
  }

  function getClipById(id) {
    return VE.state.clips.find((c) => c.id === id);
  }

  function getIndexById(id) {
    return VE.state.clips.findIndex((c) => c.id === id);
  }

  function removeClip(id) {
    const idx = getIndexById(id);
    if (idx === -1) return;
    const [clip] = VE.state.clips.splice(idx, 1);
    URL.revokeObjectURL(clip.url);
    if (VE.state.selectedClipId === id) VE.state.selectedClipId = null;
  }

  function duplicateClip(id) {
    const clip = getClipById(id);
    if (!clip) return null;
    // Each clip owns an independently-revocable object URL. Sharing the
    // source clip's URL would mean deleting the copy later revokes the
    // original's URL too (URL.createObjectURL results are reference-counted
    // per call, not per blob), breaking its playback.
    const copy = Object.assign({}, clip, { id: uid(), url: URL.createObjectURL(clip.file) });
    const idx = getIndexById(id);
    VE.state.clips.splice(idx + 1, 0, copy);
    return copy;
  }

  function moveClipToIndex(id, targetIndex) {
    const fromIndex = getIndexById(id);
    if (fromIndex === -1) return;
    const [clip] = VE.state.clips.splice(fromIndex, 1);
    const clamped = clamp(targetIndex, 0, VE.state.clips.length);
    VE.state.clips.splice(clamped, 0, clip);
  }

  // Splits the clip that contains globalTime into two clips at that point.
  // Returns true if a split happened.
  function splitAtGlobalTime(globalTime) {
    const { items } = computeLayout();
    const item = items.find((it) => globalTime > it.start && globalTime < it.start + it.duration);
    if (!item) return false;
    const clip = item.clip;
    const localTime = clip.inPoint + (globalTime - item.start) * clip.speed;
    const min = VE.MIN_CLIP_DURATION;
    if (localTime - clip.inPoint < min || clip.outPoint - localTime < min) return false;

    // Give the new half its own object URL — see the comment in
    // duplicateClip() for why sharing clip.url here would be unsafe.
    const secondHalf = Object.assign({}, clip, {
      id: uid(),
      inPoint: localTime,
      url: URL.createObjectURL(clip.file),
    });
    clip.outPoint = localTime;
    const idx = getIndexById(clip.id);
    VE.state.clips.splice(idx + 1, 0, secondHalf);
    return true;
  }

  function setTrim(clip, inPoint, outPoint) {
    const min = VE.MIN_CLIP_DURATION;
    clip.inPoint = clamp(inPoint, 0, clip.outPoint - min);
    clip.outPoint = clamp(outPoint, clip.inPoint + min, clip.srcDuration || outPoint);
  }

  return {
    addFiles,
    hasEffects,
    resetEffects,
    nearestThumb,
    clipDuration,
    computeLayout,
    getClipById,
    getIndexById,
    removeClip,
    duplicateClip,
    moveClipToIndex,
    splitAtGlobalTime,
    setTrim,
  };
})();
