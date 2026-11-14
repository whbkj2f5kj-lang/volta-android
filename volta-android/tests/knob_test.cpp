#include <QtTest>
#include <QQmlApplicationEngine>
#include <QQuickWindow>
#include "../android_knob.cpp"

class KnobTest : public QObject {
    Q_OBJECT
    std::unique_ptr<QQmlApplicationEngine> engine;
    QQuickWindow *window = nullptr;
    KnobNavigator *nav = nullptr;
    bool load(const QByteArray &body) {
        engine = std::make_unique<QQmlApplicationEngine>();
        engine->loadData("import QtQuick; import QtQuick.Window; import QtQuick.Controls; Window { id: root; width:640; height:480; visible:true; property int clicks:0; " + body + " }");
        if (engine->rootObjects().isEmpty()) return false;
        window = qobject_cast<QQuickWindow *>(engine->rootObjects().first());
        QCoreApplication::processEvents();
        return window != nullptr;
    }
    static QByteArray button(const char *name, int x, int y, const char *extra = "") {
        return QByteArray("Rectangle { x:") + QByteArray::number(x) + "; y:" + QByteArray::number(y)
            + "; width:160; height:50; color:'gray'; MouseArea { objectName:'" + name
            + "'; anchors.fill:parent; onClicked: root.clicks += 1; " + extra + " } }";
    }
    void key(int code, bool repeat = false) {
        QKeyEvent down(QEvent::KeyPress,code,Qt::NoModifier,QString(),repeat);
        QCoreApplication::sendEvent(window,&down);
        QKeyEvent up(QEvent::KeyRelease,code,Qt::NoModifier,QString(),repeat);
        QCoreApplication::sendEvent(window,&up);
    }
    KnobFrame *frame() {
        for (auto *c : window->contentItem()->childItems())
            if (auto *f = dynamic_cast<KnobFrame *>(c)) return f;
        return nullptr;
    }
private slots:
    void init() { nav = new KnobNavigator(qApp); qApp->installEventFilter(nav); }
    void cleanup() { qApp->removeEventFilter(nav); delete nav; nav=nullptr; engine.reset(); window=nullptr; }
    void disabledKeyboardCatcherDoesNotHideButtons() {
        QVERIFY(load(button("first",100,100)+"MouseArea { anchors.fill:parent; z:999998; enabled:false }"));
        key(Qt::Key_Down); QVERIFY(frame() && frame()->isVisible());
        key(Qt::Key_Return); QCOMPARE(window->property("clicks").toInt(),1);
    }
    void directionsSelectAndEnterActivatesOnce() {
        QVERIFY(load(button("first",100,100)+button("second",100,200)));
        key(Qt::Key_Down); QVERIFY(frame()); QVERIFY(frame()->y()<150);
        key(Qt::Key_Down); QVERIFY(frame()->y()>150);
        key(Qt::Key_Return); QCOMPARE(window->property("clicks").toInt(),1);
        key(Qt::Key_Return,true); QCOMPARE(window->property("clicks").toInt(),1);
        key(Qt::Key_Up); QVERIFY(frame()->y()<150);
    }
    void firstEnterOnlySelectsInsteadOfBlindClicking() {
        QVERIFY(load(button("first",100,100))); key(Qt::Key_Return);
        QCOMPARE(window->property("clicks").toInt(),0); QVERIFY(frame() && frame()->isVisible());
        key(Qt::Key_Return); QCOMPARE(window->property("clicks").toInt(),1);
    }
    void enabledFullScreenBlockerPreventsUnderlyingActivation() {
        QVERIFY(load(button("first",100,100)+"MouseArea { anchors.fill:parent; z:999998 }"));
        key(Qt::Key_Down); QVERIFY(!frame() || !frame()->isVisible());
        key(Qt::Key_Return); QCOMPARE(window->property("clicks").toInt(),0);
    }
    void hiddenAndDisabledButtonsAreNotSelected() {
        QVERIFY(load(button("disabled",20,20,"enabled:false")+button("hidden",20,90,"visible:false")+button("allowed",300,200)));
        key(Qt::Key_Down); QVERIFY(frame()); QVERIFY(frame()->x()>250);
    }
    void textEditingKeepsItsKeys() {
        QVERIFY(load(button("first",100,100)+"TextInput { objectName:'edit'; x:200;y:300;width:180;height:40; text:'abcd' }"));
        auto *edit=window->findChild<QQuickItem *>("edit"); QVERIFY(edit); edit->forceActiveFocus();
        key(Qt::Key_Left); QVERIFY(!frame() || !frame()->isVisible());
        QCOMPARE(window->property("clicks").toInt(),0);
    }
    void pointerUseHidesSelectionAndNextArrowRestoresIt() {
        QVERIFY(load(button("first",100,100))); key(Qt::Key_Down); QVERIFY(frame()->isVisible());
        QTest::mouseClick(window,Qt::LeftButton,Qt::NoModifier,QPoint(10,10)); QVERIFY(!frame()->isVisible());
        key(Qt::Key_Down); QVERIFY(frame()->isVisible());
    }
    void modalPopupKeepsSelectionInsideIt() {
        QVERIFY(load(button("background",20,20)+"Popup { objectName:'popup'; x:200;y:100;width:220;height:150;modal:true;visible:true; contentItem: Item { MouseArea { objectName:'popupButton';anchors.fill:parent;onClicked:root.clicks += 100 } } }"));
        auto *popup=window->findChild<QObject *>("popup"); QVERIFY(popup);
        QTRY_VERIFY(popup->property("opened").toBool());
        key(Qt::Key_Down); QVERIFY(frame()); QVERIFY(frame()->x()>180);
        key(Qt::Key_Return); QCOMPARE(window->property("clicks").toInt(),100);
    }
    void clippedControlsAreNotSelected() {
        QVERIFY(load("Item { x:0;y:0;width:200;height:50;clip:true;"+button("clipped",10,90)+"}"+button("visible",300,200)));
        key(Qt::Key_Down); QVERIFY(frame()); QVERIFY(frame()->x()>250);
    }
};
QTEST_MAIN(KnobTest)
#include "knob_test.moc"
