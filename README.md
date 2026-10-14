# Clipboard Sync

Automatically share the clipboard between your Android phone and Galaxy Watch
(Wear OS) — and between any of your devices.

**Languages:** [English](README.md) · [Русский](README.ru.md) · [Deutsch](README.de.md)

## Features

- **Automatic sync** — copy on one device, paste on the other. No buttons to press.
- **Two independent channels at once:**
  - **Wi-Fi** — devices on the same network find each other automatically (mDNS);
  - **Bluetooth** — works between paired devices even on *different* networks.
- **On the watch:** the received text is shown full screen over the lock screen,
  the screen turns on even if it was off, the text is scrollable with a swipe or
  the rotating bezel, and the screen stays on until you dismiss the message.
- Starts on boot, reconnects automatically after sleep or network drops.
- UI in **English, Russian and German**.
- **No servers, no internet access.** Data goes directly between your devices only.

## Requirements

| Device | OS |
|---|---|
| Phone | Android 8.0+ |
| Watch | Wear OS 3+ (Galaxy Watch4 / Watch5 / Watch6 / Watch7 / Watch8 / FE) |

## Install

1. Download the APKs from the [Releases](../../releases) page:
   - `ClipboardSync-phone.apk` — for the phone;
   - `ClipboardSync-wear.apk` — for the watch (sideload via `adb install` or via
     the Galaxy Wearable / Wear OS installer).
2. Launch the app on both devices and turn on the **sync toggle**.
3. Grant the requested permissions (Bluetooth, notifications).
4. Tap **Accessibility settings** and enable **Clipboard Sync** in the list —
   Android 10+ does not allow reading the clipboard in the background without it.
5. Keep both devices on the same Wi-Fi network and/or paired via Bluetooth —
   they will find each other automatically.

### Tips

- **Xiaomi / MIUI / HyperOS:** enable *Autostart* for the app (Settings → Apps →
  Permissions) and tap **Disable battery optimization** inside the app,
  otherwise the system will kill the background service.
- **Guest Wi-Fi with client isolation:** auto-discovery won't work — add the
  device manually by IP in the app.

## How it works

- Devices announce themselves via **mDNS** (`_clipboardsync._tcp`) and exchange
  newline-delimited **JSON** over TCP (port 47821).
- **Bluetooth RFCOMM (SPP)** links are established with all bonded devices.
- Echo suppression: each message carries the sender id and a SHA-1 of the text,
  so the same clipboard content is never applied in a loop.
- Simultaneous mutual connections are deduplicated deterministically
  (the socket initiated by the device with the smaller id wins).

## Build from source

Requirements: **JDK 17**, Android SDK (platform 34).

```bash
./gradlew :phone:assembleRelease :wear:assembleRelease
```

APKs will appear in `phone/build/outputs/apk/release/` and
`wear/build/outputs/apk/release/`.

To sign releases, place your keystore at `keystore/release.keystore`
(alias `clipboardsync`) — without it the build produces an unsigned APK.

## Privacy

The app contains no analytics, no trackers and never accesses the internet.
The clipboard text is transmitted only directly between your own devices.

## License

[MIT](LICENSE)
