import QtQuick
QtObject {
    property var collection: []
    function clear() { collection = [] }
    function insert(s) {}
    function remove(s) {}
    function contains(s) { return false }
}
