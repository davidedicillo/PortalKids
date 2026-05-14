#!/usr/bin/env bash
set -euo pipefail

LABEL="com.davidedicillo.portalkids.hub"
SOURCE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_HOME="${PORTALKIDS_HOME:-$HOME/.portalkids}"
RUNTIME_DIR="${PORTALKIDS_RUNTIME_DIR:-$APP_HOME/runtime}"
EMBEDDED_INSTALL="${PORTALKIDS_EMBEDDED_INSTALL:-0}"

if [[ "$EMBEDDED_INSTALL" == "1" ]]; then
  mkdir -p "$RUNTIME_DIR"
  rm -rf "$RUNTIME_DIR/hub" "$RUNTIME_DIR/.toolchains"
  /usr/bin/ditto "$SOURCE_ROOT/hub" "$RUNTIME_DIR/hub"
  /usr/bin/ditto "$SOURCE_ROOT/.toolchains" "$RUNTIME_DIR/.toolchains"
  PROJECT_DIR="$RUNTIME_DIR"
else
  PROJECT_DIR="$SOURCE_ROOT"
fi

JAVA_HOME_PATH="${JAVA_HOME:-$PROJECT_DIR/.toolchains/jdk-17.0.19+10/Contents/Home}"
HUB_BIN="$PROJECT_DIR/hub/build/install/hub/bin/hub"
PORT="${PORTALKIDS_PORT:-8080}"
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
PUBLIC_URL="${PORTALKIDS_PUBLIC_URL:-http://${LAN_IP:-127.0.0.1}:$PORT}"
DB_PATH="${PORTALKIDS_DB:-$APP_HOME/portal-kids.db}"
LOG_DIR="$HOME/Library/Logs/PortalKids"
PLIST_PATH="$HOME/Library/LaunchAgents/$LABEL.plist"

xml_escape() {
  local value="$1"
  value="${value//&/&amp;}"
  value="${value//</&lt;}"
  value="${value//>/&gt;}"
  printf '%s' "$value"
}

if [[ ! -x "$JAVA_HOME_PATH/bin/java" ]]; then
  echo "Java runtime not found at $JAVA_HOME_PATH" >&2
  exit 1
fi

if [[ ! -x "$HUB_BIN" ]]; then
  echo "Hub distribution not found. Run: JAVA_HOME=$JAVA_HOME_PATH ./gradlew :hub:installDist" >&2
  exit 1
fi

mkdir -p "$(dirname "$DB_PATH")" "$LOG_DIR" "$(dirname "$PLIST_PATH")"

XML_PROJECT_DIR="$(xml_escape "$PROJECT_DIR")"
XML_JAVA_HOME_PATH="$(xml_escape "$JAVA_HOME_PATH")"
XML_PORT="$(xml_escape "$PORT")"
XML_PUBLIC_URL="$(xml_escape "$PUBLIC_URL")"
XML_DB_PATH="$(xml_escape "$DB_PATH")"
XML_HUB_BIN="$(xml_escape "$HUB_BIN")"
XML_LOG_DIR="$(xml_escape "$LOG_DIR")"

cat > "$PLIST_PATH" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$LABEL</string>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>WorkingDirectory</key>
  <string>$XML_PROJECT_DIR</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>JAVA_HOME</key>
    <string>$XML_JAVA_HOME_PATH</string>
    <key>PORTALKIDS_PORT</key>
    <string>$XML_PORT</string>
    <key>PORTALKIDS_PUBLIC_URL</key>
    <string>$XML_PUBLIC_URL</string>
    <key>PORTALKIDS_DB</key>
    <string>$XML_DB_PATH</string>
  </dict>
  <key>ProgramArguments</key>
  <array>
    <string>$XML_HUB_BIN</string>
  </array>
  <key>StandardOutPath</key>
  <string>$XML_LOG_DIR/hub.out.log</string>
  <key>StandardErrorPath</key>
  <string>$XML_LOG_DIR/hub.err.log</string>
</dict>
</plist>
PLIST

if [[ "${PORTALKIDS_SKIP_LAUNCHCTL:-0}" == "1" ]]; then
  echo "Skipping launchctl because PORTALKIDS_SKIP_LAUNCHCTL=1"
else
  launchctl bootout "gui/$(id -u)" "$PLIST_PATH" >/dev/null 2>&1 || true
  launchctl bootstrap "gui/$(id -u)" "$PLIST_PATH"
  launchctl kickstart -k "gui/$(id -u)/$LABEL"
fi

echo "PortalKids Hub launch agent installed: $PLIST_PATH"
echo "Hub URL: $PUBLIC_URL"
echo "Database: $DB_PATH"
echo "Runtime: $PROJECT_DIR"
