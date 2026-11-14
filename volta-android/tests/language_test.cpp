#include <QtTest>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QSettings>
#include <QTemporaryDir>
#include "core/LanguageManager.h"

class LanguageTest : public QObject {
    Q_OBJECT
    QTemporaryDir settings;
private slots:
    void initTestCase() {
        QCoreApplication::setOrganizationName("VoltaTranslationTests");
        QCoreApplication::setApplicationName("LanguageTest");
        QSettings::setDefaultFormat(QSettings::IniFormat);
        QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, settings.path());
    }
    void init() { QSettings().clear(); }
    void germanCatalogLoadsAndSelectionSurvivesRestart() {
        {
            QQmlEngine engine;
            LanguageManager language(&engine);
            QCOMPARE(language.code(), "en");
            language.setLanguage("de");
            QCOMPARE(language.code(), "de"); QVERIFY(language.chosen());
            QCOMPARE(language.name("de"), "Deutsch");
            QCOMPARE(QSettings().value("ui/language").toString(), "de");
            QCOMPARE(QCoreApplication::translate("Settings", "Language"), "Sprache");
            QCOMPARE(QCoreApplication::translate("VoltaLimitsQml", "Camera-confirmed speed limit"), "Durch die Kamera bestätigtes Tempolimit");
            QCOMPARE(QCoreApplication::translate("VoltaUpdateQml", "Installation failed: %1").arg("TEST"), "Installation fehlgeschlagen: TEST");
            QCOMPARE(QCoreApplication::translate("PodcastDashboard", "left"), "übrig");
            QCOMPARE(QCoreApplication::translate("ToyboxAlbum", "Cancel"), "Rückgängig");
            QCOMPARE(QCoreApplication::translate("ToyboxAlbum", "Sign out"), "Schließen");
            QCOMPARE(QCoreApplication::translate("BtRequiredOverlay", "To use ") + "TEST" +
                QCoreApplication::translate("BtRequiredOverlay", " connect your phone to the car via Bluetooth."),
                "Um TEST zu verwenden, verbinde dein Telefon per Bluetooth mit dem Auto.");
            QCOMPARE(QLocale().name(), "de_DE");
        }
        QQmlEngine engine;
        LanguageManager restored(&engine);
        QCOMPARE(restored.code(), "de"); QVERIFY(restored.chosen());
        QCOMPARE(QCoreApplication::translate("Settings", "Vehicle"), "Fahrzeug");
    }
    void qmlBindingsChangeImmediatelyAndCanReturnToEnglish() {
        QQmlEngine engine; LanguageManager language(&engine);
        engine.rootContext()->setContextProperty("Lang", &language);
        QQmlComponent component(&engine);
        component.setData("import QtQml\nQtObject { property string label: qsTranslate(\"Settings\", \"Language\"); property string day: qsTranslate(\"CalendarApp\", \"Wednesday\") }", QUrl());
        std::unique_ptr<QObject> object(component.create()); QVERIFY2(object, qPrintable(component.errorString()));
        QCOMPARE(object->property("label").toString(), "Language");
        language.setLanguage("de");
        QCOMPARE(object->property("label").toString(), "Sprache");
        QCOMPARE(object->property("day").toString(), "Mittwoch");
        language.setLanguage("en");
        QCOMPARE(object->property("label").toString(), "Language");
        language.setLanguage("invalid");
        QCOMPARE(language.code(), "en");
    }
    void actualPickerContainsGermanAndHasRoomForAllLanguages() {
        QQmlEngine engine; LanguageManager language(&engine);
        engine.rootContext()->setContextProperty("Lang", &language);
        QQmlComponent component(&engine, QUrl::fromLocalFile(QStringLiteral(VOLTA_TRANSLATION_UPSTREAM) + "/qml/LanguagePicker.qml"));
        std::unique_ptr<QObject> picker(component.create()); QVERIFY2(picker, qPrintable(component.errorString()));
        language.setLanguage("de");
        QVariant german;
        QVERIFY(QMetaObject::invokeMethod(picker.get(), "nameOf", Q_RETURN_ARG(QVariant, german), Q_ARG(QVariant, "de")));
        QCOMPARE(german.toString(), "Deutsch");
        const auto langs = picker->property("langs").value<QJSValue>().toVariant().toList();
        QCOMPARE(langs.size(), 6);
        picker->setProperty("open", true);
        bool foundFlag = false;
        for (QObject *child : picker->findChildren<QObject *>())
            if (child->property("code").toString() == "de") foundFlag = true;
        QVERIFY(foundFlag);
    }
};
QTEST_MAIN(LanguageTest)
#include "language_test.moc"
