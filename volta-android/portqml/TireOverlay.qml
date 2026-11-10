import QtQuick
import VoltaApp 1.0
import VoltaPort 1.0
Item {
 id: overlay
 property var scene
 property bool lightTheme: false
 Repeater {
  model: 4
  Item {
   id: label
   required property int index
   width: 78; height: 40
   property var reading: VoltaTpms.wheels.length > index ? VoltaTpms.wheels[index] : ({valid:false})
   property point point: Qt.point(-1000,-1000)
   x: point.x-width/2; y: point.y-46
   visible: point.x>0 && point.y>0 && point.x<overlay.width && point.y<overlay.height
   Rectangle {anchors.fill:parent;radius:6;color:overlay.lightTheme?"#D9FFFFFF":"#CC202020"}
   Column {anchors.centerIn:parent;spacing:1
    Text {anchors.horizontalCenter:parent.horizontalCenter;text:label.reading.valid?Number(label.reading.pressure).toFixed(2)+" bar":"— bar";font.pixelSize:14;font.weight:Font.DemiBold;color:label.reading.warning?"#ED4E3B":overlay.lightTheme?"#171717":"#F0F0F0"}
    Text {anchors.horizontalCenter:parent.horizontalCenter;text:label.reading.valid?label.reading.temperature+" °C":"— °C";font.pixelSize:11;color:overlay.lightTheme?"#666666":"#AAAAAA"}
   }
   Timer {interval:80;repeat:true;running:overlay.visible;triggeredOnStart:true;onTriggered:label.point=overlay.scene.portTirePoint(label.index,overlay)}
  }
 }
}
