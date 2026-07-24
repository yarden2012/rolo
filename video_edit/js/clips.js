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
      thumb: null,
      ready: false,
    };
  }

  function captureThumbnail(probe, clip) {
    try {
      const canvas = document.createElement('canvas');
      const w = 160, h = 90;
      canvas.width = w;
      canvas.height = h;
      const ctx = canvas.getContext('2d');
      const rect = VE.utils.containRect(probe.videoWidth, probe.videoHeight, w, h);
      ctx.fillStyle = '#000';
      ctx.fillRect(0, 0, w, h);
      ctx.drawImage(probe, rect.x, rect.y, rect.w, rect.h);
      clip.thumb = canvas.toDataURL('image/jpeg', 0.7);
    } catch (e) {
      // Thumbnail generation is best-effort; ignore failures (e.g. tainted canvas).
    }
  }

  // Loads metadata (duration) and a thumbnail for a clip using a detached
  // <video> element, so it never interferes with the shared playback stage.
  function hydrateClip(clip, onUpdate) {
    const probe = document.createElement('video');
    probe.preload = 'auto';
    probe.muted = true;
    probe.playsInline = true;
    probe.src = clip.url;

    probe.addEventListener('loadedmetadata', async () => {
      // resolveVideoDuration may itself perform seeks (Infinity-duration
      // workaround), so it must fully settle before we register the
      // one-time 'seeked' listener used for the thumbnail capture below.
      const duration = await resolveVideoDuration(probe);
      clip.srcDuration = duration;
      clip.outPoint = duration;
      clip.ready = true;
      onUpdate();

      const seekTarget = Math.min(0.15, duration / 2 || 0);
      await new Promise((resolve) => {
        probe.addEventListener('seeked', resolve, { once: true });
        probe.currentTime = seekTarget;
      });
      captureThumbnail(probe, clip);
      onUpdate();
    }, { once: true });

    probe.addEventListener('error', () => {
      console.warn('Failed to load video metadata for', clip.name);
      onUpdate();
    }, { once: true });
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
