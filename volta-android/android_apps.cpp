// Android-Port von Volta OS: installierte Android-Apps fuer Voltas App-Drawer, Verknuepfungen
// von Voltas eigenen Symbolen (Telefon, CarPlay ...) mit Android-Apps und Launcher-Verhalten
// (Zurueck-Taste beendet Volta nicht). In QML als  import VoltaPort 1.0  ->  AndroidPort.
// GPL-3.0-or-later
#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QGuiApplication>
#include <QKeyEvent>
#include <QObject>
#include <QHash>
#include <QSet>
#include <QPointer>
#include <QQmlEngine>
#include <QSettings>
#include <QStandardPaths>
#include <QTimer>
#include <QUrl>
#include <QVariantList>
#include <QVariantMap>
#include <algorithm>
#include <thread>

#ifdef Q_OS_ANDROID
#include <QJniEnvironment>
#include <QJniObject>
// android_system.cpp
QString voltaPortSetBrightness(int percent);
bool voltaPortMediaAccess();
void voltaPortOpenAndroidSettings(const QString &action);
#endif

namespace {

// Voltas eigene Symbole, die auf eine Android-App umgeleitet werden koennen
struct Slot { const char *id; const char *name; };
const Slot kSlots[] = {
    {"phone", "Telefon"},
    {"androidauto", "CarPlay / Android Auto"},
    {"messages", "Nachrichten"},
    {"radio", "Radio"},
    {"localmusic", "Musik"},
    {"spotifyapp", "Spotify"},
    {"spotify", "YT Music"},
    {"podcast", "Podcasts"},
    {"browser", "Browser"},
    {"camera", "Kamera"},
    {"bluetooth", "Bluetooth"},
    {"calendar", "Kalender"},
    {"theater", "Theater"},
};

#ifdef Q_OS_ANDROID
QJniObject jstr(const QString &s) { return QJniObject::fromString(s); }

QByteArray drawableToPng(const QJniObject &drawable, int size)
{
    QJniEnvironment env;
    if (!drawable.isValid()) return {};
    const QJniObject cfg = QJniObject::getStaticObjectField("android/graphics/Bitmap$Config", "ARGB_8888",
                                                            "Landroid/graphics/Bitmap$Config;");
    const QJniObject bmp = QJniObject::callStaticObjectMethod(
        "android/graphics/Bitmap", "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;",
        jint(size), jint(size), cfg.object());
    if (env.checkAndClearExceptions() || !bmp.isValid()) return {};
    const QJniObject canvas("android/graphics/Canvas", "(Landroid/graphics/Bitmap;)V", bmp.object());
    drawable.callMethod<void>("setBounds", "(IIII)V", jint(0), jint(0), jint(size), jint(size));
    drawable.callMethod<void>("draw", "(Landroid/graphics/Canvas;)V", canvas.object());
    if (env.checkAndClearExceptions()) return {};
    const QJniObject fmt = QJniObject::getStaticObjectField("android/graphics/Bitmap$CompressFormat", "PNG",
                                                            "Landroid/graphics/Bitmap$CompressFormat;");
    const QJniObject out("java/io/ByteArrayOutputStream");
    bmp.callMethod<jboolean>("compress", "(Landroid/graphics/Bitmap$CompressFormat;ILjava/io/OutputStream;)Z",
                             fmt.object(), jint(100), out.object());
    const QJniObject arr = out.callObjectMethod("toByteArray", "()[B");
    if (env.checkAndClearExceptions() || !arr.isValid()) return {};
    const jbyteArray ja = arr.object<jbyteArray>();
    const jsize len = env->GetArrayLength(ja);
    QByteArray data(len, Qt::Uninitialized);
    env->GetByteArrayRegion(ja, 0, len, reinterpret_cast<jbyte *>(data.data()));
    bmp.callMethod<void>("recycle");
    return data;
}
#endif

class AndroidPort : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList apps READ apps NOTIFY appsChanged)
    Q_PROPERTY(QVariantMap links READ links NOTIFY linksChanged)
    Q_PROPERTY(QVariantList targets READ slotList CONSTANT)
    Q_PROPERTY(bool android READ isAndroid CONSTANT)
    Q_PROPERTY(bool mediaAccess READ mediaAccess NOTIFY mediaAccessChanged)
public:
    explicit AndroidPort(QObject *parent) : QObject(parent)
    {
        QSettings s;
        const QVariantMap m = s.value(QStringLiteral("port/links")).toMap();
        for (auto it = m.begin(); it != m.end(); ++it) m_links.insert(it.key(), it.value().toString());
        m_iconDir = QStandardPaths::writableLocation(QStandardPaths::CacheLocation) + QStringLiteral("/android_icons");
        QDir().mkpath(m_iconDir);
        connect(qApp, &QGuiApplication::applicationStateChanged, this, [this](Qt::ApplicationState st) {
            if (st != Qt::ApplicationActive) return;
            refresh();   // nach Installieren/Entfernen von Apps
            updateMediaAccess();   // nach der Rueckkehr aus Androids Einstellungen
        });
        QMetaObject::invokeMethod(this, &AndroidPort::updateMediaAccess, Qt::QueuedConnection);
        QMetaObject::invokeMethod(this, &AndroidPort::refresh, Qt::QueuedConnection);
    }

    QVariantList apps() const { return m_apps; }
    QVariantMap links() const
    {
        QVariantMap m;
        for (auto it = m_links.begin(); it != m_links.end(); ++it) m.insert(it.key(), it.value());
        return m;
    }
    QVariantList slotList() const
    {
        QVariantList l;
        for (const Slot &s : kSlots)
            l.append(QVariantMap{{QStringLiteral("id"), QString::fromLatin1(s.id)},
                                 {QStringLiteral("name"), QString::fromUtf8(s.name)}});
        return l;
    }
    bool isAndroid() const
    {
#ifdef Q_OS_ANDROID
        return true;
#else
        return qEnvironmentVariableIsSet("VOLTA_PORT_FAKEAPPS");
#endif
    }

    bool mediaAccess() const { return m_mediaAccess; }
    void updateMediaAccess()
    {
#ifdef Q_OS_ANDROID
        const bool ok = voltaPortMediaAccess();
        if (ok != m_mediaAccess) { m_mediaAccess = ok; emit mediaAccessChanged(); }
#endif
    }
    // Androids Seite "Benachrichtigungszugriff" (fuer das Musik-Widget)
    Q_INVOKABLE void openMediaAccess()
    {
#ifdef Q_OS_ANDROID
        voltaPortOpenAndroidSettings(QStringLiteral("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
#endif
    }
    // Voltas Helligkeitsregler -> Android
    Q_INVOKABLE void setBrightness(int percent)
    {
#ifdef Q_OS_ANDROID
        const QString mode = voltaPortSetBrightness(qBound(5, percent, 100));
        static QString said;
        if (mode != said) { said = mode; qInfo().noquote() << "[system] brightness via" << mode; }
#else
        Q_UNUSED(percent);
#endif
    }

    // Name einer App zum Paket ("" wenn unbekannt)
    Q_INVOKABLE QString appName(const QString &pkg) const
    {
        for (const QVariant &v : m_apps) {
            const QVariantMap a = v.toMap();
            if (a.value(QStringLiteral("pkg")).toString() == pkg) return a.value(QStringLiteral("name")).toString();
        }
        return {};
    }

    // Verknuepfung setzen; pkg "" = wieder Voltas eigene Funktion
    Q_INVOKABLE void setLink(const QString &slotId, const QString &pkg)
    {
        if (pkg.isEmpty()) m_links.remove(slotId);
        else m_links.insert(slotId, pkg);
        saveLinks();
        qInfo().noquote() << "[apps] link" << slotId << "->" << (pkg.isEmpty() ? QStringLiteral("Volta") : pkg);
        emit linksChanged();
    }
    Q_INVOKABLE QString link(const QString &slotId) const { return m_links.value(slotId); }

    // Wird beim Oeffnen jeder Volta-App aufgerufen: true = eine Android-App wurde gestartet
    Q_INVOKABLE bool handle(const QString &appId)
    {
        if (appId.startsWith(QLatin1String("android:"))) return launch(appId.mid(8));
        const QString pkg = m_links.value(appId);
        if (pkg.isEmpty()) return false;
        if (launch(pkg)) return true;
        qWarning().noquote() << "[apps] linked app missing, using Volta's own:" << appId << pkg;
        return false;
    }

    Q_INVOKABLE bool launch(const QString &pkg)
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        const QJniObject ctx = QNativeInterface::QAndroidApplication::context();
        const QJniObject pm = ctx.callObjectMethod("getPackageManager", "()Landroid/content/pm/PackageManager;");
        const QJniObject intent = pm.callObjectMethod("getLaunchIntentForPackage",
                                                      "(Ljava/lang/String;)Landroid/content/Intent;", jstr(pkg).object());
        if (env.checkAndClearExceptions() || !intent.isValid()) {
            qWarning().noquote() << "[apps] cannot launch" << pkg;
            return false;
        }
        // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        intent.callObjectMethod("addFlags", "(I)Landroid/content/Intent;", jint(0x10000000 | 0x00200000));
        ctx.callMethod<void>("startActivity", "(Landroid/content/Intent;)V", intent.object());
        if (env.checkAndClearExceptions()) {
            qWarning().noquote() << "[apps] start failed" << pkg;
            return false;
        }
        qInfo().noquote() << "[apps] launched" << pkg;
        return true;
#else
        qInfo().noquote() << "[apps] (not Android) would launch" << pkg;
        return false;
#endif
    }

    // Androids eigene Einstellungen (fuer alles, was eine App nicht selbst schalten darf)
    Q_INVOKABLE bool openAndroidSettings(const QString &action = QStringLiteral("android.settings.SETTINGS"))
    {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        const QJniObject ctx = QNativeInterface::QAndroidApplication::context();
        const QJniObject intent("android/content/Intent", "(Ljava/lang/String;)V", jstr(action).object());
        intent.callObjectMethod("addFlags", "(I)Landroid/content/Intent;", jint(0x10000000));
        ctx.callMethod<void>("startActivity", "(Landroid/content/Intent;)V", intent.object());
        return !env.checkAndClearExceptions();
#else
        Q_UNUSED(action);
        return false;
#endif
    }

    Q_INVOKABLE void refresh()
    {
#ifdef Q_OS_ANDROID
        if (m_loading) return;
        m_loading = true;
        const QString iconDir = m_iconDir;
        const QPointer<AndroidPort> self(this);
        std::thread([self, iconDir]() {
            QVariantList list;
            QStringList logLines;
            {
                QJniEnvironment env;
                const QJniObject ctx = QNativeInterface::QAndroidApplication::context();
                const QJniObject pm = ctx.callObjectMethod("getPackageManager", "()Landroid/content/pm/PackageManager;");
                const QString own = ctx.callObjectMethod("getPackageName", "()Ljava/lang/String;").toString();
                const QJniObject intent("android/content/Intent", "(Ljava/lang/String;)V",
                                        jstr(QStringLiteral("android.intent.action.MAIN")).object());
                intent.callObjectMethod("addCategory", "(Ljava/lang/String;)Landroid/content/Intent;",
                                        jstr(QStringLiteral("android.intent.category.LAUNCHER")).object());
                const QJniObject res = pm.callObjectMethod("queryIntentActivities",
                                                           "(Landroid/content/Intent;I)Ljava/util/List;", intent.object(), jint(0));
                const int n = res.isValid() ? res.callMethod<jint>("size") : 0;
                QSet<QString> seen;
                for (int i = 0; i < n; ++i) {
                    const QJniObject ri = res.callObjectMethod("get", "(I)Ljava/lang/Object;", jint(i));
                    const QJniObject ai = ri.getObjectField("activityInfo", "Landroid/content/pm/ActivityInfo;");
                    const QString pkg = ai.getObjectField("packageName", "Ljava/lang/String;").toString();
                    if (pkg.isEmpty() || pkg == own || seen.contains(pkg)) continue;
                    seen.insert(pkg);
                    const QString name = ri.callObjectMethod("loadLabel", "(Landroid/content/pm/PackageManager;)Ljava/lang/CharSequence;",
                                                             pm.object())
                                             .callObjectMethod("toString", "()Ljava/lang/String;").toString();
                    if (env.checkAndClearExceptions()) continue;
                    const QString iconFile = iconDir + QLatin1Char('/') + pkg + QStringLiteral(".png");
                    if (!QFileInfo::exists(iconFile)) {
                        const QJniObject dr = ri.callObjectMethod("loadIcon", "(Landroid/content/pm/PackageManager;)Landroid/graphics/drawable/Drawable;",
                                                                  pm.object());
                        if (!env.checkAndClearExceptions()) {
                            const QByteArray png = drawableToPng(dr, 144);
                            QFile f(iconFile);
                            if (!png.isEmpty() && f.open(QIODevice::WriteOnly)) f.write(png);
                        }
                    }
                    list.append(QVariantMap{{QStringLiteral("id"), QStringLiteral("android:") + pkg},
                                            {QStringLiteral("pkg"), pkg},
                                            {QStringLiteral("name"), name.isEmpty() ? pkg : name},
                                            {QStringLiteral("icon"), QUrl::fromLocalFile(iconFile).toString()},
                                            {QStringLiteral("android"), true}});
                    logLines << (name + QStringLiteral(" = ") + pkg);
                }
            }
            std::sort(list.begin(), list.end(), [](const QVariant &a, const QVariant &b) {
                return a.toMap().value(QStringLiteral("name")).toString().compare(
                           b.toMap().value(QStringLiteral("name")).toString(), Qt::CaseInsensitive) < 0;
            });
            QMetaObject::invokeMethod(qApp, [self, list, logLines]() {
                if (!self) return;
                self->m_loading = false;
                if (list == self->m_apps) return;
                const bool first = self->m_apps.isEmpty();
                self->m_apps = list;
                if (first) qInfo().noquote() << "[apps] installed apps:" << logLines.join(QStringLiteral(" | "));
                self->applyDefaults();
                emit self->appsChanged();
            });
        }).detach();
#else
        // nur fuer die Kontroll-Bildschirmfotos auf Linux: erfundene Eintraege
        if (qEnvironmentVariableIsSet("VOLTA_PORT_FAKEAPPS") && m_apps.isEmpty()) {
            const char *names[] = {"ZLINK", "Bluetooth Phone", "Torque", "TPMS", "DVR", "USB Camera", "YouTube",
                                   "Dashboard W204", "Topgadget Voice", "Spotify", "Google Maps", "Waze"};
            for (const char *n : names) {
                const QString name = QString::fromLatin1(n);
                const QString pkg = QStringLiteral("demo.") + name.toLower().remove(QLatin1Char(' '));
                m_apps.append(QVariantMap{{QStringLiteral("id"), QStringLiteral("android:") + pkg}, {QStringLiteral("pkg"), pkg},
                                          {QStringLiteral("name"), name},
                                          {QStringLiteral("icon"), QStringLiteral("qrc:/VoltaApp/icons/settings.svg")},
                                          {QStringLiteral("android"), true}});
            }
            m_links.insert(QStringLiteral("androidauto"), QStringLiteral("demo.zlink"));
            m_links.insert(QStringLiteral("phone"), QStringLiteral("demo.bluetoothphone"));
            emit appsChanged();
            emit linksChanged();
        }
#endif
    }

signals:
    void appsChanged();
    void linksChanged();
    void mediaAccessChanged();

private:
    void saveLinks()
    {
        QVariantMap m;
        for (auto it = m_links.begin(); it != m_links.end(); ++it) m.insert(it.key(), it.value());
        QSettings().setValue(QStringLiteral("port/links"), m);
    }

    // Beim ersten Mal: CarPlay -> ZLINK, Telefon -> Telefon-App des Radios (wenn eindeutig erkennbar)
    void applyDefaults()
    {
        QSettings s;
        if (s.value(QStringLiteral("port/defaultsDone")).toBool()) return;
        auto find = [this](const QStringList &words) -> QString {
            for (const QVariant &v : m_apps) {
                const QVariantMap a = v.toMap();
                const QString hay = (a.value(QStringLiteral("name")).toString() + QLatin1Char(' ')
                                     + a.value(QStringLiteral("pkg")).toString()).toLower();
                for (const QString &w : words)
                    if (hay.contains(w)) return a.value(QStringLiteral("pkg")).toString();
            }
            return {};
        };
        const QString zlink = find({QStringLiteral("zlink"), QStringLiteral("carplay")});
        if (!zlink.isEmpty() && !m_links.contains(QStringLiteral("androidauto"))) m_links.insert(QStringLiteral("androidauto"), zlink);
        const QString phone = find({QStringLiteral("telefon"), QStringLiteral("phone"), QStringLiteral("dialer"),
                                    QStringLiteral("btphone"), QStringLiteral("bluetooth")});
        if (!phone.isEmpty() && !m_links.contains(QStringLiteral("phone"))) m_links.insert(QStringLiteral("phone"), phone);
        s.setValue(QStringLiteral("port/defaultsDone"), true);
        saveLinks();
        qInfo().noquote() << "[apps] default links: carplay ->" << (zlink.isEmpty() ? QStringLiteral("-") : zlink)
                          << ", phone ->" << (phone.isEmpty() ? QStringLiteral("-") : phone);
        emit linksChanged();
    }

    QVariantList m_apps;
    QHash<QString, QString> m_links;
    QString m_iconDir;
    bool m_loading = false;
    bool m_mediaAccess = true;
};

#ifdef Q_OS_ANDROID
// Launcher: die Zurueck-Taste darf Volta nicht schliessen
class BackKeyFilter : public QObject
{
public:
    using QObject::QObject;
    bool eventFilter(QObject *o, QEvent *e) override
    {
        if ((e->type() == QEvent::KeyPress || e->type() == QEvent::KeyRelease || e->type() == QEvent::ShortcutOverride)
            && static_cast<QKeyEvent *>(e)->key() == Qt::Key_Back)
            return true;
        return QObject::eventFilter(o, e);
    }
};
#endif

} // namespace

void voltaPortRegisterApps()
{
    // erst beim ersten Zugriff aus QML anlegen: dann ist die App sicher fertig gestartet
    qmlRegisterSingletonType<AndroidPort>("VoltaPort", 1, 0, "AndroidPort", [](QQmlEngine *, QJSEngine *) -> QObject * {
        static AndroidPort *port = nullptr;
        if (!port) port = new AndroidPort(QCoreApplication::instance());
        QQmlEngine::setObjectOwnership(port, QQmlEngine::CppOwnership);
        return port;
    });
    qmlRegisterType(QUrl(QStringLiteral("qrc:/volta_port/PortLinkPopup.qml")), "VoltaPort", 1, 0, "PortLinkPopup");
#ifdef Q_OS_ANDROID
    QTimer::singleShot(0, qApp, []() { qApp->installEventFilter(new BackKeyFilter(qApp)); });
#endif
}

#include "android_apps.moc"
