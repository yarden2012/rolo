# screen capture

A small system-tray app for Linux/X11. Pick any keyboard key from the tray
menu, and pressing it anywhere takes a full-screen screenshot.

## Setup

```bash
cd "screen capture"
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

## Run

```bash
.venv/bin/python main.py
```

A tray icon appears. From its menu:

- **Set screenshot key...** — click it, then press any key; that key becomes
  the full-screen screenshot hotkey.
- **Take screenshot now** — capture the full screen immediately, no hotkey
  needed.
- **Set area-screenshot key...** — like above, but for area-select mode.
- **Select area now** — same as pressing the area key.
- **Open screenshots folder** — opens the save directory in your file manager.
- **Quit**.

Area-select mode dims the screen and shows a box centered on your mouse
cursor: scroll to resize it, left-click to capture whatever is inside it,
right-click or Esc to cancel.

Screenshots are saved to `~/Pictures/Screenshots` as timestamped PNGs
(`screenshot_2026-01-01_12-00-00.png`) and copied to the clipboard, ready to
paste. Both hotkeys are remembered in `~/.config/screen-capture/config.json`
between runs.

## Autostart (KDE Plasma / XDG autostart)

```bash
mkdir -p ~/.local/bin ~/.config/autostart
ln -s "$(pwd)/scripts/launch.sh" ~/.local/bin/screen-capture-launch.sh
cat > ~/.config/autostart/screen-capture.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=Screen Capture
Comment=Tray app for hotkey and area screenshots
Exec=/home/rolo/.local/bin/screen-capture-launch.sh
Terminal=false
Icon=camera-photo
X-GNOME-Autostart-enabled=true
EOF
```

`Exec=` must be a space-free path. KDE Plasma 6 converts autostart
`.desktop` entries into systemd user services, and its handling of a
directly-quoted, space-containing `Exec=` (e.g. pointing straight at
`.venv/bin/python` inside this "screen capture" folder) silently fell back
to the system Python — which lacks `PySide6` — instead of the venv's. A
symlink at a space-free path to `scripts/launch.sh` sidesteps that.

## Notes

- Requires an X11 session (global key listening and the tray icon both
  depend on it; this won't work under plain Wayland without XWayland).
- The tray icon is built with `PySide6-Essentials` (Qt). An earlier version
  used `pystray`'s plain-Xlib backend, but that rendered a blank/invisible
  icon under KDE Plasma's compositor — Qt's own tray implementation doesn't
  have that problem.
