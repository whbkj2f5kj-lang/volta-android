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
    ${VOLTA_PORT_DIR}/volta_mapproxy.cpp
    ${VOLTA_PORT_DIR}/shim/volta_webshim.h)
target_include_directories(VoltaOS BEFORE PRIVATE
    ${VOLTA_PORT_DIR}/shim ${VOLTA_PORT_DIR}/shim/QtWebEngineQuick)
target_compile_definitions(VoltaOS PRIVATE VOLTA_PORT_STAMP="${VOLTA_PORT_STAMP}")
file(GLOB_RECURSE VOLTA_PORT_STUBS ${VOLTA_PORT_DIR}/qmlstubs/*)
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
        QT_ANDROID_VERSION_CODE "${VOLTA_PORT_CODE}")
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
    (r'^#include "CarModelManager.h"\n', '#include "CarModelManager.h"\nQString voltaPortLocalFile(const QString &path);\n', 1),
    (r"QString p = srcPath;", "QString p = voltaPortLocalFile(srcPath);", 2),
])
print("port.py: ok")
