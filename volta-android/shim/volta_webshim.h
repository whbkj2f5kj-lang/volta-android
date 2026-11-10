// Platzhalter für Qt WebEngine (gibt es auf Android nicht): gerade so viel, dass SystemHelper.cpp baut.
// GPL-3.0-or-later
#pragma once
#include <QObject>
#include <QUrl>
#include <QByteArray>
#include <QNetworkCookie>

class QWebEngineUrlRequestInfo {
public:
    QUrl requestUrl() const { return {}; }
    void setHttpHeader(const QByteArray &, const QByteArray &) {}
};

class QWebEngineUrlRequestInterceptor : public QObject {
    Q_OBJECT
public:
    explicit QWebEngineUrlRequestInterceptor(QObject *parent = nullptr) : QObject(parent) {}
    virtual void interceptRequest(QWebEngineUrlRequestInfo &info) = 0;
};

class QWebEngineCookieStore : public QObject {
    Q_OBJECT
public:
    using QObject::QObject;
    void loadAllCookies() {}
    void deleteCookie(const QNetworkCookie &, const QUrl & = QUrl()) {}
    void setCookie(const QNetworkCookie &, const QUrl & = QUrl()) {}
signals:
    void cookieAdded(const QNetworkCookie &cookie);
    void cookieRemoved(const QNetworkCookie &cookie);
};

class QQuickWebEngineProfile : public QObject {
    Q_OBJECT
public:
    using QObject::QObject;
    QWebEngineCookieStore *cookieStore() const { return nullptr; }
    void setUrlRequestInterceptor(QWebEngineUrlRequestInterceptor *) {}
};
