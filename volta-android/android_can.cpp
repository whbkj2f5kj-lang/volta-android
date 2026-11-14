// Fahrzeugdaten vom Radio (CAN-Box ueber com.qf.vehicle, nur lesen) fuer Voltas Oberflaeche.
// values: letzte Werte; seen: Felder, die das Auto schon einmal geliefert hat (nur diese zeigt Volta an).
#include <QCoreApplication>
#include <QDebug>
#include <QJsonDocument>
#include <QJsonObject>
#include <QObject>
#include <QQmlEngine>
#include <QElapsedTimer>
#include <QTimer>
#include <QVariantMap>
#ifdef Q_OS_ANDROID
#include <QJniEnvironment>
#include <QJniObject>
#endif

namespace {
class VoltaCanQml : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool connected READ connected NOTIFY changed)
    Q_PROPERTY(QVariantMap values READ values NOTIFY changed)
    Q_PROPERTY(QVariantMap seen READ seen NOTIFY changed)
    Q_PROPERTY(bool doorSwap READ doorSwap NOTIFY changed)
    // fertig aufbereitet fuer die Anzeigen
    Q_PROPERTY(bool reverse READ reverse NOTIFY derivedChanged)
    Q_PROPERTY(bool hasSpeed READ hasSpeed NOTIFY speedChanged)
    Q_PROPERTY(int speed READ speed NOTIFY speedChanged)
    Q_PROPERTY(bool hasDoors READ hasDoors NOTIFY changed)
    Q_PROPERTY(QString tempLeft READ tempLeft NOTIFY changed)
    Q_PROPERTY(QString tempRight READ tempRight NOTIFY changed)
    Q_PROPERTY(double odometerKm READ odometerKm NOTIFY changed)
    Q_PROPERTY(bool lightsOn READ lightsOn NOTIFY derivedChanged)
    Q_PROPERTY(QString gear READ gear NOTIFY derivedChanged)
    Q_PROPERTY(bool beltWarning READ beltWarning NOTIFY derivedChanged)
public:
    explicit VoltaCanQml(QObject *parent) : QObject(parent)
    {
        m_timer.setInterval(250);
        connect(&m_timer, &QTimer::timeout, this, &VoltaCanQml::poll);
        m_timer.start();
#ifndef Q_OS_ANDROID
        if (qEnvironmentVariableIsSet("VOLTA_PORT_FAKEAPPS")) {
            m_connected = true;
            m_values = QVariantMap{{"speed", 87}, {"rpm", 1850}, {"lowBeam", 1}, {"doorFL", 0}, {"tempLeftRaw", 42}, {"tempRightRaw", 44}};
            m_seen = QVariantMap{{"speed", true}, {"rpm", true}, {"lowBeam", true}, {"doorFL", true}, {"tempLeftRaw", true}};
        }
#endif
    }

    bool connected() const { return m_connected; }
    QVariantMap values() const { return m_values; }
    QVariantMap seen() const { return m_seen; }
    bool doorSwap() const { return m_doorSwap; }
    double v(const char *k) const { return m_values.value(QLatin1String(k)).toDouble(); }
    bool has(const char *k) const { return m_seen.value(QLatin1String(k)).toBool(); }
    bool reverse() const { return v("reverseGear") > 0 || v("reverseProp") > 0 || v("reverseCam") > 0; }
    bool hasSpeed() const { return m_connected && has("speed"); }
    int speed() const { return int(v("speed")); }
    bool hasDoors() const { return has("doorFL") || has("doorFR") || has("doorRL") || has("doorRR") || has("trunk") || has("hood"); }
    static QString temp(double raw)
    {
        if (raw <= 0) return QString();
        if (raw >= 0xFE) return raw >= 0xFF ? QStringLiteral("HI") : QStringLiteral("LO");
        return QString::number(raw / 2.0, 'f', 1) + QStringLiteral("°");
    }
    QString tempLeft() const { return temp(v("tempLeftRaw")); }
    QString tempRight() const { return temp(v("tempRightRaw")); }
    // Rohwert 1.928.350 entsprach 192.835 km -> Einheit 0,1 km (am Tacho gegenpruefen)
    double odometerKm() const { return v("odometerRaw") / 10.0; }
    // Fahrlicht: Abblendlicht vom CAN, sonst das Lichtsignal des Radios (sys.qf.vehicle.headlight_state)
    bool lightsOn() const { return has("lowBeam") ? v("lowBeam") > 0 : v("headlightProp") > 0; }

    // Die CAN-Box des W204 meldet keinen Wahlhebel. Daraus abgeleitet:
    // Motor aus -> P; nach dem Start P bis zur ersten Fahrt; danach D (auch an der Ampel); R nur mit Rueckwaertssignal.
    QString gear() const { return m_gear; }
    // Gurt-Bit = 1 heisst angelegt (war waehrend der ganzen Fahrt 1). Warnung nur, wenn das Auto den Gurt schon gemeldet hat.
    bool beltWarning() const { return m_connected && has("beltDriver") && v("beltDriver") <= 0 && (v("speed") > 5); }

    Q_INVOKABLE bool has(const QString &k) const { return m_seen.value(k).toBool(); }
    Q_INVOKABLE double value(const QString &k) const { return m_values.value(k).toDouble(); }
    Q_INVOKABLE void setDoorSwap(bool on)
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>("de/jarvis/volta/CanListener", "setDoorSwap", "(Landroid/content/Context;Z)V",
                                           QNativeInterface::QAndroidApplication::context().object(), jboolean(on));
        env.checkAndClearExceptions();
#endif
        m_doorSwap = on;
        emit changed();
    }

signals:
    // changed: alles Langsame (Tueren, Licht, Klima ...) sofort; reine Tempo-/Drehzahl-Aenderungen hoechstens 1x/s.
    // Sonst rechnen waehrend der Fahrt ~80 Anzeigen 4x pro Sekunde neu (jede wandelt die ganze Werteliste um).
    void changed();
    void speedChanged();     // speed, hasSpeed: sofort
    void derivedChanged();   // gear, reverse, lightsOn, beltWarning: nur bei echter Aenderung

private:
    QString derivedKey() const
    {
        return m_gear + QLatin1Char(reverse() ? 'r' : '-') + QLatin1Char(lightsOn() ? 'l' : '-') + QLatin1Char(beltWarning() ? 'b' : '-');
    }

    void poll()
    {
        // zurueckgehaltene Tempo-/Drehzahl-Aenderung nachreichen, auch wenn danach nichts mehr kommt
        if (m_pendingEmit && m_lastEmit.elapsed() >= 1000) { m_lastEmit.restart(); m_pendingEmit = false; emit changed(); }
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        const QString raw = QJniObject::callStaticObjectMethod("de/jarvis/volta/CanListener", "snapshot",
                                                               "()Ljava/lang/String;").toString();
        if (env.checkAndClearExceptions() || raw == m_last) return;
        m_last = raw;
        const QJsonObject o = QJsonDocument::fromJson(raw.toUtf8()).object();
        if (o.isEmpty()) return;
        const bool was = m_connected, wasSwap = m_doorSwap, wasHasSpeed = hasSpeed();
        const int wasSpeed = speed();
        const QString wasDerived = derivedKey();
        const QVariantMap oldSeen = m_seen;
        QVariantMap slowOld = m_values;
        m_connected = o.value(QLatin1String("bound")).toBool();
        m_doorSwap = o.value(QLatin1String("doorSwap")).toBool();
        m_values = o.value(QLatin1String("values")).toObject().toVariantMap();
        m_seen = o.value(QLatin1String("seen")).toObject().toVariantMap();
        updateGear();
        if (was != m_connected) qInfo() << "[can] radio vehicle service" << (m_connected ? "connected" : "not connected");
        if (speed() != wasSpeed || hasSpeed() != wasHasSpeed) emit speedChanged();
        if (derivedKey() != wasDerived) emit derivedChanged();
        QVariantMap slowNew = m_values;
        for (const char *fast : {"speed", "rpm", "rpmFlags", "consumptionNow", "avgSpeed1", "driveTime1", "trip1", "trip2", "trip3", "fuel", "rest", "range", "odometerRaw"}) {
            slowOld.remove(QLatin1String(fast)); slowNew.remove(QLatin1String(fast));
        }
        const bool slow = slowOld != slowNew || oldSeen != m_seen || was != m_connected || wasSwap != m_doorSwap;
        if (slow || !m_lastEmit.isValid() || m_lastEmit.elapsed() >= 1000) { m_lastEmit.restart(); m_pendingEmit = false; emit changed(); }
        else m_pendingEmit = true;
#endif
    }

    void updateGear()
    {
        QString g;
        if (m_connected && has("rpm")) {
            const bool engine = v("rpm") > 300, moving = v("speed") > 3;
            if (reverse()) g = QStringLiteral("R");
            else if (!engine) { m_moved = false; g = QStringLiteral("P"); }
            else { if (moving) m_moved = true; g = m_moved ? QStringLiteral("D") : QStringLiteral("P"); }
        }
        m_gear = g;
    }

    QTimer m_timer;
    QString m_last, m_gear;
    bool m_moved = false, m_pendingEmit = false;
    QElapsedTimer m_lastEmit;
    bool m_connected = false, m_doorSwap = false;
    QVariantMap m_values, m_seen;
};
}

void voltaPortRegisterCan()
{
    qmlRegisterSingletonType<VoltaCanQml>("VoltaPort", 1, 0, "VoltaCan", [](QQmlEngine *, QJSEngine *) -> QObject * {
        static VoltaCanQml *c = nullptr;
        if (!c) c = new VoltaCanQml(QCoreApplication::instance());
        QQmlEngine::setObjectOwnership(c, QQmlEngine::CppOwnership);
        return c;
    });
}
#include "android_can.moc"
