// Android-Port von Volta OS: legt beim Start die mitgelieferten Dateien (services/, assets/)
// aus dem APK in den App-Speicher und zeigt mit VOLTA_DIR darauf. GPL-3.0-or-later
#include <QCoreApplication>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QStandardPaths>
#include <QUrl>
#include <QDebug>

#include <QGuiApplication>
#include <QWindow>
#include <QTimer>
#ifdef Q_OS_ANDROID
#include <QScreen>
#include <QSysInfo>
#include <QDateTime>
#include <QJniObject>
#include <QMutex>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#endif

#ifndef VOLTA_PORT_STAMP
#define VOLTA_PORT_STAMP "dev"
#endif

// Die Android-Dateiauswahl gibt keine Pfade, sondern content://-Adressen zurueck. Volta erwartet
// eine Datei mit Endung: also in den Zwischenspeicher kopieren (Endung anhand des Inhalts).
QString voltaPortLocalFile(const QString &path)
{
#ifdef Q_OS_ANDROID
    if (!path.startsWith(QLatin1String("content:"))) return path;
    // gleiche Auswahl schon kopiert (Volta prueft die Datei erst und laedt sie dann)
    static QString lastUri, lastOut;
    if (path == lastUri && QFileInfo::exists(lastOut)) return lastOut;
    QFile in(path);
    if (!in.open(QIODevice::ReadOnly)) { qWarning().noquote() << "[port] cannot open" << path; return path; }
    // Inhalt lesen (peek() geht bei Android-Adressen nicht immer); die Endung kommt aus dem Inhalt,
    // weil content://-Adressen meist keinen Dateinamen haben
    QByteArray first = in.read(1 << 20);
    QString ext;
    if (first.startsWith("glTF")) ext = QStringLiteral("glb");
    else if (first.trimmed().startsWith("{")) ext = QStringLiteral("gltf");
    else {
        ext = QFileInfo(QUrl::fromPercentEncoding(path.toUtf8())).suffix().toLower();
        if (ext.isEmpty() || ext.size() > 5) ext = QStringLiteral("bin");
    }
    const QString dir = QDir::tempPath() + QStringLiteral("/volta_pick");
    QDir(dir).removeRecursively();
    QDir().mkpath(dir);
    const QString out = dir + QStringLiteral("/car.") + ext;
    QFile to(out);
    if (!to.open(QIODevice::WriteOnly)) return path;
    to.write(first);
    while (!in.atEnd()) {
        const QByteArray chunk = in.read(1 << 20);
        if (chunk.isEmpty()) break;
        to.write(chunk);
    }
    to.close();
    qInfo().noquote() << "[port] picked file copied to" << out << to.size() << "bytes";
    lastUri = path; lastOut = out;
    return out;
#else
    return path;
#endif
}

// Blender (zum Verkleinern/Umwandeln von 3D-Modellen) gibt es auf Android nicht
bool voltaPortNoBlender()
{
#ifdef Q_OS_ANDROID
    return true;
#else
    return false;
#endif
}

void voltaPortStartMapProxy();   // volta_mapproxy.cpp
void voltaPortRegisterApps();    // android_apps.cpp

static void voltaPortPrepare()
{
    voltaPortRegisterApps();
#ifndef Q_OS_ANDROID
    // auf dem Linux-Testrechner nur auf Wunsch (dort gibt es sonst Voltas Python-Dienst)
    if (qEnvironmentVariableIsSet("VOLTA_PORT_PROXY")) voltaPortStartMapProxy();
    // nur fuer die Kontroll-Bildschirmfotos: Auto-Ansicht verkleinern, damit die Karte daneben sichtbar ist
    if (qEnvironmentVariableIsSet("VOLTA_PORT_SPLIT"))
        QTimer::singleShot(6000, qApp, []() {
            const auto wins = QGuiApplication::allWindows();
            for (QWindow *w : wins)
                if (w->property("carFullScreen").isValid()) w->setProperty("carFullScreen", false);
        });
    if (qEnvironmentVariableIsSet("VOLTA_PORT_GRID"))
        QTimer::singleShot(7000, qApp, []() {
            const auto wins = QGuiApplication::allWindows();
            for (QWindow *w : wins)
                if (w->property("showGrid").isValid()) w->setProperty("showGrid", true);
        });
#endif
#ifdef Q_OS_ANDROID
    voltaLogOpen();
    voltaPortStartMapProxy();
    const QString src = QStringLiteral("assets:/volta");
    const QString dst = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/volta");
    const QString stampFile = dst + QStringLiteral("/.port_stamp");
    QByteArray have;
    { QFile f(stampFile); if (f.open(QIODevice::ReadOnly)) have = f.readAll(); }
    if (have != QByteArray(VOLTA_PORT_STAMP)) {
        int n = 0;
        QDirIterator it(src, QDir::Files, QDirIterator::Subdirectories);
        while (it.hasNext()) {
            const QString from = it.next();
            const QString to = dst + from.mid(src.size());
            QDir().mkpath(QFileInfo(to).absolutePath());
            QFile::remove(to);
            if (QFile::copy(from, to)) {
                QFile::setPermissions(to, QFile::ReadOwner | QFile::WriteOwner);
                ++n;
            }
        }
        QFile f(stampFile);
        if (f.open(QIODevice::WriteOnly)) f.write(VOLTA_PORT_STAMP);
        qInfo().noquote() << "[port] files unpacked:" << n << "->" << dst;
    }
    qputenv("VOLTA_DIR", dst.toUtf8());
#endif
}
Q_COREAPP_STARTUP_FUNCTION(voltaPortPrepare)
