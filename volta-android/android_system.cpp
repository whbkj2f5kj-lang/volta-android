// Android-Port von Volta OS: Ersatz fuer services/network_settings_service.py (Port 9097).
// Voltas Einstellungsseiten fuer WLAN und Bluetooth fragen dort per HTTP; hier wird das an
// Android weitergegeben (PortJava.java). Dazu Lautstaerke und Helligkeit fuer SystemHelper.
// Nur Android. GPL-3.0-or-later
#include <QCoreApplication>
#include <QDebug>
#include <QHostAddress>
#include <QJniEnvironment>
#include <QJniObject>
#include <QJsonDocument>
#include <QJsonObject>
#include <QPointer>
#include <QSet>
#include <QTimer>
#include <QSettings>
#include <QTcpServer>
#include <QTcpSocket>
#include <QUrl>
#include <memory>

namespace {

const char kJava[] = "de/jarvis/volta/PortJava";

QJniObject ctx() { return QNativeInterface::QAndroidApplication::context(); }

QByteArray jstrToBytes(const QJniObject &o)
{
    QJniEnvironment env;
    env.checkAndClearExceptions();
    return o.isValid() ? o.toString().toUtf8() : QByteArray("{}");
}

QByteArray wifi(const QString &what, const QJsonObject &body)
{
    const QJniObject c = ctx();
    if (what == QLatin1String("status"))
        return jstrToBytes(QJniObject::callStaticObjectMethod(kJava, "wifiStatus", "(Landroid/content/Context;)Ljava/lang/String;", c.object()));
    if (what == QLatin1String("scan"))
        return jstrToBytes(QJniObject::callStaticObjectMethod(kJava, "wifiScan", "(Landroid/content/Context;)Ljava/lang/String;", c.object()));
    if (what == QLatin1String("toggle"))
        return jstrToBytes(QJniObject::callStaticObjectMethod(kJava, "wifiToggle", "(Landroid/content/Context;Z)Ljava/lang/String;",
                                                              c.object(), jboolean(body.value(QLatin1String("on")).toBool())));
    if (what == QLatin1String("connect")) {
        const QString ssid = body.value(QLatin1String("ssid")).toString();
        if (ssid.isEmpty()) return R"({"ok":false,"message":"SSID missing"})";
        return jstrToBytes(QJniObject::callStaticObjectMethod(
            kJava, "wifiConnect", "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", c.object(),
            QJniObject::fromString(ssid).object<jstring>(),
            QJniObject::fromString(body.value(QLatin1String("password")).toString()).object<jstring>()));
    }
    if (what == QLatin1String("forget"))
        return jstrToBytes(QJniObject::callStaticObjectMethod(
            kJava, "wifiForget", "(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;", c.object(),
            QJniObject::fromString(body.value(QLatin1String("ssid")).toString()).object<jstring>()));
    if (what == QLatin1String("disconnect"))
        return jstrToBytes(QJniObject::callStaticObjectMethod(kJava, "wifiDisconnect", "(Landroid/content/Context;)Ljava/lang/String;", c.object()));
    if (what == QLatin1String("saved")) return R"({"saved":[]})";
    return {};
}

QByteArray bt(const QString &what, const QJsonObject &body)
{
    const QJniObject c = ctx();
    if (what == QLatin1String("status"))
        return jstrToBytes(QJniObject::callStaticObjectMethod(kJava, "btStatus", "(Landroid/content/Context;)Ljava/lang/String;", c.object()));
    if (what == QLatin1String("devices"))
        return jstrToBytes(QJniObject::callStaticObjectMethod(kJava, "btDevices", "(Landroid/content/Context;)Ljava/lang/String;", c.object()));
    if (what == QLatin1String("toggle"))
        return jstrToBytes(QJniObject::callStaticObjectMethod(kJava, "btToggle", "(Landroid/content/Context;Z)Ljava/lang/String;",
                                                              c.object(), jboolean(body.value(QLatin1String("on")).toBool())));
    if (what == QLatin1String("scan"))
        return jstrToBytes(QJniObject::callStaticObjectMethod(kJava, "btScan", "(Landroid/content/Context;Z)Ljava/lang/String;",
                                                              c.object(), jboolean(body.value(QLatin1String("on")).toBool(true))));
    if (what == QLatin1String("pair") || what == QLatin1String("forget") || what == QLatin1String("connect")
        || what == QLatin1String("disconnect")) {
        const QString addr = body.value(QLatin1String("address")).toString();
        if (addr.isEmpty()) return R"({"ok":false,"message":"address missing"})";
        return jstrToBytes(QJniObject::callStaticObjectMethod(
            kJava, "btAction", "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", c.object(),
            QJniObject::fromString(what).object<jstring>(), QJniObject::fromString(addr).object<jstring>()));
    }
    // Kopplungsanfragen mit PIN zeigt Android selbst an
    if (what == QLatin1String("pair/request")) return R"({"request":null})";
    if (what == QLatin1String("pair/answer")) return R"({"ok":true})";
    // Musik vom Handy laeuft ueber das Musik-Widget (Android-Mediensitzungen), nicht hierueber
    if (what.startsWith(QLatin1String("player"))) return R"({"available":false,"playing":false})";
    return {};
}

class SystemService : public QObject
{
public:
    explicit SystemService(QObject *parent) : QObject(parent)
    {
        connect(&m_srv, &QTcpServer::newConnection, this, [this]() {
            while (QTcpSocket *s = m_srv.nextPendingConnection()) accept(s);
        });
        if (m_srv.listen(QHostAddress::LocalHost, 9097))
            qInfo() << "[system] Wi-Fi/Bluetooth service on 127.0.0.1:9097";
        else
            qWarning().noquote() << "[system] cannot listen on 9097:" << m_srv.errorString();
    }

private:
    void accept(QTcpSocket *s)
    {
        connect(s, &QTcpSocket::disconnected, s, &QObject::deleteLater);
        auto buf = std::make_shared<QByteArray>();
        auto done = std::make_shared<bool>(false);
        connect(s, &QTcpSocket::readyRead, this, [this, s, buf, done]() {
            if (*done) { s->readAll(); return; }
            buf->append(s->readAll());
            const int end = buf->indexOf("\r\n\r\n");
            if (end < 0) { if (buf->size() > 65536) s->abort(); return; }
            const QByteArray head = buf->left(end);
            qsizetype len = 0;
            for (const QByteArray &line : head.split('\n'))
                if (line.toLower().startsWith("content-length:")) len = line.mid(15).trimmed().toLongLong();
            if (buf->size() < end + 4 + len) return;     // Rumpf noch nicht ganz da
            *done = true;
            const QList<QByteArray> first = head.left(head.indexOf("\r\n")).split(' ');
            const QByteArray lower = head.toLower();
            if (first.size() < 2 || lower.contains("\r\nsec-fetch-site: cross-site") || lower.contains("\r\nsec-fetch-site: same-site")) {
                reply(s, 403, "{}");
                return;
            }
            const QUrl url = QUrl::fromEncoded("http://127.0.0.1:9097" + first.at(1));
            const QJsonObject body = QJsonDocument::fromJson(buf->mid(end + 4, len)).object();
            route(s, url.path(), body);
        });
    }

    static void reply(QTcpSocket *s, int code, const QByteArray &body)
    {
        if (!s || s->state() != QAbstractSocket::ConnectedState) return;
        QByteArray h = "HTTP/1.1 " + QByteArray::number(code) + (code == 200 ? " OK" : code == 404 ? " Not Found" : " Error") + "\r\n";
        h += "Content-Type: application/json\r\nCache-Control: no-store\r\n";
        h += "Content-Length: " + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n";
        s->write(h);
        s->write(body);
        s->disconnectFromHost();
    }

    void route(QTcpSocket *s, const QString &path, const QJsonObject &body)
    {
        QByteArray out;
        if (path.startsWith(QLatin1String("/wifi/"))) out = wifi(path.mid(6), body);
        else if (path.startsWith(QLatin1String("/bt/"))) out = bt(path.mid(4), body);
        if (path == QLatin1String("/bt/wait")) {
            // Original: wartet bis zu 25 s auf eine Aenderung. Hier: nach 15 s "vielleicht geaendert"
            const QPointer<QTcpSocket> ps(s);
            QTimer::singleShot(15000, this, [ps, this]() { if (ps) reply(ps, 200, "{\"v\":" + QByteArray::number(++m_v) + "}"); });
            return;
        }
        if (out.isEmpty()) {
            if (m_unknown.size() < 30 && !m_unknown.contains(path)) {
                m_unknown.insert(path);
                qInfo().noquote() << "[system] not ported:" << path;
            }
            reply(s, 404, R"({"ok":false,"message":"not available on Android"})");
            return;
        }
        reply(s, 200, out);
    }

    QTcpServer m_srv;
    QSet<QString> m_unknown;
    int m_v = 0;
};

} // namespace

void voltaPortStartSystemService()
{
    static SystemService *svc = nullptr;
    if (!svc) svc = new SystemService(QCoreApplication::instance());
}

int voltaPortGetVolume()
{
    QJniEnvironment env;
    const int v = QJniObject::callStaticMethod<jint>(kJava, "getVolume", "(Landroid/content/Context;)I", ctx().object());
    env.checkAndClearExceptions();
    return v;
}

void voltaPortSetVolume(int percent)
{
    QJniEnvironment env;
    QJniObject::callStaticMethod<void>(kJava, "setVolume", "(Landroid/content/Context;I)V", ctx().object(), jint(percent));
    env.checkAndClearExceptions();
}

// Helligkeit: systemweit, wenn Volta das darf (einmalige Erlaubnis), sonst nur fuer Voltas Fenster
QString voltaPortSetBrightness(int percent)
{
    QJniEnvironment env;
    const QString mode = QJniObject::callStaticObjectMethod(kJava, "setBrightness", "(Landroid/content/Context;I)Ljava/lang/String;",
                                                            ctx().object(), jint(percent)).toString();
    env.checkAndClearExceptions();
    if (mode != QLatin1String("system")) {
        QSettings s;
        if (!s.value(QStringLiteral("port/askedWriteSettings")).toBool()) {
            s.setValue(QStringLiteral("port/askedWriteSettings"), true);
            qInfo() << "[system] asking once for permission to change the system brightness";
            QJniObject::callStaticMethod<void>(kJava, "openSettings", "(Landroid/content/Context;Ljava/lang/String;)V", ctx().object(),
                                               QJniObject::fromString(QStringLiteral("android.settings.action.MANAGE_WRITE_SETTINGS")).object<jstring>());
            env.checkAndClearExceptions();
        }
    }
    return mode;
}

bool voltaPortMediaAccess()
{
    QJniEnvironment env;
    const bool ok = QJniObject::callStaticMethod<jboolean>(kJava, "mediaAccess", "(Landroid/content/Context;)Z", ctx().object());
    env.checkAndClearExceptions();
    return ok;
}

void voltaPortOpenAndroidSettings(const QString &action)
{
    QJniEnvironment env;
    QJniObject::callStaticMethod<void>(kJava, "openSettings", "(Landroid/content/Context;Ljava/lang/String;)V", ctx().object(),
                                       QJniObject::fromString(action).object<jstring>());
    env.checkAndClearExceptions();
}
