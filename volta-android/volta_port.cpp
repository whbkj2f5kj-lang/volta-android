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
#include "volta_log_sanitize.h"

#include <QGuiApplication>
#include <QWindow>
#include <QTimer>
#ifdef Q_OS_ANDROID
#include <QScreen>
#include <QJniEnvironment>
#include <QSysInfo>
#include <QDateTime>
#include <QJniObject>
#include <QMutex>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <unwind.h>
#include <dlfcn.h>
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

    // Android Storage Access Framework: content:// muss ueber ContentResolver gelesen werden.
    // QFile(path) kann diese URI nicht als normalen Dateisystempfad oeffnen.
    static QString lastUri, lastOut;
    if (path == lastUri && QFileInfo::exists(lastOut) && QFileInfo(lastOut).size() > 0)
        return lastOut;

    const QString dir = QStandardPaths::writableLocation(QStandardPaths::CacheLocation)
                      + QStringLiteral("/volta_pick");
    if (dir.isEmpty() || !QDir().mkpath(dir)) {
        qWarning() << "[port] cannot create private cache for selected 3D model";
        return path;
    }
    const QString out = dir + QStringLiteral("/car.glb");
    QFile::remove(out);

    const QJniObject uri = QJniObject::fromString(path);
    const QJniObject target = QJniObject::fromString(out);
    const jboolean copied = QJniObject::callStaticMethod<jboolean>(
        "de/jarvis/volta/PortJava", "copyContentUri",
        "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Z",
        QNativeInterface::QAndroidApplication::context().object(),
        uri.object<jstring>(), target.object<jstring>());

    QJniEnvironment env;
    if (env.checkAndClearExceptions() || !copied) {
        qWarning().noquote() << "[port] ContentResolver could not copy selected file:" << path;
        return path;
    }

    QFile verify(out);
    if (!verify.open(QIODevice::ReadOnly)) {
        qWarning().noquote() << "[port] copied model cannot be opened:" << out;
        return path;
    }
    const QByteArray magic = verify.read(4);
    verify.close();
    if (magic != QByteArray("glTF", 4)) {
        qWarning().noquote() << "[port] selected file is not a binary GLB (missing glTF header):" << out;
        return path;
    }

    qInfo().noquote() << "[port] picked GLB copied to" << out << QFileInfo(out).size() << "bytes";
    lastUri = path;
    lastOut = out;
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

#ifdef Q_OS_ANDROID
// ── Fehlerprotokoll ──────────────────────────────────────────────────────────
// Alles, was Volta ausgibt, landet zusaetzlich in einer Datei, die man ohne Root mit dem
// Dateimanager findet: Android/data/de.jarvis.volta/files/volta_log.txt (der vorige Lauf: volta_log_alt.txt).
// Bis der Ordner bekannt ist (erst nach dem App-Start), wird im Speicher gesammelt.
static QtMessageHandler g_prevHandler = nullptr;
static QBasicMutex g_logMutex;
static QByteArray *g_logPending = nullptr;
static int g_logFd = -1;
static qint64 g_logBytes = 0;
static const qint64 kLogMax = 2 * 1024 * 1024;

static void voltaLogRaw(const QByteArray &line)
{
    if (g_logFd < 0) {
        if (!g_logPending) g_logPending = new QByteArray;
        if (g_logPending->size() < 256 * 1024) g_logPending->append(line);
        return;
    }
    if (g_logBytes > kLogMax) return;
    g_logBytes += line.size();
    if (g_logBytes > kLogMax) {
        static const char cut[] = "[port] log limit reached, further output dropped\n";
        (void)::write(g_logFd, cut, sizeof(cut) - 1);
        return;
    }
    (void)::write(g_logFd, line.constData(), size_t(line.size()));
}

static void voltaLogHandler(QtMsgType type, const QMessageLogContext &ctx, const QString &msg)
{
    const char *lvl = type == QtDebugMsg ? "D" : type == QtInfoMsg ? "I" : type == QtWarningMsg ? "W"
                    : type == QtCriticalMsg ? "E" : "F";
    QByteArray line = QDateTime::currentDateTime().toString(QStringLiteral("HH:mm:ss.zzz")).toLatin1();
    line += ' '; line += lvl; line += ' '; line += voltaSafeLog(msg).toUtf8(); line += '\n';
    {
        QMutexLocker lock(&g_logMutex);
        voltaLogRaw(line);
    }
    if (g_prevHandler) g_prevHandler(type, ctx, voltaSafeLog(msg));
}

static struct sigaction g_oldSig[32];
// Aufrufkette beim Absturz: Bibliothek + Versatz je Ebene, damit man sieht, WO es knallt
// (Grafik, Kamera, 3D, Karte ...). Nur write(), keine Speicheranforderung.
static void voltaCrashHex(uintptr_t v)
{
    char b[2 + 16]; int n = 0; b[n++] = '0'; b[n++] = 'x';
    bool on = false;
    for (int i = 15; i >= 0; --i) {
        const int d = int((v >> (i * 4)) & 0xF);
        if (d || on || i == 0) { on = true; b[n++] = char(d < 10 ? '0' + d : 'a' + d - 10); }
    }
    (void)::write(g_logFd, b, size_t(n));
}
struct VoltaTrace { int n; };
static _Unwind_Reason_Code voltaCrashFrame(struct _Unwind_Context *ctx, void *arg)
{
    auto *t = static_cast<VoltaTrace *>(arg);
    const uintptr_t pc = _Unwind_GetIP(ctx);
    if (!pc || t->n >= 24) return _URC_END_OF_STACK;
    ++t->n;
    Dl_info info;
    std::memset(&info, 0, sizeof(info));
    static const char pre[] = "[port]   at ";
    (void)::write(g_logFd, pre, sizeof(pre) - 1);
    if (dladdr(reinterpret_cast<void *>(pc), &info) && info.dli_fname) {
        const char *base = std::strrchr(info.dli_fname, '/');
        base = base ? base + 1 : info.dli_fname;
        (void)::write(g_logFd, base, std::strlen(base));
        (void)::write(g_logFd, " +", 2);
        voltaCrashHex(pc - reinterpret_cast<uintptr_t>(info.dli_fbase));
        if (info.dli_sname) {
            (void)::write(g_logFd, " ", 1);
            (void)::write(g_logFd, info.dli_sname, std::strlen(info.dli_sname) > 120 ? 120 : std::strlen(info.dli_sname));
        }
    } else {
        voltaCrashHex(pc);
    }
    (void)::write(g_logFd, "\n", 1);
    return _URC_NO_REASON;
}
static void voltaCrashTrace()
{
    static const char h[] = "[port] call chain at the crash:\n";
    (void)::write(g_logFd, h, sizeof(h) - 1);
    VoltaTrace t{0};
    _Unwind_Backtrace(voltaCrashFrame, &t);
}

static void voltaCrashSig(int sig)
{
    // nur write(): im Signal-Handler ist sonst nichts erlaubt
    if (g_logFd >= 0) {
        static const char a[] = "\n[port] CRASH: the app was killed by signal ";
        (void)::write(g_logFd, a, sizeof(a) - 1);
        char num[4] = { char('0' + (sig / 10) % 10), char('0' + sig % 10), '\n', 0 };
        (void)::write(g_logFd, num, 3);
        static const char b[] = "[port] (11 = memory access, 6 = abort, 7 = bus error, 4 = illegal instruction, 8 = arithmetic)\n";
        (void)::write(g_logFd, b, sizeof(b) - 1);
        voltaCrashTrace();
        ::fsync(g_logFd);
    }
    if (sig > 0 && sig < 32) sigaction(sig, &g_oldSig[sig], nullptr);   // Android schreibt danach seinen eigenen Absturzbericht
    else signal(sig, SIG_DFL);
    raise(sig);
}

// sehr frueh (beim Laden der Bibliothek), damit auch die ersten Zeilen von main() ankommen
static const int g_logInstalled = []() { g_prevHandler = qInstallMessageHandler(voltaLogHandler); return 1; }();

static void voltaLogOpen()
{
    QString dir;
    const QJniObject ctx = QNativeInterface::QAndroidApplication::context();
    if (ctx.isValid()) {
        const QJniObject f = ctx.callObjectMethod("getExternalFilesDir", "(Ljava/lang/String;)Ljava/io/File;", nullptr);
        if (f.isValid()) dir = f.callObjectMethod("getAbsolutePath", "()Ljava/lang/String;").toString();
    }
    if (dir.isEmpty()) dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(dir);
    const QString file = dir + QStringLiteral("/volta_log.txt");
    const QString old = dir + QStringLiteral("/volta_log_alt.txt");
    QFile::remove(old);
    QFile::rename(file, old);
    const int fd = ::open(QFile::encodeName(file).constData(), O_WRONLY | O_CREAT | O_TRUNC | O_APPEND, 0644);
    QByteArray head = "Volta OS Android port, build " VOLTA_PORT_STAMP "\n";
    head += "started " + QDateTime::currentDateTime().toString(Qt::ISODate).toUtf8() + "\n";
    head += "device: " + QJniObject::getStaticObjectField("android/os/Build", "MANUFACTURER", "Ljava/lang/String;").toString().toUtf8()
          + " " + QJniObject::getStaticObjectField("android/os/Build", "MODEL", "Ljava/lang/String;").toString().toUtf8()
          + ", " + QSysInfo::prettyProductName().toUtf8() + ", cpu " + QSysInfo::currentCpuArchitecture().toUtf8()
          + " (app built for " + QSysInfo::buildCpuArchitecture().toUtf8() + ")\n";
    head += "log file: " + file.toUtf8() + "\n----\n";
    {
        QMutexLocker lock(&g_logMutex);
        g_logFd = fd;
        voltaLogRaw(head);
        if (g_logPending) { voltaLogRaw(*g_logPending); delete g_logPending; g_logPending = nullptr; }
    }
    for (int sig : {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE}) {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = voltaCrashSig;
        sigemptyset(&sa.sa_mask);
        sigaction(sig, &sa, &g_oldSig[sig]);
    }
    // Bildschirm und Grafik: erst wenn die Oberflaeche steht
    QTimer::singleShot(4000, qApp, []() {
        if (auto *g = qobject_cast<QGuiApplication *>(qApp)) {
            if (QScreen *sc = g->primaryScreen())
                qInfo().noquote() << "[port] screen" << sc->size().width() << "x" << sc->size().height()
                                  << "scale" << sc->devicePixelRatio() << "refresh" << sc->refreshRate() << "Hz";
            qInfo().noquote() << "[port] windows open:" << g->allWindows().size();
        }
    });
    QObject::connect(qApp, &QCoreApplication::aboutToQuit, []() { qInfo() << "[port] app is closing normally"; });
    // Speicher: alle 15 s Verbrauch der App und freier Speicher des Geraets (MB).
    // Steigt "app" stetig oder faellt "free" gegen null, ist der Speicher die Ursache.
    auto *memTimer = new QTimer(qApp);
    memTimer->setInterval(15000);
    QObject::connect(memTimer, &QTimer::timeout, []() {
        auto field = [](const char *path, const QByteArray &key) -> long {
            QFile f(QString::fromLatin1(path));
            if (!f.open(QIODevice::ReadOnly)) return -1;
            for (const QByteArray &line : f.readAll().split('\n'))
                if (line.startsWith(key)) return line.mid(key.size()).trimmed().split(' ').value(0).toLong() / 1024;
            return -1;
        };
        static long peak = 0;
        const long rss = field("/proc/self/status", "VmRSS:");
        if (rss > peak) peak = rss;
        qInfo().noquote() << QStringLiteral("[mem] app %1 MB (peak %2) | device free %3 MB of %4 MB")
            .arg(rss).arg(peak).arg(field("/proc/meminfo", "MemAvailable:")).arg(field("/proc/meminfo", "MemTotal:"));
    });
    memTimer->start();
}
#endif

void voltaPortStartMapProxy();   // volta_mapproxy.cpp
void voltaPortRegisterApps();    // android_apps.cpp
void voltaPortRegisterLimits();
void voltaPortRegisterTpms();
void voltaPortRegisterUpdate();   // android_update.cpp
const char *voltaPortStamp() { return VOLTA_PORT_STAMP; }
void voltaPortRegisterDashcam();
void voltaPortRegisterCar();     // android_car.cpp
#ifdef Q_OS_ANDROID
void voltaPortStartSystemService();   // android_system.cpp

// ── Schutz vor Absturz-Schleifen durch das 3D-Auto ──────────────────────────
// Volta laedt das Auto bei jedem Start. Stuerzt die App dabei ab (zu grosses Modell, Grafik-
// treiber), waere sie als Startbildschirm unbrauchbar. Darum: waehrend der ersten 45 s nach dem
// Start liegt eine Merker-Datei. Ist sie beim naechsten Start noch da, ist Volta in dieser Zeit
// abgestuerzt -> das Auto wird beiseitegelegt (car/*.glb -> *.glb.crashed), Volta startet ohne.
static void voltaPortCarGuard()
{
    const QString carDir = QDir::homePath() + QStringLiteral("/.config/volta/car");
    const QString marker = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/car_loading");
    const QStringList cars = QDir(carDir).entryList({QStringLiteral("auto*.glb")}, QDir::Files);
    if (QFileInfo::exists(marker)) {
        QFile::remove(marker);
        for (const QString &c : cars) {
            QFile::remove(carDir + QLatin1Char('/') + c + QStringLiteral(".crashed"));
            QFile::rename(carDir + QLatin1Char('/') + c, carDir + QLatin1Char('/') + c + QStringLiteral(".crashed"));
        }
        if (!cars.isEmpty())
            qWarning() << "[port] Volta crashed while starting with the 3D car: the car is put aside, load it again or use a lighter model";
        return;
    }
    if (cars.isEmpty()) return;
    QDir().mkpath(QFileInfo(marker).absolutePath());
    QFile f(marker);
    if (f.open(QIODevice::WriteOnly)) f.write("1");
    f.close();
    QTimer::singleShot(45000, qApp, [marker]() { QFile::remove(marker); qInfo() << "[port] 3D car loaded fine"; });
    QObject::connect(qApp, &QCoreApplication::aboutToQuit, [marker]() { QFile::remove(marker); });
}
#endif

static void voltaPortPrepare()
{
    voltaPortRegisterApps();
    voltaPortRegisterCar();
    voltaPortRegisterDashcam();
    voltaPortRegisterTpms();
    voltaPortRegisterUpdate();
    voltaPortRegisterLimits();
#ifdef Q_OS_ANDROID
    // CAN-Daten des Radios mitlesen (nur lesen) -> volta_can_log.txt
    QTimer::singleShot(8000, qApp, []() {
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>("de/jarvis/volta/CanListener", "start", "(Landroid/content/Context;)V",
                                           QNativeInterface::QAndroidApplication::context().object());
        if (env.checkAndClearExceptions()) qWarning() << "[can] listener could not start";
        else qInfo() << "[can] listener started (volta_can_log.txt)";
    });
#endif
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
    voltaPortStartSystemService();
    voltaPortCarGuard();
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
