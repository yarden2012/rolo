// Wires DOM controls to the state/clips/player/timeline/overlays/exporter
// modules and boots the app once the page has loaded.
(function () {
  const uploadInput = document.getElementById('upload-input');
  const dropZone = document.getElementById('drop-zone');
  const canvas = document.getElementById('preview-canvas');
  const stageVideo = document.getElementById('stage-video');
  const playPauseBtn = document.getElementById('play-pause-btn');
  const stopBtn = document.getElementById('stop-btn');
  const zoomRange = document.getElementById('zoom-range');
  const exportBtn = document.getElementById('export-btn');

  const noSelectionMsg = document.getElementById('no-selection-msg');
  const clipProps = document.getElementById('clip-props');
  const clipNameEl = document.getElementById('clip-name');
  const clipDurationEl = document.getElementById('clip-duration');
  const clipVolume = document.getElementById('clip-volume');
  const clipMuted = document.getElementById('clip-muted');
  const clipSpeed = document.getElementById('clip-speed');
  const splitBtn = document.getElementById('split-btn');
  const duplicateBtn = document.getElementById('duplicate-btn');
  const deleteBtn = document.getElementById('delete-btn');

  const clipFilter = document.getElementById('clip-filter');
  const clipBrightness = document.getElementById('clip-brightness');
  const clipContrast = document.getElementById('clip-contrast');
  const clipSaturation = document.getElementById('clip-saturation');
  const clipFadeIn = document.getElementById('clip-fade-in');
  const clipFadeOut = document.getElementById('clip-fade-out');
  const resetEffectsBtn = document.getElementById('reset-effects-btn');
  const brightnessVal = document.getElementById('clip-brightness-val');
  const contrastVal = document.getElementById('clip-contrast-val');
  const saturationVal = document.getElementById('clip-saturation-val');

  const overlayForm = document.getElementById('overlay-form');
  const overlayList = document.getElementById('overlay-list');
  const overlayText = document.getElementById('overlay-text');
  const overlayStart = document.getElementById('overlay-start');
  const overlayEnd = document.getElementById('overlay-end');
  const overlayX = document.getElementById('overlay-x');
  const overlayY = document.getElementById('overlay-y');
  const overlaySize = document.getElementById('overlay-size');
  const overlayColor = document.getElementById('overlay-color');

  function refreshAll() {
    VE.timeline.render();
    renderClipProps();
    renderOverlayList();
  }

  // --- Upload -----------------------------------------------------------
  uploadInput.addEventListener('change', (e) => {
    VE.clips.addFiles(e.target.files, refreshAll);
    uploadInput.value = '';
  });

  ['dragenter', 'dragover'].forEach((evt) => {
    dropZone.addEventListener(evt, (e) => {
      e.preventDefault();
      dropZone.classList.add('drop-zone-active');
    });
  });
  ['dragleave', 'drop'].forEach((evt) => {
    dropZone.addEventListener(evt, (e) => {
      e.preventDefault();
      dropZone.classList.remove('drop-zone-active');
    });
  });
  dropZone.addEventListener('drop', (e) => {
    if (e.dataTransfer && e.dataTransfer.files.length) {
      VE.clips.addFiles(e.dataTransfer.files, refreshAll);
    }
  });

  // --- Player wiring ------------------------------------------------------
  VE.player.init(canvas, stageVideo);
  VE.player.onFrame((t) => VE.timeline.updatePlayheadUI(t));
  VE.player.onEnded(() => {
    if (!VE.state.exporting) setPlayIcon(false);
  });

  function setPlayIcon(isPlaying) {
    playPauseBtn.textContent = isPlaying ? '❚❚' : '▶';
  }

  async function togglePlay() {
    if (VE.state.exporting) return;
    if (VE.state.isPlaying) {
      VE.player.pause();
      setPlayIcon(false);
    } else {
      setPlayIcon(true);
      await VE.player.play();
      setPlayIcon(VE.state.isPlaying);
    }
  }

  playPauseBtn.addEventListener('click', togglePlay);
  stopBtn.addEventListener('click', () => {
    VE.player.stop();
    setPlayIcon(false);
    VE.timeline.updatePlayheadUI(0);
  });

  zoomRange.addEventListener('input', () => {
    VE.state.pxPerSecond = Number(zoomRange.value);
    VE.timeline.render();
  });

  // --- Timeline handlers ----------------------------------------------------
  VE.timeline.setHandlers({
    selectClip(id) {
      VE.state.selectedClipId = id;
      refreshAll();
    },
    structureChanged() {
      const total = VE.player.totalDuration();
      if (VE.state.playhead > total) VE.state.playhead = total;
      VE.player.seekTo(VE.state.playhead);
      refreshAll();
    },
    async scrub(globalTime) {
      if (VE.state.isPlaying) {
        VE.player.pause();
        setPlayIcon(false);
      }
      await VE.player.seekTo(globalTime);
      VE.timeline.updatePlayheadUI(VE.state.playhead);
    },
  });

  // --- Clip properties panel -------------------------------------------------
  function renderClipProps() {
    const clip = VE.clips.getClipById(VE.state.selectedClipId);
    if (!clip) {
      noSelectionMsg.classList.remove('hidden');
      clipProps.classList.add('hidden');
      return;
    }
    noSelectionMsg.classList.add('hidden');
    clipProps.classList.remove('hidden');
    clipNameEl.textContent = clip.name;
    clipDurationEl.textContent = VE.utils.formatTime(VE.clips.clipDuration(clip));
    clipVolume.value = clip.volume;
    clipMuted.checked = clip.muted;
    clipSpeed.value = String(clip.speed);
    clipFilter.value = clip.filterPreset;
    clipBrightness.value = clip.brightness;
    clipContrast.value = clip.contrast;
    clipSaturation.value = clip.saturation;
    clipFadeIn.value = clip.fadeIn;
    clipFadeOut.value = clip.fadeOut;
    renderEffectLabels(clip);
  }

  function renderEffectLabels(clip) {
    brightnessVal.textContent = Math.round(clip.brightness * 100) + '%';
    contrastVal.textContent = Math.round(clip.contrast * 100) + '%';
    saturationVal.textContent = Math.round(clip.saturation * 100) + '%';
  }

  // Applies an effect change to the selected clip and repaints immediately,
  // so adjustments are visible even while playback is paused.
  function updateEffect(mutate, { rerenderTimeline = false } = {}) {
    const clip = VE.clips.getClipById(VE.state.selectedClipId);
    if (!clip) return;
    mutate(clip);
    renderEffectLabels(clip);
    VE.player.drawFrame(VE.state.playhead);
    if (rerenderTimeline) VE.timeline.render();
  }

  clipFilter.addEventListener('change', () => {
    updateEffect((clip) => { clip.filterPreset = clipFilter.value; }, { rerenderTimeline: true });
  });
  clipBrightness.addEventListener('input', () => {
    updateEffect((clip) => { clip.brightness = Number(clipBrightness.value); });
  });
  clipContrast.addEventListener('input', () => {
    updateEffect((clip) => { clip.contrast = Number(clipContrast.value); });
  });
  clipSaturation.addEventListener('input', () => {
    updateEffect((clip) => { clip.saturation = Number(clipSaturation.value); });
  });
  clipBrightness.addEventListener('change', () => VE.timeline.render());
  clipContrast.addEventListener('change', () => VE.timeline.render());
  clipSaturation.addEventListener('change', () => VE.timeline.render());
  clipFadeIn.addEventListener('input', () => {
    updateEffect((clip) => { clip.fadeIn = Math.max(0, Number(clipFadeIn.value) || 0); }, { rerenderTimeline: true });
  });
  clipFadeOut.addEventListener('input', () => {
    updateEffect((clip) => { clip.fadeOut = Math.max(0, Number(clipFadeOut.value) || 0); }, { rerenderTimeline: true });
  });
  resetEffectsBtn.addEventListener('click', () => {
    const clip = VE.clips.getClipById(VE.state.selectedClipId);
    if (!clip) return;
    VE.clips.resetEffects(clip);
    refreshAll();
    VE.player.drawFrame(VE.state.playhead);
  });

  clipVolume.addEventListener('input', () => {
    const clip = VE.clips.getClipById(VE.state.selectedClipId);
    if (!clip) return;
    clip.volume = Number(clipVolume.value);
    VE.player.syncClipAudio(clip);
  });
  clipMuted.addEventListener('change', () => {
    const clip = VE.clips.getClipById(VE.state.selectedClipId);
    if (!clip) return;
    clip.muted = clipMuted.checked;
    VE.player.syncClipAudio(clip);
    VE.timeline.render();
  });
  clipSpeed.addEventListener('change', () => {
    const clip = VE.clips.getClipById(VE.state.selectedClipId);
    if (!clip) return;
    clip.speed = Number(clipSpeed.value);
    VE.player.syncClipAudio(clip);
    VE.player.seekTo(VE.state.playhead);
    refreshAll();
  });

  splitBtn.addEventListener('click', () => {
    if (VE.clips.splitAtGlobalTime(VE.state.playhead)) {
      refreshAll();
    } else {
      alert('Move the playhead inside the selected clip (not right at its edges) to split it.');
    }
  });
  duplicateBtn.addEventListener('click', () => {
    const copy = VE.clips.duplicateClip(VE.state.selectedClipId);
    if (copy) {
      VE.state.selectedClipId = copy.id;
      refreshAll();
    }
  });
  deleteBtn.addEventListener('click', () => {
    if (!VE.state.selectedClipId) return;
    VE.clips.removeClip(VE.state.selectedClipId);
    const total = VE.player.totalDuration();
    if (VE.state.playhead > total) VE.state.playhead = total;
    VE.player.seekTo(VE.state.playhead);
    refreshAll();
  });

  // --- Overlays --------------------------------------------------------------
  overlayForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const overlay = VE.overlays.add({
      text: overlayText.value,
      start: Number(overlayStart.value),
      end: Number(overlayEnd.value),
      x: Number(overlayX.value),
      y: Number(overlayY.value),
      fontSize: Number(overlaySize.value),
      color: overlayColor.value,
    });
    VE.state.selectedOverlayId = overlay.id;
    overlayForm.reset();
    overlayColor.value = '#ffffff';
    renderOverlayList();
  });

  function renderOverlayList() {
    overlayList.innerHTML = '';
    VE.state.overlays.forEach((overlay) => {
      const li = document.createElement('li');
      li.className = 'overlay-item' + (overlay.id === VE.state.selectedOverlayId ? ' selected' : '');
      const textSpan = document.createElement('span');
      textSpan.className = 'overlay-item-text';
      textSpan.textContent = overlay.text;
      const timeSpan = document.createElement('span');
      timeSpan.className = 'overlay-item-time';
      timeSpan.textContent = `${VE.utils.formatTime(overlay.start)}–${VE.utils.formatTime(overlay.end)}`;
      const delBtn = document.createElement('button');
      delBtn.className = 'overlay-item-del';
      delBtn.textContent = '✕';
      delBtn.title = 'Delete overlay';
      delBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        VE.overlays.remove(overlay.id);
        renderOverlayList();
        VE.player.drawFrame(VE.state.playhead);
      });
      li.addEventListener('click', () => {
        VE.state.selectedOverlayId = overlay.id;
        renderOverlayList();
      });
      li.appendChild(textSpan);
      li.appendChild(timeSpan);
      li.appendChild(delBtn);
      overlayList.appendChild(li);
    });
  }

  // --- Export -----------------------------------------------------------------
  exportBtn.addEventListener('click', () => {
    setPlayIcon(true);
    VE.exporter.run().finally(() => setPlayIcon(false));
  });

  // --- Keyboard shortcuts ---------------------------------------------------
  document.addEventListener('keydown', (e) => {
    if (VE.state.exporting) return;
    const tag = (e.target.tagName || '').toLowerCase();
    if (tag === 'input' || tag === 'select' || tag === 'textarea') return;
    if (e.code === 'Space') {
      e.preventDefault();
      togglePlay();
    } else if (e.key === 's' || e.key === 'S') {
      splitBtn.click();
    } else if (e.key === 'Delete' || e.key === 'Backspace') {
      if (VE.state.selectedClipId) deleteBtn.click();
    }
  });

  // --- Initial render -----------------------------------------------------
  refreshAll();
})();
