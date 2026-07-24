// Exports the composed timeline to a downloadable .webm file by recording
// the live preview canvas + mixed audio graph with MediaRecorder.
window.VE = window.VE || {};

VE.exporter = (() => {
  const { formatTime } = VE.utils;

  const overlayEl = document.getElementById('export-overlay');
  const progressBar = document.getElementById('export-progress-bar');
  const progressLabel = document.getElementById('export-progress-label');
  const cancelBtn = document.getElementById('cancel-export-btn');

  let recorder = null;
  let progressRaf = null;
  let active = false;
  let cancelled = false;
  let unsubscribeEnded = null;

  function pickMimeType() {
    const candidates = [
      'video/webm;codecs=vp9,opus',
      'video/webm;codecs=vp8,opus',
      'video/webm',
    ];
    return candidates.find((type) => window.MediaRecorder && MediaRecorder.isTypeSupported(type)) || '';
  }

  function updateProgress() {
    const total = VE.player.totalDuration();
    const t = Math.min(VE.state.playhead, total);
    const pct = total > 0 ? (t / total) * 100 : 0;
    progressBar.style.width = pct + '%';
    progressLabel.textContent = `${formatTime(t)} / ${formatTime(total)}`;
    if (active) progressRaf = requestAnimationFrame(updateProgress);
  }

  async function run() {
    if (VE.state.clips.length === 0) {
      alert('Add at least one video clip before exporting.');
      return;
    }
    if (!window.MediaRecorder) {
      alert('Your browser does not support MediaRecorder, so export is unavailable.');
      return;
    }
    const mimeType = pickMimeType();

    VE.state.exporting = true;
    active = true;
    cancelled = false;
    overlayEl.classList.remove('hidden');
    progressBar.style.width = '0%';

    VE.player.ensureAudioGraph();
    const canvasStream = VE.player.canvas.captureStream(30);
    const audioStream = VE.player.getAudioDestinationStream();
    const combined = new MediaStream([
      ...canvasStream.getVideoTracks(),
      ...audioStream.getAudioTracks(),
    ]);

    const chunks = [];
    recorder = new MediaRecorder(combined, mimeType ? { mimeType, videoBitsPerSecond: 6_000_000 } : undefined);
    recorder.ondataavailable = (e) => { if (e.data && e.data.size > 0) chunks.push(e.data); };

    const finished = new Promise((resolve) => {
      recorder.onstop = () => {
        if (!cancelled && chunks.length > 0) {
          const blob = new Blob(chunks, { type: mimeType || 'video/webm' });
          downloadBlob(blob);
        }
        resolve();
      };
    });

    await VE.player.seekTo(0);
    recorder.start(250);
    progressRaf = requestAnimationFrame(updateProgress);

    unsubscribeEnded = VE.player.onEnded(() => {
      finalize();
    });
    await VE.player.play();

    await finished;
  }

  function finalize() {
    if (!active) return;
    active = false;
    if (recorder && recorder.state !== 'inactive') recorder.stop();
    cancelAnimationFrame(progressRaf);
    if (unsubscribeEnded) { unsubscribeEnded(); unsubscribeEnded = null; }
    VE.state.exporting = false;
    overlayEl.classList.add('hidden');
  }

  function cancel() {
    if (!active) return;
    cancelled = true;
    VE.player.pause();
    finalize();
  }

  function downloadBlob(blob) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    a.href = url;
    a.download = `rolo-export-${stamp}.webm`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
  }

  cancelBtn.addEventListener('click', cancel);

  return { run, cancel };
})();
