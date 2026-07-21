#!/usr/bin/env bash
# Sets up (or rebuilds) the .venv for open_key.
#
# pynput's plain `pip install` pulls in `evdev`, which needs to compile a C
# extension against Python.h -- headers that atomic/immutable distros like
# Bazzite don't ship by default. evdev is only needed for the /dev/uinput
# input-synthesis backend (Wayland/headless); under X11 pynput listens via
# python-xlib instead, which is pure Python. So we install pynput's real
# dependencies ourselves and skip evdev.
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

python3 -m venv "$DIR/.venv"
"$DIR/.venv/bin/pip" install --upgrade pip
"$DIR/.venv/bin/pip" install six python-xlib pyside6
"$DIR/.venv/bin/pip" install --no-deps pynput

echo "Setup complete. Try: $DIR/open_key_gui.sh"
