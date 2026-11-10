#!/usr/bin/env python3
"""Bereitet einen unveraenderten VoltaOS-Checkout fuer den Android-Port vor.

Aufruf: port.py <voltaos-verzeichnis>
Aendert nur den Build (CMakeLists.txt) und eine Zeile in main.cpp; alles andere
kommt ueber Platzhalter aus diesem Verzeichnis (shim/, qmlstubs/). GPL-3.0-or-later
"""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])


def patch(path, edits):
    p = root / path
    s = p.read_text(encoding="utf-8")
    for old, new, count in edits:
        found = len(re.findall(old, s, flags=re.M))
        if found != count:
            sys.exit(f"port.py: {path}: erwartet {count}x, gefunden {found}x: {old}")
        s = re.sub(old, new, s, flags=re.M)
    p.write_text(s, encoding="utf-8")


patch("CMakeLists.txt", [
    (r"COMPONENTS WebEngineQuick WebSockets Network\)", "COMPONENTS WebSockets Network Svg)", 1),
    (r"COMPONENTS Core Gui Qml Quick DBus\)", "COMPONENTS Core Gui Qml Quick)", 1),
    (r"Qt6::Quick Qt6::DBus Qt6::Location", "Qt6::Quick Qt6::Location", 1),
    (r"^find_package\(X11 REQUIRED\)\n", "", 1),
    (r"^find_package\(PkgConfig REQUIRED\)\n", "", 1),
    (r"^find_package\(Qt6 OPTIONAL_COMPONENTS WaylandCompositor QUIET\)\n", "", 2),
    (r"^\s*src/wayland/Volta\w+\.(cpp|h)\n", "", 7),
    (r"Qt6::WebEngineQuick Qt6::WebSockets", "Qt6::WebSockets Qt6::Svg", 1),
    (r"^\s*\$\{X11_LIBRARIES\}\n", "", 1),
    (r"^pkg_check_modules\((LIBUSB|AVCODEC) .*\n", "", 2),
    (r" PkgConfig::LIBUSB PkgConfig::AVCODEC\)", ")", 1),
])

with open(root / "CMakeLists.txt", "a", encoding="utf-8") as f:
    f.write(r'''

# ── Android-Port (volta-android/port.py) ─────────────────────────────────────
if(NOT VOLTA_PORT_DIR)
    message(FATAL_ERROR "VOLTA_PORT_DIR fehlt")
endif()
if(NOT VOLTA_PORT_STAMP)
    set(VOLTA_PORT_STAMP "dev")
endif()
target_sources(VoltaOS PRIVATE
    ${VOLTA_PORT_DIR}/volta_port.cpp
    ${VOLTA_PORT_DIR}/android_apps.cpp
    ${VOLTA_PORT_DIR}/android_car.cpp
    ${VOLTA_PORT_DIR}/volta_mapproxy.cpp
    ${VOLTA_PORT_DIR}/shim/volta_webshim.h)
target_include_directories(VoltaOS BEFORE PRIVATE
    ${VOLTA_PORT_DIR}/shim ${VOLTA_PORT_DIR}/shim/QtWebEngineQuick)
target_compile_definitions(VoltaOS PRIVATE VOLTA_PORT_STAMP="${VOLTA_PORT_STAMP}")
file(GLOB_RECURSE VOLTA_PORT_STUBS ${VOLTA_PORT_DIR}/qmlstubs/*)
file(GLOB VOLTA_PORT_QML ${VOLTA_PORT_DIR}/portqml/*.qml)
qt_add_resources(VoltaOS "volta_port_qml"
    PREFIX "/volta_port"
    BASE ${VOLTA_PORT_DIR}/portqml
    FILES ${VOLTA_PORT_QML})
qt_add_resources(VoltaOS "volta_port_stubs"
    PREFIX "/qt/qml"
    BASE ${VOLTA_PORT_DIR}/qmlstubs
    FILES ${VOLTA_PORT_STUBS})
if(ANDROID)
    # Qt D-Bus fehlt in Qt fuer Android
    target_sources(VoltaOS PRIVATE ${VOLTA_PORT_DIR}/shim-dbus/volta_dbusshim.h)
    target_include_directories(VoltaOS BEFORE PRIVATE
        ${VOLTA_PORT_DIR}/shim-dbus ${VOLTA_PORT_DIR}/shim-dbus/QtDBus)
else()
    find_package(Qt6 REQUIRED COMPONENTS DBus)
    target_link_libraries(VoltaOS PRIVATE Qt6::DBus)
endif()
if(ANDROID)
    # Standort ueber Android statt serieller GPS-Maus
    get_target_property(_volta_src VoltaOS SOURCES)
    list(REMOVE_ITEM _volta_src src/nav/GpsService.cpp ${CMAKE_CURRENT_SOURCE_DIR}/src/nav/GpsService.cpp
                                src/media/MprisManager.cpp ${CMAKE_CURRENT_SOURCE_DIR}/src/media/MprisManager.cpp)
    set_property(TARGET VoltaOS PROPERTY SOURCES ${_volta_src})
    # Standort, Musik, WLAN/Bluetooth, Lautstaerke und Helligkeit ueber Android
    target_sources(VoltaOS PRIVATE ${VOLTA_PORT_DIR}/android_gps.cpp ${VOLTA_PORT_DIR}/android_media.cpp
                                   ${VOLTA_PORT_DIR}/android_system.cpp)
endif()
# Karte: MapLibre fuer Qt (selbst gebaut, siehe Workflow)
if(VOLTA_MAPLIBRE_DIR)
    list(APPEND CMAKE_PREFIX_PATH ${VOLTA_MAPLIBRE_DIR})
    list(APPEND CMAKE_FIND_ROOT_PATH ${VOLTA_MAPLIBRE_DIR})
    find_package(QMapLibre COMPONENTS Location REQUIRED)
    find_package(Qt6 REQUIRED COMPONENTS Sql)
    target_link_libraries(VoltaOS PRIVATE QMapLibre::Location Qt6::Sql)
    qmaplibre_location_setup_plugins(VoltaOS)
    if(ANDROID)
        file(GLOB _volta_ml_libs ${VOLTA_MAPLIBRE_DIR}/lib/libQMapLibre*.so)
        set_property(TARGET VoltaOS APPEND PROPERTY QT_ANDROID_EXTRA_LIBS ${_volta_ml_libs})
        message(STATUS "MapLibre libs for the APK: ${_volta_ml_libs}")
    endif()
endif()
if(ANDROID)
    set_target_properties(VoltaOS PROPERTIES
        QT_ANDROID_PACKAGE_SOURCE_DIR ${VOLTA_PORT_DIR}/android
        QT_ANDROID_PACKAGE_NAME "de.jarvis.volta"
        QT_ANDROID_VERSION_NAME "${VOLTA_PORT_VERSION}"
        QT_ANDROID_VERSION_CODE "${VOLTA_PORT_CODE}"
        # wie beim Dashboard: als aeltere App gilt Volta auf Android 10 nicht fuer die neuen Sperren
        # (WLAN/Bluetooth ein-/ausschalten, alte Netz-Schnittstellen)
        QT_ANDROID_TARGET_SDK_VERSION 28)
    if(VOLTA_OPENSSL_DIR)
        include(${VOLTA_OPENSSL_DIR}/android_openssl.cmake)
        add_android_openssl_libraries(VoltaOS)
    endif()
endif()
''')

patch("src/main.cpp", [
    (r'^(\s*)qputenv\("QT_QPA_PLATFORM", "xcb"\);',
     r'\1{\n#ifndef Q_OS_ANDROID\n\1qputenv("QT_QPA_PLATFORM", "xcb");\n#endif\n\1}', 1),
    # Voltas QML-Modul liegt in den Ressourcen unter :/VoltaApp. Auf Linux findet Qt es zusaetzlich als
    # Ordner neben dem Programm; auf Android gibt es den nicht -> "VoltaApp contains no type named Main".
    (r'^(\s*)engine\.addImportPath\("/usr/local/qml"\);',
     r'\1engine.addImportPath("/usr/local/qml");\n\1engine.addImportPath(QStringLiteral("qrc:/"));', 1),
])
# Android-Dateiauswahl liefert content://-Adressen: vor dem Einlesen in eine echte Datei kopieren
patch("src/core/CarModelManager.cpp", [
    (r'^#include "CarModelManager.h"\n',
     '#include "CarModelManager.h"\nQString voltaPortLocalFile(const QString &path);\nbool voltaPortNoBlender();\n', 1),
    (r"QString p = srcPath;", "QString p = voltaPortLocalFile(srcPath);", 2),
    # "Verkleinern und laden" braucht Blender: auf Android die .glb so laden, wie sie ist
    (r"if \(!lighten && p\.endsWith\(QLatin1String\(\"\.glb\"\), Qt::CaseInsensitive\)\)",
     'if ((!lighten || voltaPortNoBlender()) && p.endsWith(QLatin1String(".glb"), Qt::CaseInsensitive))', 1),
])

# ── Android-Apps in Voltas eigenem App-Drawer und in Einstellungen > Apps ──────
IMP = (r'^import VoltaApp 1\.0\n', 'import VoltaApp 1.0\nimport VoltaPort 1.0\n', 1)

patch("qml/AppDrawer.qml", [
    IMP,
    # Voltas Liste + installierte Android-Apps (gleiches Raster, gleiche Zellen, ziehbar in die Leiste)
    (r'^    property var qtApps: \[', '    property var _voltaQtApps: [', 1),
    (r'^    property var androidApps: \[\]\n',
     '    property var androidApps: []\n    property var qtApps: drawer._voltaQtApps.concat(AndroidPort.apps)\n', 1),
    # bei vielen Symbolen: Drawer nicht hoeher als der Bildschirm, dann scrollen
    (r'^    height: contentCol\.height \+ 36 \* f\n',
     '    readonly property real portFullH: contentCol.height + 36 * f\n'
     '    readonly property real portMaxH: Window.window ? Window.window.height * 0.74 : 99999\n'
     '    readonly property bool portScroll: portFullH > portMaxH + 1\n'
     '    height: Math.min(portFullH, portMaxH)\n', 1),
    (r'^    Column \{\n        id: contentCol\n',
     '    Flickable {\n        id: portFlick\n        anchors.fill: parent\n        contentWidth: width\n'
     '        contentHeight: drawer.portFullH\n        interactive: drawer.portScroll\n        clip: drawer.portScroll\n'
     '        boundsBehavior: Flickable.StopAtBounds\n    Column {\n        id: contentCol\n', 1),
    (r'\n        \}\n    \}\n\}\s*\Z', '\n        }\n    }\n    }\n}\n', 1),
    # beim Scrollen: Symbol erst nach langem Druecken verschieben (sonst wie im Original sofort)
    (r'^(\s*)property bool dragging: false\n',
     r'\1property bool dragging: false\n\1property bool portHeld: false\n\1onPressAndHold: dragArea.portHeld = true\n', 1),
    (r'^(\s*)dragArea\.pressY = mouse\.y\n', r'\1dragArea.pressY = mouse.y\n\1dragArea.portHeld = false\n', 1),
    (r'preventStealing: true', 'preventStealing: !drawer.portScroll || dragArea.portHeld', 1),
    (r'if \(!dragArea\.dragging && \(Math\.abs\(dx\) > 10',
     'if (!dragArea.dragging && (!drawer.portScroll || dragArea.portHeld) && (Math.abs(dx) > 10', 1),
])

patch("qml/Main.qml", [
    IMP,
    # Android-App oder verknuepftes Volta-Symbol (Telefon -> Telefon-App ...): Android-App starten
    (r'^    onActiveAppChanged: \{\n',
     '    onActiveAppChanged: {\n'
     '        if (activeApp !== "" && AndroidPort.handle(activeApp)) { showGrid = false; activeApp = ""; return }\n', 1),
])

patch("qml/Settings.qml", [
    IMP,
    # Einstellungen > Apps: auch die Android-Apps ein-/ausblenden
    (r'(\{ id: "bluetooth",    name: "Bluetooth", icon: "qrc:/VoltaApp/icons/bluetooth\.svg" \}\n\s*\])',
     r'\1.concat(AndroidPort.apps)', 1),
    # ... und bei Voltas Symbolen waehlen, welche App sie oeffnen
    (r'^(\s*)TToggle \{\n(\s*)anchors\.right: parent\.right; anchors\.verticalCenter: parent\.verticalCenter\n(\s*)checked: !root\.isAppHidden\(modelData\.id\)',
     r'\1Text {\n'
     r'\1    visible: AndroidPort.android && !modelData.android\n'
     r'\1    anchors.right: parent.right; anchors.rightMargin: 84; anchors.verticalCenter: parent.verticalCenter\n'
     r'\1    text: AndroidPort.links[modelData.id] ? (AndroidPort.appName(AndroidPort.links[modelData.id]) || AndroidPort.links[modelData.id]) + "  ›" : "Volta  ›"\n'
     r'\1    color: AndroidPort.links[modelData.id] ? root.blue : root.txtHint; font.pixelSize: 13\n'
     r'\1    MouseArea { anchors.fill: parent; anchors.margins: -14; onClicked: portLinkPopup.openFor(modelData.id, modelData.name) }\n'
     r'\1}\n'
     r'\1TToggle {\n\2anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter\n\3checked: !root.isAppHidden(modelData.id)', 1),
    (r'\n\}\s*\Z',
     '\n    PortLinkPopup {\n        id: portLinkPopup\n        anchors.fill: parent\n        z: 9999\n'
     '        txt: root.txt; txtHint: root.txtHint; accent: root.blue\n    }\n}\n', 1),
])

# ── Tacho-Bereich: Tempo vom GPS; Anzeigen ohne echte Daten (Gang, Licht, Gurt, Airbag) ausblenden ──
patch("qml/CarPanel.qml", [
    (r'^import QtQuick3D\n', 'import QtQuick3D\nimport VoltaPort 1.0\n', 1),
    (r'^(\s*)gear:       "P"\n\s*speedValue: "0"\n\s*speedUnit:  "km/h"\n\s*headlightOn: true\n\s*seatbeltOn:  true\n',
     r'\1property real portKmh: 0\n'
     r'\1readonly property real portShownKmh: VoltaCar.live && VoltaCar.speed >= 0 ? VoltaCar.speed : portKmh\n'
     r'\1speedValue: Math.round(portShownKmh).toString()\n'
     r'\1speedUnit:  "km/h"\n'
     r'\1headlightOn: false\n'
     r'\1seatbeltOn:  false\n'
     r'\1Connections {\n'
     r'\1    target: Gps\n'
     r'\1    function onUpdated(d) { var k = (d.speed || 0) * 3.6; speedCluster.portKmh = k < 2 ? 0 : k; portSpeedStale.restart() }\n'
     r'\1}\n'
     r'\1Timer { id: portSpeedStale; interval: 3000; onTriggered: speedCluster.portKmh = 0 }\n'
     r'\1Column {\n'
     r'\1    visible: VoltaCar.live\n'
     r'\1    x: carPanel.rhd ? speedCluster.width - width : 0\n'
     r'\1    y: speedCluster.implicitHeight + 6\n'
     r'\1    spacing: 10\n'
     r'\1    Repeater {\n'
     r'\1        // feste Liste (nicht bei jedem Wert neu aufbauen); fehlende Werte blenden sich aus\n'
     r'\1        model: ["rpm", "coolant", "oil", "boost", "voltage", "consumption"]\n'
     r'\1        Column {\n'
     r'\1            readonly property real val: VoltaCar[modelData]\n'
     r'\1            readonly property bool warn: (modelData === "rpm" && val > 4200) || (modelData === "coolant" && val >= 108)\n'
     r'\1                                         || (modelData === "oil" && val >= 125) || (modelData === "voltage" && val >= 0 && val < 11.8)\n'
     r'\1            readonly property bool cold: (modelData === "coolant" || modelData === "oil") && val >= 0 && val < 60\n'
     r'\1            visible: val >= 0\n'
     r'\1            spacing: -1\n'
     r'\1            Text {\n'
     r'\1                text: Number(parent.val).toFixed(modelData === "boost" || modelData === "voltage" || modelData === "consumption" ? 1 : 0)\n'
     r'\1                color: parent.warn ? "#ED4E3B" : parent.cold ? "#A8C3F4" : (carPanel._lt ? "#171717" : "#F0F0F0")\n'
     r'\1                font.pixelSize: 26; font.weight: Font.Light\n'
     r'\1            }\n'
     r'\1            Text {\n'
     r'\1                text: ({ rpm: "RPM", coolant: "COOLANT °C", oil: "OIL °C", boost: "BOOST BAR", voltage: "BATTERY V", consumption: "L/100 KM" })[modelData]\n'
     r'\1                color: "#757575"; font.pixelSize: 11; font.weight: Font.DemiBold; font.letterSpacing: 1\n'
     r'\1            }\n'
     r'\1        }\n'
     r'\1    }\n'
     r'\1}\n', 1),
    (r'fillPct: 0 // To be connected to the real GPS if you want',
     'fillPct: Math.min(100, speedCluster.portShownKmh / 2)   // Android-Port: Tempo (OBD, sonst GPS), voll bei 200 km/h', 1),
])
patch("qml/SpeedCluster.qml", [
    (r'^(\s*)Row \{\n(\s*)x: cluster\.rhd \? col\.width - width : 0\n(\s*)spacing: 7\n',
     r'\1Row {\n\2visible: false   // Android-Port: Gang ist nicht bekannt\n\2x: cluster.rhd ? col.width - width : 0\n\3spacing: 7\n', 1),
])
patch("qml/StatusBar.qml", [
    (r'property bool   showAirbag: true', 'property bool   showAirbag: false   // Android-Port: kein Signal vom Auto', 1),
])

# ── Lautstaerke ueber Android (Original: pactl) ──────────────────────────────
patch("src/core/SystemHelper.cpp", [
    (r'^void SystemHelper::setVolume\(int percent\)\n\{\n',
     '#ifdef Q_OS_ANDROID\nvoid voltaPortSetVolume(int percent);\nint voltaPortGetVolume();\n#endif\n'
     'void SystemHelper::setVolume(int percent)\n{\n#ifdef Q_OS_ANDROID\n    voltaPortSetVolume(qBound(0, percent, 100));\n    return;\n#endif\n', 1),
    (r'^int SystemHelper::getVolume\(\)\n\{\n',
     'int SystemHelper::getVolume()\n{\n#ifdef Q_OS_ANDROID\n    return voltaPortGetVolume();\n#endif\n', 1),
])

patch("qml/Main.qml", [
    # Helligkeit: Android statt schwarzer Abdunkel-Flaeche
    (r'^    property int  screenBrightness: 100\n',
     '    property int  screenBrightness: 100\n'
     '    onScreenBrightnessChanged: if (AndroidPort.android) AndroidPort.setBrightness(screenBrightness)\n', 1),
    (r'opacity: root\.screenBrightness >= 100 \? 0 : \(1 - root\.screenBrightness / 100\) \* 0\.85',
     'opacity: AndroidPort.android ? 0 : (root.screenBrightness >= 100 ? 0 : (1 - root.screenBrightness / 100) * 0.85)', 1),
    # Lautstaerke von Lenkrad/Radio-Tasten in Voltas Anzeige uebernehmen
    (r'^    property int  callSeconds: 0\n',
     '    property int _portSysVol: -1\n'
     '    Timer {\n        interval: 1500; repeat: true; running: AndroidPort.android\n'
     '        // nur Aenderungen von aussen (Lenkrad, Radio-Tasten) uebernehmen; Android rundet auf seine Stufen,\n'
     '        // darum kleine Abweichungen nach Voltas eigenem Regler ignorieren\n'
     '        onTriggered: { var v = sysHelper.getVolume(); if (v < 0 || v > 100 || v === root._portSysVol) return;\n'
     '                       if (root._portSysVol >= 0 && Math.abs(v - root.volume) > 4) root.volume = v; root._portSysVol = v }\n'
     '    }\n'
     '    property int  callSeconds: 0\n', 1),
])

# ── Musik-Widget: was auf dem Radio laeuft (Quelle "android", siehe android_media.cpp) ──
patch("qml/MediaHub.qml", [
    (r'^    function providerName\(p\) \{\n        return ',
     '    function providerName(p) {\n        if (p === "android") return Mpris.activeIdentity || qsTr("Music")\n        return ', 1),
    (r'^    function providerIcon\(p\) \{\n        return ',
     '    function providerIcon(p) {\n        if (p === "android") return ((Mpris.players["android"] || {}).icon) || "qrc:/VoltaApp/icons/localmusic.svg"\n        return ', 1),
    (r'^    function providerAppId\(p\) \{\n        return ',
     '    function providerAppId(p) {\n        if (p === "android") return "android:" + ((Mpris.players["android"] || {}).pkg || "")\n        return ', 1),
    (r'^    readonly property string activeProvider: \{\n',
     '    readonly property string activeProvider: {\n'
     '        if (hub.mprisReady && Mpris.activeSource === "android" && Mpris.hasActive) return "android"\n', 1),
    (r'^    readonly property var nowPlaying: \{\n        var p = hub\.activeProvider\n',
     '    readonly property var nowPlaying: {\n        var p = hub.activeProvider\n'
     '        if (p === "android") {\n'
     '            var am = hub.mprisState(p) || {}\n'
     '            return { provider: p, title: am.title || Mpris.activeIdentity, subtitle: am.artist || "", cover: am.artUrl || "",\n'
     '                     isPlaying: !!am.playing, progressMs: am.positionMs || 0, durationMs: am.lengthMs > 0 ? am.lengthMs : 1,\n'
     '                     isVideo: false, canSeek: !!am.canSeek }\n'
     '        }\n', 1),
])

# ── Einstellungen > App: Hinweis, wenn der Musik-Zugriff fehlt ──
patch("qml/Settings.qml", [
    (r'(\n(\s*)text: qsTr\("Hide the apps you don\'t use: they disappear from the drawer but stay installed, and you can always bring them back from here\."\)\n(\s*)color: root\.txtHint; font\.pixelSize: 12; wrapMode: Text\.WordWrap\n(\s*)\})',
     r'\1\n\4Text {\n\4    visible: AndroidPort.android && !AndroidPort.mediaAccess\n\4    width: parent.width\n'
     r'\4    text: qsTr("Music widget: allow Volta to see what other apps are playing") + "  ›"\n'
     r'\4    color: root.blue; font.pixelSize: 13; wrapMode: Text.WordWrap\n'
     r'\4    MouseArea { anchors.fill: parent; anchors.margins: -8; onClicked: AndroidPort.openMediaAccess() }\n\4}', 1),
])

# ── Einstellungen > Vehicle: OBD-Adapter und Status (Technik aus dem Dashboard W204) ──
patch("qml/Settings.qml", [
    (r'"Arcade", qsTr\("System"\)\]', '"Arcade", qsTr("System"), qsTr("Vehicle")]', 1),
    (r'"bluetooth", "apps", "directions_car", "sports_esports", "settings"\]',
     '"bluetooth", "apps", "directions_car", "sports_esports", "settings", "speed"]', 1),
    (r'^(\s*)// ══════ PAGINA: SISTEMA ══════\n',
     r'\1// ══════ PAGINA: VEHICLE (Android-Port: OBD, nur lesend) ══════\n'
     r'\1Column {\n'
     r'\1    visible: root.pageIdx === root.pages.indexOf(qsTr("Vehicle"))\n'
     r'\1    width: parent.width; spacing: 14\n'
     r'\1    SectionTitle { title: qsTr("Vehicle") }\n'
     r'\1    Text {\n'
     r'\1        width: parent.width; wrapMode: Text.WordWrap; font.pixelSize: 15\n'
     r'\1        color: VoltaCar.live ? root.txt : root.txtHint\n'
     r'\1        text: VoltaCar.live ? (qsTr("Connected") + (VoltaCar.demo ? qsTr(" (demo values)") : "") + (VoltaCar.rate > 0 ? "  ·  " + VoltaCar.rate.toFixed(1) + qsTr(" values/s") : ""))\n'
     r'\1                            : (VoltaCar.message !== "" ? VoltaCar.message : qsTr("Not connected"))\n'
     r'\1    }\n'
     r'\1    Text {\n'
     r'\1        width: parent.width; wrapMode: Text.WordWrap; font.pixelSize: 12; color: root.txtHint\n'
     r'\1        text: qsTr("Volta only reads values from the car (OBD). Nothing is written, cleared or coded.")\n'
     r'\1    }\n'
     r'\1    SectionTitle { title: qsTr("OBD adapter") }\n'
     r'\1    Text {\n'
     r'\1        visible: VoltaCar.adapters.length === 0\n'
     r'\1        width: parent.width; wrapMode: Text.WordWrap; font.pixelSize: 13; color: root.txtHint\n'
     r'\1        text: qsTr("No paired Bluetooth devices. Pair the OBD adapter in Bluetooth first (PIN usually 1234 or 0000).")\n'
     r'\1    }\n'
     r'\1    Column {\n'
     r'\1        width: parent.width; spacing: 0\n'
     r'\1        Repeater {\n'
     r'\1            model: VoltaCar.adapters\n'
     r'\1            Rectangle {\n'
     r'\1                width: parent.width; height: 56; color: "transparent"\n'
     r'\1                readonly property bool sel: !VoltaCar.demo && VoltaCar.adapterAddress === modelData.address\n'
     r'\1                Text {\n'
     r'\1                    anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; anchors.verticalCenterOffset: -8\n'
     r'\1                    text: modelData.name; color: parent.sel ? root.blue : root.txt; font.pixelSize: 15\n'
     r'\1                }\n'
     r'\1                Text {\n'
     r'\1                    anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; anchors.verticalCenterOffset: 11\n'
     r'\1                    text: modelData.address + (modelData.obd ? qsTr("  ·  probably OBD") : ""); color: root.txtHint; font.pixelSize: 12\n'
     r'\1                }\n'
     r'\1                Text {\n'
     r'\1                    anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter\n'
     r'\1                    text: parent.sel ? "✓" : ""; color: root.blue; font.pixelSize: 18; font.weight: Font.Bold\n'
     r'\1                }\n'
     r'\1                MouseArea { anchors.fill: parent; onClicked: VoltaCar.setAdapter(modelData.address, modelData.name) }\n'
     r'\1                TDivider { anchors.bottom: parent.bottom }\n'
     r'\1            }\n'
     r'\1        }\n'
     r'\1    }\n'
     r'\1    Row {\n'
     r'\1        spacing: 12\n'
     r'\1        TSecondary { label: qsTr("Refresh list"); onClicked: VoltaCar.refreshAdapters() }\n'
     r'\1        TSecondary { label: VoltaCar.demo ? qsTr("Stop demo") : qsTr("Demo values"); onClicked: VoltaCar.setDemo(!VoltaCar.demo) }\n'
     r'\1    }\n'
     r'\1}\n\n'
     r'\1// ══════ PAGINA: SISTEMA ══════\n', 1),
])

# ── Settings-Navigation: Seitenleiste auf kleinen Touchscreens scrollbar ──────
# "Vehicle" ist der elfte Eintrag. Die originale Sidebar-Column war nicht
# scrollbar; auf 1024x600 lag der Eintrag dadurch ausserhalb des Bildschirms.
patch("qml/Settings.qml", [
    (r'(?s)    Rectangle \{\n        id: sidebar\n.*?\n    \}\n\n    component SectionTitle',
'''    Rectangle {
        id: sidebar
        x: 0; y: root.topBarHeight; width: 250; height: parent.height - root.topBarHeight
        color: root.sideBg

        Flickable {
            id: sidebarFlick
            anchors.fill: parent
            contentWidth: width
            contentHeight: Math.max(height, pageColumn.implicitHeight + 40)
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            flickableDirection: Flickable.VerticalFlick

            // Der Auswahlbalken scrollt zusammen mit den Menueintraegen.
            Rectangle {
                x: 16; width: parent.width - 32; height: 46; radius: 8
                y: 20 + root.pageIdx * 52
                color: root.lightTheme ? "#D5D5D5" : "#3A3A3A"
                Behavior on y {
                    NumberAnimation { duration: root.selAnimMs; easing.type: root.selAnimCurve; easing.overshoot: root.selAnimOvershoot }
                }
            }

            Column {
                id: pageColumn
                x: 16; y: 20; width: parent.width - 32; spacing: 6

                Repeater {
                    model: root.pages
                    Rectangle {
                        width: parent.width; height: 46; radius: 8
                        color: "transparent"
                        Item {
                            x: 16; anchors.verticalCenter: parent.verticalCenter
                            width: 22; height: 22
                            Image {
                                id: pgIconImg
                                anchors.fill: parent
                                source: "qrc:/VoltaApp/icons/ms_" + root.pageIcons[index] + ".svg"
                                sourceSize: Qt.size(44, 44)
                                fillMode: Image.PreserveAspectFit
                                visible: false
                            }
                            ColorOverlay {
                                anchors.fill: pgIconImg
                                source: pgIconImg
                                color: index === root.pageIdx ? root.txt : root.txtSub
                                Behavior on color { ColorAnimation { duration: root.selAnimMs } }
                            }
                        }
                        Text {
                            x: 52; anchors.verticalCenter: parent.verticalCenter
                            text: modelData
                            color: index === root.pageIdx ? root.txt : root.txtSub
                            Behavior on color { ColorAnimation { duration: root.selAnimMs } }
                            font.pixelSize: 15
                            font.weight: index === root.pageIdx ? Font.DemiBold : Font.Normal
                        }
                        MouseArea { anchors.fill: parent; onClicked: root.pageIdx = index }
                    }
                }
            }
        }
    }

    component SectionTitle''', 1),
])


# ── 3D-Fahrzeug: Android-Content-URIs und sichtbare Loader-Fehler ─────────────
# Der Android-Dateidialog liefert content:// statt eines Dateisystempfads.
# ContentResolver kopiert die ausgewaehlte GLB-Datei in den privaten Cache.
patch("qml/UserCar.qml", [
    (r'^    readonly property bool loaded: loader\.status === RuntimeLoader\.Success\\n',
     '    readonly property bool loaded: loader.status === RuntimeLoader.Success\\n'
     '    property string loadError: ""\\n', 1),
    (r'^    onFileChanged: \\{ setupDone = false; rigged = false; _bnd = null \\}.*
,
     '    onFileChanged: {\\n'
     '        setupDone = false; rigged = false; _bnd = null; loadError = ""\\n'
     '        if (file !== "") { console.info("[3D car] loading:", file); loadTimeout.restart() }\\n'
     '        else loadTimeout.stop()\\n'
     '    }', 1),
    (r'if \\(status === RuntimeLoader\\.Success\\) setupTimer\\.restart\\(\\)\\n'
     r'                else if \\(status === RuntimeLoader\\.Error\\) console\\.log\\("\\[3D car\\] error:", errorString\\)',
     'if (status === RuntimeLoader.Success) {\\n'
     '                    loadTimeout.stop(); console.info("[3D car] RuntimeLoader success"); setupTimer.restart()\\n'
     '                } else if (status === RuntimeLoader.Error) {\\n'
     '                    loadTimeout.stop(); rig.loadError = errorString || qsTr("The 3D model could not be loaded.");\\n'
     '                    console.warn("[3D car] RuntimeLoader error:", errorString)\\n'
     '                }', 1),
    (r'^    Timer \\{ id: setupTimer; interval: 30; onTriggered: rig\\._setup\\(\\) \\}
,
     '    Timer { id: setupTimer; interval: 30; onTriggered: rig._setup() }\\n'
     '    Timer {\\n'
     '        id: loadTimeout; interval: 60000; repeat: false\\n'
     '        onTriggered: if (!rig.loaded && rig.file !== "") {\\n'
     '            rig.loadError = qsTr("Loading timed out after 60 seconds. The GLB may be unsupported or too large.")\\n'
     '            console.warn("[3D car] RuntimeLoader timeout; status:", loader.status)\\n'
     '        }\\n'
     '    }', 1),
])
patch("qml/CarSetup.qml", [
    (r'^    signal finished\\(\\)\\n',
     '    signal finished()\\n    signal failed()\\n', 1),
    (r'^    readonly property bool ready: rig && rig\\.loaded && rig\\.setupDone\\n',
     '    readonly property bool ready: rig && rig.loaded && rig.setupDone\\n'
     '    readonly property string loadError: rig ? rig.loadError : ""\\n', 1),
    (r'text: qsTr\\("Loading the car…")',
     'text: setup.loadError !== "" ? setup.loadError : qsTr("Loading the car…")', 1),
    (r'color: setup\\.fg; font\\.pixelSize: 18',
     'color: setup.loadError !== "" ? "#FF6B6B" : setup.fg; font.pixelSize: 18', 1),
    (r'font\\.pixelSize: 18\\n            \\}',
     'font.pixelSize: 18\\n            }\\n'
     '            BtnBlue {\\n'
     '                visible: setup.loadError !== ""\\n'
     '                width: parent.width; label: qsTr("Choose another model")\\n'
     '                onClicked: setup.failed()\\n'
     '            }', 1),
])
patch("qml/CarScene.qml", [
    (r'lightTheme: root\\.lightTheme\\n        visible: root\\.userCarActive && !root\\.replacing && !root\\.fsdMode && !CarModels\\.configured',
     'lightTheme: root.lightTheme\\n'
     '        onFailed: { root._carErr = userCarMain.loadError; root.replacing = true }\\n'
     '        visible: root.userCarActive && !root.replacing && !root.fsdMode && !CarModels.configured', 1),
])

print("port.py: ok")
