// Toasts, popover menus, shared icon snippets.

export const I = {
  rotate: '<svg viewBox="0 0 24 24"><path d="M21 12a9 9 0 1 1-2.64-6.36L21 8"/><path d="M21 3v5h-5"/></svg>',
  trash: '<svg viewBox="0 0 24 24"><path d="M3 6h18"/><path d="M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2"/><path d="m19 6-.8 13.2A2 2 0 0 1 16.2 21H7.8a2 2 0 0 1-2-1.8L5 6"/></svg>',
  extract: '<svg viewBox="0 0 24 24"><path d="M12 3v12m0 0 4.5-4.5M12 15 7.5 10.5"/><path d="M4 20h16"/></svg>',
  close: '<svg viewBox="0 0 24 24"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>',
  check: '<svg class="check" viewBox="0 0 24 24"><path d="M20 6 9 17l-5-5"/></svg>',
  square: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="2"/></svg>',
  circle: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/></svg>',
  line: '<svg viewBox="0 0 24 24"><line x1="19" y1="5" x2="5" y2="19"/></svg>',
  arrow: '<svg viewBox="0 0 24 24"><path d="M7 17 17 7"/><path d="M8 7h9v9"/></svg>',
  note: '<svg viewBox="0 0 24 24"><path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z"/></svg>',
};

export function toast(msg, type = 'info', ms = 3200) {
  const host = document.getElementById('toasts');
  const t = document.createElement('div');
  t.className = `toast ${type}`;
  t.textContent = msg;
  host.appendChild(t);
  setTimeout(() => { t.style.opacity = '0'; t.style.transition = 'opacity .25s'; }, ms - 250);
  setTimeout(() => t.remove(), ms);
}

let openMenu = null;

export function closeMenu() {
  if (openMenu) { openMenu.remove(); openMenu = null; }
}

// items: { label, icon?, action?, checked?, sep? }
export function showMenu(anchor, items) {
  closeMenu();
  const m = document.createElement('div');
  m.className = 'menu';
  for (const it of items) {
    if (it.sep) { const s = document.createElement('div'); s.className = 'menuSep'; m.appendChild(s); continue; }
    const b = document.createElement('button');
    b.className = 'menuItem' + (it.checked ? ' checked' : '');
    b.innerHTML = `${it.icon || ''}<span>${it.label}</span>${I.check}`;
    b.addEventListener('click', () => { closeMenu(); it.action?.(); });
    m.appendChild(b);
  }
  document.body.appendChild(m);
  const r = anchor.getBoundingClientRect();
  const mw = m.offsetWidth, mh = m.offsetHeight;
  let x = r.left, y = r.bottom + 6;
  if (x + mw > innerWidth - 8) x = r.right - mw;
  if (y + mh > innerHeight - 8) y = r.top - mh - 6;
  m.style.left = `${Math.max(8, x)}px`;
  m.style.top = `${Math.max(8, y)}px`;
  openMenu = m;
  setTimeout(() => {
    const onDoc = (e) => { if (!m.contains(e.target)) { closeMenu(); document.removeEventListener('pointerdown', onDoc); } };
    document.addEventListener('pointerdown', onDoc);
  }, 0);
}
