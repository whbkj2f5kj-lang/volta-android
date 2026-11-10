import QtQuick
QtObject {
    property bool allowRunningInsecureContent: false
    property bool fullScreenSupportEnabled: false
    property bool javascriptCanAccessClipboard: false
    property bool javascriptCanPaste: false
    property bool javascriptCanOpenWindows: true
    property bool javascriptEnabled: true
    property bool localContentCanAccessRemoteUrls: false
    property bool localContentCanAccessFileUrls: true
    property bool localStorageEnabled: true
    property bool playbackRequiresUserGesture: true
    property bool pluginsEnabled: false
    property bool showScrollBars: true
    property bool autoLoadImages: true
    property bool webGLEnabled: true
    property bool accelerated2dCanvasEnabled: true
    property bool focusOnNavigationEnabled: false
    property bool screenCaptureEnabled: false
    property bool pdfViewerEnabled: false
    property bool dnsPrefetchEnabled: false
    property bool errorPageEnabled: true
    property bool forceDarkMode: false
    property bool scrollAnimatorEnabled: false
    property bool spatialNavigationEnabled: false
    property bool touchIconsEnabled: false
    property string defaultTextEncoding
}
