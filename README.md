# Volta OS für Android

Android-Port von [VoltaOS](https://github.com/Omardigio/VoltaOS) für ein Android-Autoradio.
Der Volta-Code selbst wird beim Bau unverändert vom Original geholt (fester Stand); hier liegt nur,
was für Android nötig ist.

## App herunterladen

Unter **Releases → „Volta OS (Android-Port)“**:

- `volta-arm32.apk` – für das Radio (32-Bit-Android)
- `volta-arm64.apk` – für 64-Bit-Geräte

Jede Änderung in diesem Repo baut die APKs automatisch neu (Reiter **Actions**).

## Was hier liegt

| Ordner / Datei | Zweck |
|---|---|
| `volta-android/port.py` | passt Voltas Build für Android an |
| `volta-android/volta_port.cpp` | Start auf Android, Fehlerprotokoll, Dateiauswahl |
| `volta-android/volta_mapproxy.cpp` | Kartendienst (ersetzt Voltas Python-Dienst) |
| `volta-android/android_gps.cpp` | Standort über Android |
| `volta-android/shim*`, `qmlstubs/` | Platzhalter für Linux-Teile, die es auf Android nicht gibt |
| `volta-android/android/` | Android-Manifest (App + wählbarer Startbildschirm) |
| `.github/workflows/volta.yml` | automatischer Bau |

## Fehlerprotokoll

Auf dem Gerät unter `Android/data/de.jarvis.volta/files/volta_log.txt` (vorheriger Start: `volta_log_alt.txt`).

## Lizenz

GPL-3.0 wie VoltaOS (siehe `LICENSE`). Kartenbibliothek: MapLibre Native Qt (BSD-2-Clause).
