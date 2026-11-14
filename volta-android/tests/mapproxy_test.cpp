#include <QtTest>
#include <QTemporaryDir>
#include "../volta_mapproxy.cpp"
#include "../volta_log_sanitize.h"

#include "fake_map_network.h"

class MapProxyTest : public QObject {
    Q_OBJECT
    QByteArray getPath(MapProxy &proxy, const QString &path, int *status = nullptr) {
        QNetworkAccessManager client;
        QNetworkReply *reply = client.get(QNetworkRequest(QUrl(QString("http://127.0.0.1:%1").arg(proxy.port()) + path)));
        QSignalSpy done(reply, &QNetworkReply::finished);
        if (!reply->isFinished() && !done.wait(3000)) return {};
        if (status) *status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        return reply->readAll();
    }
    QByteArray image(const char *format = "PNG", int size = 256) {
        QImage img(size, size, QImage::Format_RGB32); img.fill(QColor(200, 150, 100));
        QByteArray bytes; QBuffer output(&bytes); output.open(QIODevice::WriteOnly);
        if (!img.save(&output, format)) return {};
        return bytes;
    }
    QByteArray get(MapProxy &proxy, const QString &style = "dark") {
        QNetworkAccessManager client;
        QNetworkReply *reply = client.get(QNetworkRequest(QUrl(QString("http://127.0.0.1:%1/style.json?s=%2").arg(proxy.port()).arg(style))));
        QSignalSpy done(reply, &QNetworkReply::finished);
        if (!reply->isFinished() && !done.wait(3000)) return {};
        if (reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() != 200) return {};
        return reply->readAll();
    }
private slots:
    void init() { qputenv("MAPTILER_KEY", "dummy-map-key-do-not-use"); }
    void cleanup() { qunsetenv("MAPTILER_KEY"); }
    void rasterProvidersResolveLocallyWithDistinctThemes() {
        QTemporaryDir cache; FakeNetwork network; MapProxy proxy(nullptr, &network, cache.path(), 0);
        const QHash<QString, QString> styles{{"street", "light"}, {"satellite", "sat"}, {"cycle", "light"},
            {"transit", "light"}, {"night-transit", "dark"}, {"terrain", "satnight"}, {"hiking", "light"}};
        for (auto it = styles.begin(); it != styles.end(); ++it) {
            int status = 0;
            auto meta = QJsonDocument::fromJson(getPath(proxy, "/raster-providers/" + it.key(), &status)).object();
            QCOMPARE(status, 200); QVERIFY(meta["Enabled"].toBool());
            QCOMPARE(meta["ImageFormat"].toString(), "png");
            QCOMPARE(meta["UrlTemplate"].toString(), QString("http://127.0.0.1:%1/raster/%2/%z/%x/%y.png").arg(proxy.port()).arg(it.value()));
            QVERIFY(!meta["DataCopyRight"].toString().isEmpty());
            QCOMPARE(meta["MaximumZoomLevel"].toInt(), 19);
        }
        QVERIFY(network.urls.isEmpty());
    }
    void rasterRoadsDoNotNeedKeysOrVectorStyles() {
        qunsetenv("MAPTILER_KEY");
        QTemporaryDir cache; FakeNetwork network; network.tileBody = image();
        MapProxy proxy(nullptr, &network, cache.path(), 0);
        for (const QString &style : {QString("dark"), QString("light")}) {
            int status = 0;
            auto tile = QImage::fromData(getPath(proxy, "/raster/" + style + "/3/4/2.png", &status));
            QCOMPARE(status, 200); QCOMPARE(tile.size(), QSize(256, 256));
        }
        QCOMPARE(network.urls.size(), 2);
        for (const QUrl &url : network.urls) {
            QCOMPARE(url.host(), "basemaps.cartocdn.com"); QVERIFY(!url.hasQuery());
        }
        int status = 0; getPath(proxy, "/raster/sat/3/4/2.png", &status); QCOMPARE(status, 404);
        QCOMPARE(network.urls.size(), 2);
    }
    void satelliteJpegBecomesPngAndNightIsDarkerWithoutAnotherDownload() {
        QTemporaryDir cache; FakeNetwork network; network.tileBody = image("JPEG"); QVERIFY(!network.tileBody.isEmpty());
        MapProxy proxy(nullptr, &network, cache.path(), 0);
        int status = 0;
        const QByteArray day = getPath(proxy, "/raster/sat/3/4/2.png", &status); QCOMPARE(status, 200);
        const QByteArray night = getPath(proxy, "/raster/satnight/3/4/2.png", &status); QCOMPARE(status, 200);
        QVERIFY(day.startsWith("\x89PNG")); QVERIFY(night.startsWith("\x89PNG"));
        QVERIFY(QImage::fromData(night).pixelColor(0, 0).red() < QImage::fromData(day).pixelColor(0, 0).red());
        QCOMPARE(network.urls.size(), 1);
        QVERIFY(network.urls.first().path().startsWith("/tiles/satellite-v2/"));
    }
    void invalidRasterCoordinatesCannotReachAProvider() {
        QTemporaryDir cache; FakeNetwork network; MapProxy proxy(nullptr, &network, cache.path(), 0);
        for (const QString &path : {"/raster/dark/20/0/0.png", "/raster/dark/3/8/0.png", "/raster/dark/0/0/1.png",
                "/raster/dark/3/999999999999999/0.png", "/raster/light/-1/0/0.png", "/raster/other/0/0/0.png"}) {
            int status = 0; getPath(proxy, path, &status); QCOMPARE(status, 400);
        }
        QVERIFY(network.urls.isEmpty());
    }
    void malformedOrOversizedTilesAndNetworkFailuresStayHttpErrors() {
        for (const QByteArray &bytes : {QByteArray("<html>error</html>"), image("PNG", 1024)}) {
            QTemporaryDir cache; FakeNetwork network; network.tileBody = bytes;
            MapProxy proxy(nullptr, &network, cache.path(), 0);
            int status = 0; getPath(proxy, "/raster/dark/0/0/0.png", &status); QCOMPARE(status, 502);
            // A provider returning HTML with HTTP 200 must not poison its cache.
            network.tileBody = image();
            QVERIFY(!QImage::fromData(getPath(proxy, "/raster/dark/0/0/0.png", &status)).isNull());
            QCOMPARE(status, 200); QCOMPARE(network.urls.size(), 2);
        }
        QTemporaryDir cache; FakeNetwork network; network.tileStatus = 503;
        MapProxy proxy(nullptr, &network, cache.path(), 0);
        int status = 0; getPath(proxy, "/raster/dark/0/0/0.png", &status); QCOMPARE(status, 503);
        QCOMPARE(getPath(proxy, "/ping", &status), QByteArray("ok")); QCOMPARE(status, 200);
    }
    void inaccessibleCustomStyleUsesFreeStyleAndStopsRetrying() {
        QTemporaryDir cache; FakeNetwork network;
        MapProxy proxy(nullptr, &network, cache.path(), 0);
        QCOMPARE(QJsonDocument::fromJson(get(proxy)).object()["name"].toString(), "free");
        QCOMPARE(QJsonDocument::fromJson(get(proxy)).object()["name"].toString(), "free");
        QCOMPARE(network.urls.size(), 2); // One failed custom style and one cached free style.
    }
    void forbiddenAndMalformedStylesAlsoFallback() {
        for (int status : {403, 200}) {
            QTemporaryDir cache; FakeNetwork network; network.primaryStatus = status; network.primaryBody = "{}";
            MapProxy proxy(nullptr, &network, cache.path(), 0);
            QCOMPARE(QJsonDocument::fromJson(get(proxy, "light")).object()["name"].toString(), "free");
        }
    }
    void availableCustomStyleIsPreserved() {
        QTemporaryDir cache; FakeNetwork network; network.primaryStatus = 200;
        MapProxy proxy(nullptr, &network, cache.path(), 0);
        QCOMPARE(QJsonDocument::fromJson(get(proxy)).object()["name"].toString(), "custom");
        QCOMPARE(network.urls.size(), 1);
    }
    void savedKeySurvivesProxyRestartWithoutBreakingStyleLoading() {
        QTemporaryDir cache;
        {
            FakeNetwork network; MapProxy first(nullptr, &network, cache.path(), 0);
            QVERIFY(!get(first).isEmpty());
        }
        FakeNetwork network; MapProxy restarted(nullptr, &network, cache.path(), 0);
        QCOMPARE(QJsonDocument::fromJson(get(restarted)).object()["name"].toString(), "free");
        QCOMPARE(qgetenv("MAPTILER_KEY"), QByteArray("dummy-map-key-do-not-use"));
    }
    void noKeyAndNetworkFailureStillReturnsValidBackgroundStyle() {
        qunsetenv("MAPTILER_KEY"); QTemporaryDir cache; FakeNetwork network; network.freeStatus = 503;
        MapProxy proxy(nullptr, &network, cache.path(), 0);
        auto result = QJsonDocument::fromJson(get(proxy)).object();
        QCOMPARE(result["version"].toInt(), 8);
        QCOMPARE(result["layers"].toArray().size(), 1);
        QVERIFY(result["sources"].toObject().isEmpty());
    }
    void neitherProviderReachableDoesNotMakeStyleAnErrorPage() {
        QTemporaryDir cache; FakeNetwork network; network.freeStatus = 503;
        MapProxy proxy(nullptr, &network, cache.path(), 0);
        QCOMPARE(QJsonDocument::fromJson(get(proxy)).object()["name"].toString(), "Maps temporarily unavailable");
    }
    void logCredentialsAreRedactedForFileAndLogcat() {
        const QString safe = voltaSafeLog("failure https://example.test/style?key=dummy-map-key-do-not-use&x=1 token=unused https://x/?access_token=secret-token&z=2");
        QVERIFY(!safe.contains("dummy-map-key-do-not-use")); QVERIFY(!safe.contains("secret-token"));
        QVERIFY(safe.contains("&x=1")); QVERIFY(safe.contains("&z=2"));
        QVERIFY(safe.contains("[redacted]"));
    }
};
QTEST_GUILESS_MAIN(MapProxyTest)
#include "mapproxy_test.moc"
