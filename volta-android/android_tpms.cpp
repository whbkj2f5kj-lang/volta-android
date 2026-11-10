// USB-Reifendruck aus der W204-Technik, Darstellung ausschliesslich in Volta.
#include <QCoreApplication>
#include <QObject>
#include <QQmlEngine>
#include <QSettings>
#include <QTimer>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QVariantList>
#ifdef Q_OS_ANDROID
#include <QJniEnvironment>
#include <QJniObject>
#endif
namespace {
class VoltaTpmsQml:public QObject {
 Q_OBJECT
 Q_PROPERTY(bool enabled READ enabled NOTIFY settingsChanged)
 Q_PROPERTY(double frontTarget READ frontTarget NOTIFY settingsChanged)
 Q_PROPERTY(double rearTarget READ rearTarget NOTIFY settingsChanged)
 Q_PROPERTY(bool targetsConfigured READ targetsConfigured NOTIFY settingsChanged)
 Q_PROPERTY(QVariantList wheels READ wheels NOTIFY changed)
 Q_PROPERTY(QString status READ status NOTIFY changed)
public:
 explicit VoltaTpmsQml(QObject*p):QObject(p){QSettings s;m_on=s.value("tpms/enabled",false).toBool();m_front=s.value("tpms/front",0).toDouble();m_rear=s.value("tpms/rear",0).toDouble();m_map=s.value("tpms/mapping","0,1,2,3").toString();connect(&m_timer,&QTimer::timeout,this,&VoltaTpmsQml::poll);m_timer.start(500);QTimer::singleShot(0,this,&VoltaTpmsQml::configure);}
 bool enabled()const{return m_on;} double frontTarget()const{return m_front;} double rearTarget()const{return m_rear;}
 bool targetsConfigured()const{return m_front>=1.5&&m_front<=4&&m_rear>=1.5&&m_rear<=4;}
 QVariantList wheels()const{return m_data.value("wheels").toArray().toVariantList();}
 QString status()const{return m_data.value("message").toString(m_on?tr("Waiting for USB TPMS receiver"):tr("TPMS disabled"));}
 Q_INVOKABLE void setEnabled(bool on){m_on=on;save();}
 Q_INVOKABLE void setFront(double v){m_front=qRound(qBound(1.5,v,4.0)*10)/10.0;save();}
 Q_INVOKABLE void setRear(double v){m_rear=qRound(qBound(1.5,v,4.0)*10)/10.0;save();}
 Q_INVOKABLE void assign(int wheel,int channel){if(wheel<0||wheel>3||channel<0||channel>3)return;auto l=m_map.split(',');if(l.size()!=4)l={"0","1","2","3"};int previous=l.indexOf(QString::number(channel));if(previous<0)return;l[previous]=l[wheel];l[wheel]=QString::number(channel);m_map=l.join(',');save();}
 signals:void settingsChanged();void changed();void warningTriggered(QString wheel,QString reason);
private:
 void save(){QSettings s;s.setValue("tpms/enabled",m_on);s.setValue("tpms/front",m_front);s.setValue("tpms/rear",m_rear);s.setValue("tpms/mapping",m_map);configure();emit settingsChanged();}
 void configure(){
#ifdef Q_OS_ANDROID
 QJniEnvironment env;QJniObject::callStaticMethod<void>("de/jarvis/w204/volta/VoltaTpms","configure","(Landroid/content/Context;ZDDLjava/lang/String;)V",QNativeInterface::QAndroidApplication::context().object(),jboolean(m_on),jdouble(m_front),jdouble(m_rear),QJniObject::fromString(m_map).object<jstring>());env.checkAndClearExceptions();
#endif
 }
 void poll(){
#ifdef Q_OS_ANDROID
 QJniEnvironment env;auto json=QJniObject::callStaticObjectMethod("de/jarvis/w204/volta/VoltaTpms","snapshot","()Ljava/lang/String;");if(env.checkAndClearExceptions())return;auto doc=QJsonDocument::fromJson(json.toString().toUtf8());if(!doc.isObject())return;auto next=doc.object();if(next!=m_data){m_data=next;emit changed();}auto a=next.value("wheels").toArray();for(int i=0;i<qMin(4,int(a.size()));++i){auto o=a.at(i).toObject();QString warn=o.value("warning").toString();if(m_on&&!warn.isEmpty()&&warn!=m_warnings[i])emit warningTriggered(o.value("wheel").toString(),warn);m_warnings[i]=warn;}
#endif
 }
 bool m_on=false;double m_front=0,m_rear=0;QString m_map;QString m_warnings[4];QTimer m_timer;QJsonObject m_data;
};
}
void voltaPortRegisterTpms(){qmlRegisterSingletonType<VoltaTpmsQml>("VoltaPort",1,0,"VoltaTpms",[](QQmlEngine*,QJSEngine*)->QObject*{static auto*t=new VoltaTpmsQml(QCoreApplication::instance());QQmlEngine::setObjectOwnership(t,QQmlEngine::CppOwnership);return t;});}
#include "android_tpms.moc"
