#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIST_DIR="$ROOT_DIR/installer/dist"
APP_DIR="$DIST_DIR/PortalKids Installer.app"
ZIP_PATH="$DIST_DIR/PortalKids-Installer-Mac.zip"
CHECKSUM_PATH="$ZIP_PATH.sha256"

"$ROOT_DIR/installer/scripts/build-mac-installer.sh"

rm -f "$ZIP_PATH" "$CHECKSUM_PATH"
/usr/bin/ditto -c -k --keepParent "$APP_DIR" "$ZIP_PATH"
shasum -a 256 "$ZIP_PATH" > "$CHECKSUM_PATH"

echo "Packaged $ZIP_PATH"
echo "Checksum $CHECKSUM_PATH"
