// Playback engine: drives a single hidden <video> ("stage") through the
// sequence of clips, draws each frame (plus overlays) onto the preview
// canvas, and exposes the same render pipeline for export.
window.VE = window.VE || {};

VE.player = (() => {
  const { clamp } = VE.utils;

  let canvas, ctx, stageVideo;
  let audioCtx = null, sourceNode = null, gainNode = null, mediaStreamDest = null;
  let currentClipId = null;
  let rafId = null;
  const endedCallbacks = new Set();
  const frameCallbacks = new Set();
  let pendingLoadToken = 0;

  function init(canvasEl, videoEl) {
    canvas = canvasEl;
    ctx = canvas.getContext('2d');
    stageVideo = videoEl;
    stageVideo.crossOrigin = 'anonymous';
  }

  function ensureAudioGraph() {
    if (audioCtx) return;
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    audioCtx = new AudioCtx();
    sourceNode = audioCtx.createMediaElementSource(stageVideo);
    gainNode = audioCtx.createGain();
    mediaStreamDest = audioCtx.createMediaStreamDestination();
    sourceNode.connect(gainNode);
    gainNode.connect(audioCtx.destination);
    gainNode.connect(mediaStreamDest);
  }

  function getAudioDestinationStream() {
    ensureAudioGraph();
    return mediaStreamDest.stream;
  }

  function totalDuration() {
    return VE.clips.computeLayout().totalDuration;
  }

  function findItemAt(globalTime) {
    const { items, totalDuration: total } = VE.clips.computeLayout();
    if (items.length === 0) return null;
    const t = clamp(globalTime, 0, Math.max(total - 0.0001, 0));
    for (const item of items) {
      if (t >= item.start && t < item.start + item.duration) return item;
    }
    return items[items.length - 1];
  }

  // Loads (if needed) the given clip into the stage video and seeks to
  // localTime (in source seconds). Resolves once a frame is ready to draw.
  function loadClipForItem(item, localTime) {
    const token = ++pendingLoadToken;
    const clip = item.clip;
    return new Promise((resolve) => {
      const finishSeek = () => {
        if (token !== pendingLoadToken) return resolve(false);
        stageVideo.removeEventListener('seeked', finishSeek);
        currentClipId = clip.id;
        applyClipAudioSettings(clip);
        resolve(true);
      };

      const doSeek = () => {
        stageVideo.playbackRate = clip.speed || 1;
        const target = clamp(localTime, 0, Math.max(clip.srcDuration - 0.01, 0));
        // Some browsers never fire 'seeked' when the target time is
        // effectively unchanged (e.g. re-seeking to the same spot);
        // short-circuit instead of waiting on an event that won't come.
        if (Math.abs(stageVideo.currentTime - target) < 0.005 && !stageVideo.seeking) {
          currentClipId = clip.id;
          applyClipAudioSettings(clip);
          resolve(true);
          return;
        }
        stageVideo.addEventListener('seeked', finishSeek, { once: true });
        stageVideo.currentTime = target;
      };

      if (currentClipId === clip.id && stageVideo.src === clip.url) {
        doSeek();
        return;
      }

      const onError = () => {
        if (token !== pendingLoadToken) return resolve(false);
        console.warn('Failed to load video for clip', clip.name);
        stageVideo.removeEventListener('loadedmetadata', onLoaded);
        stageVideo.removeEventListener('seeked', finishSeek);
        resolve(false);
      };

      const onLoaded = () => {
        if (token !== pendingLoadToken) return resolve(false);
        stageVideo.removeEventListener('loadedmetadata', onLoaded);
        stageVideo.removeEventListener('error', onError);
        doSeek();
      };
      stageVideo.addEventListener('loadedmetadata', onLoaded, { once: true });
      stageVideo.addEventListener('error', onError, { once: true });
      stageVideo.src = clip.url;
      stageVideo.load();
    });
  }

  function applyClipAudioSettings(clip) {
    if (!gainNode) return;
    gainNode.gain.value = clip.muted ? 0 : clamp(clip.volume, 0, 1);
  }

  function drawFrame(globalTime) {
    if (!canvas) return;
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    if (stageVideo.readyState >= 2 && stageVideo.videoWidth) {
      const rect = VE.utils.containRect(stageVideo.videoWidth, stageVideo.videoHeight, canvas.width, canvas.height);
      ctx.drawImage(stageVideo, rect.x, rect.y, rect.w, rect.h);
    }
    VE.overlays.render(ctx, canvas.width, canvas.height, globalTime);
  }

  async function seekTo(globalTime) {
    const total = totalDuration();
    const target = clamp(globalTime, 0, total);
    VE.state.playhead = target;
    const item = findItemAt(target);
    if (!item) {
      drawFrame(0);
      return;
    }
    const localTime = item.clip.inPoint + (target - item.start) * item.clip.speed;
    await loadClipForItem(item, localTime);
    drawFrame(target);
  }

  async function play() {
    ensureAudioGraph();
    if (audioCtx.state === 'suspended') await audioCtx.resume();
    if (VE.state.clips.length === 0) return;
    if (VE.state.playhead >= totalDuration() - 0.01) {
      VE.state.playhead = 0;
    }
    const item = findItemAt(VE.state.playhead);
    if (item) {
      const localTime = item.clip.inPoint + (VE.state.playhead - item.start) * item.clip.speed;
      await loadClipForItem(item, localTime);
    }
    VE.state.isPlaying = true;
    try { await stageVideo.play(); } catch (e) { /* autoplay restrictions, ignored */ }
    tick();
  }

  function pause() {
    VE.state.isPlaying = false;
    stageVideo.pause();
    if (rafId) cancelAnimationFrame(rafId);
    rafId = null;
  }

  function stop() {
    pause();
    seekTo(0);
  }

  async function tick() {
    if (!VE.state.isPlaying) return;
    const item = findItemAt(VE.state.playhead);
    if (!item) { pause(); return; }
    const clip = item.clip;

    if (stageVideo.currentTime >= clip.outPoint - 0.02) {
      const { items, totalDuration: total } = VE.clips.computeLayout();
      const idx = items.findIndex((it) => it.clip.id === clip.id);
      const next = items[idx + 1];
      if (next) {
        await loadClipForItem(next, next.clip.inPoint);
        VE.state.playhead = next.start;
        if (VE.state.isPlaying) {
          try { await stageVideo.play(); } catch (e) { /* ignored */ }
        }
      } else {
        VE.state.playhead = total;
        drawFrame(total);
        pause();
        endedCallbacks.forEach((cb) => cb());
        return;
      }
    } else {
      VE.state.playhead = item.start + (stageVideo.currentTime - clip.inPoint) / clip.speed;
    }

    drawFrame(VE.state.playhead);
    frameCallbacks.forEach((cb) => cb(VE.state.playhead));
    if (VE.state.isPlaying) rafId = requestAnimationFrame(tick);
  }

  // Registers a listener; returns an unsubscribe function. Multiple
  // independent consumers (UI, exporter) can each hold their own listener
  // without clobbering one another.
  function onEnded(cb) {
    endedCallbacks.add(cb);
    return () => endedCallbacks.delete(cb);
  }

  function onFrame(cb) {
    frameCallbacks.add(cb);
    return () => frameCallbacks.delete(cb);
  }

  // Re-applies volume/mute/speed for the given clip if it is the one
  // currently loaded on the stage (used for live edits during playback).
  function syncClipAudio(clip) {
    if (clip.id !== currentClipId) return;
    applyClipAudioSettings(clip);
    stageVideo.playbackRate = clip.speed || 1;
  }

  return {
    init,
    ensureAudioGraph,
    getAudioDestinationStream,
    totalDuration,
    seekTo,
    play,
    pause,
    stop,
    onEnded,
    onFrame,
    syncClipAudio,
    drawFrame,
    get canvas() { return canvas; },
    get currentClipId() { return currentClipId; },
  };
})();
