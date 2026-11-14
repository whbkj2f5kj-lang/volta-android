// Diagnose-Logs automatisch in ein privates GitHub-Repo hochladen (LogUploader.java).
#include <QCoreApplication>
#include <QDebug>
#include <QJsonDocument>
#include <QJsonObject>
#include <QObject>
#include <QQmlEngine>
#include <QTimer>
#ifdef Q_OS_ANDROID
#include <QJniEnvironment>
#include <QJniObject>
#endif

#ifdef Q_OS_ANDROID
QString voltaImportKeys(const QByteArray &text);   // volta_port.cpp
#endif

namespace {
#ifdef Q_OS_ANDROID
constexpr char kUp[] = "de/jarvis/volta/LogUploader";
constexpr char kPick[] = "de/jarvis/volta/KeysPicker";
#endif

class VoltaUploadQml final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool enabled READ enabled NOTIFY changed)
    Q_PROPERTY(bool hasToken READ hasToken NOTIFY changed)
    Q_PROPERTY(QString repo READ repo NOTIFY changed)
    Q_PROPERTY(QString status READ status NOTIFY changed)
    Q_PROPERTY(QString keysStatus READ keysStatus NOTIFY changed)
public:
    explicit VoltaUploadQml(QObject *parent) : QObject(parent)
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kUp, "start", "(Landroid/content/Context;)V",
                                           QNativeInterface::QAndroidApplication::context().object());
        if (env.checkAndClearExceptions()) qWarning() << "[upload] could not start";
#endif
        m_timer.setInterval(2000);
        connect(&m_timer, &QTimer::timeout, this, &VoltaUploadQml::poll);
        m_timer.start();
        QTimer::singleShot(100, this, &VoltaUploadQml::poll);
    }

    bool enabled() const { return m_enabled; }
    bool hasToken() const { return m_hasToken; }
    QString repo() const { return m_repo; }
    QString status() const { return m_status; }
    QString keysStatus() const { return m_keysStatus; }

    // keys.env ueber die Android-Dateiauswahl holen; Ergebnis kommt ueber poll()
    Q_INVOKABLE void pickKeys()
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kPick, "open", "(Landroid/content/Context;)V",
                                           QNativeInterface::QAndroidApplication::context().object());
        env.checkAndClearExceptions();
        m_keysResult.clear();
        m_timer.setInterval(500);
#endif
        poll();
    }

    Q_INVOKABLE void setEnabled(bool on)
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kUp, "setEnabled", "(Z)V", jboolean(on));
        env.checkAndClearExceptions();
#endif
        poll();
    }
    Q_INVOKABLE void tokenFromClipboard()
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticObjectMethod(kUp, "tokenFromClipboard", "()Ljava/lang/String;");
        env.checkAndClearExceptions();
#endif
        poll();
    }
    Q_INVOKABLE void clearToken() { call("clearToken"); }
    Q_INVOKABLE void uploadNow() { call("uploadNow"); }
    Q_INVOKABLE void setRepo(const QString &r)
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kUp, "setRepo", "(Ljava/lang/String;)V", QJniObject::fromString(r).object<jstring>());
        env.checkAndClearExceptions();
#else
        Q_UNUSED(r);
#endif
        poll();
    }

signals:
    void changed();

private:
    void call(const char *name)
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kUp, name, "()V");
        env.checkAndClearExceptions();
#else
        Q_UNUSED(name);
#endif
        poll();
    }

    void poll()
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        {
            const QString text = QJniObject::callStaticObjectMethod(kPick, "take", "()Ljava/lang/String;").toString();
            QString ks = QJniObject::callStaticObjectMethod(kPick, "status", "()Ljava/lang/String;").toString();
            env.checkAndClearExceptions();
            if (!text.isEmpty()) {
                const QString r = voltaImportKeys(text.toUtf8());
                m_keysResult = r.isEmpty() ? QStringLiteral("Keine Schlüssel in der Datei gefunden")
                                           : QStringLiteral("Übernommen: ") + r
                                             + (r.contains(QLatin1String("_KEY")) || r.contains(QLatin1String("TOKEN"))
                                                ? QStringLiteral(" – Karten/Wetter nach Neustart von Volta") : QString());
                m_timer.setInterval(2000);
            }
            if (ks == QLatin1String("Datei gelesen") && !m_keysResult.isEmpty()) ks = m_keysResult;
            else if (ks != QLatin1String("Datei auswählen …")) m_timer.setInterval(2000);
            if (ks != m_keysStatus) { m_keysStatus = ks; m_last.clear(); }
        }
        const QString raw = QJniObject::callStaticObjectMethod(kUp, "snapshot", "()Ljava/lang/String;").toString();
        if (env.checkAndClearExceptions() || raw == m_last) return;
        m_last = raw;
        const QJsonObject o = QJsonDocument::fromJson(raw.toUtf8()).object();
        const QString st = o.value(QLatin1String("status")).toString();
        if (st != m_status) qInfo().noquote() << "[upload]" << st;
        m_enabled = o.value(QLatin1String("enabled")).toBool();
        m_hasToken = o.value(QLatin1String("hasToken")).toBool();
        m_repo = o.value(QLatin1String("repo")).toString();
        m_status = st;
        emit changed();
#endif
    }

    QTimer m_timer;
    QString m_last, m_repo, m_status = QStringLiteral("Aus"), m_keysStatus, m_keysResult;
    bool m_enabled = false, m_hasToken = false;
};
} // namespace

void voltaPortRegisterUpload()
{
    qmlRegisterSingletonType<VoltaUploadQml>("VoltaPort", 1, 0, "VoltaUpload", [](QQmlEngine *, QJSEngine *) -> QObject * {
        static VoltaUploadQml *u = nullptr;
        if (!u) u = new VoltaUploadQml(QCoreApplication::instance());
        QQmlEngine::setObjectOwnership(u, QQmlEngine::CppOwnership);
        return u;
    });
    // ohne Oeffnen der Einstellungen starten, damit auch ohne Bedienung hochgeladen wird
    QTimer::singleShot(10000, qApp, []() {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kUp, "start", "(Landroid/content/Context;)V",
                                           QNativeInterface::QAndroidApplication::context().object());
        if (env.checkAndClearExceptions()) qWarning() << "[upload] could not start";
#endif
    });
}
#include "android_upload.moc"
