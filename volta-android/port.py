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
    list(REMOVE_ITEM _volta_src src/nav/GpsService.cpp ${CMAKE_CURRENT_SOURCE_DIR}/src/nav/GpsService.cpp)
    set_property(TARGET VoltaOS PROPERTY SOURCES ${_volta_src})
    target_sources(VoltaOS PRIVATE ${VOLTA_PORT_DIR}/android_gps.cpp)
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
    (r'^(\s*)gear:       "P"\n\s*speedValue: "0"\n\s*speedUnit:  "km/h"\n\s*headlightOn: true\n\s*seatbeltOn:  true\n',
     r'\1property real portKmh: 0\n'
     r'\1speedValue: Math.round(portKmh).toString()\n'
     r'\1speedUnit:  "km/h"\n'
     r'\1headlightOn: false\n'
     r'\1seatbeltOn:  false\n'
     r'\1Connections {\n'
     r'\1    target: Gps\n'
     r'\1    function onUpdated(d) { var k = (d.speed || 0) * 3.6; speedCluster.portKmh = k < 2 ? 0 : k; portSpeedStale.restart() }\n'
     r'\1}\n'
     r'\1Timer { id: portSpeedStale; interval: 3000; onTriggered: speedCluster.portKmh = 0 }\n', 1),
    (r'fillPct: 0 // To be connected to the real GPS if you want',
     'fillPct: Math.min(100, speedCluster.portKmh / 2)   // Android-Port: GPS-Tempo, voll bei 200 km/h', 1),
])
patch("qml/SpeedCluster.qml", [
    (r'^(\s*)Row \{\n(\s*)x: cluster\.rhd \? col\.width - width : 0\n(\s*)spacing: 7\n',
     r'\1Row {\n\2visible: false   // Android-Port: Gang ist nicht bekannt\n\2x: cluster.rhd ? col.width - width : 0\n\3spacing: 7\n', 1),
])
patch("qml/StatusBar.qml", [
    (r'property bool   showAirbag: true', 'property bool   showAirbag: false   // Android-Port: kein Signal vom Auto', 1),
])
print("port.py: ok")
