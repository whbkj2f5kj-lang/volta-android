import QtQuick
QtObject {
    property var collection: []
    function clear() { collection = [] }
    function insert(s) { collection=collection.concat([s]) }
    function remove(s) { collection=collection.filter(function(x){return x!==s}) }
    function contains(s) { return collection.indexOf(s)>=0 }
}
