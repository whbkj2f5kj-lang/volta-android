import QtQuick
QtObject {
    enum InjectionPoint { Deferred, DocumentReady, DocumentCreation }
    enum ScriptWorldId { MainWorld, ApplicationWorld, UserWorld }
    property string name
    property string sourceCode
    property url sourceUrl
    property int injectionPoint: 0
    property int worldId: 0
    property bool runOnSubframes: false
}
