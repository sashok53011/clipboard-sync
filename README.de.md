# Clipboard Sync

Teilen Sie die Zwischenablage automatisch zwischen Ihrem Android-Handy und
Galaxy Watch (Wear OS) — und zwischen all Ihren Geräten.

**Sprachen:** [English](README.md) · [Русский](README.ru.md) · [Deutsch](README.de.md)

## Funktionen

- **Automatische Synchronisation** — auf einem Gerät kopieren, auf dem anderen
  einfügen. Keine Knöpfe nötig.
- **Zwei unabhängige Kanäle gleichzeitig:**
  - **WLAN** — Geräte im selben Netzwerk finden sich automatisch (mDNS);
  - **Bluetooth** — funktioniert zwischen gekoppelten Geräten sogar in
    *verschiedenen* Netzwerken.
- **Auf der Uhr:** Der empfangene Text wird Vollbild über dem Sperrbildschirm
  angezeigt, der Bildschirm schaltet sich auch im ausgeschalteten Zustand ein,
  der Text lässt sich wischen oder mit dem Drehbezirk scrollen, und der
  Bildschirm bleibt an, bis die Nachricht geschlossen wird.
- Autostart nach dem Neustart, automatische Wiederverbindung nach Schlafmodus
  oder Netzwerkausfällen.
- Oberfläche auf **Englisch, Russisch und Deutsch**.
- **Keine Server, kein Internetzugriff.** Die Daten gehen ausschließlich direkt
  zwischen Ihren Geräten.

## Anforderungen

| Gerät | OS |
|---|---|
| Handy | Android 8.0+ |
| Uhr | Wear OS 3+ (Galaxy Watch4 / Watch5 / Watch6 / Watch7 / Watch8 / FE) |

## Installation

1. Laden Sie die APKs von der Seite [Releases](../../releases) herunter:
   - `ClipboardSync-phone.apk` — für das Handy;
   - `ClipboardSync-wear.apk` — für die Uhr (Sideload per `adb install` oder
     über Galaxy Wearable / Wear OS).
2. Starten Sie die App auf beiden Geräten und aktivieren Sie den
   **Synchronisations-Schalter**.
3. Erteilen Sie die angeforderten Berechtigungen (Bluetooth, Benachrichtigungen).
4. Tippen Sie auf **Accessibility-Einstellungen** und aktivieren Sie
   **Clipboard Sync** in der Liste — Android 10+ erlaubt das Lesen der
   Zwischenablage im Hintergrund nicht ohne diesen Service.
5. Halten Sie beide Geräte im selben WLAN und/oder per Bluetooth gekoppelt —
   sie finden sich automatisch.

### Tipps

- **Xiaomi / MIUI / HyperOS:** Aktivieren Sie den Autostart für die App
  (Einstellungen → Apps → Berechtigungen) und tippen Sie in der App auf
  **Akkuoptimierung deaktivieren**, sonst beendet das System den
  Hintergrundservice.
- **Gast-WLAN mit Client-Isolation:** Die Auto-Suche funktioniert nicht —
  fügen Sie das Gerät manuell per IP hinzu.

## Technik

- Geräte kündigen sich über **mDNS** (`_clipboardsync._tcp`) an und tauschen
  **JSON**-Nachrichten über TCP aus (Port 47821).
- **Bluetooth RFCOMM (SPP)** verbindet sich mit allen gekoppelten Geräten.
- Echo-Unterdrückung: Jede Nachricht trägt die Sender-ID und einen SHA-1 des
  Texts — derselbe Text wird nie in einer Schleife angewendet.
- Gleichzeitige gegenseitige Verbindungen werden deterministisch dedupliziert
  (der vom Gerät mit der kleineren ID initiierte Socket gewinnt).

## Aus den Quellen bauen

Voraussetzungen: **JDK 17**, Android SDK (Platform 34).

```bash
./gradlew :phone:assembleRelease :wear:assembleRelease
```

Die APKs erscheinen in `phone/build/outputs/apk/release/` und
`wear/build/outputs/apk/release/`.

Für signierte Releases legen Sie Ihr Keystore unter
`keystore/release.keystore` ab (Alias `clipboardsync`) — ohne es wird eine
unsignierte APK erzeugt.

## Datenschutz

Die App enthält keine Analyse-Tracker und greift nie auf das Internet zu.
Zwischenablage-Text wird ausschließlich direkt zwischen Ihren Geräten übertragen.

## Lizenz

[MIT](LICENSE)
