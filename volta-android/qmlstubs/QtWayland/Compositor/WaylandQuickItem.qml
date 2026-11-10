import QtQuick
Item {
    property var surface: null
    property var output: null
    property var compositor: null
    property bool inputEventsEnabled: true
    property bool focusOnClick: true
    property bool touchEventsEnabled: true
    property bool paintEnabled: true
    property bool bufferLocked: false
    property bool allowDiscardFrontBuffer: false
    property var subsurfaceHandler: null
    signal surfaceDestroyed()
    function takeFocus(device) {}
    function setPrimary() {}
}
