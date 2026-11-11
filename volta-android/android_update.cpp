// Selbst-Update: Volta prueft auf GitHub, ob ein neuerer Build vorliegt, laedt ihn im
// Hintergrund herunter und bietet ihn als Karte zum Installieren an (ein Tipp, Android bestaetigt).
#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QFile>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QObject>
#include <QQmlEngine>
#include <QStandardPaths>
#include <QTimer>
#include <QUrl>
#ifdef Q_OS_ANDROID
#include <QCoreApplication>
#include <QJniEnvironment>
#include <QJniObject>
#endif

const char *voltaPortStamp();   // volta_port.cpp

namespace {
const char kBase[] = "https://github.com/whbkj2f5kj-lang/volta-android/releases/download/volta/";
#ifdef Q_OS_ANDROID
const char kUpdater[] = "de/jarvis/volta/Updater";
#endif

class VoltaUpdateQml : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString state READ state NOTIFY changed)       // idle, checking, current, downloading, ready, error
    Q_PROPERTY(bool ready READ ready NOTIFY changed)
    Q_PROPERTY(QString remote READ remote NOTIFY changed)
    Q_PROPERTY(QString current READ current CONSTANT)
    Q_PROPERTY(int progress READ progress NOTIFY changed)
    Q_PROPERTY(QString message READ message NOTIFY changed)
public:
    explicit VoltaUpdateQml(QObject *parent) : QObject(parent)
    {
        m_dir = QStandardPaths::writableLocation(QStandardPaths::CacheLocation) + QStringLiteral("/update");
        QDir().mkpath(m_dir);
        // Reste frueherer Updates entfernen (der laufende Build ist ja installiert)
        for (const QString &f : QDir(m_dir).entryList(QDir::Files)) QFile::remove(m_dir + QLatin1Char('/') + f);
        m_timer.setInterval(10 * 60 * 1000);
        connect(&m_timer, &QTimer::timeout, this, &VoltaUpdateQml::check);
        if (current() != QLatin1String("dev")) {
            QTimer::singleShot(90 * 1000, this, &VoltaUpdateQml::check);
            m_timer.start();
        }
    }

    QString state() const { return m_state; }
    bool ready() const { return m_state == QLatin1String("ready"); }
    QString remote() const { return m_remote.left(7); }
    QString current() const { return QString::fromLatin1(voltaPortStamp()); }
    int progress() const { return m_progress; }
    QString message() const { return m_message; }

    Q_INVOKABLE void check()
    {
        if (m_state == QLatin1String("checking") || m_state == QLatin1String("downloading")) return;
        if (ready()) { emit readyToInstall(); return; }
        setState(QStringLiteral("checking"));
        QNetworkRequest rq(QUrl(QString::fromLatin1(kBase) + QStringLiteral("volta-build.txt")));
        rq.setAttribute(QNetworkRequest::CacheLoadControlAttribute, QNetworkRequest::AlwaysNetwork);
        rq.setTransferTimeout(20000);
        QNetworkReply *r = m_net.get(rq);
        connect(r, &QNetworkReply::finished, this, [this, r]() {
            r->deleteLater();
            const QByteArray body = r->readAll().trimmed();
            if (r->error() != QNetworkReply::NoError || body.size() < 7) {
                setState(QStringLiteral("idle"), QStringLiteral("no connection to GitHub"));
                return;
            }
            m_remote = QString::fromLatin1(body.split('\n').value(0).trimmed());
            if (m_remote.startsWith(current())) { setState(QStringLiteral("current")); return; }
            qInfo().noquote() << "[update] new build" << m_remote.left(8) << "(installed" << current() << ")";
            download();
        });
    }

    Q_INVOKABLE void install()
    {
        if (!ready()) { check(); return; }
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        const QJniObject ctx = QNativeInterface::QAndroidApplication::context();
        const bool allowed = QJniObject::callStaticMethod<jboolean>(kUpdater, "canInstall",
            "(Landroid/content/Context;)Z", ctx.object());
        if (!allowed) {
            QJniObject::callStaticMethod<void>(kUpdater, "askPermission", "(Landroid/content/Context;)V", ctx.object());
            env.checkAndClearExceptions();
            setState(QStringLiteral("ready"), QStringLiteral("allow \"Install unknown apps\" for Volta, then tap Install again"));
            return;
        }
        const QString err = QJniObject::callStaticObjectMethod(kUpdater, "install",
            "(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;",
            ctx.object(), QJniObject::fromString(m_file).object<jstring>()).toString();
        if (env.checkAndClearExceptions() || !err.isEmpty()) {
            qWarning().noquote() << "[update] install failed:" << err;
            setState(QStringLiteral("ready"), QStringLiteral("install failed: ") + err);
            return;
        }
        qInfo().noquote() << "[update] installing" << m_remote.left(8);
#endif
    }

signals:
    void changed();
    void readyToInstall();

private:
    void setState(const QString &s, const QString &msg = QString())
    {
        m_state = s; m_message = msg;
        emit changed();
    }

    void download()
    {
        const QString apk = QStringLiteral("volta-arm64.apk");
        m_file = m_dir + QStringLiteral("/volta-") + m_remote.left(8) + QStringLiteral(".apk");
        m_out.setFileName(m_file + QStringLiteral(".part"));
        if (!m_out.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
            setState(QStringLiteral("error"), QStringLiteral("cannot write update file"));
            return;
        }
        m_progress = 0;
        setState(QStringLiteral("downloading"));
        QNetworkRequest rq(QUrl(QString::fromLatin1(kBase) + apk));
        rq.setAttribute(QNetworkRequest::CacheLoadControlAttribute, QNetworkRequest::AlwaysNetwork);
        rq.setTransferTimeout(60000);
        QNetworkReply *r = m_net.get(rq);
        // direkt in die Datei schreiben: die APK (~90 MB) soll nicht im Arbeitsspeicher landen
        connect(r, &QNetworkReply::readyRead, this, [this, r]() { m_out.write(r->readAll()); });
        connect(r, &QNetworkReply::downloadProgress, this, [this](qint64 got, qint64 total) {
            const int p = total > 0 ? int(got * 100 / total) : 0;
            if (p != m_progress) { m_progress = p; emit changed(); }
        });
        connect(r, &QNetworkReply::finished, this, [this, r]() {
            r->deleteLater();
            m_out.write(r->readAll());
            m_out.close();
            QFile part(m_out.fileName());
            QByteArray magic;
            if (part.open(QIODevice::ReadOnly)) { magic = part.read(2); part.close(); }
            if (r->error() != QNetworkReply::NoError || part.size() < 5 * 1024 * 1024 || magic != "PK") {
                qWarning().noquote() << "[update] download failed:" << r->errorString() << part.size();
                QFile::remove(part.fileName());
                setState(QStringLiteral("idle"), QStringLiteral("download failed, retrying later"));
                return;
            }
            QFile::remove(m_file);
            QFile::rename(part.fileName(), m_file);
            qInfo().noquote() << "[update] downloaded" << m_remote.left(8) << part.size() / (1024 * 1024) << "MB";
            setState(QStringLiteral("ready"));
            emit readyToInstall();
        });
    }

    QNetworkAccessManager m_net;
    QTimer m_timer;
    QFile m_out;
    QString m_dir, m_file, m_remote, m_message, m_state = QStringLiteral("idle");
    int m_progress = 0;
};
}

void voltaPortRegisterUpdate()
{
    qmlRegisterSingletonType<VoltaUpdateQml>("VoltaPort", 1, 0, "VoltaUpdate", [](QQmlEngine *, QJSEngine *) -> QObject * {
        static VoltaUpdateQml *u = nullptr;
        if (!u) u = new VoltaUpdateQml(QCoreApplication::instance());
        QQmlEngine::setObjectOwnership(u, QQmlEngine::CppOwnership);
        return u;
    });
}
#include "android_update.moc"
