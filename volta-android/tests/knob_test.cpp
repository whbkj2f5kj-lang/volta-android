#include <QtTest>
#include <QQmlApplicationEngine>
#include <QQuickWindow>
#include <QQmlContext>
#include <QTemporaryDir>
#include "../android_knob.cpp"

class DrawerTheme : public QObject {
    Q_OBJECT
    Q_PROPERTY(QColor border READ border CONSTANT)
    Q_PROPERTY(QColor textSecondary READ border CONSTANT)
public: using QObject::QObject; QColor border() const {return QColor("#777777");}
};
class DrawerData : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantList list READ list CONSTANT)
    Q_PROPERTY(QVariantList apps READ list CONSTANT)
public: using QObject::QObject; QVariantList list() const {return {};}
    Q_INVOKABLE void remove(const QString &) {}
signals: void changed();
};
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
    void initTestCase() {
        QCoreApplication::setOrganizationName("VoltaKnobTests");
        QCoreApplication::setApplicationName("Navigation");
        qmlRegisterSingletonType<DrawerTheme>("VoltaApp",1,0,"Theme",[](QQmlEngine *e,QJSEngine *)->QObject * {return new DrawerTheme(e);});
        qmlRegisterSingletonType<DrawerData>("VoltaApp",1,0,"WebApps",[](QQmlEngine *e,QJSEngine *)->QObject * {return new DrawerData(e);});
        qmlRegisterSingletonType<DrawerData>("VoltaPort",1,0,"AndroidPort",[](QQmlEngine *e,QJSEngine *)->QObject * {return new DrawerData(e);});
    }
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
    void controlsInsideDelayedFlickableCanBeActivated() {
        QVERIFY(load("Flickable { width:300;height:180;contentHeight:700;clip:true;pressDelay:120;"+button("scrollButton",20,30)+"}"));
        key(Qt::Key_Down); key(Qt::Key_Return);
        QTRY_COMPARE(window->property("clicks").toInt(),1);
        QVERIFY(frame() && frame()->isVisible());
    }
    void tapHandlersCanBeActivated() {
        QVERIFY(load("Rectangle { x:20;y:30;width:160;height:50;color:'gray';TapHandler {onTapped: root.clicks += 1} }"));
        key(Qt::Key_Down);key(Qt::Key_Return);
        QTRY_COMPARE(window->property("clicks").toInt(),1);
    }
    void scrollLaneRevealsNextRowInsteadOfSelectingDock() {
        QVERIFY(load("Item { width:640;height:350;property bool portKnobScope:true;Flickable { objectName:'menu';width:300;height:180;contentHeight:700;clip:true;"+button("first",20,30)+button("next",20,500)+"}}"+button("dock",20,400)));
        key(Qt::Key_Down);key(Qt::Key_Down);
        auto *menu=window->findChild<QQuickItem *>("menu");QVERIFY(menu);
        QVERIFY(menu->property("contentY").toDouble()>300);
        QVERIFY(frame() && frame()->y()<180);
        key(Qt::Key_Return);QTRY_COMPARE(window->property("clicks").toInt(),1);
        key(Qt::Key_Up);QVERIFY(menu->property("contentY").toDouble()<40);
    }
    void settingsScopeExcludesDockAndKeepsIndependentColumns() {
        QVERIFY(load("Item { width:640;height:350;property bool portKnobScope:true;Flickable { objectName:'sidebar';width:200;height:180;contentHeight:700;clip:true;"+button("tab",20,30)+button("laterTab",20,500)+"}Flickable {objectName:'content';x:300;width:300;height:180;contentHeight:700;clip:true;"+button("control",20,30)+button("laterControl",20,500)+"}}"+button("dock",20,400)));
        key(Qt::Key_Down);key(Qt::Key_Right);key(Qt::Key_Down);
        auto *content=window->findChild<QQuickItem *>("content");auto *sidebar=window->findChild<QQuickItem *>("sidebar");
        QVERIFY(content->property("contentY").toDouble()>300);QCOMPARE(sidebar->property("contentY").toDouble(),0.0);
    }
    void backClosesTheNavigationScope() {
        QVERIFY(load("Item {width:640;height:350;property bool portKnobScope:true;function portKnobBack() {root.clicks += 10;}"+button("control",20,30)+"}"));
        key(Qt::Key_Back);QCOMPARE(window->property("clicks").toInt(),10);
    }
    void actualDrawerEmitsAppSelectionFromEnter() {
        const QString sourceDir=qEnvironmentVariable("VOLTA_TEST_QML_DIR");
        if(sourceDir.isEmpty()) QSKIP("Set VOLTA_TEST_QML_DIR to a patched upstream qml directory");
        QVERIFY(QFileInfo::exists(sourceDir+"/AppDrawer.qml"));
        QTemporaryDir isolated; const QString dir=isolated.path();
        QVERIFY(QFile::copy(sourceDir+"/AppDrawer.qml",dir+"/AppDrawer.qml"));
        engine=std::make_unique<QQmlApplicationEngine>();
        const QByteArray qml=QString("import QtQuick;import QtQuick.Window;import '.';Window {id:root;width:800;height:600;visible:true;property real uPx:1;property string selected:'';property bool editMode:false;AppDrawer {anchors.centerIn:parent;width:600;editModeActive:root.editMode;qtApps:[{id:'settings',name:'Settings',icon:''},{id:'android:test',name:'Music',icon:''}];onPortKnobCloseRequested:{root.editMode=false;root.selected='closed'};onAppSelected: appId => root.selected=appId}}").toUtf8();
        engine->loadData(qml,QUrl::fromLocalFile(dir+"/KnobDrawerTest.qml")); QVERIFY(!engine->rootObjects().isEmpty());
        window=qobject_cast<QQuickWindow *>(engine->rootObjects().first());QVERIFY(window);
        QTRY_VERIFY(window->isExposed() && window->contentItem()->width()==window->width());
        QQuickItem *target=nullptr;
        std::function<void(QQuickItem *)> findTarget=[&](QQuickItem *it) {
            if(it->property("pressX").isValid() && it->property("dragging").isValid()) target=it;
            for(auto *child:it->childItems()) findTarget(child);
        };
        auto ready=[&]{findTarget(window->contentItem());return target!=nullptr && target->width()>0;};
        QTRY_VERIFY(ready());
        key(Qt::Key_Down);
        for(int i=0;i<30;i++) {
            if(frame() && QLineF(frame()->position()+QPointF(frame()->width()/2,frame()->height()/2),target->mapToScene(QPointF(target->width()/2,target->height()/2))).length()<2) break;
            key(Qt::Key_Tab);
        }
        QVERIFY(frame());
        QVERIFY(QLineF(frame()->position()+QPointF(frame()->width()/2,frame()->height()/2),target->mapToScene(QPointF(target->width()/2,target->height()/2))).length()<2);
        key(Qt::Key_Return);
        QTRY_VERIFY(!window->property("selected").toString().isEmpty());
        window->setProperty("editMode",true);key(Qt::Key_Back);
        QCOMPARE(window->property("selected").toString(),QString("closed"));
        QVERIFY(!window->property("editMode").toBool());
        engine.reset();window=nullptr;
    }
    void semanticActionsOnButtonAndTapHandlerAreInvoked() {
        QVERIFY(load("Rectangle {x:20;y:30;width:160;height:50;function portKnobActivate(){root.clicks += 10;}MouseArea {anchors.fill:parent;onClicked:root.clicks += 1}}Rectangle {x:20;y:130;width:160;height:50;TapHandler {function portKnobActivate(){root.clicks += 100;}onTapped:root.clicks += 1}}"));
        key(Qt::Key_Down);key(Qt::Key_Return);QCOMPARE(window->property("clicks").toInt(),10);
        key(Qt::Key_Down);key(Qt::Key_Return);QCOMPARE(window->property("clicks").toInt(),110);
    }
    void ignoredBackgroundIsNotAnAction() {
        QVERIFY(load("MouseArea {width:200;height:100;property bool portKnobIgnore:true;}"+button("button",300,200)));
        key(Qt::Key_Down);QVERIFY(frame() && frame()->x()>250);
    }
    void clippedControlsAreNotSelected() {
        QVERIFY(load("Item { x:0;y:0;width:200;height:50;clip:true;"+button("clipped",10,90)+"}"+button("visible",300,200)));
        key(Qt::Key_Down); QVERIFY(frame()); QVERIFY(frame()->x()>250);
    }
};
QTEST_MAIN(KnobTest)
#include "knob_test.moc"
