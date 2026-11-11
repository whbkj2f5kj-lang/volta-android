// Android-Port von Volta OS: Fahrzeugdaten aus der Dashboard-Technik (core/, VoltaCar.kt) fuer QML.
// In QML:  import VoltaPort 1.0  ->  VoltaCar.speed, VoltaCar.rpm, VoltaCar.live ...
// GPL-3.0-or-later
#include <QCoreApplication>
#include <QDebug>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QObject>
#include <QQmlEngine>
#include <QSettings>
#include <QTimer>
#include <QVariantList>
#include <cmath>

#ifdef Q_OS_ANDROID
#include <QJniEnvironment>
#include <QJniObject>
#endif

namespace {

#ifdef Q_OS_ANDROID
const char kCar[] = "de/jarvis/w204/volta/VoltaCar";
#endif

class VoltaCarQml : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool live READ live NOTIFY changed)
    Q_PROPERTY(QString state READ state NOTIFY changed)
    Q_PROPERTY(QString message READ message NOTIFY changed)
    Q_PROPERTY(double rate READ rate NOTIFY changed)
    Q_PROPERTY(int speed READ speed NOTIFY changed)
    Q_PROPERTY(int rpm READ rpm NOTIFY changed)
    Q_PROPERTY(int coolant READ coolant NOTIFY changed)
    Q_PROPERTY(int oil READ oil NOTIFY changed)
    Q_PROPERTY(double boost READ boost NOTIFY changed)
    Q_PROPERTY(double voltage READ voltage NOTIFY changed)
    Q_PROPERTY(double consumption READ consumption NOTIFY changed)
    Q_PROPERTY(double fuelRate READ fuelRate NOTIFY changed)
    Q_PROPERTY(int fuel READ fuel NOTIFY changed)
    Q_PROPERTY(bool fuelEstimated READ fuelEstimated NOTIFY changed)
    Q_PROPERTY(bool engineRunning READ engineRunning NOTIFY changed)
    Q_PROPERTY(QString adapterAddress READ adapterAddress NOTIFY settingsChanged)
    Q_PROPERTY(QString adapterName READ adapterName NOTIFY settingsChanged)
    Q_PROPERTY(bool demo READ demo NOTIFY settingsChanged)
    Q_PROPERTY(QVariantList adapters READ adapters NOTIFY adaptersChanged)
    Q_PROPERTY(bool mercedesBusy READ mercedesBusy NOTIFY mercedesChanged)
    Q_PROPERTY(QString mercedesReport READ mercedesReport NOTIFY mercedesChanged)
    Q_PROPERTY(QString mercedesExport READ mercedesExport NOTIFY mercedesChanged)
public:
    explicit VoltaCarQml(QObject *parent) : QObject(parent)
    {
        QSettings s;
        m_address = s.value(QStringLiteral("car/address")).toString();
        m_name = s.value(QStringLiteral("car/name")).toString();
        m_demo = s.value(QStringLiteral("car/demo"), false).toBool();
        m_poll.setInterval(250);
        connect(&m_poll, &QTimer::timeout, this, &VoltaCarQml::poll);
        m_poll.start();
        QTimer::singleShot(500, this, [this]() { restart(); refreshAdapters(); });
#ifndef Q_OS_ANDROID
        // nur fuer die Kontroll-Bildschirmfotos auf Linux
        if (qEnvironmentVariableIsSet("VOLTA_PORT_FAKEAPPS")) {
            m_live = true; m_state = QStringLiteral("DEMO"); m_speed = 87; m_rpm = 1850; m_coolant = 88; m_oil = 92;
            m_boost = 0.8; m_voltage = 14.1; m_consumption = 5.9; m_fuel = 62; m_fuelRate = 2.4; m_engine = true;
        }
#endif
    }

    bool live() const { return m_live; }
    QString state() const { return m_state; }
    QString message() const { return m_message; }
    double rate() const { return m_rate; }
    int speed() const { return m_speed; }
    int rpm() const { return m_rpm; }
    int coolant() const { return m_coolant; }
    int oil() const { return m_oil; }
    double boost() const { return m_boost; }
    double voltage() const { return m_voltage; }
    double consumption() const { return m_consumption; }
    double fuelRate() const { return m_fuelRate; }
    int fuel() const { return m_fuel; }
    bool fuelEstimated() const { return m_fuelEstimated; }
    bool engineRunning() const { return m_engine; }
    QString adapterAddress() const { return m_address; }
    QString adapterName() const { return m_name; }
    bool demo() const { return m_demo; }
    QVariantList adapters() const { return m_adapters; }
    bool mercedesBusy() const { return m_mercedesBusy; }
    QString mercedesReport() const { return m_mercedesReport; }
    QString mercedesExport() const { return m_mercedesExport; }

    Q_INVOKABLE void runMercedesCheck()
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kCar, "runMercedesCheck", "()V");
        if (env.checkAndClearExceptions()) qWarning() << "[car] Mercedes test failed to start";
#endif
    }

    Q_INVOKABLE void exportMercedesCheck()
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kCar, "exportMercedesCheck", "(Landroid/content/Context;)V",
                                           QNativeInterface::QAndroidApplication::context().object());
        if (env.checkAndClearExceptions()) qWarning() << "[car] Mercedes report export failed";
#endif
    }

    Q_INVOKABLE void setAdapter(const QString &address, const QString &name)
    {
        m_address = address;
        m_name = name;
        m_demo = false;
        QSettings s;
        s.setValue(QStringLiteral("car/address"), address);
        s.setValue(QStringLiteral("car/name"), name);
        s.setValue(QStringLiteral("car/demo"), false);
        qInfo().noquote() << "[car] OBD adapter:" << (name.isEmpty() ? QStringLiteral("none") : name) << address;
        emit settingsChanged();
        restart();
    }

    Q_INVOKABLE void setDemo(bool on)
    {
        m_demo = on;
        QSettings().setValue(QStringLiteral("car/demo"), on);
        emit settingsChanged();
        restart();
    }

    Q_INVOKABLE void refreshAdapters()
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        const QString json = QJniObject::callStaticObjectMethod(kCar, "adapters", "(Landroid/content/Context;)Ljava/lang/String;",
                                                                QNativeInterface::QAndroidApplication::context().object()).toString();
        env.checkAndClearExceptions();
        m_adapters = QJsonDocument::fromJson(json.toUtf8()).array().toVariantList();
        emit adaptersChanged();
#endif
    }

signals:
    void changed();
    void settingsChanged();
    void adaptersChanged();
    void mercedesChanged();

private:
    void restart()
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>(kCar, "start", "(Landroid/content/Context;Ljava/lang/String;Z)V",
                                           QNativeInterface::QAndroidApplication::context().object(),
                                           QJniObject::fromString(m_address).object<jstring>(), jboolean(m_demo));
        if (env.checkAndClearExceptions()) qWarning() << "[car] could not start the vehicle connection";
        else qInfo().noquote() << "[car] started" << (m_demo ? QStringLiteral("(demo)") : m_address);
#endif
    }

    void poll()
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        const QString json = QJniObject::callStaticObjectMethod(kCar, "snapshot", "()Ljava/lang/String;").toString();
        if (env.checkAndClearExceptions()) return;
        const QJsonDocument snapshot = QJsonDocument::fromJson(json.toUtf8());
        if (!snapshot.isObject()) return;
        const QJsonObject o = snapshot.object();
        // Avoid notifying every QML binding when the 250 ms read contains
        // exactly the same vehicle data. Keep the original polling rate.
        const bool vehicleChanged = !m_hadVehicleSnapshot || o != m_vehicleSnapshot;
        m_hadVehicleSnapshot = true;
        m_vehicleSnapshot = o;
        auto i = [&o](const char *k) { return o.contains(QLatin1String(k)) ? int(std::lround(o.value(QLatin1String(k)).toDouble())) : -1; };
        auto d = [&o](const char *k) { return o.contains(QLatin1String(k)) ? o.value(QLatin1String(k)).toDouble() : -1.0; };
        const QString st = o.value(QLatin1String("state")).toString();
        if (st != m_state) qInfo().noquote() << "[car]" << st << o.value(QLatin1String("message")).toString();
        m_state = st;
        m_message = o.value(QLatin1String("message")).toString();
        m_live = o.value(QLatin1String("live")).toBool();
        m_rate = o.value(QLatin1String("rate")).toDouble();
        m_speed = i("speed"); m_rpm = i("rpm"); m_coolant = i("coolant"); m_oil = i("oil");
        m_boost = d("boost"); m_voltage = d("voltage"); m_consumption = d("consumption"); m_fuelRate = d("fuelRate");
        m_fuel = i("fuel"); m_fuelEstimated = o.value(QLatin1String("fuelEstimated")).toBool();
        m_engine = o.value(QLatin1String("engineRunning")).toBool();
        if (vehicleChanged) emit changed();
        // Diagnostic report rarely changes: poll at most once per second while
        // idle, or at 250 ms while a manually started diagnostic is running.
        if (!m_mercedesBusy && (++m_diagnosticPollCounter % 4) != 1) return;
        const QString diagnostic = QJniObject::callStaticObjectMethod(kCar, "mercedesCheckStatus", "()Ljava/lang/String;").toString();
        if (!env.checkAndClearExceptions()) {
            const QJsonObject report = QJsonDocument::fromJson(diagnostic.toUtf8()).object();
            const bool busy = report.value(QLatin1String("busy")).toBool();
            const QString text = report.value(QLatin1String("report")).toString();
            const QString exported = report.value(QLatin1String("export")).toString();
            if (busy != m_mercedesBusy || text != m_mercedesReport || exported != m_mercedesExport) {
                m_mercedesBusy = busy; m_mercedesReport = text; m_mercedesExport = exported;
                emit mercedesChanged();
            }
        }
#endif
    }

    QTimer m_poll;
    QString m_address, m_name, m_state = QStringLiteral("IDLE"), m_message;
    bool m_demo = false, m_live = false, m_engine = false;
    double m_rate = 0, m_boost = -1, m_voltage = -1, m_consumption = -1, m_fuelRate = -1;
    int m_speed = -1, m_rpm = -1, m_coolant = -1, m_oil = -1, m_fuel = -1;
    bool m_fuelEstimated = false;
    QVariantList m_adapters;
    bool m_mercedesBusy = false;
    int m_diagnosticPollCounter = 0;
    bool m_hadVehicleSnapshot = false;
    QJsonObject m_vehicleSnapshot;
    QString m_mercedesReport, m_mercedesExport;
};

} // namespace

void voltaPortRegisterCar()
{
    qmlRegisterSingletonType<VoltaCarQml>("VoltaPort", 1, 0, "VoltaCar", [](QQmlEngine *, QJSEngine *) -> QObject * {
        static VoltaCarQml *car = nullptr;
        if (!car) car = new VoltaCarQml(QCoreApplication::instance());
        QQmlEngine::setObjectOwnership(car, QQmlEngine::CppOwnership);
        return car;
    });
}

#include "android_car.moc"
