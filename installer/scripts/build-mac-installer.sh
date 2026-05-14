#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA_HOME_PATH="${JAVA_HOME:-$ROOT_DIR/.toolchains/jdk-17.0.19+10/Contents/Home}"
ADB_PATH="$ROOT_DIR/.toolchains/android-sdk/platform-tools/adb"
APP_NAME="PortalKids Installer.app"
DIST_DIR="$ROOT_DIR/installer/dist"
APP_DIR="$DIST_DIR/$APP_NAME"
CONTENTS_DIR="$APP_DIR/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
RESOURCES_DIR="$CONTENTS_DIR/Resources"

if [[ ! -x "$JAVA_HOME_PATH/bin/java" ]]; then
  echo "Missing bundled JDK at $JAVA_HOME_PATH" >&2
  exit 1
fi

if [[ ! -x "$ADB_PATH" ]]; then
  echo "Missing bundled adb at $ADB_PATH" >&2
  exit 1
fi

JAVA_HOME="$JAVA_HOME_PATH" "$ROOT_DIR/gradlew" \
  -p "$ROOT_DIR" \
  :app:assembleDebug \
  :hub:installDist

swift build \
  --package-path "$ROOT_DIR/installer/macos" \
  -c release

rm -rf "$APP_DIR"
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR/platform-tools" "$RESOURCES_DIR/hub" "$RESOURCES_DIR/.toolchains"

cp "$ROOT_DIR/installer/macos/.build/release/PortalKidsInstaller" "$MACOS_DIR/PortalKidsInstaller"
cp "$ADB_PATH" "$RESOURCES_DIR/platform-tools/adb"
cp "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk" "$RESOURCES_DIR/PortalKids.apk"
rsync -a --delete "$ROOT_DIR/hub/build/install/hub/" "$RESOURCES_DIR/hub/build/install/hub/"
rsync -a --delete "$ROOT_DIR/hub/scripts/" "$RESOURCES_DIR/hub/scripts/"
rsync -a --delete "$ROOT_DIR/.toolchains/jdk-17.0.19+10/" "$RESOURCES_DIR/.toolchains/jdk-17.0.19+10/"

chmod +x "$MACOS_DIR/PortalKidsInstaller" "$RESOURCES_DIR/platform-tools/adb" "$RESOURCES_DIR/hub/scripts/install-launch-agent.sh"

cat > "$CONTENTS_DIR/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key>
  <string>PortalKidsInstaller</string>
  <key>CFBundleIdentifier</key>
  <string>com.davidedicillo.portalkids.installer</string>
  <key>CFBundleName</key>
  <string>PortalKids Installer</string>
  <key>CFBundleDisplayName</key>
  <string>PortalKids Installer</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>0.1.0</string>
  <key>CFBundleVersion</key>
  <string>1</string>
  <key>LSMinimumSystemVersion</key>
  <string>14.0</string>
  <key>NSHighResolutionCapable</key>
  <true/>
</dict>
</plist>
PLIST

codesign --force --deep --sign - "$APP_DIR" >/dev/null

echo "Built $APP_DIR"
