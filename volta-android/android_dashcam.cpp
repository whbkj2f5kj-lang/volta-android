// ASD-derived USB UVC camera and H.264 recorder bridge for Volta OS.
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

namespace {
#ifdef Q_OS_ANDROID
constexpr char kDash[] = "de/jarvis/w204/volta/VoltaDashcam";
QJniObject context() { return QNativeInterface::QAndroidApplication::context(); }
#endif

class VoltaDashcamQml final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool enabled READ enabled NOTIFY changed)
    Q_PROPERTY(bool recording READ recording NOTIFY changed)
    Q_PROPERTY(bool recordingEnabled READ recordingEnabled NOTIFY changed)
    Q_PROPERTY(bool signsEnabled READ signsEnabled NOTIFY changed)
    Q_PROPERTY(QString signStatus READ signStatus NOTIFY changed)
    Q_PROPERTY(double signFps READ signFps NOTIFY changed)
    Q_PROPERTY(QString status READ status NOTIFY changed)
    Q_PROPERTY(int fps READ fps NOTIFY changed)
    Q_PROPERTY(int savedCount READ savedCount NOTIFY changed)
public:
    explicit VoltaDashcamQml(QObject *parent) : QObject(parent)
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kDash, "initialize", "(Landroid/content/Context;)V", context().object());
        if (env.checkAndClearExceptions()) qWarning() << "[dashcam] initialization failed";
#endif
        m_timer.setInterval(1000);
        connect(&m_timer, &QTimer::timeout, this, &VoltaDashcamQml::poll);
        m_timer.start();
        QTimer::singleShot(100, this, &VoltaDashcamQml::poll);
    }

    bool enabled() const { return m_enabled; }
    bool recording() const { return m_recording; }
    bool recordingEnabled() const { return m_recordingEnabled; }
    bool signsEnabled() const { return m_signsEnabled; }
    QString signStatus() const { return m_signStatus; }
    double signFps() const { return m_signFps; }
    QString status() const { return m_status; }
    int fps() const { return m_fps; }
    int savedCount() const { return m_saved; }

    Q_INVOKABLE void setEnabled(bool on)
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kDash, "configure", "(Landroid/content/Context;Z)V",
                                            context().object(), jboolean(on));
        if (env.checkAndClearExceptions()) qWarning() << "[dashcam] configuration failed";
#endif
        m_enabled = on;
        emit changed();
    }

    Q_INVOKABLE void setRecordingEnabled(bool on) { setFlag("setRecording", on); }
    Q_INVOKABLE void setSignsEnabled(bool on) { setFlag("setSigns", on); }

    Q_INVOKABLE void saveEvent()
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kDash, "protect", "()V");
        env.checkAndClearExceptions();
#endif
    }

    Q_INVOKABLE void preview(bool visible, double x, double y, double w, double h, double sceneW, double sceneH)
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kDash, "preview", "(Landroid/content/Context;ZDDDDDD)V",
            context().object(), jboolean(visible), jdouble(x), jdouble(y), jdouble(w),
            jdouble(h), jdouble(sceneW), jdouble(sceneH));
        env.checkAndClearExceptions();
#else
        Q_UNUSED(visible); Q_UNUSED(x); Q_UNUSED(y); Q_UNUSED(w);
        Q_UNUSED(h); Q_UNUSED(sceneW); Q_UNUSED(sceneH);
#endif
    }
signals:
    void changed();
private:
    void setFlag(const char *name, bool on) {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kDash, name, "(Landroid/content/Context;Z)V", context().object(), jboolean(on));
        env.checkAndClearExceptions();
#else
        Q_UNUSED(name); Q_UNUSED(on);
#endif
    }
    void poll()
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        const QString raw = QJniObject::callStaticObjectMethod(kDash, "snapshot", "()Ljava/lang/String;").toString();
        if (env.checkAndClearExceptions()) return;
        const QJsonObject obj = QJsonDocument::fromJson(raw.toUtf8()).object();
        if (obj.isEmpty()) return;
        const bool e = obj.value("enabled").toBool();
        const bool r = obj.value("recording").toBool();
        const bool recEnabled = obj.value("recordingEnabled").toBool();
        const bool signsEnabled = obj.value("signsEnabled").toBool();
        const QString signStatus = obj.value("signStatus").toString();
        const double signFps = obj.value("signFps").toDouble();
        const QString st = obj.value("status").toString();
        const int fps = obj.value("fps").toInt();
        const int saved = obj.value("savedCount").toInt();
        if (e != m_enabled || r != m_recording || st != m_status || fps != m_fps || saved != m_saved ||
            recEnabled != m_recordingEnabled || signsEnabled != m_signsEnabled || signStatus != m_signStatus || signFps != m_signFps) {
            m_enabled = e; m_recording = r; m_status = st; m_fps = fps; m_saved = saved;
            emit changed();
        }
#endif
    }

    QTimer m_timer;
    bool m_enabled = false, m_recording = false;
    bool m_recordingEnabled = true, m_signsEnabled = true;
    QString m_signStatus = QStringLiteral("Warte auf Kamera");
    double m_signFps = 0;
    int m_fps = 0, m_saved = 0;
    QString m_status = QStringLiteral("Aus");
};
}

void voltaPortRegisterDashcam()
{
    qmlRegisterSingletonType<VoltaDashcamQml>("VoltaPort", 1, 0, "VoltaDashcam",
        [](QQmlEngine *, QJSEngine *) -> QObject * {
            static auto *bridge = new VoltaDashcamQml(QCoreApplication::instance());
            QQmlEngine::setObjectOwnership(bridge, QQmlEngine::CppOwnership);
            return bridge;
        });
}
#include "android_dashcam.moc"
