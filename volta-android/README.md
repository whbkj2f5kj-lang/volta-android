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
