#include <QtTest>
#include <QQmlComponent>
#include <QQmlEngine>
#include <QQmlContext>
#include <QQmlAbstractUrlInterceptor>
#include <QQuickItem>
#include <QQuickWindow>
#include <QTemporaryDir>

class WebTracker : public QObject {
    Q_OBJECT
public:
    int created = 0, destroyed = 0;
    bool bridgeWasReady = false;
    Q_INVOKABLE void made(bool ready) { created++; bridgeWasReady=ready; }
    Q_INVOKABLE void gone() { destroyed++; }
};
class AndroidForWebTest : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool android READ android CONSTANT)
public:
    using QObject::QObject;
    bool android() const { return true; }
};
class SurfaceUrl : public QQmlAbstractUrlInterceptor {
public:
    QUrl local;
    QUrl intercept(const QUrl &url,DataType) override {
        return url==QUrl("qrc:/volta_port/AndroidWebSurface.qml") ? local : url;
    }
};
class WebLifecycleTest : public QObject {
    Q_OBJECT
private slots:
    void initTestCase() {
        qmlRegisterSingletonType<AndroidForWebTest>("VoltaPort",1,0,"AndroidPort",[](QQmlEngine *e,QJSEngine *)->QObject * {return new AndroidForWebTest(e);});
    }
    void hiddenStartupSingleCreationAndBackgroundPersistence() {
        QTemporaryDir dir;
        const QString sourceDir=qEnvironmentVariableIsSet("VOLTA_TEST_WEB_STUB_DIR")
            ? qEnvironmentVariable("VOLTA_TEST_WEB_STUB_DIR") : QFINDTESTDATA("../qmlstubs/QtWebEngine");
        QVERIFY(!sourceDir.isEmpty());
        for(const auto &name:QDir(sourceDir).entryList({"*.qml"},QDir::Files))
            QVERIFY(QFile::copy(sourceDir+"/"+name,dir.filePath(name)));
        QFile fake(dir.filePath("FakeSurface.qml")); QVERIFY(fake.open(QIODevice::WriteOnly));
        fake.write("import QtQuick; Item { property var bridge; Component.onCompleted: tracker.made(bridge !== null && bridge !== undefined); Component.onDestruction: tracker.gone() }"); fake.close();
        WebTracker tracker; SurfaceUrl urls; urls.local=QUrl::fromLocalFile(fake.fileName());
        QQmlEngine engine; engine.addUrlInterceptor(&urls); engine.rootContext()->setContextProperty("tracker",&tracker);
        QQuickWindow window; window.resize(640,480); window.show();
        QQmlComponent component(&engine,QUrl::fromLocalFile(dir.filePath("WebEngineView.qml")));
        QScopedPointer<QObject> view(component.createWithInitialProperties({{"visible",false}}));
        QVERIFY2(view, qPrintable(component.errorString()));
        qobject_cast<QQuickItem *>(view.data())->setParentItem(window.contentItem());
        QCoreApplication::processEvents(); QCOMPARE(tracker.created,0);
        view->setProperty("url",QUrl("https://example.invalid/remembered"));
        view->setProperty("visible",true); QTRY_COMPARE(tracker.created,1); QVERIFY(tracker.bridgeWasReady);
        view->setProperty("visible",false); view->setProperty("lifecycleState",1);
        QCoreApplication::processEvents(); QCOMPARE(tracker.created,1); QCOMPARE(tracker.destroyed,0);
        view->setProperty("visible",true); view->setProperty("lifecycleState",0);
        QCoreApplication::processEvents(); QCOMPARE(tracker.created,1);
        view->setProperty("visible",false); view->setProperty("lifecycleState",2);
        QTRY_COMPARE(tracker.destroyed,1);
        view->setProperty("lifecycleState",0); QCoreApplication::processEvents(); QCOMPARE(tracker.created,1);
        view->setProperty("visible",true); QTRY_COMPARE(tracker.created,2);
        QCOMPARE(view->property("url").toUrl(),QUrl("https://example.invalid/remembered"));
        view.reset(); QCoreApplication::processEvents();
    }
};
QTEST_MAIN(WebLifecycleTest)
#include "web_lifecycle_test.moc"
