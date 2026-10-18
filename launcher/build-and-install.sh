#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.tvlauncher}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
# secrets.xml holds passwords and tokens and is never committed; start it from the template.
SECRETS=app/src/main/res/values/secrets.xml
[ -f "$SECRETS" ] || { cp secrets.example.xml "$SECRETS"; \
  echo "Created $SECRETS from the template. Email and Telegram stay off until you fill it in."; }
./gradlew assembleDebug -q --console=plain
PY=../tools/venv/bin/python
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/tvhome.apk" \
  "rm -f /data/local/tmp/tvhome.apk" \
  "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow" \
  "cmd package set-home-activity --user 0 com.gathin.tvhome" \
  "pm disable-user --user 0 $STOCK_LAUNCHER" \
  "am start-foreground-service -n com.gathin.tvhome/.BoardService" \
  | "$PY" -I ../tools/tv.py --batch
echo "Installed. Press Home on the remote to see it."
echo "Family board page: http://$TV_HOST:8080"
