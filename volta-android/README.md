# Volta OS – Android-Port (Build-Schicht)

Baut das Original [Omardigio/VoltaOS](https://github.com/Omardigio/VoltaOS) (GPL-3.0) unverändert
mit Qt für Android. Dieses Verzeichnis enthält nur die Port-Schicht: Patches für den Build,
Platzhalter für Linux-only-Teile (Wayland-Compositor, WebEngine, libusb, ffmpeg) und das Android-Manifest.
Der VoltaOS-Quellcode wird beim Build von GitHub geholt (fester Commit, siehe Workflow).

Lizenz dieser Port-Schicht: GPL-3.0-or-later (wie VoltaOS).

## Updates

**Settings → System → Software update** zeigt die installierte Android-Version,
einen festen Button **Check for updates**, den Download-Fortschritt und nach dem
Download **Install update**. Der Installieren-Button bleibt dort erreichbar,
auch wenn die Update-Benachrichtigung geschlossen wurde. Android verlangt die
Installationsbestaetigung und gegebenenfalls die Freigabe fuer „unbekannte Apps“.
Die bestehende automatische Pruefung laeuft 90 Sekunden nach dem Start und
danach alle zehn Minuten weiter.

## Schritt 3: Reifendruck und Android-Anbindung

USB-TPMS nutzt den vorhandenen W204-Empfänger (CH340/FTDI, 19200 Baud). In Vehicle → Tyre pressure aktivieren, USB-Zugriff erlauben und Sollwerte vom Fahrzeugaufkleber einstellen. Die vier Empfängerkanäle lassen sich den Rädern zuordnen; Sensor-Anlernen erfolgt am Empfänger. Unbekannte/veraltete Daten werden nicht als sichere Werte angezeigt. Warnungen erscheinen in Voltas vorhandenen Benachrichtigungen, ohne Sprachausgabe.

Android-Web-Apps und Browser verwenden Qt WebView / Android System WebView. Cookies und Anmeldung bleiben im Android-WebView-Speicher. Manche Anbieter erlauben Anmeldung oder DRM-Wiedergabe nur im externen Browser; dafür gibt es bei Ladefehlern einen Browser-Link. Android System WebView muss installiert und aktiviert sein. Die Linux-Vorschau enthält weiterhin keine Browser-Engine.

Bluetooth unterscheidet fehlende Hardware, fehlende Berechtigungen und einen ausgeschalteten Adapter. BlueStacks ohne Bluetooth-Adapter kann keine echten Geräte koppeln. Verbinden/Trennen führt auf Android zu dessen Bluetooth-Einstellungen, statt eine erfolgreiche Verbindung vorzutäuschen.

OBD verwendet derzeit Bluetooth Classic SPP. USB-TPMS-Unterstützung bedeutet nicht USB-OBD-Unterstützung. Physischer TPMS-Empfänger, Bluetooth-Radio und Anbieter-Anmeldungen müssen zusätzlich am Zielgerät geprüft werden.

## Kartendienste: Suche, POIs, Ampelstandorte und Regenradar

Die vorhandenen Volta-Kartenansichten verwenden jetzt native Android-/Qt-Routen statt
der nicht verfuegbaren Python-Dienste. Das Layout und Voltas GPS bleiben erhalten.

- **Suche:** `/poi-search` nutzt `TOMTOM_KEY`; bei fehlendem Key oder Anbieterfehler
  faellt die Adresssuche auf `MAPTILER_KEY` zurueck. Text wird sicher als Suchtext
  kodiert. Die Suche ist nicht auf die naehere Umgebung beschraenkt. Ein veraltetes
  Suchergebnis kann keine neuere Suchantwort ueberschreiben.
- **Tankstellen/Restaurants:** `/nearby-places` nutzt TomTom (15 km, maximal 20
  Ergebnisse). Dafuer wird `TOMTOM_KEY` benoetigt. Es werden keine Kraftstoffpreise
  erfunden oder aus dem italienischen Originaldienst fuer Deutschland uebernommen.
- **Strasse/Ort:** `/reverse-geocode` nutzt MapTiler, ersatzweise TomTom. Fehlende
  Keys und Netzfehler werden nicht als erfolgreiche Anbieterantwort behandelt.
- **POIs, Ampeln/Stoppschilder und feste Blitzer:** `/map-pois`, `/map-signals`
  und `/map-speedcams` lesen OpenStreetMap ueber die bereits fuer Tempolimits
  verwendeten Overpass-Server. Symbole, Detailansichten und visuelle Hinweise kommen
  aus Voltas vorhandener Oberflaeche. Ampeldaten bezeichnen Standorte, keine
  Echtzeit-Ampelphasen. Blitzer sind OSM-Eintraege, kein Live-Verzeichnis mobiler
  Kontrollen. Unbekannte Richtung/Tempolimits bleiben unbekannt; keine Sprachausgabe.
  Visuelle Blitzerwarnungen setzen einen hoechstens drei Sekunden alten Android-GPS-Fix
  mit gueltiger Geschwindigkeit/Richtung und hoechstens 30 m Genauigkeitsradius voraus.
  Wiederholte Auslieferungen desselben Fixes verlaengern diese Frist nicht. Die geometrische
  Vorauswahl garantiert trotzdem keine Zuordnung zur eigenen Fahrspur oder Strasse.
- **Wetter:** `WeatherPopup.qml` bezieht Vorhersage und Luftqualitaet bereits direkt
  von Open-Meteo. Neu ist `?w=1` im Kartenstil und `/weather-tile/z/x/y` fuer das
  Regenradar: RainViewer, bei Ausfall OpenWeather mit `OWM_KEY`. Kein Key fuer
  RainViewer erforderlich. Zoomstufen ueber 7 vergroessert die Kartenengine; es
  wird keine hoehere Radaraufloesung behauptet. Die Regenfarben stammen vom Anbieter.
  Mehr als zwei Stunden alte Radarframes werden nicht als aktuelles Radar gezeigt.

Provider-Keys bleiben in der vorhandenen privaten `keys.env`. Such-/Umgebungspunkte
gehen an den jeweiligen Suchanbieter, OSM-Abfragen an Overpass und Radarkacheln an
RainViewer/OpenWeather. Kameraaufnahmen sind an keinem dieser Aufrufe beteiligt.

Abfragen sind asynchron, gleichzeitige Downloads und Warteschlangen begrenzt.
Hochstens zwei Overpass-Anfragen laufen zugleich; Suchanfragen haben eigene freie
Kapazitaet und Prioritaet vor wartenden Kartenkacheln. Pro Kartenausschnitt werden
hoechstens zwoelf Kacheln geladen (JSON markiert abgeschnittene Ausschnitte).
Fehler haben eine 60-Sekunden-Pause. Der getrennte Cache ist auf rund 24 MiB und
160 Dateien begrenzt, einzelne Antworten auf 4 MiB. POIs/Blitzer sind sieben Tage,
Ampeln 30 Tage frisch; begrenzt aeltere Kopien werden waehrend einer Aktualisierung
als `stale` markiert. Such- und Wetterdaten werden nicht unbegrenzt als aktuell
ausgegeben. Suchtexte, Standorte und Provider-Keys werden nicht ins Dienstlog geschrieben.

Automatische Tests verwenden simulierte Anbieterantworten und Dummy-Keys, u.a.
Offline-Neustart, API-Fehler, Cache, ungueltige Koordinaten, Radar-Metadaten und
Antwortformate. Echte TomTom-/MapTiler-Key-Berechtigungen muessen mit den am Radio
gespeicherten Keys bestaetigt werden.

Quellen: [VoltaOS map_proxy.py](https://github.com/Omardigio/VoltaOS/blob/7304484efe66a5412445f51838f68ef56075fd5f/services/map_proxy.py)
(GPL-3.0), [OpenStreetMap](https://www.openstreetmap.org/copyright) (ODbL),
[TomTom Search](https://developer.tomtom.com/search-api/documentation),
[MapTiler Geocoding](https://docs.maptiler.com/cloud/api/geocoding/),
[RainViewer](https://www.rainviewer.com/api.html), [OpenWeather](https://openweathermap.org/api/weathermaps).

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

## Start mit gespeicherten Karten-Keys

Ein gueltiger MapTiler-Key garantiert keinen Zugriff auf Voltas fest eingetragene Custom-Stile. Liefert ein Stil 404/403 oder ungueltige Daten, faellt der Android-Kartenproxy auf OpenFreeMap zurueck und unterdrueckt weitere Versuche fuer denselben fehlgeschlagenen Stil bis zum Neustart. Geaenderte Keys erhalten einen neuen Versuch. Ist auch die freie Karte nicht erreichbar, wird ein gueltiger Hintergrundstil ohne Kartendaten geliefert. Schluesseldatei und Modelle werden dabei nicht geloescht.

Exportierte Android-Logs und der vorherige Qt-Loghandler erhalten geschwaerzte Schluessel. Karten-Netzwerkfehler protokollieren Host, Pfad, HTTP- und Netzwerkstatus, keine URL mit Query-Key. Bereits vorhandene Logs werden dadurch nicht nachtraeglich geaendert.

`tests/mapproxy_test.cpp` prueft den echten lokalen HTTP-Proxy mit simulierten Providern: 404/403, ungueltiger Stil, weiterhin erreichbarer Originalstil, Neustart mit gespeichertem Key, vollstaendiger Netzausfall und Logschwaerzung. Es werden keine echten API-Keys oder Provider-Anfragen verwendet. Ein voller Android-/BlueStacks-Neustart ist damit nicht simuliert.

USB-Kameravorschau, Dashcam-Aufzeichnung und die Kamerabild-Anbindung der Schildererkennung sind eingebaut; OSM-Tempolimits bleiben eine separate Datenquelle.

## Kamera und Schildererkennung

**Einstellungen → Vehicle → USB camera** aktivieren und die Kamera-/USB-Berechtigungen erlauben.
Die Live-Vorschau liegt in der **Volta-Kamera-App, grosses Feld Front dashcam**.
Der Knopf **Open camera preview** im Vehicle-Bereich oeffnet genau diese Ansicht.
Die seitlichen Kamerafelder bleiben unverbunden. Die Vorschau behaelt das Seitenverhaeltnis
und verschwindet beim App-Wechsel; kein zweites Dashboard und keine Sprachausgabe.

**Loop recording** und **Traffic sign recognition** sind separat schaltbar. Die
Kamera bleibt standardmaessig aus; Aufnahme und Erkennung sind bei aktivierter Kamera
zunaechst an (Aufnahme wie bisher). Fuer Vorschau/Erkennung ohne Videospeicherung
Loop recording ausschalten. Es wird kein Ton aufgezeichnet. Bei USB-Kameras mit
Mikrofon kann Android trotzdem die Mikrofonberechtigung fuer den Composite-USB-Zugriff
verlangen. Nach Erlaubnis wird die Kamera automatisch erneut geoeffnet.

Die Erkennung verwendet den W204-SignReader mit raeumlicher Mehrbildbestaetigung,
frischen OCR-Lesungen statt wiederholt gezaehlten Cache-Ergebnissen und strenger
Zahlenpruefung. Jede uebernommene Zahl braucht OCR; widersprechende Klassifikatorwerte
werden verworfen (ausser nachvollziehbaren GTSRB-Klassenluecken wie 130/90/40).
Fahrzeugmasken stammen aus demselben Bild und filtern Schilder/Aufkleber auf Autos und Lkw.
Alle Modelle sind in der APK enthalten; keine Bilduebertragung an Cloud-Dienste,
kein Modelldownload auf dem Radio und keine Google-Play-Dienste fuer OCR erforderlich.
Modelle und Lizenzen: `core/src/main/assets/VISION_NOTICE.txt`.

Bestaetigte unbedingte Limits haben zeitweise Vorrang vor Kartenwerten. Zusatzzeichen
(Naesse, Lkw, Uhrzeiten, unleserliche Bedingungen) ergeben keinen allgemeinen Zahlenwert.
Ein erkanntes Aufhebungs- oder Ortsschild erfindet kein neues Limit. Kamerawerte verfallen
spaetestens nach 45 Sekunden, nach einem Wechsel der zugeordneten Strasse oder bei mehr
als 3 Sekunden ohne Kamerabild; Ausschalten/Trennen loescht sie sofort. OSM/Navigation
bleiben unabhaengige Datenquellen. Das ist keine rechtssichere Fahrspurzuordnung:
Abbiegespuren, verdeckte Zusatzzeichen und schwierige Beleuchtung muessen am Fahrzeug
mit echten Aufnahmen bewertet werden. Multi-frame-Verfahren reduzieren Fehlmeldungen,
beweisen aber nicht die Gueltigkeit eines Schilds fuer die eigene Spur.

Die Bildanalyse hat einen eigenen Arbeitsthread und hoechstens ein Bild in Bearbeitung;
bei Last werden Bilder verworfen statt eine Warteschlange aufzubauen. Die ersten nativen
Modell-/Bildaufrufe sind mit einer Startmarkierung abgesichert: Nach einer unterbrochenen
Initialisierung bleibt die Erkennung beim naechsten Start aus, bis sie explizit wieder
eingeschaltet wird. Kameravorschau und Aufnahme bleiben davon getrennt.

Pruefung am Radio: USB-Geraet erkannt → Front-dashcam-Livebild → Aufnahme aus bei weiter
laufender Erkennung → Erkennung aus/an → USB trennen/erneut anschliessen → App-Wechsel.
IMX662 bezeichnet den Sensor; USB-Formate und Berechtigungen haengen vom konkreten
Kameramodul und Radio ab. Die Integration ist automatisiert geprueft, die Hardwarefolge
und reale Erkennungsqualitaet sind ohne die Kamera hier nicht bestaetigt.
