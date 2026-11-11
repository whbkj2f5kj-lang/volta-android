# OBD-Befund: W204 C220 CDI, OM651, 5G-Tronic

Grundlage: zwei bereitgestellte Adapter-Test-Exporte vom 02.11.2026, 00:38; älteren Treffern ohne Antwortadresse kann kein Steuergerät sicher zugeordnet werden. Keine FIN oder Rohdateien werden veröffentlicht.

## Was die Dateien belegen

- Protokoll laut Adapter: CAN 11 Bit, 500 kbit/s. `ELM327 v1.5` ist eine Selbstauskunft, kein Echtheitsnachweis.
- `22F100` liefert `7E8 07 62 F1 00 02 11 0E 01`, selbst nach `ATSH7E1` und `ATSH7E2`. Tatsächlicher Antwortsender ist damit 7E8. Diese Antworten sind keine bestätigten Getriebemesswerte.
- Mit Empfangsfilter 7E9 antworten die getesteten Getriebe-Kandidaten nicht. `2130` liefert auch ohne Filter keine Daten. Die Formel für eine Getriebeöltemperatur ist damit nicht verifiziert.
- Standard-DPF-Abfragen `017A`, `017C`, `018B` liefern keine Daten. Die Unterstützungsmaske für 0140 enthält keinen nächsten Block 0160. Dies belegt fehlende Standard-PIDs in dieser Verbindung, nicht das Fehlen herstellerspezifischer DPF-Diagnose.
- `22F100` ist bereits eine positive Datenkennungsantwort außerhalb Mode 01. Bedeutung und Skalierung sind unbekannt. Das letzte Byte folgt hier der gewählten Diagnosesitzung (01/03); es darf nicht als Öltemperatur oder anderer Fahrwert interpretiert werden.
- `010C`: 0/min; `0105`: 44 °C. `0101`: Motorkontrollleuchte aus, 0 emissionsbezogene gespeicherte Fehler laut Motor-OBD. Das ist kein vollständiger Fehlercheck aller Steuergeräte.

## Was noch offen ist

Ein ignorierter Sendeheader im Adapter ist eine plausible Erklärung der falschen Antwortquelle. Die Exporte zeigen aber nicht den tatsächlich gesendeten CAN-Frame. Adapterverhalten, Diagnoseweg/Gateway, Adressierung und konkrete Steuergerätevariante sind noch voneinander zu trennen. Auch ein Rundruf muss nicht sämtliche Mercedes-Steuergeräte sichtbar machen.

5G-Tronic grenzt die Suche auf die 722.6-Familie ein. Ein KWP-Kandidat eines fremden Getriebes oder einer Ersatz-TCU ist keine bestätigte Abfrage für das originale Steuergerät. Ebenso darf eine UDS-Sitzungsnummer nicht ungeprüft auf KWP übertragen werden.

## Konsequenz im Code

Der gezielte Scanner aktiviert Antwortheader und prüft die tatsächliche Quelle zusätzlich zum Adapterfilter. Headerlose, fremde, unvollständige und falsch sequenzierte ISO-TP-Antworten erzeugen keine neuen gezielten Treffer. Der Adapter-Test bewahrt die Rohantworten. Treffer und Fortsetzungsstände verwenden einen neuen Speicherbereich. Alte, nicht quellgeprüfte Trefferdateien bleiben erhalten, werden aber nicht als neue verifizierte Treffer geladen.

Es werden keine spekulativen Mercedes-Formeln in Live-Anzeigen eingebaut. Eine Regeneration aus Abgastemperatur bleibt eine Schätzung, kein ausgelesener DPF-Zustand. Eine bestätigte Quelle allein belegt noch nicht die Bedeutung eines Datenbytes.

## Nächster sinnvoller Nachweis

Mit einem bekannten funktionierenden Mercedes-Diagnosegerät Motor-/Getriebesteuergerätevariante sowie die gewünschten Istwerte (zuerst Getriebeöltemperatur und DPF-Differenzdruck/Rußbeladung) lesen. Anschließend legal verfügbare Dokumentation oder einen passiven Mitschnitt genau dieser Leseabfragen verwenden: Adresse, Transport, Dienst, Kennung und Skalierung getrennt belegen. Keine weiteren blinden Vollbereichsscans.

Voltas OBD-Transport ist momentan Bluetooth Classic SPP. Ein USB-Adapter benötigt einen eigenen OBD-Transport und kann nicht allein durch Anstecken verwendet werden. USB-TPMS ist ein anderer Treiber/Protokollpfad. Der vLinker FS wird vom Hersteller primär für FORScan/Ford/Mazda beworben; das ist kein Nachweis für Zugriff auf diese Mercedes-Getriebevariante.

Quellen: [ELM327-Datenblatt](https://elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf), [Vgate vLinker FS USB](https://vgatemall.com/products-detail/i-19/), [Ultimate NAG52 (Ersatzsteuergerät, nicht OEM-PID-Nachweis)](https://github.com/rnd-ash/ultimate-nag52-fw).

## MBLink-Abgleich (10.11.2026; implementiert als Forschungsgrundlage)

Quellen: [MBLink Mercedes](https://github.com/Infiltrator-Projects/MBLink/blob/main/docs/MERCEDES.md),
[MBLink DID Lab](https://github.com/Infiltrator-Projects/MBLink/blob/main/docs/DID_LAB.md),
[MBLink ELM327](https://github.com/Infiltrator-Projects/MBLink/blob/main/docs/ELM327.md).

Voltas `SafeElm` unterstützt zusätzlich zu den unveränderten funktionalen/physikalischen
OBD-Routen eine **explizite Allowlist dokumentierter Mercedes-TX/RX-Paare**.
Die Antwort wird nur anerkannt, wenn ihr tatsächlicher CAN-Absender der
erwarteten RX-ID entspricht. Ein `OK` auf `ATSH` oder `ATCRA` beweist
noch **keine** korrekte CAN-Übertragung durch den Adapter.

| Diagnoseroute TX → RX | Herkunft / behauptete Familie | Belegstatus |
| --- | --- | --- |
| `602 → 480` | Mercedes Produktions-VIN-Route, ECU unklassifiziert | Externe Dokumentation |
| `607 → 587` | Mercedes Produktions-VIN-Route, ECU unklassifiziert | Externe Dokumentation |
| `612 → 482` | EIS_204/EIS_212 | Externe Dokumentation |
| `632 → 486` | ABR2XT/ESP | Externe Dokumentation |
| `652 → 48A` | HU_204 | Externe Dokumentation |
| `6B2 → 496` | EPS212 | Externe Dokumentation; Einbau im W204 unbekannt |
| `7E1 → 7E9` | GS/Getriebe, KWP `21 30` | Berichteter Kandidat; bei diesem Fahrzeug bislang keine Antwort |

Die Tabelle ist **keine ECU-Bestandsliste** des Fahrzeugs. Sie wird **nicht**
automatisch über alle Einträge abgefragt. Die vorhandene ELM-Bluetooth-SPP-Anbindung,
Adaptertests, Quellenprüfung und Exportlogik bleiben bestehen. Es wird insbesondere
kein Mercedes-Deep-Scan aus der MBLink-Dokumentation ungeprüft übernommen.

Weitere Kandidaten bleiben getrennt von der produktiven Live-Anzeige:

- Delphi CRD3 `22 2007`: dokumentierte Antwort `62 20 07 05 FC`
  entspricht `0x05FC / 128 = 11,96875 V`. Ein strikter Decoder ist als
  Forschungsbaustein vorhanden, wird jedoch **nicht** automatisch abgefragt.
  Es ist nicht verifiziert, dass das konkrete W204-Motorsteuergerät diese DID unterstützt.
- GS `21 30`: vorhandener Versuch über `7E1 → 7E9` blieb ohne nachgewiesene
  Antwort. Ungeprüfte Bytepositionen oder die Formel `L − 50` dürfen nicht als
  bestätigte Getriebetemperatur dargestellt werden.
- OM651 DPF-Ruß-/Aschebeladung, Differenzdruck und Regenerationsstatus:
  MBLink führt diese Größen als `corroborated-unmapped`. Es gibt dort
  **noch keine** für diesen W204 bestätigte DID-/Skalierungsdefinition.

### Nächste Verifikation

Zuerst die genaue Motor-ECU-Teilenummer und die Getriebe-ECU-Variante über ein
bewährtes Mercedes-Diagnosesystem ermitteln. Dann für **einen** Messwert die
tatsächliche TX/RX-Paarung, vollständige Antwort-PDU, Kodierung und plausible
Referenzwerte dokumentieren. Erst danach den Wert für die Live-Anzeige freigeben.
Keine weiteren pauschalen PID- oder CAN-Bereichsscans.


## Volta Vehicle: gezielter Mercedes-Test

Unter **Settings → Vehicle → Mercedes diagnostic check · 5G-Tronic** steht ein
manuell auslösbarer, schreibgeschützter Test mit **Save report** zur Verfügung.
Die Diagnose läuft asynchron im Android-Kotlin-Core über den bereits bestehenden,
exklusiven OBD-Adapterzugriff. Nach dem Test werden die Ergebnisse im Vehicle-Menü
angezeigt und können unter `Downloads/W204/W204-Mercedes-*.txt` exportiert werden
(Android 10+; ältere Android-Versionen nutzen den App-Dateiordner).

Der Test sendet **nur diese beiden** quellengeprüften Leseabfragen:

1. `7E1 → 7E9`, KWP `21 30`: 722.6/EGS-Getriebeöltemperatur-Kandidat.
   Positive Antworten werden als roher `61 30 ...`-Nachweis abgelegt,
   **nicht** als Grad Celsius angezeigt, weil Bytes/Skalierung am W204 fehlen.
2. `7E0 → 7E8`, UDS `22 2007`: Delphi-CRD3-Batteriespannungs-Kandidat;
   nur bei exakt gültigem `62 20 07 XX YY`-Format als **unbestätigter
   Kandidat** berechnet.

Damit sind die vorhandenen Mercedes-Routen technisch nutzbar, ohne den
Produktivscanner durch einen pauschalen DID-Sweep zu ersetzen. Der Test
berücksichtigt den tatsächlichen CAN-Antwortabsender; `NO DATA`,
Negativantworten, abgelehnte Filter und Adapter-Fehler werden unverfälscht
im Report dokumentiert. **DPF-Rußbeladung wird nicht angefragt**, weil
dafür weiterhin keine belegte herstellerspezifische Kennung vorliegt.

## OM651 / Delphi CRD3: belegte Kennungen aus WiCAN_W906 (11.11.2026)

Quelle: [mcgyver78/WiCAN_W906](https://github.com/mcgyver78/WiCAN_W906) (GPL-3.0). Dort wurden an einem
Sprinter W906 316 CDI (OM651, Delphi CRD3, Modelljahr 2016) rund 45 Kennungen des Motorsteuergeraets
(`7E0 → 7E8`, UDS `22 xxxx`, Standard-Sitzung) gegen die Xentry-Istwerte ermittelt, darunter
Russbeladung (`604B`), Aschegehalt (`60B4`), Strecke seit Regeneration (`6040`), Regenerationsstatus
(`6081`), DPF-Differenzdruck (`2012`), Abgastemperaturen, Oeltemperatur und Oelstand. `2007`
(Bordspannung, /128) deckt sich mit der unabhaengigen MBLink-Angabe; das spricht fuer eine gemeinsame
Kennungsfamilie.

Volta fuehrt diese Liste in `Crd3Values.kt` und liest sie im Mercedes-Test genau einmal, nur lesend.
Der Test gleicht Drehzahl, Kuehlmittel, Ansaugluft, Spannung, Luftdruck und Raildruck mit den genormten
OBD-Werten desselben Motors ab. Ein passender Abgleich stuetzt ausschliesslich den verglichenen Kanal. Andere Kennungen, insbesondere DPF-Werte, bleiben unbestaetigt und werden nicht automatisch fuer die Live-Anzeige freigegeben.

## Belastbarer ELM-Bericht (11.11.2026)

Die WiCAN-Kandidaten bleiben erhalten. Referenzstand der externen Quelle:
[`8660053b0b154a511f8964b0fe6d0767322ba6d9`](https://github.com/mcgyver78/WiCAN_W906/blob/8660053b0b154a511f8964b0fe6d0767322ba6d9/W906.md).
Die MBito-APK wurde hier nicht dekompiliert. Im Repository liegt kein nachvollziehbarer MBito-Nachweis fuer eine neue Kennung samt Route und Skalierung vor. Die Rohkandidaten werden deshalb nicht als MBito-verifizierte Messwerte ausgegeben. MBitos eigene Adapteranbindung ist kein Beweis fuer Kompatibilitaet mit einem beliebigen ELM327.

Der manuelle Mercedes-Test prueft jetzt die vorhandenen Kennungen wie folgt:

- Der passende unterstuetzte Mode-01-Motorwert wird direkt vor und nach dem jeweiligen Herstellerwert gelesen. Es werden keine alten Dashboard-Werte verwendet. Spannung wird mit Motor-PID `0142` verglichen, nicht mit der eigenen `ATRV`-Messung des Adapters.
- Jede Referenz gilt nur fuer ihren Messkanal. Bei fehlender Referenz, einem Zeitfenster ueber 5 Sekunden oder instabilen Vorher-/Nachher-Werten ist kein belastbarer Vergleich moeglich. Ein einzelner passender Vergleich ist noch keine fahrzeugspezifische Validierung der Formel.
- Positive Antwort, Ablehnung mit NRC, Warten (`7F xx 78`), unerwartete Antwort und fehlende Daten bleiben getrennt. Alle vollstaendigen PDUs werden durchsucht, statt nur die erste Zeile zu verwenden. Ein passendes SID/DID-Echo ist erforderlich.
- Beim NRC 78 bleibt der Transport innerhalb der urspruenglichen Anfragefrist empfangsbereit, auch wenn der Adapter dazwischen einen Prompt liefert. Es gibt keine automatische Wiederholung der Anfrage und keine verlaengerte Endlosschleife. Fehlt die Endantwort, wird WARTEN statt Ablehnung gemeldet.
- Der gespeicherte Bericht enthaelt zusaetzlich Adapterbefehle, rohe Antwortframes einschliesslich tatsaechlicher CAN-Absender und Zeiten relativ zum Teststart. Rohantworten werden auf 4096 Zeichen je Austausch begrenzt und nicht in Logcat oder an einen Server geschrieben. Verbindungsabbrueche und verworfene Antworten bleiben damit unterscheidbar. Der Routenkontext am Zeilenanfang bezeichnet die zuletzt erfolgreich eingerichtete Route; bei AT-Konfigurationsbefehlen zeigt der Befehl selbst die neu angeforderte Einstellung.
- Nach einem gescheiterten Routenaufbau werden Messwertabfragen blockiert und beim Verlassen des exklusiven Zugriffs die Standard-OBD-Konfiguration wiederhergestellt.

DPF-Russmasse/-Prozent aus derselben Kennung sind keine zwei unabhaengigen Bestaetigungen. Ein erfolgreicher Drehzahl-/Spannungsabgleich bestaetigt weder DPF-Asche noch Regenerationsstatus. Alle herstellerspezifischen Werte bleiben als Kandidaten gekennzeichnet. Es wird kein Live-Polling, keine FIN-Abfrage und keine neue Sitzungsumschaltung hinzugefuegt.

Der naechste Fahrzeugbericht soll fuer jeden interessanten Wert liefern: genaue Anfrage, TX/RX, rohe Antwort, Kennung/Laenge, berechneter Kandidat und gegebenenfalls zeitnaher unabhaengiger Referenzvergleich. Erst eine separate, messwertbezogene Verifikation erlaubt eine spaetere Freigabe. Ein `NO DATA` allein beweist keinen Adapterdefekt.
