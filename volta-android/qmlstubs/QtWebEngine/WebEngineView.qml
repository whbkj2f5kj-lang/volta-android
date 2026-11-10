// Platzhalter fuer Qt WebEngine (auf Android nicht verfuegbar). GPL-3.0-or-later
import QtQuick
import VoltaPort 1.0
Item {
    id: root
    enum LifecycleState { Active, Frozen, Discarded }
    enum LoadStatus { LoadStartedStatus, LoadStoppedStatus, LoadSucceededStatus, LoadFailedStatus }
    enum Feature { Geolocation, MediaAudioCapture, MediaVideoCapture, MediaAudioVideoCapture, DesktopVideoCapture, DesktopAudioVideoCapture, Notifications }
    property bool portVisible: visible
    property bool loading: false
    property string portError: ""
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
    function portUrlChanged(value) { if (String(url)!==String(value)) url=value }
    function runJavaScript(script, a, b) { if (native.item) native.item.runJavaScript(script, typeof a === "function" ? a : b) }
    function reload() { if (native.item) native.item.reload() }
    function reloadAndBypassCache() { reload() }
    function stop() { if (native.item) native.item.stop() }
    function goBack() { if (native.item) native.item.goBack() }
    function goForward() { if (native.item) native.item.goForward() }
    function loadHtml(html, baseUrl) { if (native.item) native.item.loadHtml(html, baseUrl) }
    function triggerWebAction(action) {}
    function grantFeaturePermission(origin, feature, granted) {}
    function fullScreenCancelled() {}
    function acceptAsNewWindow(request) {}
    Loader {
        id: native
        anchors.fill: parent
        active: AndroidPort.android && root.lifecycleState !== 2
        Component.onCompleted: if (active) setSource("qrc:/volta_port/AndroidWebSurface.qml", {bridge:root})
        onActiveChanged: if (active) setSource("qrc:/volta_port/AndroidWebSurface.qml", {bridge:root})
    }
    Rectangle {
        visible: !AndroidPort.android || native.status === Loader.Error || root.portError !== ""
        anchors.fill: parent
        color: root.backgroundColor
        Text {
            anchors.centerIn: parent
            width: Math.min(parent.width - 48, 520)
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.WordWrap
            color: "#7A8794"
            font.pixelSize: 18
            text: root.portError || "The web view could not be opened. Check that Android System WebView is installed and enabled."
        }
        Row {
            anchors.horizontalCenter:parent.horizontalCenter;anchors.bottom:parent.bottom;anchors.bottomMargin:24;spacing:12
            Repeater { model:["Reload","Open in browser"]
                Rectangle {width:160;height:44;radius:8;color:"#3A3A3A"
                    Text {anchors.centerIn:parent;text:modelData;color:"white";font.pixelSize:14}
                    MouseArea {anchors.fill:parent;onClicked:index===0?root.reload():Qt.openUrlExternally(root.url)}
                }
            }
        }
    }
}
