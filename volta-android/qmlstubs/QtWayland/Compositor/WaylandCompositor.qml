// Platzhalter: der eingebettete Wayland-Compositor (Linux-Programme im Fenster) existiert auf Android nicht. GPL-3.0-or-later
import QtQuick
QtObject {
    default property list<QtObject> data
    property string socketName
    property bool created: false
    property var defaultOutput: null
    property var defaultSeat: null
    signal surfaceRequested(var client, int id, int version)
    signal surfaceCreated(var surface)
    function destroyClient(c) {}
    function destroyClientForSurface(s) {}
}
