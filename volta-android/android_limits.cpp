// Stufe 2: Datenbruecke zur OSM-Technik. Darstellung bleibt in Voltas vorhandenen QML-Komponenten.
#include <QCoreApplication>
#include <QDebug>
#include <QJsonDocument>
#include <QJsonObject>
#include <QObject>
#include <QQmlEngine>
#include <QSettings>
#include <QTimer>
#include <QVariantMap>
#include <limits>
#ifdef Q_OS_ANDROID
#include <QJniEnvironment>
#include <QJniObject>
#endif

namespace {
#ifdef Q_OS_ANDROID
const char kLimits[] = "de/jarvis/w204/volta/VoltaLimits";
#endif
class VoltaLimitsQml : public QObject {
    Q_OBJECT
    Q_PROPERTY(int limit READ limit NOTIFY changed)
    Q_PROPERTY(bool overspeed READ overspeed NOTIFY changed)
    Q_PROPERTY(QString source READ source NOTIFY changed)
    Q_PROPERTY(QString road READ road NOTIFY changed)
    Q_PROPERTY(QString status READ status NOTIFY changed)
    Q_PROPERTY(bool enabled READ enabled NOTIFY settingsChanged)
    Q_PROPERTY(bool warningsEnabled READ warningsEnabled NOTIFY settingsChanged)
    Q_PROPERTY(int tolerance READ tolerance NOTIFY settingsChanged)
public:
    explicit VoltaLimitsQml(QObject *parent) : QObject(parent) {
        QSettings s;
        m_enabled = s.value("limits/osm", true).toBool();
        m_warnings = s.value("limits/warnings", true).toBool();
        m_tolerance = qBound(0, s.value("limits/tolerance", 5).toInt(), 20);
        connect(&m_poll, &QTimer::timeout, this, &VoltaLimitsQml::poll);
        m_poll.start(250);
        QTimer::singleShot(0, this, &VoltaLimitsQml::configure);
    }
    int limit() const { return m_data.value("limit").toInt(-1); }
    bool overspeed() const { return m_data.value("overspeed").toBool(); }
    QString source() const { return m_data.value("source").toString(); }
    QString road() const { return m_data.value("road").toString(); }
    QString status() const {
        const QString s = m_data.value("status").toString();
        if (s == "camera") return tr("Camera-confirmed speed limit");
        if (s == "camera_conditional") return tr("Camera sign has additional conditions; no general limit applied");
        if (s == "camera_end") return tr("Camera detected an end sign; new limit unknown");
        if (s == "camera_town") return tr("Camera detected a town sign; numeric limit not inferred");
        if (s == "ready") return tr("Speed limit available");
        if (s == "loading") return tr("Loading OSM road data");
        if (s == "offline") return tr("OSM unavailable; using cached roads where available");
        if (s == "conditional") return tr("Conditional limit cannot be determined reliably");
        if (s == "estimated") return tr("Estimated road limit; no warning");
        if (s == "variable") return tr("Variable speed limit; no warning");
        if (s == "unlimited") return tr("No numeric speed limit recorded");
        if (s == "disabled") return tr("OSM speed limits disabled");
        if (s == "unknown") return tr("No reliable speed limit for this road");
        if (s == "error") return tr("Speed limit service unavailable");
        return tr("Waiting for an accurate GPS position from Volta");
    }
    bool enabled() const { return m_enabled; }
    bool warningsEnabled() const { return m_warnings; }
    int tolerance() const { return m_tolerance; }

    Q_INVOKABLE void setEnabled(bool on) { m_enabled = on; save(); }
    Q_INVOKABLE void setWarningsEnabled(bool on) { m_warnings = on; save(); }
    Q_INVOKABLE void setTolerance(int value) { m_tolerance = qBound(0, value, 20); save(); }
    Q_INVOKABLE void setNavigationLimit(int value) {
        if (m_navigation == value) return;
        m_navigation = value;
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kLimits, "navigation", "(I)V", jint(value));
        env.checkAndClearExceptions();
#endif
    }
    Q_INVOKABLE void updatePosition(const QVariantMap &d) {
#ifdef Q_OS_ANDROID
        // Android-GPS kennzeichnet echte Messungen. Die 10-Hz-Wiederholung zaehlt nicht erneut.
        if (!d.contains("fixId") || !d.contains("ageMs")) return;
        const qint64 id = d.value("fixId").toLongLong();
        if (id == m_lastFix) return;
        m_lastFix = id;
        const float nan = std::numeric_limits<float>::quiet_NaN();
        const float accuracy = d.value("accuracyValid").toBool() ? d.value("accuracy").toFloat() : nan;
        const float bearing = d.value("bearingValid").toBool() ? d.value("bearing").toFloat() : nan;
        const float speed = d.value("speedValid").toBool() ? d.value("speed").toFloat() : nan;
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kLimits, "updatePosition", "(Landroid/content/Context;DDFFFJ)V",
            QNativeInterface::QAndroidApplication::context().object(),
            jdouble(d.value("lat").toDouble()), jdouble(d.value("lon").toDouble()),
            jfloat(accuracy), jfloat(bearing), jfloat(speed), jlong(d.value("ageMs").toLongLong()));
        if (env.checkAndClearExceptions()) qWarning() << "[limits] GPS delivery failed";
#else
        Q_UNUSED(d);
#endif
    }
signals:
    void changed();
    void settingsChanged();
    void warningTriggered(int value);
private:
    void save() {
        QSettings s;
        s.setValue("limits/osm", m_enabled); s.setValue("limits/warnings", m_warnings); s.setValue("limits/tolerance", m_tolerance);
        configure(); emit settingsChanged();
    }
    void configure() {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kLimits, "configure", "(Landroid/content/Context;ZZI)V",
            QNativeInterface::QAndroidApplication::context().object(), jboolean(m_enabled), jboolean(m_warnings), jint(m_tolerance));
        if (env.checkAndClearExceptions()) { qWarning() << "[limits] service initialization failed"; return; }
        QJniObject::callStaticMethod<void>(kLimits, "navigation", "(I)V", jint(m_navigation));
        env.checkAndClearExceptions();
#endif
    }
    void poll() {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        const QJniObject json = QJniObject::callStaticObjectMethod(kLimits, "snapshot", "()Ljava/lang/String;");
        if (env.checkAndClearExceptions()) {
            m_data = QJsonObject{{"status", "error"}}; emit changed(); return;
        }
        QJsonParseError error;
        const auto doc = QJsonDocument::fromJson(json.toString().toUtf8(), &error);
        if (error.error != QJsonParseError::NoError || !doc.isObject()) return;
        const QJsonObject next = doc.object();
        const qint64 sequence = next.value("sequence").toVariant().toLongLong();
        if (next != m_data) { m_data = next; emit changed(); }
        if (sequence > m_sequence) {
            m_sequence = sequence;
            if (overspeed() && limit() > 0) { qInfo() << "[limits] visual speed warning" << limit(); emit warningTriggered(limit()); }
        }
#endif
    }
    QTimer m_poll;
    QJsonObject m_data;
    qint64 m_lastFix = -1, m_sequence = 0;
    bool m_enabled = true, m_warnings = true;
    int m_tolerance = 5, m_navigation = -1;
};
}
void voltaPortRegisterLimits() {
    qmlRegisterSingletonType<VoltaLimitsQml>("VoltaPort", 1, 0, "VoltaLimits", [](QQmlEngine *, QJSEngine *) -> QObject * {
        static VoltaLimitsQml *limits = nullptr;
        if (!limits) limits = new VoltaLimitsQml(QCoreApplication::instance());
        QQmlEngine::setObjectOwnership(limits, QQmlEngine::CppOwnership);
        return limits;
    });
}
#include "android_limits.moc"
