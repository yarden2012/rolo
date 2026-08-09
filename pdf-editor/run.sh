#!/usr/bin/env bash
# Launch the PDF editor locally.
cd "$(dirname "$0")"
PORT="${1:-8123}"
URL="http://localhost:$PORT"
echo "PDF Editor → $URL"
( sleep 0.7; xdg-open "$URL" >/dev/null 2>&1 ) &
exec python3 -m http.server "$PORT" --bind 127.0.0.1
