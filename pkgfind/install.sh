#!/usr/bin/env bash
# Puts `pkgfind` on your PATH and adds it to the application menu.
# Nothing is copied — both entries point back at this directory.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="$HOME/.local/bin"
APP_DIR="$HOME/.local/share/applications"

mkdir -p "$BIN_DIR" "$APP_DIR"
chmod +x "$HERE/pkgfind" "$HERE/pkgfind.py"

ln -sf "$HERE/pkgfind" "$BIN_DIR/pkgfind"
sed "s|__EXEC__|$HERE/pkgfind|" "$HERE/pkgfind.desktop" > "$APP_DIR/dev.rolo.pkgfind.desktop"

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$APP_DIR" >/dev/null 2>&1 || true
fi

echo "Installed:"
echo "  $BIN_DIR/pkgfind"
echo "  $APP_DIR/dev.rolo.pkgfind.desktop"

case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) echo; echo "Note: $BIN_DIR is not on your PATH yet." ;;
esac
