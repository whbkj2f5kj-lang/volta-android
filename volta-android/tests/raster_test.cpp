#include <QtTest>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>
#include <QTemporaryDir>
#include <algorithm>
#include "../volta_mapproxy.cpp"
#include "fake_map_network.h"

class RasterTest : public QObject {
    Q_OBJECT
private slots:
    void realPortPluginsResolveAndSwitchRasterTiles_data() {
        QTest::addColumn<QString>("file");
        QTest::newRow("dashboard") << "MapLibreView.qml";
        QTest::newRow("mini") << "MapLibreMini.qml";
    }
    void realPortPluginsResolveAndSwitchRasterTiles() {
        QFETCH(QString, file);
        qputenv("MAPTILER_KEY", "dummy-raster-test-key");
        const QString upstream = qEnvironmentVariable("VOLTA_TEST_QML_DIR");
        QVERIFY2(!upstream.isEmpty(), "VOLTA_TEST_QML_DIR must point to the patched upstream QML");
        QFile source(upstream + '/' + file); QVERIFY(source.open(QIODevice::ReadOnly));
        const QString original = QString::fromUtf8(source.readAll());
        // Load the actual plugin configuration produced by port.py. A wrong
        // provider name/parameter must fail here before an Android APK ships.
        const int start = original.indexOf("Plugin {"); QVERIFY(start >= 0);
        int end = original.indexOf('{', start), depth = 1;
        for (++end; end < original.size() && depth; ++end) {
            if (original[end] == '{') ++depth;
            if (original[end] == '}') --depth;
        }
        QString plugin = original.mid(start, end - start);
        QVERIFY(plugin.contains("name: \"osm\"")); QVERIFY(!plugin.contains("maplibre"));
        plugin.remove(QRegularExpression("id: mapPlugin"));
        QTemporaryDir cache;
        FakeNetwork network;
        QImage tile(256, 256, QImage::Format_RGB32); tile.fill(Qt::darkGray);
        QBuffer buffer(&network.tileBody); buffer.open(QIODevice::WriteOnly); QVERIFY(tile.save(&buffer, "PNG"));
        MapProxy proxy(nullptr, &network, cache.path() + "/proxy", 0);
        plugin.replace("127.0.0.1:9098", "127.0.0.1:" + QString::number(proxy.port()));
        plugin.replace("StandardPaths.writableLocation(StandardPaths.CacheLocation) + \"/volta-raster-v1\"",
                       '"' + cache.path() + "/tiles\"");
        const QString helper = QUrl::fromLocalFile(QStringLiteral(VOLTA_PORT_TEST_DIR) + "/../portqml/RasterMap.js").toString();
        const QString qml = "import QtQuick\nimport QtCore\nimport QtLocation\nimport QtPositioning\nimport \"" + helper + "\" as RasterMap\n"
            "Item { width: 640; height: 400; property bool light: false; property bool satellite: false; property bool night: false;"
            "property int actualStyle: map.activeMapType.style; property bool actualNight: map.activeMapType.night;"
            "function apply() { RasterMap.apply(map, light, satellite, night) }"
            "onLightChanged: apply(); onSatelliteChanged: apply(); onNightChanged: apply();"
            "Map { id: map; anchors.fill: parent; plugin: " + plugin +
            "\n center: QtPositioning.coordinate(0, 0); zoomLevel: 3; copyrightsVisible: true;"
            "onSupportedMapTypesChanged: parent.apply(); Component.onCompleted: parent.apply() } }";
        QQmlEngine engine;
        QQmlComponent component(&engine); component.setData(qml.toUtf8(), QUrl("file:///raster-test.qml"));
        QTRY_VERIFY2_WITH_TIMEOUT(component.status() != QQmlComponent::Loading, qPrintable(component.errorString()), 5000);
        QVERIFY2(component.isReady(), qPrintable(component.errorString()));
        std::unique_ptr<QObject> object(component.create()); QVERIFY2(object, qPrintable(component.errorString()));
        QQuickWindow window;
        window.resize(640, 400);
        auto item = qobject_cast<QQuickItem *>(object.get()); QVERIFY(item);
        item->setParentItem(window.contentItem()); window.show();
        QTRY_VERIFY_WITH_TIMEOUT(object->property("actualNight").toBool(), 3000);
        QCOMPARE(object->property("actualStyle").toInt(), 6); // Night transit = dark roads.
        auto requested = [&network](const QString &prefix) {
            return std::any_of(network.urls.cbegin(), network.urls.cend(), [&prefix](const QUrl &url) { return url.path().startsWith(prefix); });
        };
        QTRY_VERIFY_WITH_TIMEOUT(requested("/dark_all/"), 3000);
        object->setProperty("light", true);
        QTRY_COMPARE(object->property("actualStyle").toInt(), 1);
        QTRY_VERIFY_WITH_TIMEOUT(requested("/light_all/"), 3000);
        object->setProperty("satellite", true);
        QTRY_COMPARE(object->property("actualStyle").toInt(), 2);
        QTRY_VERIFY_WITH_TIMEOUT(requested("/tiles/satellite-v2/"), 3000);
        object->setProperty("night", true);
        QTRY_COMPARE(object->property("actualStyle").toInt(), 4);
        // The renderer is attached to a real Quick window. Exercise scene-graph
        // synchronization and hide/show, in addition to provider discovery.
        QTest::qWait(200); window.hide(); window.show(); QTest::qWait(200);
        for (const QUrl &url : network.urls)
            QVERIFY2(url.host() == "basemaps.cartocdn.com" || url.path().startsWith("/tiles/satellite-v2/"), qPrintable(url.toString(QUrl::RemoveQuery)));
        item->setParentItem(nullptr);
    }
    void cleanup() { qunsetenv("MAPTILER_KEY"); }
};
QTEST_MAIN(RasterTest)
#include "raster_test.moc"
