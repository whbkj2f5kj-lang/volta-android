// Android-Port von Volta OS: Ersatz fuer src/media/MprisManager.cpp (nur Android).
// Auf Linux liest Volta Musik-Player ueber MPRIS/D-Bus. Hier kommt der Titel aus Androids
// Mediensitzungen (Spotify-App, Radio-App, Bluetooth-Musik ...), als Quelle "android".
// Gleiche Klasse, gleiche Eigenschaften und Befehle wie im Original. GPL-3.0-or-later
#include "MprisManager.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QElapsedTimer>
#include <QFileInfo>
#include <QJniEnvironment>
#include <QJniObject>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSettings>
#include <QStandardPaths>
#include <QUrl>

void voltaPortOpenAndroidSettings(const QString &action);   // android_system.cpp

namespace {
const char kJava[] = "de/jarvis/volta/PortJava";
const QString kSrc = QStringLiteral("android");

struct Now {
    bool access = true;
    QString pkg, app, title, artist, album, art, icon;
    qint64 lengthMs = 0, posMs = 0;
    bool playing = false, canSeek = false, canNext = false, canPrev = false;
    QElapsedTimer clock;      // seit der letzten Abfrage (Position hochrechnen)
};
Now g_now;
bool g_saidNoAccess = false;
QString g_artDir;
} // namespace

MprisManager::MprisManager(QObject *parent) : QObject(parent), m_bus(QDBusConnection::sessionBus())
{
    g_artDir = QStandardPaths::writableLocation(QStandardPaths::CacheLocation) + QStringLiteral("/media_art");
    QDir().mkpath(g_artDir);
    m_poll.setInterval(1000);
    connect(&m_poll, &QTimer::timeout, this, &MprisManager::onPoll);
    m_poll.start();
    m_tick.setInterval(250);
    connect(&m_tick, &QTimer::timeout, this, &MprisManager::onTick);
    m_tick.start();
    QTimer::singleShot(1500, this, &MprisManager::onPoll);
}

MprisManager::~MprisManager() = default;

void MprisManager::onPoll()
{
    QJniEnvironment env;
    const QJniObject ctx = QNativeInterface::QAndroidApplication::context();
    const QString json = QJniObject::callStaticObjectMethod(kJava, "mediaState", "(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;",
                                                            ctx.object(), QJniObject::fromString(g_artDir).object<jstring>()).toString();
    env.checkAndClearExceptions();
    const QJsonObject o = QJsonDocument::fromJson(json.toUtf8()).object();

    const bool hadActive = !m_active.isEmpty();
    const QString oldTitle = g_now.title, oldPkg = g_now.pkg;
    const bool wasPlaying = g_now.playing;

    g_now.access = o.value(QLatin1String("access")).toBool(true);
    if (!g_now.access) {
        if (!g_saidNoAccess) {
            g_saidNoAccess = true;
            qInfo() << "[media] no notification access: the music widget cannot see other apps";
            QSettings s;
            if (!s.value(QStringLiteral("port/askedMediaAccess")).toBool()) {
                s.setValue(QStringLiteral("port/askedMediaAccess"), true);
                QTimer::singleShot(8000, this, []() {
                    voltaPortOpenAndroidSettings(QStringLiteral("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                });
            }
        }
    } else if (g_saidNoAccess) {
        g_saidNoAccess = false;
        qInfo() << "[media] notification access granted";
    }

    g_now.pkg = o.value(QLatin1String("pkg")).toString();
    if (g_now.pkg.isEmpty()) {
        g_now = Now{};
        g_now.access = o.value(QLatin1String("access")).toBool(true);
        m_active.clear();
    } else {
        m_active = kSrc;
        g_now.app = o.value(QLatin1String("app")).toString();
        g_now.title = o.value(QLatin1String("title")).toString();
        g_now.artist = o.value(QLatin1String("artist")).toString();
        g_now.album = o.value(QLatin1String("album")).toString();
        const QString art = o.value(QLatin1String("art")).toString();
        g_now.art = art.isEmpty() ? QString() : QUrl::fromLocalFile(art).toString();
        const QString icon = QStandardPaths::writableLocation(QStandardPaths::CacheLocation) + QStringLiteral("/android_icons/")
                             + g_now.pkg + QStringLiteral(".png");
        g_now.icon = QFileInfo::exists(icon) ? QUrl::fromLocalFile(icon).toString() : QString();
        g_now.lengthMs = qint64(o.value(QLatin1String("durationMs")).toDouble());
        g_now.posMs = qint64(o.value(QLatin1String("positionMs")).toDouble());
        g_now.playing = o.value(QLatin1String("playing")).toBool();
        g_now.canSeek = o.value(QLatin1String("canSeek")).toBool();
        g_now.canNext = o.value(QLatin1String("canNext")).toBool();
        g_now.canPrev = o.value(QLatin1String("canPrev")).toBool();
        g_now.clock.restart();
    }

    if (hadActive != !m_active.isEmpty() || oldPkg != g_now.pkg) {
        if (!g_now.pkg.isEmpty()) qInfo().noquote() << "[media] now from" << g_now.app << "(" + g_now.pkg + ")";
        emit playersChanged();
    }
    if (oldTitle != g_now.title) emit trackChanged();
    if (g_now.playing && !wasPlaying) emit sourceStartedPlaying(kSrc);
    emit stateChanged();
    emit positionChanged();
}

void MprisManager::onTick()
{
    if (g_now.playing) emit positionChanged();
}

// ── Eigenschaften ──
QString MprisManager::activeSource() const { return m_active.isEmpty() ? QString() : kSrc; }
QString MprisManager::activeIdentity() const { return g_now.app; }
QString MprisManager::title() const { return g_now.title; }
QString MprisManager::artist() const { return g_now.artist; }
QString MprisManager::album() const { return g_now.album; }
QString MprisManager::artUrl() const { return g_now.art; }
qint64 MprisManager::lengthMs() const { return g_now.lengthMs; }
bool MprisManager::playing() const { return g_now.playing; }
QString MprisManager::playbackStatus() const
{
    return m_active.isEmpty() ? QStringLiteral("Stopped") : g_now.playing ? QStringLiteral("Playing") : QStringLiteral("Paused");
}
bool MprisManager::shuffle() const { return false; }
QString MprisManager::loopStatus() const { return QStringLiteral("None"); }
bool MprisManager::hasShuffle() const { return false; }
bool MprisManager::hasLoop() const { return false; }
bool MprisManager::canSeek() const { return g_now.canSeek; }
bool MprisManager::canGoNext() const { return g_now.canNext || !m_active.isEmpty(); }
bool MprisManager::canGoPrevious() const { return g_now.canPrev || !m_active.isEmpty(); }
qint64 MprisManager::positionMs() const
{
    qint64 p = g_now.posMs;
    if (g_now.playing && g_now.clock.isValid()) p += g_now.clock.elapsed();
    if (g_now.lengthMs > 0) p = qMin(p, g_now.lengthMs);
    return p;
}
double MprisManager::progress() const { return g_now.lengthMs > 0 ? double(positionMs()) / double(g_now.lengthMs) : 0.0; }
QStringList MprisManager::sources() const { return m_active.isEmpty() ? QStringList() : QStringList{kSrc}; }
QVariantMap MprisManager::players() const
{
    if (m_active.isEmpty()) return {};
    QVariantMap p;
    p.insert(QStringLiteral("title"), g_now.title);
    p.insert(QStringLiteral("artist"), g_now.artist);
    p.insert(QStringLiteral("artUrl"), g_now.art);
    p.insert(QStringLiteral("playing"), g_now.playing);
    p.insert(QStringLiteral("positionMs"), positionMs());
    p.insert(QStringLiteral("lengthMs"), g_now.lengthMs);
    p.insert(QStringLiteral("shuffle"), false);
    p.insert(QStringLiteral("loopStatus"), QStringLiteral("None"));
    p.insert(QStringLiteral("canSeek"), g_now.canSeek);
    p.insert(QStringLiteral("identity"), g_now.app);
    p.insert(QStringLiteral("pkg"), g_now.pkg);
    p.insert(QStringLiteral("icon"), g_now.icon);
    return QVariantMap{{kSrc, p}};
}
void MprisManager::setExclusive(bool on)
{
    if (m_exclusive == on) return;
    m_exclusive = on;
    emit exclusiveChanged();
}

// ── Befehle ──
static void mediaCmd(const char *cmd, qint64 arg = 0)
{
    QJniEnvironment env;
    const QJniObject ctx = QNativeInterface::QAndroidApplication::context();
    QJniObject::callStaticMethod<jboolean>(kJava, "mediaCommand", "(Landroid/content/Context;Ljava/lang/String;J)Z", ctx.object(),
                                           QJniObject::fromString(QString::fromLatin1(cmd)).object<jstring>(), jlong(arg));
    env.checkAndClearExceptions();
}

void MprisManager::playPause() { mediaCmd("toggle"); QTimer::singleShot(300, this, &MprisManager::onPoll); }
void MprisManager::play() { mediaCmd("play"); QTimer::singleShot(300, this, &MprisManager::onPoll); }
void MprisManager::pause() { mediaCmd("pause"); QTimer::singleShot(300, this, &MprisManager::onPoll); }
void MprisManager::next() { mediaCmd("next"); QTimer::singleShot(500, this, &MprisManager::onPoll); }
void MprisManager::previous() { mediaCmd("prev"); QTimer::singleShot(500, this, &MprisManager::onPoll); }
void MprisManager::seekTo(qint64 ms) { mediaCmd("seek", qMax<qint64>(0, ms)); QTimer::singleShot(300, this, &MprisManager::onPoll); }
void MprisManager::seekToFraction(double f)
{
    if (g_now.lengthMs > 0) seekTo(qint64(qBound(0.0, f, 1.0) * double(g_now.lengthMs)));
}
void MprisManager::setShuffle(bool) {}
void MprisManager::setLoopStatus(const QString &) {}
void MprisManager::cycleLoopStatus() {}

bool MprisManager::hasSource(const QString &source) const { return source == kSrc && !m_active.isEmpty(); }
bool MprisManager::isPlaying(const QString &source) const { return hasSource(source) && g_now.playing; }
void MprisManager::playPauseSource(const QString &source) { if (source == kSrc) playPause(); }
void MprisManager::playSource(const QString &source) { if (source == kSrc) play(); }
void MprisManager::pauseSource(const QString &source) { if (source == kSrc) pause(); }
void MprisManager::nextSource(const QString &source) { if (source == kSrc) next(); }
void MprisManager::previousSource(const QString &source) { if (source == kSrc) previous(); }
void MprisManager::seekSourceToFraction(const QString &source, double f) { if (source == kSrc) seekToFraction(f); }
void MprisManager::setSourceShuffle(const QString &, bool) {}
void MprisManager::setSourceLoopStatus(const QString &, const QString &) {}
void MprisManager::pauseAll() { if (g_now.playing) pause(); }
void MprisManager::pauseAllExcept(const QString &source) { if (source != kSrc && g_now.playing) pause(); }
void MprisManager::activate(const QString &) {}
void MprisManager::setTrackHint(const QString &, const QString &, const QString &, const QString &) {}
void MprisManager::clearTrackHint(const QString &) {}
void MprisManager::playEffect(const QString &, const QStringList &, double) {}
void MprisManager::ensureBluetoothProxy() {}

// D-Bus-Teile des Originals: auf Android ohne Funktion
void MprisManager::onNameOwnerChanged(const QString &, const QString &, const QString &) {}
void MprisManager::onPropertiesChanged(const QDBusMessage &) {}
void MprisManager::onSeeked(const QDBusMessage &) {}
