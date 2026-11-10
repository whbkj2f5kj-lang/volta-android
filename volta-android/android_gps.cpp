// Android-Port von Volta OS: Standort ueber Android statt ueber einen seriellen GPS-Empfaenger.
// Ersetzt auf Android src/nav/GpsService.cpp (gleiche Klasse, gleiche Signale und Daten),
// damit Karte, Tempo-Anzeige und Navigation unveraendert weiterarbeiten. GPL-3.0-or-later
#include "GpsService.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QGeoCoordinate>
#include <QGeoPositionInfo>
#include <QGeoPositionInfoSource>
#include <QPermissions>
#include <QTimer>
#include <cmath>

static QGeoPositionInfoSource *g_src = nullptr;
static qint64 g_lastFixMs = 0;

GpsService::GpsService(QObject *parent) : QObject(parent)
{
    m_tick = new QTimer(this);
    m_tick->setInterval(100);                       // 10 Meldungen pro Sekunde, wie im Original
    connect(m_tick, &QTimer::timeout, this, &GpsService::broadcast);
    m_tick->start();
    m_source = QStringLiteral("Android");
    QTimer::singleShot(0, this, &GpsService::tryOpen);
}

GpsService::~GpsService() = default;

// fragt (einmal) nach der Standort-Erlaubnis und startet dann die Android-Ortung
void GpsService::tryOpen()
{
    QLocationPermission perm;
    perm.setAccuracy(QLocationPermission::Precise);
    perm.setAvailability(QLocationPermission::WhenInUse);
    const Qt::PermissionStatus st = qApp->checkPermission(perm);
    if (st == Qt::PermissionStatus::Undetermined) {
        qApp->requestPermission(perm, this, [this](const QPermission &p) {
            if (p.status() == Qt::PermissionStatus::Granted) tryOpen();
            else qWarning() << "[GPS] location permission denied: the map stays without position";
        });
        return;
    }
    if (st != Qt::PermissionStatus::Granted) {
        qWarning() << "[GPS] no location permission (allow it in the Android app settings)";
        return;
    }
    if (g_src) return;
    g_src = QGeoPositionInfoSource::createDefaultSource(this);
    if (!g_src) {
        qWarning() << "[GPS] Android offers no position source";
        return;
    }
    g_src->setPreferredPositioningMethods(QGeoPositionInfoSource::AllPositioningMethods);
    g_src->setUpdateInterval(500);
    connect(g_src, &QGeoPositionInfoSource::positionUpdated, this, [this](const QGeoPositionInfo &info) {
        const QGeoCoordinate c = info.coordinate();
        if (!c.isValid()) return;
        m_lat = c.latitude();
        m_lon = c.longitude();
        if (c.type() == QGeoCoordinate::Coordinate3D) m_altitude = c.altitude();
        if (info.hasAttribute(QGeoPositionInfo::GroundSpeed)) {
            const double v = info.attribute(QGeoPositionInfo::GroundSpeed);
            if (std::isfinite(v) && v >= 0) m_speed = v;
        }
        if (info.hasAttribute(QGeoPositionInfo::Direction)) {
            const double b = info.attribute(QGeoPositionInfo::Direction);
            if (std::isfinite(b)) m_bearing = b;
        }
        if (info.hasAttribute(QGeoPositionInfo::HorizontalAccuracy))
            m_accuracy = info.attribute(QGeoPositionInfo::HorizontalAccuracy);
        g_lastFixMs = QDateTime::currentMSecsSinceEpoch();
        const bool was = m_fix;
        m_fix = true;
        if (!was) {
            m_fd = 1;                               // nur als Merker: connected() liest m_fd >= 0
            m_port = QStringLiteral("android");
            m_source = QStringLiteral("Android (") + g_src->sourceName() + QLatin1Char(')');
            emit connectedChanged();
        }
    });
    connect(g_src, &QGeoPositionInfoSource::errorOccurred, this, [](QGeoPositionInfoSource::Error e) {
        qWarning() << "[GPS] Android position source error" << int(e);
    });
    g_src->startUpdates();
    qInfo().noquote() << "[GPS] Android position source started:" << g_src->sourceName();
}

void GpsService::broadcast()
{
    if (!m_fix) return;
    // seit 6 s nichts Neues (Tunnel, Ortung aus): nicht weiter die alte Position als frisch melden
    if (QDateTime::currentMSecsSinceEpoch() - g_lastFixMs > 6000) {
        m_fix = false;
        return;
    }
    static bool first = true;
    if (first) {
        first = false;
        qInfo().noquote() << QStringLiteral("[GPS] first fix: %1, %2").arg(m_lat, 0, 'f', 5).arg(m_lon, 0, 'f', 5);
    }
    QVariantMap d;
    d.insert(QStringLiteral("lat"), m_lat);
    d.insert(QStringLiteral("lon"), m_lon);
    d.insert(QStringLiteral("speed"), m_speed);
    d.insert(QStringLiteral("bearing"), m_bearing);
    d.insert(QStringLiteral("accuracy"), m_accuracy);
    d.insert(QStringLiteral("altitude"), m_altitude);
    d.insert(QStringLiteral("sats"), m_sats);
    d.insert(QStringLiteral("fix"), true);
    emit updated(d);
}

// Bluetooth-GPS-Empfaenger aus den Einstellungen: auf Android nicht noetig (Android liefert den Standort)
void GpsService::refreshBtDevices() {}
void GpsService::selectBluetooth(const QString &) {}
void GpsService::selectAuto() {}
