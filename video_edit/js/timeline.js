// Timeline UI: renders clip blocks + ruler, and handles drag-to-reorder,
// drag-to-trim, and click/drag-to-seek interactions.
window.VE = window.VE || {};

VE.timeline = (() => {
  const { clamp, formatTime } = VE.utils;

  const trackEl = document.getElementById('timeline-track');
  const rulerEl = document.getElementById('timeline-ruler');
  const scrollEl = document.getElementById('timeline-scroll');
  const playheadEl = document.getElementById('timeline-playhead');
  const currentTimeEl = document.getElementById('current-time');
  const totalTimeEl = document.getElementById('total-time');
  const emptyStateEl = document.getElementById('empty-state');

  let onSelectClip = () => {};
  let onStructureChanged = () => {};
  let onScrub = () => {};

  function setHandlers({ selectClip, structureChanged, scrub }) {
    if (selectClip) onSelectClip = selectClip;
    if (structureChanged) onStructureChanged = structureChanged;
    if (scrub) onScrub = scrub;
  }

  function pxPerSecond() {
    return VE.state.pxPerSecond;
  }

  function render() {
    const { items, totalDuration } = VE.clips.computeLayout();
    const px = pxPerSecond();
    const widthPx = Math.max(totalDuration * px, scrollEl.clientWidth);

    trackEl.style.width = widthPx + 'px';
    trackEl.querySelectorAll('.clip-block').forEach((el) => el.remove());

    emptyStateEl.classList.toggle('hidden', items.length > 0);

    items.forEach((item) => {
      const block = buildClipBlock(item, px);
      trackEl.appendChild(block);
    });

    renderRuler(totalDuration, px, widthPx);
    updatePlayheadUI(VE.state.playhead);
    totalTimeEl.textContent = formatTime(totalDuration);
  }

  function buildClipBlock(item, px) {
    const clip = item.clip;
    const block = document.createElement('div');
    block.className = 'clip-block' + (clip.id === VE.state.selectedClipId ? ' selected' : '');
    block.dataset.id = clip.id;
    block.style.width = Math.max(item.duration * px, 4) + 'px';
    if (clip.thumb) {
      block.style.backgroundImage = `url(${clip.thumb})`;
    }

    const body = document.createElement('div');
    body.className = 'clip-body';
    const label = document.createElement('div');
    label.className = 'clip-label';
    label.textContent = `${clip.name} · ${formatTime(item.duration)}`;
    body.appendChild(label);
    block.appendChild(body);

    if (clip.muted) {
      const badge = document.createElement('div');
      badge.className = 'clip-mute-badge';
      badge.textContent = '🔇';
      block.appendChild(badge);
    }
    if (clip.speed !== 1) {
      const badge = document.createElement('div');
      badge.className = 'clip-speed-badge';
      badge.textContent = clip.speed + '×';
      block.appendChild(badge);
    }

    const leftHandle = document.createElement('div');
    leftHandle.className = 'clip-trim-handle left';
    const rightHandle = document.createElement('div');
    rightHandle.className = 'clip-trim-handle right';
    block.appendChild(leftHandle);
    block.appendChild(rightHandle);

    leftHandle.addEventListener('mousedown', (e) => startTrim(e, clip, 'left'));
    rightHandle.addEventListener('mousedown', (e) => startTrim(e, clip, 'right'));
    body.addEventListener('mousedown', (e) => startReorderOrSelect(e, clip));

    return block;
  }

  function renderRuler(totalDuration, px, widthPx) {
    rulerEl.innerHTML = '';
    rulerEl.style.width = widthPx + 'px';
    const step = px < 40 ? 10 : px < 90 ? 5 : px < 160 ? 2 : 1;
    const maxT = Math.max(totalDuration, scrollEl.clientWidth / px);
    for (let t = 0; t <= maxT; t += step) {
      const tick = document.createElement('div');
      tick.className = 'tick';
      tick.style.left = (t * px) + 'px';
      tick.textContent = formatTime(t);
      rulerEl.appendChild(tick);
    }
  }

  function rulerClickHandler(e) {
    const rect = rulerEl.getBoundingClientRect();
    const x = e.clientX - rect.left + scrollEl.scrollLeft;
    onScrub(x / pxPerSecond());
  }

  function updatePlayheadUI(globalTime) {
    const x = globalTime * pxPerSecond();
    playheadEl.style.left = x + 'px';
    currentTimeEl.textContent = formatTime(globalTime);
  }

  // --- Trim handle dragging -------------------------------------------------
  function startTrim(e, clip, edge) {
    e.preventDefault();
    e.stopPropagation();
    const startX = e.clientX;
    const startIn = clip.inPoint;
    const startOut = clip.outPoint;
    const px = pxPerSecond();
    const handleEl = e.target;
    handleEl.classList.add('active');

    function onMove(ev) {
      const deltaSec = ((ev.clientX - startX) / px) * clip.speed;
      if (edge === 'left') {
        VE.clips.setTrim(clip, startIn + deltaSec, clip.outPoint);
      } else {
        VE.clips.setTrim(clip, clip.inPoint, startOut + deltaSec);
      }
      render();
    }
    function onUp() {
      handleEl.classList.remove('active');
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      onStructureChanged();
    }
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  }

  // --- Reorder-by-drag / click-to-select ------------------------------------
  function startReorderOrSelect(e, clip) {
    if (e.button !== 0) return;
    const startX = e.clientX;
    let dragging = false;

    // render() rebuilds all clip-block DOM nodes, so the dragged block's
    // element must be re-looked-up (by data-id) after every re-render
    // rather than captured once, or the 'dragging' class would end up on
    // a detached node.
    function currentBlockEl() {
      return trackEl.querySelector(`.clip-block[data-id="${clip.id}"]`);
    }

    function onMove(ev) {
      if (!dragging && Math.abs(ev.clientX - startX) > 5) {
        dragging = true;
        const el = currentBlockEl();
        if (el) el.classList.add('dragging');
      }
      if (!dragging) return;
      const scrollRect = scrollEl.getBoundingClientRect();
      const x = ev.clientX - scrollRect.left + scrollEl.scrollLeft;
      const targetIndex = indexAtX(x, clip.id);
      const currentIndex = VE.clips.getIndexById(clip.id);
      if (targetIndex !== currentIndex && targetIndex !== null) {
        VE.clips.moveClipToIndex(clip.id, targetIndex);
        render();
        const el = currentBlockEl();
        if (el) el.classList.add('dragging');
      }
    }
    function onUp() {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      const el = currentBlockEl();
      if (el) el.classList.remove('dragging');
      if (!dragging) {
        onSelectClip(clip.id);
      } else {
        onStructureChanged();
      }
    }
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  }

  function indexAtX(x, excludeId) {
    const { items } = VE.clips.computeLayout();
    const px = pxPerSecond();
    let idx = 0;
    for (const item of items) {
      if (item.clip.id === excludeId) { idx++; continue; }
      const startPx = item.start * px;
      const midPx = startPx + (item.duration * px) / 2;
      if (x > midPx) idx++;
      else break;
    }
    // Adjust for the fact the dragged item itself still occupies a slot.
    const currentIndex = VE.clips.getIndexById(excludeId);
    if (idx > currentIndex) idx--;
    return idx;
  }

  rulerEl.addEventListener('click', rulerClickHandler);

  // --- Seeking by dragging on the empty scroll area -------------------------
  scrollEl.addEventListener('mousedown', (e) => {
    if (e.target.closest('.clip-block')) return;
    scrub(e);
    function onMove(ev) { scrub(ev); }
    function onUp() {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    }
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  });

  function scrub(e) {
    const rect = scrollEl.getBoundingClientRect();
    const x = e.clientX - rect.left + scrollEl.scrollLeft;
    onScrub(Math.max(0, x / pxPerSecond()));
  }

  return { render, updatePlayheadUI, setHandlers };
})();
