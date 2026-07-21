#!/usr/bin/env bash
# Convenience launcher: runs the GUI using its own virtualenv,
# no matter what directory you call this script from.
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$DIR/.venv/bin/python" "$DIR/open_key_gui.py" "$@"
