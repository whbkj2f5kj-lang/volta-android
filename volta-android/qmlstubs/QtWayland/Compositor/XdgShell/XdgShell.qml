import QtQuick
QtObject {
    signal toplevelCreated(var toplevel, var xdgSurface)
    signal popupCreated(var popup, var xdgSurface)
    signal xdgSurfaceCreated(var xdgSurface)
    signal pong(int serial)
}
