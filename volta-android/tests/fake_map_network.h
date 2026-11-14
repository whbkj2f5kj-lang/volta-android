#pragma once
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QTimer>

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
    int tileStatus = 200;
    QByteArray tileBody;
    QByteArray primaryBody = R"({"version":8,"name":"custom","sources":{},"layers":[]})";
    QList<QUrl> urls;
protected:
    QNetworkReply *createRequest(Operation, const QNetworkRequest &r, QIODevice *) override {
        urls += r.url();
        if (r.url().host() == "basemaps.cartocdn.com" || r.url().path().startsWith("/tiles/satellite-v2/"))
            return new FakeReply(r, tileStatus, tileBody, this);
        if (r.url().host() == "api.maptiler.com") return new FakeReply(r, primaryStatus, primaryBody, this);
        return new FakeReply(r, freeStatus, R"({"version":8,"name":"free","sources":{},"layers":[]})", this);
    }
};
