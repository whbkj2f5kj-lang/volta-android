.pragma library

// OSM provider discovery is asynchronous: choose by type, never by list index.
// The local repository assigns terrain to the darkened satellite imagery.
function apply(map, light, satellite, night) {
    if (!map) return;
    for (var i = 0; i < map.supportedMapTypes.length; ++i) {
        var type = map.supportedMapTypes[i];
        // QGeoMapType::MapStyle values: Street=1, SatelliteDay=2, Terrain=4,
        // Transit=6. Keep this helper independent of the caller's QML imports.
        var wanted = satellite ? (night ? 4 : 2) : (light ? 1 : 6);
        if (type.style !== wanted || (!satellite && !light && !type.night)) continue;
        if (map.activeMapType !== type) map.activeMapType = type;
        return;
    }
}
