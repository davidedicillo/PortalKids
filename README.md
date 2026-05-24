# PortalKids

PortalKids is a simple routine board for Meta Portal devices. It is designed to work by itself on the Portal first, with an optional Mac hub for always-on admin/backups.

Website: <https://davidedicillo.github.io/PortalKids/>

Latest Mac installer: <https://github.com/davidedicillo/PortalKids/releases/latest/download/PortalKids-Installer-Mac.zip>

## What It Does

- Shows a kid-friendly routine board with large task tiles, icons, progress, and point totals.
- Tracks points in persistent child wallets instead of resetting them at the end of the week.
- Lets kids redeem parent-configured real-world rewards from the Portal.
- Supports task point values and repeatable tasks, such as reading three 20-minute blocks.
- Gives parents a PIN-protected admin page for children, routine windows, draggable task ordering, rewards, deductions, and reset settings.

## Easiest Install Path

1. Download `PortalKids Installer for Mac`.
2. On the Portal, enable Developer Options and USB debugging.
3. Connect the Portal to the Mac with USB.
4. Open `PortalKids Installer`.
5. Click **Refresh** until the Portal is shown as ready.
6. Click **Install PortalKids**.

The installer bundles its own ADB, APK, hub service, and Java runtime. Users do not need Android Studio, Gradle, Java, or platform-tools installed.

Local developer builds are ad-hoc signed but not notarized. A public release should be Developer ID signed and notarized; until then, macOS may require right-click, then Open.

## Optional Mac Hub

The Portal app works standalone by default. If you have an always-on Mac, the installer can also install the PortalKids Hub on that Mac. The hub provides a central admin page and a stable database for future multi-device use.

Use the **Also run PortalKids Hub on this Mac** option in the installer only if that Mac is expected to stay on.

Hub installation copies the bundled hub and Java runtime into `~/.portalkids/runtime`, stores data in `~/.portalkids/portal-kids.db`, and starts a LaunchAgent named `com.davidedicillo.portalkids.hub`.

When a Portal is connected to the hub, task completions, repeat counts, wallet reward redemptions, rewards, and deductions reconcile through the hub's local API.

## Developer Build

```bash
installer/scripts/build-mac-installer.sh
```

This creates:

```text
installer/dist/PortalKids Installer.app
```

Developer USB install without the GUI:

```bash
installer/scripts/install-portal-usb.sh
```

Create the release zip locally:

```bash
installer/scripts/package-release.sh
```
