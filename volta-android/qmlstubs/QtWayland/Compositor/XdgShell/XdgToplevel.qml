import QtQuick
QtObject {
    enum State { MaximizedState = 1, FullscreenState, ResizingState, ActivatedState }
    enum DecorationMode { ClientSideDecoration = 1, ServerSideDecoration }
    property string title
    property string appId
    property bool activated: false
    property bool fullscreen: false
    property bool maximized: false
    property int decorationMode: 0
    function sendClose() {}
    function sendFullscreen(size) {}
    function sendMaximized(size) {}
    function sendConfigure(size, states) {}
    function sendResizing(size) {}
    function sendUnmaximized(size) {}
}
