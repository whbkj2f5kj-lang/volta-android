# Volta OS – Android-Port (Build-Schicht)

Baut das Original [Omardigio/VoltaOS](https://github.com/Omardigio/VoltaOS) (GPL-3.0) unverändert
mit Qt für Android. Dieses Verzeichnis enthält nur die Port-Schicht: Patches für den Build,
Platzhalter für Linux-only-Teile (Wayland-Compositor, WebEngine, libusb, ffmpeg) und das Android-Manifest.
Der VoltaOS-Quellcode wird beim Build von GitHub geholt (fester Commit, siehe Workflow).

Lizenz dieser Port-Schicht: GPL-3.0-or-later (wie VoltaOS).

## Schritt 3: Reifendruck und Android-Anbindung

USB-TPMS nutzt den vorhandenen W204-Empfänger (CH340/FTDI, 19200 Baud). In Vehicle → Tyre pressure aktivieren, USB-Zugriff erlauben und Sollwerte vom Fahrzeugaufkleber einstellen. Die vier Empfängerkanäle lassen sich den Rädern zuordnen; Sensor-Anlernen erfolgt am Empfänger. Unbekannte/veraltete Daten werden nicht als sichere Werte angezeigt. Warnungen erscheinen in Voltas vorhandenen Benachrichtigungen, ohne Sprachausgabe.

Android-Web-Apps und Browser verwenden Qt WebView / Android System WebView. Cookies und Anmeldung bleiben im Android-WebView-Speicher. Manche Anbieter erlauben Anmeldung oder DRM-Wiedergabe nur im externen Browser; dafür gibt es bei Ladefehlern einen Browser-Link. Android System WebView muss installiert und aktiviert sein. Die Linux-Vorschau enthält weiterhin keine Browser-Engine.

Bluetooth unterscheidet fehlende Hardware, fehlende Berechtigungen und einen ausgeschalteten Adapter. BlueStacks ohne Bluetooth-Adapter kann keine echten Geräte koppeln. Verbinden/Trennen führt auf Android zu dessen Bluetooth-Einstellungen, statt eine erfolgreiche Verbindung vorzutäuschen.

OBD verwendet derzeit Bluetooth Classic SPP. USB-TPMS-Unterstützung bedeutet nicht USB-OBD-Unterstützung. Physischer TPMS-Empfänger, Bluetooth-Radio und Anbieter-Anmeldungen müssen zusätzlich am Zielgerät geprüft werden.

## Camera / USB dashcam (source: Jarvis ASD)

The opt-in USB-camera feature reuses the UVC driver strategy from
[`whbkj2f5kj-lang/asd`](https://github.com/whbkj2f5kj-lang/asd)
(`vision/UsbCameraFeed.kt`) and its adapted H.264 loop recorder
(`dashcam/Dashcam.kt`). The Jetpack Compose camera UI is NOT imported:
Volta retains its own Camera app and Tesla-style Settings UI.

- **Off by default**, switch in **Settings → Vehicle → USB dashcam**.
- For Android 10 head units, the UVC driver requests camera permission and,
  if the USB device advertises audio, microphone permission needed for USB
  access. Recordings contain **video only**.
- When enabled, the USB camera and recording run through ASD's
  `com.herohan:UVCAndroid:1.0.13` backend. Camera image is placed over
  Volta's existing main Camera tile; no separate Jarvis screen.
- 1-minute H.264 ring segments with a 3-minute retention window:
  `Movies/W204-Dashcam/`, manual "Save event" copies the relevant period to
  `Movies/W204-Dashcam/Gesichert/`.
- Disabling should stop recording and release the USB camera. Camera
  permissions and actual UVC formats/USB bandwidth **must be validated on
  the radio**. Installing the APK alone does not prove video capture.
- The port is an adaptation of the source; full Jarvis sign recognition and
  the Compose camera UI are not silently included.
- On old Android 10 head units the build uses targetSdk 27 to follow the
  tested ASD UVC USB-permission workaround.
- Recording may affect weak-device performance; keep disabled unless needed.
- Review applicable privacy and dashcam rules. Protected footage may contain
  identifiable people, plates, and locations.

The **Vehicle** icon and sidebar scrolling are separately corrected: no
missing `ms_speed.svg`, and sidebar taps no longer use a competing full-size
MouseArea.
