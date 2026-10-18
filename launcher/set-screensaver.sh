#!/usr/bin/env bash
# Make the Ambient screensaver the TV's screensaver and (optionally) preview it right now.
# Run build-and-install.sh first so the app is on the TV.
#   TV_HOST=192.168.1.50 launcher/set-screensaver.sh [preview]
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PY=../tools/venv/bin/python
{
  echo "settings put secure screensaver_components com.gathin.tvhome/com.gathin.tvhome.AmbientDream"
  echo "settings put secure screensaver_enabled 1"
  echo "settings get secure screensaver_components"
  if [ "${1:-}" = "preview" ]; then
    # Starts the screensaver immediately. Press any remote button to leave it.
    echo "am start -n com.android.systemui/.Somnambulator"
  fi
} | "$PY" -I ../tools/tv.py --batch
echo "Done. Start time is set on the TV: Settings > Device Preferences > Screen saver > When to start."
echo "If the TV ignores this, pick 'Ambient' by hand in that same menu."
