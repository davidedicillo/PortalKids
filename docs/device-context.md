# Meta Portal Device Context

This project targets a Meta Portal that now has normal ADB access and supports sideloaded APKs. This file captures the device facts discovered during setup so another agent can continue without chat history.

## Device

- Device family: Meta Portal
- Product/model from ADB: `aloha_prod`, `Portal`, `aloha`
- Android version: `9`
- CPU ABI: `arm64-v8a`
- Build fingerprint: `Facebook/aloha_prod/aloha:9/PKQ1.191202.001/1041515800015050:user/prod-keys`
- ADB shell identity: `uid=2000(shell)`, not root
- Root status: `adb root` should not be assumed available

## ADB Access

ADB was enabled from the Portal Debug menu. USB ADB worked first, then Wi-Fi ADB was enabled.

Current observed Wi-Fi target:

```bash
adb connect 192.168.4.38:5555
adb -s 192.168.4.38:5555 devices -l
```

The IP may change if the router assigns a different address. To rediscover it over USB:

```bash
adb shell ip route
adb shell ip addr show wlan0
```

To disable Wi-Fi ADB when not debugging:

```bash
adb -s 192.168.4.38:5555 usb
```

## Installed Fallback Apps

These apps were sideloaded successfully:

- F-Droid: `org.fdroid.fdroid`
- KISS Launcher: `fr.neamar.kiss`
- Fennec / Firefox from F-Droid: `org.mozilla.fennec_fdroid`

KISS Launcher was set as the default Home launcher during exploration:

```bash
adb -s 192.168.4.38:5555 shell cmd package set-home-activity fr.neamar.kiss/.MainActivity
adb -s 192.168.4.38:5555 shell input keyevent HOME
```

The PortalKids app should eventually replace KISS as the default Home activity.

## Useful Commands

Launch installed fallback apps:

```bash
adb -s 192.168.4.38:5555 shell monkey -p fr.neamar.kiss 1
adb -s 192.168.4.38:5555 shell monkey -p org.fdroid.fdroid 1
adb -s 192.168.4.38:5555 shell monkey -p org.mozilla.fennec_fdroid 1
```

Install a built APK:

```bash
adb -s 192.168.4.38:5555 install -r path/to/app.apk
```

Set PortalKids as Home once implemented:

```bash
adb -s 192.168.4.38:5555 shell cmd package set-home-activity com.davidedicillo.portalroutine/.HomeActivity
adb -s 192.168.4.38:5555 shell input keyevent HOME
```

Capture a screenshot:

```bash
adb -s 192.168.4.38:5555 exec-out screencap -p > portal-screenshot.png
```

## Safety Notes

- Do not assume bootloader unlock, root, or system partition write access.
- Do not leave Wi-Fi ADB enabled when the device is not actively being debugged.
- Keep KISS Launcher installed as a fallback Home target until PortalKids is stable.
