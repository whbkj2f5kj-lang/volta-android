// Android-Port von Volta OS: Auswahl, welche Android-App ein Volta-Symbol oeffnet
// (z. B. Telefon -> Telefon-App des Radios, CarPlay -> ZLINK). Im Stil von Voltas Einstellungen.
// GPL-3.0-or-later
import QtQuick
import VoltaApp 1.0
import VoltaPort 1.0

Item {
    id: pop
    visible: slotId !== ""
    property string slotId: ""
    property string slotName: ""
    property color txt: "#FFFFFF"
    property color txtHint: "#A2A3A5"
    property color accent: Theme.blue
    property color panel: "#1F1F21"
    property color rowPressed: "#2C2C2E"

    function openFor(id, name) { slotId = id; slotName = name }
    function close() { slotId = "" }

    Rectangle {
        anchors.fill: parent
        color: "#99000000"
        MouseArea { anchors.fill: parent; onClicked: pop.close() }
    }

    Rectangle {
        id: card
        anchors.centerIn: parent
        width: Math.min(parent.width - 40, 520)
        height: Math.min(parent.height - 60, head.height + list.contentHeight + 32)
        radius: 18
        color: pop.panel
        MouseArea { anchors.fill: parent }

        Column {
            id: head
            x: 24; y: 22
            width: parent.width - 48
            spacing: 6
            Text { text: pop.slotName; color: pop.txt; font.pixelSize: 19; font.weight: Font.DemiBold }
            Text {
                width: parent.width
                text: qsTr("Choose which app opens when you tap this icon.")
                color: pop.txtHint; font.pixelSize: 13; wrapMode: Text.WordWrap
            }
        }

        ListView {
            id: list
            anchors.top: head.bottom; anchors.topMargin: 12
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.leftMargin: 12; anchors.rightMargin: 12; anchors.bottomMargin: 12
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            model: [{ pkg: "", name: qsTr("Volta (built-in)"), icon: "" }].concat(AndroidPort.apps)
            delegate: Rectangle {
                width: ListView.view.width; height: 56; radius: 10
                readonly property bool current: (AndroidPort.links[pop.slotId] || "") === modelData.pkg
                color: rowMA.pressed ? pop.rowPressed : "transparent"
                Image {
                    id: ic
                    visible: modelData.icon !== ""
                    anchors.left: parent.left; anchors.leftMargin: 12; anchors.verticalCenter: parent.verticalCenter
                    width: 34; height: 34
                    source: modelData.icon
                    sourceSize: Qt.size(102, 102); fillMode: Image.PreserveAspectFit; smooth: true; mipmap: true
                }
                Text {
                    anchors.left: parent.left; anchors.leftMargin: 60
                    anchors.right: tick.left; anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    text: modelData.name; color: pop.txt; font.pixelSize: 15; elide: Text.ElideRight
                }
                Text {
                    id: tick
                    anchors.right: parent.right; anchors.rightMargin: 16; anchors.verticalCenter: parent.verticalCenter
                    text: parent.current ? "✓" : ""; color: pop.accent; font.pixelSize: 18; font.weight: Font.Bold
                }
                MouseArea {
                    id: rowMA
                    anchors.fill: parent
                    onClicked: { AndroidPort.setLink(pop.slotId, modelData.pkg); pop.close() }
                }
            }
        }
    }
}
