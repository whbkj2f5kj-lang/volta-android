package de.jarvis.w204.obd

/**
 * Dringlichkeit:
 * 0 = Info, 1 = beobachten, 2 = zeitnah in die Werkstatt, 3 = sofort sicher anhalten.
 */
data class DtcInfo(
    val title: String,
    val system: String,
    val causes: String,
    val advice: String,
    val severity: Int,
    val known: Boolean,
) {
    val severityText: String
        get() = when (severity) {
            3 -> "Sofort anhalten"
            2 -> "Werkstatt zeitnah"
            1 -> "Beobachten"
            else -> "Info"
        }
}

object DtcDatabase {

    private data class E(val title: String, val causes: String, val advice: String, val sev: Int)

    private const val WS = "Weiterfahren meist möglich, aber zeitnah prüfen lassen."
    private const val NOTLAUF = "Motor kann in den Notlauf (wenig Leistung) gehen. Zeitnah prüfen lassen."

    private val db: Map<String, E> = mapOf(
        // ---- Kraftstoffsystem / Common-Rail
        "P0087" to E("Raildruck zu niedrig", "Kraftstofffilter zugesetzt, Luft im Kraftstoffsystem, Leck in Leitung, Hochdruckpumpe oder Druckregelventil, Mengenregelventil.", NOTLAUF + " Bei Dieselgeruch nicht weiterfahren.", 2),
        "P0088" to E("Raildruck zu hoch", "Druckregelventil oder Mengenregelventil klemmt, Raildrucksensor fehlerhaft.", NOTLAUF, 2),
        "P0089" to E("Kraftstoffdruckregelung – Funktion", "Druckregelventil/Mengenregelventil, Kabel/Stecker, Kraftstoffversorgung.", NOTLAUF, 2),
        "P0090" to E("Kraftstoffdruckregler – Stromkreis", "Kabel/Stecker am Mengen- oder Druckregelventil, Ventil defekt.", NOTLAUF, 2),
        "P0093" to E("Großes Leck im Kraftstoffsystem erkannt", "Undichte Hochdruckleitung, Rücklauf, Injektor oder Pumpe.", "Sicher anhalten und Motor aus – Brandgefahr bei Diesel auf heißen Teilen. Auf Dieselgeruch/Tropfen prüfen.", 3),
        "P0180" to E("Kraftstofftemperatursensor – Stromkreis", "Sensor, Kabel oder Stecker.", WS, 1),
        "P0182" to E("Kraftstofftemperatursensor – Signal zu niedrig", "Kurzschluss im Kabel, Sensor defekt.", WS, 1),
        "P0183" to E("Kraftstofftemperatursensor – Signal zu hoch", "Unterbrechung im Kabel, Sensor defekt.", WS, 1),
        "P0190" to E("Raildrucksensor – Stromkreis", "Sensor, Kabel, Stecker (Korrosion).", NOTLAUF, 2),
        "P0191" to E("Raildrucksensor – Bereich/Funktion", "Sensor ungenau, Druckregelung gestört.", NOTLAUF, 2),
        "P0192" to E("Raildrucksensor – Signal zu niedrig", "Kurzschluss, Sensor defekt.", NOTLAUF, 2),
        "P0193" to E("Raildrucksensor – Signal zu hoch", "Unterbrechung, Sensor defekt.", NOTLAUF, 2),
        "P0200" to E("Einspritzventile – Stromkreis", "Kabelbaum zu den Injektoren, Steuergerät-Endstufe.", NOTLAUF, 2),
        "P0201" to E("Injektor Zylinder 1 – Stromkreis", "Injektor, Kabel/Stecker am Injektor.", NOTLAUF, 2),
        "P0202" to E("Injektor Zylinder 2 – Stromkreis", "Injektor, Kabel/Stecker am Injektor.", NOTLAUF, 2),
        "P0203" to E("Injektor Zylinder 3 – Stromkreis", "Injektor, Kabel/Stecker am Injektor.", NOTLAUF, 2),
        "P0204" to E("Injektor Zylinder 4 – Stromkreis", "Injektor, Kabel/Stecker am Injektor.", NOTLAUF, 2),
        "P0263" to E("Zylinder 1 – Mengenabweichung/Laufruhe", "Injektor verschlissen oder undicht, Kompression.", "Injektor-Mengenabgleich in der Werkstatt prüfen lassen.", 2),
        "P0266" to E("Zylinder 2 – Mengenabweichung/Laufruhe", "Injektor verschlissen oder undicht, Kompression.", "Injektor-Mengenabgleich in der Werkstatt prüfen lassen.", 2),
        "P0269" to E("Zylinder 3 – Mengenabweichung/Laufruhe", "Injektor verschlissen oder undicht, Kompression.", "Injektor-Mengenabgleich in der Werkstatt prüfen lassen.", 2),
        "P0272" to E("Zylinder 4 – Mengenabweichung/Laufruhe", "Injektor verschlissen oder undicht, Kompression.", "Injektor-Mengenabgleich in der Werkstatt prüfen lassen.", 2),
        "P0627" to E("Kraftstoffpumpe – Steuerstromkreis offen", "Vorförderpumpe, Relais, Sicherung, Kabel.", "Startprobleme möglich. Zeitnah prüfen lassen.", 2),

        // ---- Luft / Ladedruck / Sensoren
        "P0045" to E("Turbolader-Ladedrucksteller – Stromkreis offen", "Elektrischer Steller am Turbo, Kabel/Stecker.", NOTLAUF, 2),
        "P0046" to E("Turbolader-Ladedrucksteller – Bereich/Funktion", "Steller schwergängig, VTG-Leitschaufeln verkokt.", NOTLAUF, 2),
        "P0047" to E("Turbolader-Ladedrucksteller – Signal zu niedrig", "Kurzschluss, Steller defekt.", NOTLAUF, 2),
        "P0048" to E("Turbolader-Ladedrucksteller – Signal zu hoch", "Unterbrechung, Steller defekt.", NOTLAUF, 2),
        "P0069" to E("Ladedruck und Luftdruck passen nicht zusammen", "Ladedrucksensor oder Höhensensor ungenau, Schlauch am Sensor.", WS, 1),
        "P0097" to E("Ladelufttemperatursensor 2 – Signal zu niedrig", "Sensor nach dem Ladeluftkühler, Kabel.", WS, 1),
        "P0098" to E("Ladelufttemperatursensor 2 – Signal zu hoch", "Sensor nach dem Ladeluftkühler, Kabel.", WS, 1),
        "P0100" to E("Luftmassenmesser – Stromkreis", "Luftmassenmesser, Kabel/Stecker.", NOTLAUF, 2),
        "P0101" to E("Luftmassenmesser – Bereich/Funktion", "Luftmassenmesser verschmutzt, Leck im Ansaug- oder Ladeluftsystem, AGR-Ventil klemmt offen.", WS, 2),
        "P0102" to E("Luftmassenmesser – Signal zu niedrig", "Kabel, Luftmassenmesser, Falschluft.", WS, 2),
        "P0103" to E("Luftmassenmesser – Signal zu hoch", "Kabel, Luftmassenmesser.", WS, 2),
        "P0106" to E("Lade-/Saugrohrdrucksensor – Bereich/Funktion", "Sensor verschmutzt (Ölnebel), Leck in der Ladeluftstrecke.", WS, 2),
        "P0107" to E("Lade-/Saugrohrdrucksensor – Signal zu niedrig", "Kabel, Sensor.", WS, 2),
        "P0108" to E("Lade-/Saugrohrdrucksensor – Signal zu hoch", "Kabel, Sensor.", WS, 2),
        "P0110" to E("Ansauglufttemperatursensor – Stromkreis", "Sensor, Kabel.", WS, 1),
        "P0112" to E("Ansauglufttemperatursensor – Signal zu niedrig", "Kurzschluss, Sensor.", WS, 1),
        "P0113" to E("Ansauglufttemperatursensor – Signal zu hoch", "Unterbrechung, Stecker ab, Sensor.", WS, 1),
        "P0234" to E("Ladedruck zu hoch (Überladung)", "Turbo-Verstellung (VTG) klemmt, Ladedrucksteller, Ladedrucksensor.", NOTLAUF + " Nicht unter Volllast fahren.", 2),
        "P0235" to E("Ladedrucksensor – Stromkreis", "Sensor, Kabel.", WS, 2),
        "P0236" to E("Ladedrucksensor – Bereich/Funktion", "Sensor verschmutzt, Schlauch zum Sensor.", WS, 2),
        "P0237" to E("Ladedrucksensor – Signal zu niedrig", "Kabel, Sensor.", WS, 2),
        "P0238" to E("Ladedrucksensor – Signal zu hoch", "Kabel, Sensor.", WS, 2),
        "P0299" to E("Ladedruck zu niedrig (Unterladung)", "Beim OM651 häufig: Ladeluftschlauch gerissen/abgerutscht oder Ladeluftkühler undicht. Außerdem Turbolader, VTG-Steller, Unterdruckleitungen.", NOTLAUF + " Erst Ladeluftschläuche auf Risse und Ölspuren prüfen – oft günstige Ursache.", 2),
        "P2263" to E("Turbolader-Ladedrucksystem – Leistung", "Undichtigkeit in der Ladeluftstrecke, Turbo, Steller.", NOTLAUF, 2),
        "P2279" to E("Falschluft im Ansaugsystem", "Undichte Schläuche, Dichtungen am Ansaugkrümmer.", WS, 2),

        // ---- Motor / Kühlung / Öl
        "P0115" to E("Kühlmitteltemperatursensor – Stromkreis", "Sensor, Kabel, Stecker.", "Kühlerlüfter läuft evtl. dauerhaft. Zeitnah prüfen.", 2),
        "P0116" to E("Kühlmitteltemperatursensor – Bereich/Funktion", "Sensor ungenau, Thermostat.", WS, 1),
        "P0117" to E("Kühlmitteltemperatursensor – Signal zu niedrig", "Kurzschluss, Sensor.", WS, 2),
        "P0118" to E("Kühlmitteltemperatursensor – Signal zu hoch", "Unterbrechung, Sensor.", WS, 2),
        "P0128" to E("Motor erreicht Betriebstemperatur nicht", "Thermostat klemmt offen (häufig), Kühlmitteltemperatursensor.", "Mehrverbrauch und schlechte Heizleistung, aber ungefährlich. Thermostat tauschen lassen.", 1),
        "P0217" to E("Motor-Übertemperatur", "Kühlmittelmangel, Kühlerlüfter, Wasserpumpe, Thermostat.", "Sofort sicher anhalten, Motor abstellen. Kühlmittel erst nach Abkühlen prüfen.", 3),
        "P0218" to E("Getriebe-Übertemperatur", "Getriebeöl, Ölkühler, starke Belastung (Anhänger, Berg).", "Anhalten und abkühlen lassen. Danach schonend weiterfahren und prüfen lassen.", 3),
        "P0219" to E("Motor überdreht", "Falscher Gang, Fehlfunktion.", "Schonend weiterfahren, prüfen lassen.", 1),
        "P0335" to E("Kurbelwellensensor – Stromkreis", "Sensor, Kabel, Geberrad.", "Motor kann ausgehen oder nicht anspringen. Zeitnah prüfen.", 2),
        "P0340" to E("Nockenwellensensor – Stromkreis", "Sensor, Kabel.", "Startprobleme möglich. Zeitnah prüfen.", 2),
        "P0520" to E("Öldrucksensor – Stromkreis", "Sensor, Kabel.", "Ölstand prüfen. Zeitnah prüfen lassen.", 2),
        "P0524" to E("Öldruck zu niedrig", "Ölmangel, Ölpumpe, Lagerschaden.", "Sofort sicher anhalten und Motor aus – sonst Motorschaden möglich. Ölstand prüfen.", 3),

        // ---- Glühanlage
        "P0380" to E("Glühanlage – Stromkreis", "Glühzeitsteuergerät, Glühkerzen, Kabel.", "Kaltstart schwierig, besonders im Winter. Prüfen lassen.", 1),
        "P0381" to E("Glühanzeige – Stromkreis", "Kombiinstrument/Kabel.", "Unkritisch.", 0),
        "P0670" to E("Glühzeitsteuergerät – Stromkreis", "Steuergerät, Kabel, Sicherung.", "Kaltstart schwierig. Prüfen lassen.", 1),
        "P0671" to E("Glühkerze Zylinder 1 – Stromkreis", "Glühkerze defekt (häufig), Kabel.", "Kaltstart schwieriger. Beim nächsten Service tauschen.", 1),
        "P0672" to E("Glühkerze Zylinder 2 – Stromkreis", "Glühkerze defekt (häufig), Kabel.", "Kaltstart schwieriger. Beim nächsten Service tauschen.", 1),
        "P0673" to E("Glühkerze Zylinder 3 – Stromkreis", "Glühkerze defekt (häufig), Kabel.", "Kaltstart schwieriger. Beim nächsten Service tauschen.", 1),
        "P0674" to E("Glühkerze Zylinder 4 – Stromkreis", "Glühkerze defekt (häufig), Kabel.", "Kaltstart schwieriger. Beim nächsten Service tauschen.", 1),

        // ---- Verbrennung
        "P0300" to E("Verbrennungsaussetzer – mehrere Zylinder", "Injektoren, Kompression, Kraftstoffqualität.", "Motor läuft unrund. Zeitnah prüfen lassen.", 2),
        "P0301" to E("Verbrennungsaussetzer Zylinder 1", "Injektor, Kompression.", "Zeitnah prüfen lassen.", 2),
        "P0302" to E("Verbrennungsaussetzer Zylinder 2", "Injektor, Kompression.", "Zeitnah prüfen lassen.", 2),
        "P0303" to E("Verbrennungsaussetzer Zylinder 3", "Injektor, Kompression.", "Zeitnah prüfen lassen.", 2),
        "P0304" to E("Verbrennungsaussetzer Zylinder 4", "Injektor, Kompression.", "Zeitnah prüfen lassen.", 2),

        // ---- AGR / Abgas / Partikelfilter
        "P0400" to E("Abgasrückführung – Durchfluss", "AGR-Ventil verkokt, AGR-Kühler.", WS, 1),
        "P0401" to E("Abgasrückführung – Durchfluss zu gering", "AGR-Ventil oder -Kühler verkokt (Kurzstrecke).", WS, 1),
        "P0402" to E("Abgasrückführung – Durchfluss zu hoch", "AGR-Ventil klemmt offen.", "Leistungsmangel/Ruß möglich. Zeitnah prüfen.", 2),
        "P0403" to E("AGR-Ventil – Stromkreis", "Ventil/Steller, Kabel.", WS, 1),
        "P0404" to E("AGR-Ventil – Bereich/Funktion", "Ventil schwergängig/verkokt.", WS, 1),
        "P0405" to E("AGR-Positionssensor – Signal zu niedrig", "Ventil/Sensor, Kabel.", WS, 1),
        "P0406" to E("AGR-Positionssensor – Signal zu hoch", "Ventil/Sensor, Kabel.", WS, 1),
        "P0420" to E("Katalysator – Wirkungsgrad zu gering", "Oxidationskatalysator gealtert, Abgastemperatursensor.", "Abgaswerte schlechter, kurzfristig unkritisch.", 1),
        "P0470" to E("Abgasdrucksensor – Stromkreis", "Sensor, Kabel, Schlauch.", WS, 1),
        "P0471" to E("Abgasdrucksensor – Bereich/Funktion", "Schlauch zum Sensor porös/verstopft, Sensor.", WS, 1),
        "P2002" to E("Partikelfilter – Wirkungsgrad zu gering", "Filter beschädigt (Risse), Differenzdrucksensor/Schläuche.", "Zeitnah prüfen lassen (AU/TÜV relevant).", 2),
        "P2031" to E("Abgastemperatursensor 2 – Stromkreis", "Sensor (häufig), Kabel.", "Regeneration des Partikelfilters kann gestört sein. Zeitnah prüfen.", 2),
        "P2032" to E("Abgastemperatursensor 2 – Signal zu niedrig", "Sensor, Kabel.", "Regeneration kann gestört sein. Zeitnah prüfen.", 2),
        "P2033" to E("Abgastemperatursensor 2 – Signal zu hoch", "Sensor, Kabel.", "Regeneration kann gestört sein. Zeitnah prüfen.", 2),
        "P242F" to E("Partikelfilter – Aschebeladung zu hoch", "Filter am Ende der Lebensdauer (Asche lässt sich nicht freibrennen), hoher Ölverbrauch.", "Filter muss gereinigt oder getauscht werden.", 2),
        "P244A" to E("Partikelfilter – Differenzdruck zu niedrig", "Schlauch zum Differenzdrucksensor ab/porös, Filter beschädigt.", "Zeitnah prüfen lassen.", 2),
        "P244B" to E("Partikelfilter – Differenzdruck zu hoch", "Filter stark beladen, Sensor.", "Längere gleichmäßige Fahrt (20+ min Landstraße/Autobahn) kann helfen. Sonst Werkstatt.", 2),
        "P2452" to E("DPF-Differenzdrucksensor – Stromkreis", "Sensor, Kabel.", "Regeneration gestört. Zeitnah prüfen.", 2),
        "P2453" to E("DPF-Differenzdrucksensor – Bereich/Funktion", "Beim OM651 häufig: Messschläuche porös, abgerutscht oder verstopft. Sensor selbst.", "Erst Schläuche zum Sensor prüfen – oft günstige Ursache.", 2),
        "P2454" to E("DPF-Differenzdrucksensor – Signal zu niedrig", "Schlauch ab, Sensor, Kabel.", "Zeitnah prüfen.", 2),
        "P2455" to E("DPF-Differenzdrucksensor – Signal zu hoch", "Filter stark beladen, Schlauch verstopft, Sensor.", "Zeitnah prüfen.", 2),
        "P2458" to E("Partikelfilter – Regeneration dauert zu lange", "Häufig abgebrochene Regenerationen (Kurzstrecke), Abgastemperatursensor.", "Öfter längere Strecken fahren. Prüfen lassen.", 2),
        "P2459" to E("Partikelfilter – Regeneration zu häufig", "Kurzstrecke, erhöhter Rußausstoß (Injektoren, AGR), Sensor.", "Prüfen lassen. Ölstand beobachten (Ölverdünnung).", 2),
        "P2463" to E("Partikelfilter – Rußbeladung zu hoch", "Viele abgebrochene Regenerationen (Kurzstrecke), Sensor, erhöhter Rußausstoß.", "Wenn möglich 20–30 min gleichmäßig über 2.000 U/min fahren (Autobahn/Landstraße). Bleibt der Fehler: Werkstatt (Zwangsregeneration). Nicht ignorieren – Filter kann irreparabel verstopfen.", 2),

        // ---- Elektrik / Steuergerät
        "P0480" to E("Kühlerlüfter – Steuerung", "Lüfter, Relais/Endstufe, Kabel.", "Überhitzungsgefahr im Stau. Temperatur im Blick behalten, zeitnah prüfen.", 2),
        "P0500" to E("Geschwindigkeitssignal", "Raddrehzahlsensor/ESP-Signal, CAN.", WS, 1),
        "P0560" to E("Bordnetzspannung – Fehlfunktion", "Batterie, Lichtmaschine, Masseverbindungen.", "Batterie und Ladespannung prüfen.", 1),
        "P0562" to E("Bordnetzspannung zu niedrig", "Batterie schwach, Lichtmaschine/Regler, Masse.", "Batterie prüfen – Startprobleme möglich.", 1),
        "P0563" to E("Bordnetzspannung zu hoch", "Lichtmaschinenregler.", "Zeitnah prüfen – Überspannung kann Elektronik schädigen.", 2),
        "P0571" to E("Bremslichtschalter – Stromkreis", "Bremslichtschalter (häufig), Kabel.", "Tempomat und Bremslicht prüfen.", 1),
        "P0600" to E("Steuergerät – Kommunikation", "CAN-Verbindung, Stecker, Bordspannung.", WS, 1),
        "P0601" to E("Steuergerät – Speicherfehler (Prüfsumme)", "Steuergerät-Software.", "Werkstatt.", 2),
        "P0602" to E("Steuergerät – Programmierfehler", "Steuergerät nicht oder falsch programmiert.", "Werkstatt.", 2),
        "P0606" to E("Steuergerät – Prozessorfehler", "Steuergerät intern, Spannungsversorgung.", "Werkstatt.", 2),
        "P0641" to E("Sensor-Referenzspannung A", "Kurzschluss an einem Sensor/Kabel dieser Versorgung.", NOTLAUF, 2),
        "P0651" to E("Sensor-Referenzspannung B", "Kurzschluss an einem Sensor/Kabel dieser Versorgung.", NOTLAUF, 2),
        "P0685" to E("Hauptrelais – Stromkreis", "Relais, Kabel.", WS, 2),

        // ---- Getriebe (5G-Tronic)
        "P0700" to E("Getriebesteuerung meldet Fehler", "Im Getriebesteuergerät ist ein Fehler gespeichert.", "Fehlerspeicher Getriebe beachten (steht weiter unten, falls gelesen).", 2),
        "P0715" to E("Getriebe – Turbinendrehzahlsensor", "Sensor im Getriebe, Leitungsplatte/Stecker (Ölundichtigkeit am Stecker häufig).", "Notlauf möglich (fester Gang). Zeitnah prüfen.", 2),
        "P0720" to E("Getriebe – Abtriebsdrehzahlsensor", "Sensor, Leitungsplatte, Stecker.", "Notlauf möglich. Zeitnah prüfen.", 2),
        "P0730" to E("Getriebe – falsche Übersetzung", "Ölstand/-qualität, Kupplungen, Ventile.", "Schonend fahren, zeitnah prüfen.", 2),
        "P0731" to E("Getriebe – Übersetzung 1. Gang", "Ölstand/-qualität, Kupplung.", "Zeitnah prüfen.", 2),
        "P0732" to E("Getriebe – Übersetzung 2. Gang", "Ölstand/-qualität, Kupplung.", "Zeitnah prüfen.", 2),
        "P0733" to E("Getriebe – Übersetzung 3. Gang", "Ölstand/-qualität, Kupplung.", "Zeitnah prüfen.", 2),
        "P0734" to E("Getriebe – Übersetzung 4. Gang", "Ölstand/-qualität, Kupplung.", "Zeitnah prüfen.", 2),
        "P0735" to E("Getriebe – Übersetzung 5. Gang", "Ölstand/-qualität, Kupplung.", "Zeitnah prüfen.", 2),
        "P0740" to E("Wandlerüberbrückungskupplung – Stromkreis", "Magnetventil, Leitungsplatte.", WS, 1),
        "P0741" to E("Wandlerüberbrückungskupplung – Funktion", "Getriebeöl, Ventil, Wandler.", "Mehrverbrauch möglich. Prüfen lassen.", 1),

        // ---- Netzwerk
        "U0001" to E("CAN-Bus (Antrieb) – Kommunikation", "Kabel/Stecker, Wasser im Steckverbinder, Steuergerät, niedrige Bordspannung.", "Oft nach Batterieproblemen. Beobachten, ggf. prüfen lassen.", 1),
        "U0100" to E("Keine Verbindung zum Motorsteuergerät", "CAN-Leitung, Stecker, Spannungsversorgung.", "Oft nach Starthilfe/Batterietausch. Beobachten.", 1),
        "U0101" to E("Keine Verbindung zum Getriebesteuergerät", "CAN-Leitung, Stecker am Getriebe.", "Beobachten, bei Schaltproblemen prüfen lassen.", 1),
        "U0121" to E("Keine Verbindung zu ABS/ESP", "CAN-Leitung, ESP-Steuergerät.", "Bremse funktioniert, ESP evtl. aus. Prüfen lassen.", 2),
        "U0140" to E("Keine Verbindung zum SAM (Karosseriesteuergerät)", "CAN-Leitung, Wasser im SAM, Spannung.", "Beobachten.", 1),
        "U0155" to E("Keine Verbindung zum Kombiinstrument", "CAN-Leitung, Instrument.", "Beobachten.", 1),
    )

    fun lookup(code: String): DtcInfo {
        val c = code.uppercase()
        val system = system(c)
        db[c]?.let { return DtcInfo(it.title, system, it.causes, it.advice, it.sev, known = true) }
        val manufacturer = c.length >= 2 && (c[1] == '1' || c[1] == '3')
        return DtcInfo(
            title = if (manufacturer) "Mercedes-spezifischer Code" else "Allgemeiner Code (nicht in der App-Liste)",
            system = system,
            causes = if (manufacturer) "Die genaue Bedeutung legt Mercedes fest. Betroffen ist laut Code-Aufbau: $system."
            else "Genaue Bedeutung nicht hinterlegt. Betroffen ist laut Code-Aufbau: $system.",
            advice = "Bei Fahrauffälligkeiten oder Kontrollleuchte zeitnah prüfen lassen; eine Werkstatt mit Mercedes-Tester kann den Code genau zuordnen.",
            severity = 1,
            known = false,
        )
    }

    /** Welches System betroffen ist – aus dem Aufbau des Codes (SAE J2012). */
    fun system(code: String): String {
        if (code.length < 5) return "unbekannt"
        return when (code[0]) {
            'U' -> "Netzwerk / Kommunikation zwischen Steuergeräten"
            'B' -> "Karosserie (Komfort, Licht, Airbag, Klima …)"
            'C' -> "Fahrwerk (ABS, ESP, Lenkung, Bremse)"
            else -> when (code[2]) {
                '0' -> "Luft-/Kraftstoffmessung, Zusatzemissionen"
                '1', '2' -> "Luft-/Kraftstoffsystem und Einspritzung"
                '3' -> "Verbrennung und Glühanlage"
                '4' -> "Abgasnachbehandlung (AGR, Katalysator, Partikelfilter)"
                '5' -> "Geschwindigkeit, Leerlauf, Bordspannung"
                '6' -> "Steuergerät und Ausgänge"
                '7', '8', '9' -> "Getriebe"
                'A', 'B', 'C' -> "Hybrid-/Antriebskomponenten"
                else -> "Antrieb"
            }
        }
    }
}
