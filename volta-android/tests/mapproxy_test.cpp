#include <QtTest>
#include <QTemporaryDir>
#include "../volta_mapproxy.cpp"
#include "../volta_log_sanitize.h"

// All provider traffic is fake. These tests never send a key or request to a map provider.
class FakeReply : public QNetworkReply {
    QByteArray m_data;
    qint64 m_pos = 0;
public:
    FakeReply(const QNetworkRequest &request, int status, const QByteArray &data, QObject *parent)
        : QNetworkReply(parent), m_data(data) {
        setRequest(request); setUrl(request.url());
        setAttribute(QNetworkRequest::HttpStatusCodeAttribute, status);
        open(QIODevice::ReadOnly | QIODevice::Unbuffered);
        QTimer::singleShot(0, this, [this, status]() {
            if (status != 200) setError(ContentAccessDenied, "Error at " + url().toString());
            setFinished(true);
            emit readyRead(); emit finished();
        });
    }
    void abort() override {}
    qint64 bytesAvailable() const override { return m_data.size() - m_pos + QNetworkReply::bytesAvailable(); }
protected:
    qint64 readData(char *target, qint64 size) override {
        const qint64 n = qMin(size, qint64(m_data.size()) - m_pos);
        if (!n) return -1;
        memcpy(target, m_data.constData() + m_pos, size_t(n)); m_pos += n; return n;
    }
};
class FakeNetwork : public QNetworkAccessManager {
public:
    int primaryStatus = 404, freeStatus = 200;
    QByteArray primaryBody = R"({"version":8,"name":"custom","sources":{},"layers":[]})";
    QList<QUrl> urls;
protected:
    QNetworkReply *createRequest(Operation, const QNetworkRequest &r, QIODevice *) override {
        urls += r.url();
        if (r.url().host() == "api.maptiler.com") return new FakeReply(r, primaryStatus, primaryBody, this);
        return new FakeReply(r, freeStatus, R"({"version":8,"name":"free","sources":{},"layers":[]})", this);
    }
};
class MapProxyTest : public QObject {
    Q_OBJECT
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
