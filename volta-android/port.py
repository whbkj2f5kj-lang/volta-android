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
    ${VOLTA_PORT_DIR}/android_dashcam.cpp
    ${VOLTA_PORT_DIR}/android_limits.cpp
    ${VOLTA_PORT_DIR}/android_tpms.cpp
    ${VOLTA_PORT_DIR}/android_update.cpp
    ${VOLTA_PORT_DIR}/volta_mapproxy.cpp
    ${VOLTA_PORT_DIR}/shim/volta_webshim.h)
target_include_directories(VoltaOS BEFORE PRIVATE
    ${VOLTA_PORT_DIR}/shim ${VOLTA_PORT_DIR}/shim/QtWebEngineQuick)
# Der Build-Stempel wird nur hier verwendet. Global wuerde jeder neue Commit
# den Compiler-Cache fuer ALLE C++-Dateien ungueltig machen.
set_property(SOURCE ${VOLTA_PORT_DIR}/volta_port.cpp APPEND PROPERTY
    COMPILE_DEFINITIONS VOLTA_PORT_STAMP="${VOLTA_PORT_STAMP}")
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
    # RuntimeLoader findet Assimp dynamisch; der QML-Imports scanner nimmt es nicht automatisch mit.
    # Ohne dieses Plugin liefert Qt exakt "Unsupported: Unknown error" fuer jede GLB-Datei.
    find_package(Qt6 REQUIRED COMPONENTS Quick3DAssetImport)
    if(NOT TARGET Qt6::AssimpImporterPlugin)
        message(FATAL_ERROR "Qt AssimpImporterPlugin fehlt: GLB-Import waere auf Android unmoeglich")
    endif()
    # Als Qt-Plugin anmelden, damit androiddeployqt Bibliothek und Abhaengigkeiten einpackt.
    target_link_libraries(VoltaOS PRIVATE Qt6::Quick3DAssetImport)
    qt_import_plugins(VoltaOS INCLUDE Qt6::AssimpImporterPlugin)
    # Qt D-Bus fehlt in Qt fuer Android
    target_sources(VoltaOS PRIVATE ${VOLTA_PORT_DIR}/shim-dbus/volta_dbusshim.h)
    target_include_directories(VoltaOS BEFORE PRIVATE
        ${VOLTA_PORT_DIR}/shim-dbus ${VOLTA_PORT_DIR}/shim-dbus/QtDBus)
else()
    find_package(Qt6 REQUIRED COMPONENTS DBus)
    target_link_libraries(VoltaOS PRIVATE Qt6::DBus)
endif()
if(ANDROID)
    find_package(Qt6 REQUIRED COMPONENTS WebView WebViewQuick)
    target_link_libraries(VoltaOS PRIVATE Qt6::WebView Qt6::WebViewQuick)
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
        QT_ANDROID_TARGET_SDK_VERSION 27)
    if(VOLTA_OPENSSL_DIR)
        include(${VOLTA_OPENSSL_DIR}/android_openssl.cmake)
        add_android_openssl_libraries(VoltaOS)
    endif()
endif()
''')

patch("src/main.cpp", [
    (r'^#include <QtWebEngineQuick>\n', '#include <QtWebEngineQuick>\n#ifdef Q_OS_ANDROID\n#include <QtWebView/QtWebView>\n#endif\n', 1),
    (r'    QGuiApplication app\(argc, argv\);', '    QGuiApplication app(argc, argv);\n#ifdef Q_OS_ANDROID\n    QtWebView::initialize();\n#endif', 1),
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
     r'\1property real portObdKmh: 0\n'
     r'\1// OBD verbunden: nur OBD-Tempo (kurze Luecken mit letztem Wert ueberbruecken, nie GPS dazwischen)\n'
     r'\1readonly property real portShownKmh: VoltaCar.live ? (VoltaCar.speed >= 0 ? VoltaCar.speed : portObdKmh) : portKmh\n'
     r'\1onPortShownKmhChanged: if (VoltaCar.live && VoltaCar.speed >= 0) portObdKmh = VoltaCar.speed\n'
     r'\1speedValue: Math.round(portShownKmh).toString()\n'
     r'\1speedUnit:  "km/h"\n'
     r'\1headlightOn: false\n'
     r'\1seatbeltOn:  false\n'
     r'\1Connections {\n'
     r'\1    target: Gps\n'
     r'\1    function onUpdated(d) { var fresh = d.speedValid !== false && (d.ageMs || 0) < 3000; var k = fresh ? (d.speed || 0) * 3.6 : 0; if (d.accuracyValid && d.accuracy > 25) k = 0; if (speedCluster.portKmh < 1 ? k < 6 : k < 3) k = 0; speedCluster.portKmh = k; if (fresh) portSpeedStale.restart(); else portSpeedStale.stop() }\n'
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
            interactive: contentHeight > height + 2
            pressDelay: 100
            maximumFlickVelocity: 2200
            flickDeceleration: 1800

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
                        // Passive tap recognizer: an actual vertical swipe stays with the Flickable.
                        // A full-size MouseArea here used to compete with scroll gestures.
                        TapHandler {
                            gesturePolicy: TapHandler.DragThreshold
                            onTapped: root.pageIdx = index
                        }
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
    (r'^    readonly property bool loaded: loader\.status === RuntimeLoader\.Success$',
     '    readonly property bool loaded: loader.status === RuntimeLoader.Success\n'
     '    property string loadError: ""\n', 1),
    (r'^    onFileChanged: \{ setupDone = false; rigged = false; _bnd = null \}.*$',
     '    onFileChanged: {\n'
     '        setupDone = false; rigged = false; _bnd = null; loadError = ""\n'
     '        if (file !== "") { console.info("[3D car] loading:", file); loadTimeout.restart() }\n'
     '        else loadTimeout.stop()\n'
     '    }', 1),
    (r'if \(status === RuntimeLoader\.Success\) setupTimer\.restart\(\)',
     'if (status === RuntimeLoader.Success) { loadTimeout.stop(); console.info("[3D car] RuntimeLoader success"); setupTimer.restart() }', 1),
    (r'else if \(status === RuntimeLoader\.Error\) console\.log\("\[3D car\] error:", errorString\)',
     'else if (status === RuntimeLoader.Error) { loadTimeout.stop(); rig.loadError = errorString || qsTr("The 3D model could not be loaded."); console.warn("[3D car] RuntimeLoader error:", errorString) }', 1),
    (r'^    Timer \{ id: setupTimer; interval: 30; onTriggered: rig\._setup\(\) \}$',
     '    Timer { id: setupTimer; interval: 30; onTriggered: rig._setup() }\n'
     '    Timer {\n'
     '        id: loadTimeout; interval: 60000; repeat: false\n'
     '        onTriggered: if (!rig.loaded && rig.file !== "") {\n'
     '            rig.loadError = qsTr("Loading timed out after 60 seconds. The GLB may be unsupported or too large.")\n'
     '            console.warn("[3D car] RuntimeLoader timeout; status:", loader.status)\n'
     '        }\n'
     '    }', 1),
])
patch("qml/CarSetup.qml", [
    (r'^    signal finished\(\)$', '    signal finished()\n    signal failed()', 1),
    (r'^    readonly property bool ready: rig && rig\.loaded && rig\.setupDone$',
     '    readonly property bool ready: rig && rig.loaded && rig.setupDone\n'
     '    readonly property string loadError: rig ? rig.loadError : ""', 1),
    (r'text: qsTr\("Loading the car…"\)',
     'text: setup.loadError !== "" ? setup.loadError : qsTr("Loading the car…")', 1),
    (r'^            // ── domanda iniziale ──$',
     '            BtnBlue {\n'
     '                visible: setup.loadError !== ""\n'
     '                width: parent.width; label: qsTr("Choose another model")\n'
     '                onClicked: setup.failed()\n'
     '            }\n\n'
     '            // ── domanda iniziale ──', 1),
    # Teile-Auswahl (Tueren, Haube, Kofferraum, Raeder): Volta aenderte nur result[kind].
    # Das meldet QML nicht als Aenderung, curList/found blieben auf dem alten Stand und
    # die Vorschau zeigte weiter die alte (automatische) Auswahl. Jetzt wird result neu gesetzt.
    (r'^    function _names\(v\) \{',
     '    function _setResult(k, v) { var r = Object.assign({}, result); r[k] = v; result = r }\n'
     '    function _names(v) {', 1),
    (r'^        if \(materialsMode\) result\[kind\] = sel\.slice\(\)$',
     '        if (materialsMode) _setResult(kind, sel.slice())', 1),
    (r'^            result\[kind\] = nm$', '            _setResult(kind, nm)', 1),
    (r'^        result\[kind\] = \[\]$', '        _setResult(kind, [])', 1),
])
patch("qml/CarScene.qml", [
    (r'^        visible: root\.userCarActive && !root\.replacing && !root\.fsdMode && !CarModels\.configured$',
     '        onFailed: { root._carErr = userCarMain.loadError; root.replacing = true }\n'
     '        visible: root.userCarActive && !root.replacing && !root.fsdMode && !CarModels.configured', 1),
])

# Eigenes Symbol fuer die Seite "Vehicle" (Tacho). Volta buendelt nur die Symbole seiner
# Einstellungsseiten; ms_car.svg gab es nicht, darum blieb das Feld leer.
(root / "assets/icons/ms_speed.svg").write_text(
    '<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 -960 960 960">'
    '<path d="M120-400a360 360 0 0 1 720 0h-70a290 290 0 0 0-580 0z'
    'M455-415 640-615l50 50-185 200z'
    'M480-330a70 70 0 1 0 0-140a70 70 0 1 0 0 140z'
    'M120-240h720v60H120z"/></svg>', encoding="utf-8")
patch("CMakeLists.txt", [
    (r'^(\s*)assets/icons/ms_directions_car\.svg\n', r'\1assets/icons/ms_directions_car.svg\n\1assets/icons/ms_speed.svg\n', 1),
])

# ── Stufe 2: OSM-Daten in Voltas vorhandenem Schild und vorhandenen Karten ────
patch("qml/CarPanel.qml", [
    (r'visible: reduced && Nav\.navigating && Nav\.speedLimit > 0',
     'visible: VoltaLimits.limit > 0', 1),
    (r'text: Nav\.speedLimit > 0 \? String\(Nav\.speedLimit\) : ""\n(\s*)color: "#111111"',
     r'text: VoltaLimits.limit > 0 ? String(VoltaLimits.limit) : ""\n\1color: VoltaLimits.overspeed ? "#D3232B" : "#111111"', 1),
])
patch("qml/Main.qml", [
    (r'    Connections \{ target: Gps; function onUpdated\(d\) \{ root\._gpsLastFix = Date\.now\(\); root\.gpsFresh = true \} \}',
'''    Connections {
        target: Gps
        function onUpdated(d) {
            root._gpsLastFix = Date.now() - (d.ageMs || 0)
            root.gpsFresh = d.fix !== false && (Date.now() - root._gpsLastFix) < 3000
            VoltaLimits.updatePosition(d)
        }
    }
    Connections {
        target: Nav
        Component.onCompleted: VoltaLimits.setNavigationLimit(Nav.navigating && !Nav.rerouting ? Nav.speedLimit : -1)
        function onProgressChanged() { VoltaLimits.setNavigationLimit(Nav.navigating && !Nav.rerouting ? Nav.speedLimit : -1) }
        function onNavigatingChanged() { VoltaLimits.setNavigationLimit(Nav.navigating && !Nav.rerouting ? Nav.speedLimit : -1) }
        function onReroutingChanged() { VoltaLimits.setNavigationLimit(Nav.navigating && !Nav.rerouting ? Nav.speedLimit : -1) }
    }
    Connections {
        target: VoltaLimits
        function onWarningTriggered(value) {
            notifStack.notify({ id: "vehicle-speed", iconType: "info", iconColor: "#D3232B",
                title: qsTr("Speed limit exceeded"), subtitle: qsTr("Speed limit: %1 km/h").arg(value),
                autoDismissMs: 6000 })
        }
        function onChanged() { if (!VoltaLimits.overspeed) notifStack.dismiss("vehicle-speed") }
    }''', 1),
])
patch("qml/Settings.qml", [
    (r'^([ \t]*)SectionTitle \{ title: qsTr\("OBD adapter"\) \}',
     r'''\1SectionTitle { title: qsTr("Speed limits") }
\1Item {
\1    width: parent.width; height: 42
\1    Text { anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("OpenStreetMap speed limits"); color: root.txt; font.pixelSize: 15 }
\1    TToggle { anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; checked: VoltaLimits.enabled; onToggled: on => VoltaLimits.setEnabled(on) }
\1}
\1Item {
\1    width: parent.width; height: 42
\1    Text { anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Visual speed warnings"); color: root.txt; font.pixelSize: 15 }
\1    TToggle { anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; checked: VoltaLimits.warningsEnabled; onToggled: on => VoltaLimits.setWarningsEnabled(on) }
\1}
\1Text { text: qsTr("Warning threshold above the limit (km/h)"); color: root.txtSub; font.pixelSize: 13 }
\1TSegmented {
\1    width: Math.min(parent.width, 420)
\1    options: ["0", "+3", "+5", "+10", "+15", "+20"]
\1    current: [0, 3, 5, 10, 15, 20].indexOf(VoltaLimits.tolerance)
\1    onPicked: idx => VoltaLimits.setTolerance([0, 3, 5, 10, 15, 20][idx])
\1}
\1Text {
\1    width: parent.width; wrapMode: Text.WordWrap; font.pixelSize: 13; color: root.txtHint
\1    text: VoltaLimits.status + (VoltaLimits.source !== "" ? " · " + VoltaLimits.source : "")
\1          + (VoltaLimits.road !== "" ? " · " + VoltaLimits.road : "")
\1}
\1Text {
\1    width: parent.width; wrapMode: Text.WordWrap; font.pixelSize: 12; color: root.txtHint
\1    text: qsTr("Position comes from Volta. Warnings are visual only. OSM needs internet or cached roads. Estimated or unclear limits do not trigger warnings.") + "\\n© OpenStreetMap contributors · ODbL"
\1}
\1TDivider {}
\1SectionTitle { title: qsTr("OBD adapter") }''', 1),
])

# ── Stufe 3: Reifendruck und Android-Systemfunktionen ───────────────────────
patch("qml/CarScene.qml", [
    IMP,
    (r'^import VoltaPort 1\.0$', 'import VoltaPort 1.0\nimport "qrc:/volta_port" as Port', 1),
    (r'    property bool adjustOpen: false', '''    property var portTireCache: []
    Connections { target: CarModels; function onCurrentChanged() { root.portTireCache = [] } function onPartsChanged() { root.portTireCache = [] } }
    function portTirePoint(index, item) {
        var rig = root.userCarMain
        var cached = root.portTireCache[index]
        if (rig && rig.loaded && (!cached || !cached.node)) {
            var code = ["FL", "FR", "RL", "RR"][index]
            var name = rig.parts["wheel" + code]
            if (name && typeof name !== "string") name = name.length ? name[0] : ""
            var node = name ? rig._nodeByName(name) : null
            if (node) {
                var bounds = CarModels.partBounds(rig.file, name)
                var center = bounds.length >= 6 ? Qt.vector3d((bounds[0]+bounds[3])/2, (bounds[1]+bounds[4])/2, (bounds[2]+bounds[5])/2) : Qt.vector3d(0,0,0)
                cached = {node:node, center:center}; root.portTireCache[index] = cached
            }
        }
        var world = cached && cached.node ? cached.node.mapPositionToScene(cached.center) : carRoot.mapPositionToScene(Qt.vector3d(index % 2 === 0 ? 0.65 : -0.65, -0.15, index < 2 ? 1 : -1))
        var p = view.mapFrom3DScene(world)
        return view.mapToItem(item, p.x, p.y)
    }
    Port.TireOverlay {
        anchors.fill: parent; z: 45; scene: root; lightTheme: root.lightTheme
        visible: VoltaTpms.enabled && !root.uiHidden && !root.fsdMode && !root.replacing && !root.configuring
    }
    property bool adjustOpen: false''', 1),
])
patch("qml/Main.qml", [
    (r'    Connections \{\n        target: VoltaLimits\n', '''    Connections {
        target: VoltaTpms
        function onWarningTriggered(wheel, reason) {
            var names = {FL:qsTr("Front left"), FR:qsTr("Front right"), RL:qsTr("Rear left"), RR:qsTr("Rear right")}
            var reasons = {leak:qsTr("Pressure loss detected"), critical_pressure:qsTr("Very low tyre pressure"), low_pressure:qsTr("Tyre pressure low"), temperature:qsTr("Tyre temperature high"), battery:qsTr("Sensor battery low")}
            notifStack.notify({id:"tyre-"+wheel, iconType:"info", iconColor:"#ED4E3B", title:names[wheel]||wheel,
                subtitle:reasons[reason]||reason, autoDismissMs:8000})
        }
    }
    Connections {
        target: VoltaLimits
''', 1),
])
patch("qml/Settings.qml", [
    (r'^([ \t]*)SectionTitle \{ title: qsTr\("Speed limits"\) \}', r'''\1SectionTitle { title: qsTr("Tyre pressure") }
\1Item {
\1    width: parent.width; height: 42
\1    Text { anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("USB tyre pressure receiver"); color: root.txt; font.pixelSize: 15 }
\1    TToggle { anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; checked: VoltaTpms.enabled; onToggled: on => VoltaTpms.setEnabled(on) }
\1}
\1Text { width: parent.width; text: VoltaTpms.status; color: root.txtHint; wrapMode: Text.WordWrap; font.pixelSize: 13 }
\1Text { width: parent.width; text: qsTr("Set cold tyre pressure targets from the vehicle placard. Pressure comparisons stay off until both targets are set."); color: root.txtHint; wrapMode: Text.WordWrap; font.pixelSize: 12 }
\1Row {
\1    spacing: 12
\1    Text { width: 100; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Front"); color: root.txt; font.pixelSize: 14 }
\1    TSecondary { width: 44; label: "−"; onClicked: VoltaTpms.setFront(VoltaTpms.frontTarget > 0 ? VoltaTpms.frontTarget - 0.1 : 2.4) }
\1    Text { width: 70; anchors.verticalCenter: parent.verticalCenter; horizontalAlignment: Text.AlignHCenter; text: VoltaTpms.frontTarget > 0 ? VoltaTpms.frontTarget.toFixed(1) + " bar" : "— bar"; color: root.txt; font.pixelSize: 15 }
\1    TSecondary { width: 44; label: "+"; onClicked: VoltaTpms.setFront(VoltaTpms.frontTarget > 0 ? VoltaTpms.frontTarget + 0.1 : 2.4) }
\1}
\1Row {
\1    spacing: 12
\1    Text { width: 100; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Rear"); color: root.txt; font.pixelSize: 14 }
\1    TSecondary { width: 44; label: "−"; onClicked: VoltaTpms.setRear(VoltaTpms.rearTarget > 0 ? VoltaTpms.rearTarget - 0.1 : 2.4) }
\1    Text { width: 70; anchors.verticalCenter: parent.verticalCenter; horizontalAlignment: Text.AlignHCenter; text: VoltaTpms.rearTarget > 0 ? VoltaTpms.rearTarget.toFixed(1) + " bar" : "— bar"; color: root.txt; font.pixelSize: 15 }
\1    TSecondary { width: 44; label: "+"; onClicked: VoltaTpms.setRear(VoltaTpms.rearTarget > 0 ? VoltaTpms.rearTarget + 0.1 : 2.4) }
\1}
\1Repeater {
\1    model: 4
\1    Column {
\1        width: parent.width; spacing: 6
\1        property var tire: VoltaTpms.wheels.length > index ? VoltaTpms.wheels[index] : ({valid:false,channel:index})
\1        Text { text: [qsTr("Front left"),qsTr("Front right"),qsTr("Rear left"),qsTr("Rear right")][index] + " · " + (parent.tire.valid ? Number(parent.tire.pressure).toFixed(2) + " bar · " + parent.tire.temperature + " °C" : qsTr("No fresh data")); color: root.txt; font.pixelSize: 14 }
\1        TSegmented { options:["VL","VR","HL","HR"]; current:parent.tire.channel; onPicked: channel => VoltaTpms.assign(index,channel) }
\1    }
\1}
\1Text { width: parent.width; text: qsTr("Assign receiver positions to the correct wheels. Sensor learning is handled by the receiver. Missing or stale readings stay unknown; warnings are visual only."); color: root.txtHint; wrapMode: Text.WordWrap; font.pixelSize: 12 }
\1TDivider {}
\1SectionTitle { title: qsTr("Speed limits") }''', 1),
])
patch("qml/BluetoothSettings.qml", [
    (r'            root.adapterAvailable = d.available !== false', '            root.adapterAvailable = d.available !== false\n            if (d.message) root.statusMsg = d.message', 1),
    (r'function\(\) \{\n            root.refreshStatus\(\)\n            if \(on\) root.startScan\(\)', 'function(status, d) {\n            if (d && d.message) root.statusMsg = d.message\n            root.refreshStatus()\n            if (on && d && d.ok) root.startScan()', 1),
    (r'visible: !root.adapterAvailable && root.poweredOn', 'visible: !root.adapterAvailable', 1),
    (r'text: qsTr\("No Bluetooth adapter found on the system\."\)', 'text: qsTr("This Android system exposes no Bluetooth adapter. BlueStacks usually cannot provide Bluetooth devices to apps. Test Bluetooth on the radio.")', 1),
])


patch("qml/BluetoothSettings.qml", [
    (r'    property bool adapterAvailable: true', '    property bool adapterAvailable: true\n    property bool androidBackend: false', 1),
    (r'            root.adapterAvailable = d.available !== false', '            root.adapterAvailable = d.available !== false\n            root.androidBackend = !!d.android', 1),
    (r'        // ── dispositivi abbinati ──', '''        Rectangle {
            visible: root.androidBackend; width: 240; height: 44; radius: 8; color: root.ctrlBg
            Text { anchors.centerIn: parent; text: qsTr("Android Bluetooth settings"); color: root.txt; font.pixelSize: 14 }
            MouseArea { anchors.fill: parent; onClicked: root.xhrCall("POST", "/bt/settings", {}, function(status,d) { if(d && d.message)root.statusMsg=d.message }) }
        }
        // ── dispositivi abbinati ──''', 1),
])
patch("qml/WebApps.qml", [
    (r'    function key\(u\) \{', '''    function favicon(u) { var m = /^https?:\/\/[^/]+/i.exec(String(u)); return m ? m[0] + "/favicon.ico" : "" }
    function key(u) {''', 1),
    (r'"http://127\.0\.0\.1:9098/webapp-icon\?url=" \+ encodeURIComponent\(String\(u\)\)', 'wa.favicon(u)', 1),
])



# QML cannot declare both property loading and signal loadingChanged(info).
# Keep the WebEngine event name and use portLoading for the two toolbar bindings.
patch("qml/BrowserApp.qml", [(r'webContent\.loading', 'webContent.portLoading', 4)])
patch("qml/WebAppView.qml", [(r'web\.loading', 'web.portLoading', 4)])


# ── Mercedes: one-tap, read-only test for W204 722.6 (results are research only) ──
patch("qml/Settings.qml", [
    (r'^([ \t]*)SectionTitle \{ title: qsTr\("OBD adapter"\) \}',
     r'''\1SectionTitle { title: qsTr("Mercedes diagnostic check · 5G-Tronic") }
\1Text {
\1    width: parent.width; wrapMode: Text.WordWrap; font.pixelSize: 12; color: root.txtHint
\1    text: qsTr("Read-only targeted check: 722.6 / EGS via 7E1–7E9 (21 30), and OM651/CRD3 candidate 22 2007. No full PID sweep. Unverified values are not shown as measurements.")
\1}
\1Row {
\1    spacing: 12
\1    TSecondary { label: VoltaCar.mercedesBusy ? qsTr("Testing…") : qsTr("Test Mercedes"); onClicked: if (!VoltaCar.mercedesBusy) VoltaCar.runMercedesCheck() }
\1    TSecondary { label: qsTr("Save report"); onClicked: VoltaCar.exportMercedesCheck() }
\1}
\1Text {
\1    width: parent.width; wrapMode: Text.WrapAnywhere; font.pixelSize: 12; color: root.txtSub
\1    text: VoltaCar.mercedesReport
\1}
\1Text {
\1    visible: VoltaCar.mercedesExport !== ""
\1    width: parent.width; wrapMode: Text.WordWrap; font.pixelSize: 12; color: root.txtHint
\1    text: VoltaCar.mercedesExport
\1}
\1TDivider {}
\1SectionTitle { title: qsTr("OBD adapter") }''', 1),
])


# Stufe 4: Dashcam aus dem ASD-Jarvis-Repository, standardmaessig AUS.
patch("qml/Settings.qml", [
    (r'^([ \t]*)SectionTitle \{ title: qsTr\("OBD adapter"\) \}',
     r'''\1SectionTitle { title: qsTr("USB dashcam · STARVIS 2") }
\1Item {
\1    width: parent.width; height: 46
\1    Text {
\1        anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter
\1        text: qsTr("Enable USB camera"); font.pixelSize: 15; color: root.txt
\1    }
\1    TToggle {
\1        anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
\1        checked: VoltaDashcam.enabled
\1        onToggled: on => VoltaDashcam.setEnabled(on)
\1    }
\1}
\1Item {
\1    width: parent.width; height: 42
\1    Text { anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Loop recording"); color: root.txt; font.pixelSize: 15 }
\1    TToggle { anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; checked: VoltaDashcam.recordingEnabled; onToggled: on => VoltaDashcam.setRecordingEnabled(on) }
\1}
\1Item {
\1    width: parent.width; height: 42
\1    Text { anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; text: qsTr("Traffic sign recognition"); color: root.txt; font.pixelSize: 15 }
\1    TToggle { anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; checked: VoltaDashcam.signsEnabled; onToggled: on => VoltaDashcam.setSignsEnabled(on) }
\1}
\1Text { width: parent.width; text: VoltaDashcam.signStatus; color: root.txtHint; wrapMode: Text.WordWrap; font.pixelSize: 12 }
\1TSecondary {
\1    label: qsTr("Open camera preview")
\1    onClicked: if (root.hostWindow) {
\1        root.hostWindow.activeApp = "camera"
\1        root.hostWindow.carFullScreen = false
\1        root.hostWindow.appPanelFull = false
\1        root.hostWindow.showGrid = false
\1    }
\1}
\1Text {
\1    width: parent.width; wrapMode: Text.WordWrap; font.pixelSize: 12; color: root.txtHint
\1    text: VoltaDashcam.status + (VoltaDashcam.recording ? " · REC " + VoltaDashcam.fps + " fps" : "")
\1          + " · " + qsTr("Saved events: ") + VoltaDashcam.savedCount
\1}
\1Row {
\1    spacing: 12
\1    TSecondary {
\1        label: qsTr("Save event")
\1        onClicked: if (VoltaDashcam.enabled) VoltaDashcam.saveEvent()
\1    }
\1}
\1Text {
\1    width: parent.width; wrapMode: Text.WordWrap; font.pixelSize: 12; color: root.txtHint
\1    text: qsTr("Preview: Camera app, Front dashcam tile. Recording and sign recognition work independently. USB camera default OFF. Recordings: Movies/W204-Dashcam.")
\1}
\1TDivider {}
\1SectionTitle { title: qsTr("OBD adapter") }''', 1),
])
patch("qml/CameraApp.qml", [
    (r'^import VoltaApp 1\.0$', 'import VoltaApp 1.0\nimport VoltaPort 1.0\nimport QtQuick.Window', 1),
    (r'    property bool active: false', '''    property bool active: false
    function portHideUsbPreview() { VoltaDashcam.preview(false,0,0,0,0,1,1) }
    onActiveChanged: if (!active) portHideUsbPreview()
    onVisibleChanged: if (!visible) portHideUsbPreview()
    Component.onDestruction: portHideUsbPreview()
    Connections {
        target: VoltaDashcam
        function onChanged() { if (!VoltaDashcam.enabled) cam.portHideUsbPreview() }
    }
    Timer {
        interval: 100; repeat: true; triggeredOnStart: true
        running: cam.active && cam.visible && Qt.application.state === Qt.ApplicationActive && VoltaDashcam.enabled && (!cam.Window.window || !cam.Window.window.showGrid)
        onRunningChanged: if (!running) cam.portHideUsbPreview()
        onTriggered: {
            var w = cam.Window.window
            if (!w) return
            var p = mainCam.mapToItem(null,0,0)
            VoltaDashcam.preview(true,p.x,p.y,mainCam.width,mainCam.height,w.width,w.height)
        }
    }''', 1),
    (r'        label: qsTr\("Rear"\)', '        label: qsTr("Front dashcam") + " · " + VoltaDashcam.status', 1),
])


# Native map services keep Volta's existing views. Provider failures are visible, speech stays off.
patch("qml/MapLibreView.qml", [
    (r'Qt\.openUrlExternally\("https://" \+ root.selectedPlace.website\)', 'Qt.openUrlExternally((root.selectedPlace.website.indexOf("https://") === 0 || root.selectedPlace.website.indexOf("http://") === 0 ? "" : "https://") + root.selectedPlace.website)', 1),
    (r'    property bool speedCamVoice: true', '    readonly property bool speedCamVoice: false', 1),
    (r'    function refreshCams\(force\) \{', '    property real portCamFixAt: 0\n    property var portCamFixId: null\n    function portTrackCameraFix(d) {\n        if (!d.fix || !d.accuracyValid || !d.bearingValid || !d.speedValid || !(d.accuracy >= 0 && d.accuracy <= 30) || !(d.ageMs >= 0 && d.ageMs <= 3000)) { root.portCamFixAt = 0; return }\n        if (d.fixId !== undefined && d.fixId !== root.portCamFixId) { root.portCamFixId = d.fixId; root.portCamFixAt = Date.now() - d.ageMs }\n    }\n    function portCameraFixFresh() { var age = Date.now() - root.portCamFixAt; return age >= 0 && age <= 3000 }\n    function refreshCams(force) {\n        if (!root.portCameraFixFresh()) return', 1),
    (r'    function evalCamAlert\(\) \{', '    function evalCamAlert() {\n        if (!root.portCameraFixFresh()) { root.camAlert = null; return }', 1),
    (r'    function handleGpsFix\(d\) \{', '    function handleGpsFix(d) {\n        root.portTrackCameraFix(d)', 1),
    (r'    property bool nearbyLoading: false', '    property bool nearbyLoading: false\n    property string portNearbyStatus: ""\n    property int portNearbySeq: 0', 1),
    (r'        root.nearbyLoading = true', '        root.nearbyLoading = true\n        root.portNearbyStatus = ""\n        var requestSeq = ++root.portNearbySeq', 1),
    (r'                root.nearbyLoading = false', '                if (requestSeq !== root.portNearbySeq) return\n                root.nearbyLoading = false\n                if (xhr.status !== 200) root.portNearbyStatus = qsTr("Nearby search unavailable. Check the TomTom key and internet connection.")', 1),
    (r'text: qsTr\("No results nearby"\)', 'text: root.portNearbyStatus || qsTr("No results nearby")', 1),
    (r'    property var poiSearchResults: \[\]', '    property var poiSearchResults: []\n    property string portPlaceStatus: ""\n    property int portPlaceSeq: 0', 1),
    (r'^    function poiSearch\(q\) \{[\s\S]*?^    \}', r"""    function poiSearch(q) {
        var seq = ++root.portPlaceSeq
        root.poiSearchResults = []
        root.portPlaceStatus = qsTr("Searching…")
        var xhr = new XMLHttpRequest()
        xhr.onreadystatechange = function() {
            if (xhr.readyState !== XMLHttpRequest.DONE || seq !== root.portPlaceSeq || searchInput.text.trim() !== q) return
            if (xhr.status === 200) {
                try {
                    var list = JSON.parse(xhr.responseText)
                    root.poiSearchResults = list
                    root.portPlaceStatus = list.length ? "" : qsTr("No matching places")
                    return
                } catch(e) {}
            }
            root.poiSearchResults = []
            root.portPlaceStatus = qsTr("Search unavailable. Check the TomTom or MapTiler key and internet connection.")
        }
        xhr.open("GET", "http://127.0.0.1:9098/poi-search?q=" + encodeURIComponent(q) +
                        "&lat=" + root.carLat + "&lon=" + root.carLon)
        xhr.send()
    }""", 1),
    (r'(                id: contentCol\n                width: parent.width\n)', r"""\1
                Text {
                    width: parent.width
                    visible: searchInput.text.trim().length >= 2 && root.portPlaceStatus !== "" && root.nearbyPanelType === ""
                    text: root.portPlaceStatus
                    color: root.cSubtle; font.pixelSize: 14; wrapMode: Text.WordWrap
                    leftPadding: 20; rightPadding: 20; topPadding: 12; bottomPadding: 12
                }
""", 1),
])

print("port.py: ok")

# ── Selbst-Update: Karte "Update bereit" mit einem Tipp zum Installieren ─────
patch("qml/Main.qml", [
    (r'^    Connections \{\n        target: VoltaLimits\n',
     '''    Connections {
        target: VoltaUpdate
        function onReadyToInstall() {
            notifStack.notify({ id: "volta-update", iconType: "info", iconColor: "#3D6FE7",
                title: qsTr("Volta update ready"),
                subtitle: VoltaUpdate.message !== "" ? VoltaUpdate.message : qsTr("New version %1").arg(VoltaUpdate.remote),
                actionLabel: qsTr("Install"), onAction: function() { VoltaUpdate.install() } })
        }
    }
    Connections {
        target: VoltaLimits
''', 1),
])
