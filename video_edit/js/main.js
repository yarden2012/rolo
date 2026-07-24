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
  }

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
