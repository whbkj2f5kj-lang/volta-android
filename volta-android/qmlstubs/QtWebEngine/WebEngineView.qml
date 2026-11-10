// Platzhalter fuer Qt WebEngine (auf Android nicht verfuegbar). GPL-3.0-or-later
import QtQuick
Item {
    id: root
    enum LifecycleState { Active, Frozen, Discarded }
    enum LoadStatus { LoadStartedStatus, LoadStoppedStatus, LoadSucceededStatus, LoadFailedStatus }
    enum Feature { Geolocation, MediaAudioCapture, MediaVideoCapture, MediaAudioVideoCapture, DesktopVideoCapture, DesktopAudioVideoCapture, Notifications }
    property url url
    property color backgroundColor: "#000000"
    property int lifecycleState: 0
    property int recommendedState: 0
    property var profile: null
    property string title: ""
    property url icon
    property int loadProgress: 0
    property bool canGoBack: false
    property bool canGoForward: false
    property bool recentlyAudible: false
    property bool audioMuted: false
    property bool isFullScreen: false
    property real zoomFactor: 1.0
    property WebEngineSettings settings: WebEngineSettings {}
    property WebEngineScriptCollection userScripts: WebEngineScriptCollection {}
    signal loadingChanged(var loadingInfo)
    signal fullScreenRequested(var request)
    signal navigationRequested(var request)
    signal newWindowRequested(var request)
    signal featurePermissionRequested(url securityOrigin, int feature)
    signal permissionRequested(var permission)
    signal certificateError(var error)
    signal renderProcessTerminated(int terminationStatus, int exitCode)
    function runJavaScript(script, a, b) {}
    function reload() {}
    function reloadAndBypassCache() {}
    function stop() {}
    function goBack() {}
    function goForward() {}
    function loadHtml(html, baseUrl) {}
    function triggerWebAction(action) {}
    function grantFeaturePermission(origin, feature, granted) {}
    function fullScreenCancelled() {}
    function acceptAsNewWindow(request) {}
    Rectangle {
        anchors.fill: parent
        color: root.backgroundColor
        Text {
            anchors.centerIn: parent
            width: Math.min(parent.width - 48, 520)
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.WordWrap
            color: "#7A8794"
            font.pixelSize: 18
            text: "Web content is not available in the Android port yet"
        }
    }
}
