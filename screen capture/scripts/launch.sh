#!/bin/sh
# Launcher used for XDG autostart. KDE Plasma 6 converts autostart .desktop
# entries into systemd user services, and its Exec= handling mangled the
# space in "screen capture/.venv/bin/python" (silently falling back to the
# system python3, which lacks PySide6). Routing through this space-free
# path avoids that entirely.
exec "/home/rolo/rolo/screen capture/.venv/bin/python" "/home/rolo/rolo/screen capture/main.py"
