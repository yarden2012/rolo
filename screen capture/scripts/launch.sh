#!/bin/sh
# Launcher used for autostart/login-script mechanisms. Invoking
# .venv/bin/python (a symlink chain ending at /usr/bin/python3) let Python
# fail to detect the venv and its pyvenv.cfg under systemd's execution
# context, silently falling back to system site-packages and missing
# PySide6 - even though the exact same invocation works fine from an
# interactive shell. Setting PYTHONPATH directly and calling the system
# interpreter sidesteps that venv auto-detection entirely.
#
# The site-packages directory is globbed rather than hardcoded: it used to
# name python3.13, which silently stopped matching after a move to a distro
# shipping python3.14.
DIR="/home/rolo/rolo/screen capture"

for sp in "$DIR"/.venv/lib/python*/site-packages; do
    [ -d "$sp" ] && PYTHONPATH="$sp" && export PYTHONPATH
done

exec /usr/bin/python3 "$DIR/main.py"
