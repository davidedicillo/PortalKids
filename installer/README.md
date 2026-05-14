# PortalKids Installer

The macOS installer is a SwiftUI app for non-technical users. It detects a Meta Portal over USB, installs the standalone APK, sets PortalKids as the Home activity, and can optionally install the Mac hub.

## Build

```bash
./installer/scripts/build-mac-installer.sh
```

The resulting app bundle is written to:

```text
installer/dist/PortalKids Installer.app
```

## Bundled Resources

The packaged app expects these files in `Contents/Resources`:

- `platform-tools/adb`
- `PortalKids.apk`
- `hub/build/install/hub/bin/hub`
- `hub/scripts/install-launch-agent.sh`
- `.toolchains/jdk-17.0.19+10/Contents/Home/bin/java`

The build script assembles this layout from the local Gradle and toolchain outputs.

For a downloadable release asset:

```bash
./installer/scripts/package-release.sh
```

That creates `installer/dist/PortalKids-Installer-Mac.zip` and a matching SHA-256 checksum file. Pushing a `v*` tag runs the GitHub release workflow and attaches those files to the release.

When the optional hub is installed from the packaged app, `hub/scripts/install-launch-agent.sh` copies those bundled runtime files into `~/.portalkids/runtime` before creating the LaunchAgent. The installer app bundle does not need to remain in place after hub installation.

## States

- **No Portal detected**: USB debugging is not ready or no device is connected.
- **Portal waiting for authorization**: the user must approve the USB debugging prompt on the Portal.
- **Portal ready**: installer can install or restore the fallback launcher.
