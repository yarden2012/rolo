// Text overlay model + canvas rendering.
window.VE = window.VE || {};

VE.overlays = (() => {
  const { uid, clamp } = VE.utils;

  function add({ text, start, end, x, y, fontSize, color }) {
    const overlay = {
      id: uid(),
      text: text || 'Text',
      start: Math.max(0, start),
      end: Math.max(start + 0.1, end),
      x: clamp(x, 0, 100),
      y: clamp(y, 0, 100),
      fontSize: fontSize || 36,
      color: color || '#ffffff',
    };
    VE.state.overlays.push(overlay);
    return overlay;
  }

  function remove(id) {
    const idx = VE.state.overlays.findIndex((o) => o.id === id);
    if (idx !== -1) VE.state.overlays.splice(idx, 1);
    if (VE.state.selectedOverlayId === id) VE.state.selectedOverlayId = null;
  }

  function getById(id) {
    return VE.state.overlays.find((o) => o.id === id);
  }

  // Draws all overlays active at globalTime onto the given 2D context.
  function render(ctx, canvasWidth, canvasHeight, globalTime) {
    VE.state.overlays.forEach((overlay) => {
      if (globalTime < overlay.start || globalTime > overlay.end) return;
      const px = (overlay.x / 100) * canvasWidth;
      const py = (overlay.y / 100) * canvasHeight;
      ctx.save();
      ctx.font = `600 ${overlay.fontSize}px -apple-system, Segoe UI, Roboto, sans-serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.lineWidth = Math.max(2, overlay.fontSize / 10);
      ctx.strokeStyle = 'rgba(0,0,0,0.85)';
      ctx.fillStyle = overlay.color;
      ctx.strokeText(overlay.text, px, py);
      ctx.fillText(overlay.text, px, py);
      ctx.restore();
    });
  }

  return { add, remove, getById, render };
})();
