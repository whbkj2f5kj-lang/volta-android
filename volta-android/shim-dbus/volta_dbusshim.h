// Platzhalter fuer Qt D-Bus (gibt es in Qt fuer Android nicht): alle Aufrufe schlagen sauber fehl,
// so wie auf einem Linux-System ohne die jeweiligen Dienste (BlueZ, MPRIS, Mutter). GPL-3.0-or-later
#pragma once
#include <QObject>
#include <QString>
#include <QStringList>
#include <QVariant>
#include <QList>
#include <QTimer>

class QDBusError {
public:
    QString message() const { return QStringLiteral("D-Bus is not available on Android"); }
    QString name() const { return QStringLiteral("org.freedesktop.DBus.Error.NotSupported"); }
    bool isValid() const { return true; }
};

class QDBusObjectPath {
public:
    QDBusObjectPath() = default;
    explicit QDBusObjectPath(const QString &p) : m_path(p) {}
    QString path() const { return m_path; }
private:
    QString m_path;
};

class QDBusVariant {
public:
    QDBusVariant() = default;
    explicit QDBusVariant(const QVariant &v) : m_v(v) {}
    QVariant variant() const { return m_v; }
private:
    QVariant m_v;
};

class QDBusArgument {
public:
    void beginStructure() const {}
    void endStructure() const {}
    void beginArray(int = 0) const {}
    void endArray() const {}
    void beginMap(int = 0, int = 0) const {}
    void endMap() const {}
    void beginMapEntry() const {}
    void endMapEntry() const {}
    bool atEnd() const { return true; }
};
template <typename T> inline QDBusArgument &operator<<(QDBusArgument &a, const T &) { return a; }
template <typename T> inline const QDBusArgument &operator>>(const QDBusArgument &a, T &) { return a; }
template <typename T> inline T qdbus_cast(const QDBusArgument &) { return T(); }
template <typename T> inline T qdbus_cast(const QVariant &) { return T(); }
template <typename T> inline int qDBusRegisterMetaType() { return 0; }

class QDBusMessage {
public:
    enum MessageType { InvalidMessage, MethodCallMessage, SignalMessage, ErrorMessage, ReplyMessage };
    static QDBusMessage createMethodCall(const QString &, const QString &, const QString &, const QString &) { return {}; }
    MessageType type() const { return ErrorMessage; }
    QList<QVariant> arguments() const { return {}; }
    void setArguments(const QList<QVariant> &) {}
    QString errorMessage() const { return QDBusError().message(); }
    QString errorName() const { return QDBusError().name(); }
    QString service() const { return {}; }
    QString path() const { return {}; }
    QString member() const { return {}; }
    QDBusMessage &operator<<(const QVariant &) { return *this; }
};

class QDBusPendingCall {
public:
    bool isError() const { return true; }
    bool isValid() const { return false; }
    bool isFinished() const { return true; }
    QDBusError error() const { return {}; }
    void waitForFinished() {}
};

template <typename T> class QDBusReply {
public:
    QDBusReply() = default;
    QDBusReply(const QDBusMessage &) {}
    QDBusReply(const QDBusPendingCall &) {}
    bool isValid() const { return false; }
    T value() const { return T(); }
    operator T() const { return T(); }
    QDBusError error() const { return {}; }
};

template <typename T = void, typename... Rest> class QDBusPendingReply {
public:
    QDBusPendingReply() = default;
    QDBusPendingReply(const QDBusPendingCall &) {}
    bool isError() const { return true; }
    bool isValid() const { return false; }
    bool isFinished() const { return true; }
    QDBusError error() const { return {}; }
    T value() const { return T(); }
    template <int I> T argumentAt() const { return T(); }
    QVariant argumentAt(int) const { return {}; }
    void waitForFinished() {}
};

class QDBusConnectionInterface {
public:
    QDBusReply<bool> isServiceRegistered(const QString &) const { return {}; }
    QDBusReply<QStringList> registeredServiceNames() const { return {}; }
};

class QDBusConnection {
public:
    static QDBusConnection sessionBus() { return {}; }
    static QDBusConnection systemBus() { return {}; }
    bool isConnected() const { return false; }
    QDBusError lastError() const { return {}; }
    QDBusConnectionInterface *interface() const { return nullptr; }
    template <typename... A> bool connect(A &&...) { return false; }
    template <typename... A> bool disconnect(A &&...) { return false; }
    QDBusPendingCall asyncCall(const QDBusMessage &, int = -1) const { return {}; }
    QDBusMessage call(const QDBusMessage &, int = 0, int = -1) const { return {}; }
    bool send(const QDBusMessage &) const { return false; }
};

class QDBusPendingCallWatcher : public QObject, public QDBusPendingCall {
    Q_OBJECT
public:
    explicit QDBusPendingCallWatcher(const QDBusPendingCall &, QObject *parent = nullptr) : QObject(parent)
    {
        QTimer::singleShot(0, this, [this]() { emit finished(this); });
    }
signals:
    void finished(QDBusPendingCallWatcher *self = nullptr);
};

class QDBusInterface : public QObject {
public:
    QDBusInterface(const QString &, const QString &, const QString & = QString(),
                   const QDBusConnection & = QDBusConnection::sessionBus(), QObject *parent = nullptr)
        : QObject(parent) {}
    bool isValid() const { return false; }
    QDBusError lastError() const { return {}; }
    void setTimeout(int) {}
    template <typename... A> QDBusMessage call(const QString &, A &&...) { return {}; }
    template <typename... A> QDBusPendingCall asyncCall(const QString &, A &&...) { return {}; }
};
