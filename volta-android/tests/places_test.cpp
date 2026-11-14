#include <QtTest>
#include <QTemporaryDir>
#include "../volta_mapproxy.cpp"

// No live providers, user positions or real keys are used by these tests.
class ProviderReply : public QNetworkReply {
    QByteArray data; qint64 offset=0;
public:
    ProviderReply(const QNetworkRequest &req,int code,QByteArray body,QObject *parent,bool hold=false):QNetworkReply(parent),data(body) {
        setRequest(req);setUrl(req.url());setAttribute(QNetworkRequest::HttpStatusCodeAttribute,code);
        open(QIODevice::ReadOnly|QIODevice::Unbuffered);
        if(!hold) QTimer::singleShot(0,this,[this,code](){if(code!=200)setError(ContentAccessDenied,"fake error");setFinished(true);emit readyRead();emit finished();});
    }
    void abort() override {}
    qint64 bytesAvailable() const override{return data.size()-offset+QNetworkReply::bytesAvailable();}
    qint64 readData(char *dst,qint64 count) override {auto n=qMin(count,qint64(data.size())-offset);if(!n)return -1;memcpy(dst,data.constData()+offset,n);offset+=n;return n;}
};
class Providers : public QNetworkAccessManager {
public:
    QList<QUrl> urls;QList<QByteArray> posts;
    int osmStatus=200,tomtomStatus=200,metadataStatus=200;
    int age=300;
    QString framePath="/v2/radar/9dc50f8b55ef";
    bool html=false,remark=false,holdBackground=false;
    QByteArray osm=R"({"elements":[
        {"type":"node","id":1,"lat":52.5201,"lon":13.4041,"tags":{"amenity":"fuel","name":"Sample Fuel"}},
        {"type":"way","id":2,"center":{"lat":52.5201,"lon":13.40415},"tags":{"amenity":"restaurant","cuisine":"pizza","name":"Sample Pizza"}},
        {"type":"node","id":3,"lat":52.5201,"lon":13.4041,"tags":{"highway":"traffic_signals"}},
        {"type":"node","id":4,"lat":52.52015,"lon":13.4041,"tags":{"highway":"stop"}},
        {"type":"node","id":5,"lat":52.5201,"lon":13.4041,"tags":{"highway":"speed_camera","maxspeed":"30 mph","direction":"NNW"}},
        {"type":"node","id":6,"lat":999,"lon":13.4041,"tags":{"amenity":"fuel"}},
        {"type":"node","id":7,"tags":{"amenity":"fuel"}}
    ]})";
    QByteArray tomtom=R"({"results":[{"type":"POI","position":{"lat":52.5201,"lon":13.4041},"dist":99,"poi":{"name":"Sample Cafe","phone":"+490000","url":"example.test","classifications":[{"names":[{"name":"Cafe"}]}]},"address":{"freeformAddress":"Sample Street 1, Berlin"}}, {"position":{"lat":999,"lon":0},"poi":{"name":"invalid"}}]})";
    QByteArray maptiler=R"({"features":[{"center":[13.4041,52.5201],"text":"Sample Street","place_name":"Sample Street, Berlin","context":[{"id":"place.1","text":"District"},{"id":"municipality.2","text":"Berlin"}]}]})";
    QByteArray png=QByteArray::fromBase64("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLttAAAAABJRU5ErkJggg==");
protected:
    QNetworkReply *createRequest(Operation,const QNetworkRequest &r,QIODevice *outgoing) override {
        urls.append(r.url());posts.append(outgoing ? outgoing->readAll() : QByteArray());
        const auto host=r.url().host();int code=200;QByteArray data;
        if(host.contains("overpass")){code=osmStatus;data=remark ? QByteArray("{\"elements\":[],\"remark\":\"timeout\"}") : osm;}
        else if(host=="api.tomtom.com"){code=tomtomStatus;data=tomtom;}
        else if(host=="api.maptiler.com")data=maptiler;
        else if(host=="api.rainviewer.com") {
            code=metadataStatus;auto time=QDateTime::currentSecsSinceEpoch()-age;
            data=QJsonDocument(QJsonObject{{"host","https://127.0.0.1:1234"},{"radar",QJsonObject{{"past",QJsonArray{QJsonObject{{"path",framePath},{"time",time}}}}}}}).toJson();
        } else if(host=="tilecache.rainviewer.com"||host=="tile.openweathermap.org")data=png;
        else data=R"({"version":8,"sources":{},"layers":[{"id":"label","type":"symbol"}]})";
        if(html)data="<html>provider error</html>";
        return new ProviderReply(r,code,data,this,holdBackground && host.contains("overpass"));
    }
};
class PlacesTest : public QObject {
    Q_OBJECT
    QLocale previousLocale;
    struct Result {int status;QByteArray data;QJsonObject object()const{return QJsonDocument::fromJson(data).object();}QJsonArray array()const{return QJsonDocument::fromJson(data).array();}};
    Result get(MapProxy &proxy,const QString &path) {
        QNetworkAccessManager client;auto *r=client.get(QNetworkRequest(QUrl(QString("http://127.0.0.1:%1%2").arg(proxy.port()).arg(path))));
        QSignalSpy done(r,&QNetworkReply::finished);
        if(!r->isFinished()&&!done.wait(2000)) return {0,{}};
        return {r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt(),r->readAll()};
    }
    const QString bbox="?s=52.5200&w=13.4040&n=52.5202&e=13.4042";
private slots:
    void init(){for(auto name:{"MAPTILER_KEY","TOMTOM_KEY","OWM_KEY"})qunsetenv(name);}
    void cleanup(){init();QLocale::setDefault(previousLocale);}
    void selectedLanguageControlsSearchAndReverseGeocoding() {
        QLocale::setDefault(QLocale("de_DE"));
        qputenv("TOMTOM_KEY","dummy-language-test");
        QTemporaryDir dir; Providers net; MapProxy proxy(nullptr,&net,dir.path(),0);
        QCOMPARE(get(proxy,"/poi-search?q=Berlin&lat=52.52&lon=13.4").status,200);
        QCOMPARE(QUrlQuery(net.urls.last()).queryItemValue("language"),"de-DE");
        qputenv("MAPTILER_KEY","dummy-map-language-test");
        QCOMPARE(get(proxy,"/reverse-geocode?lat=52.52&lon=13.4").status,200);
        QCOMPARE(QUrlQuery(net.urls.last()).queryItemValue("language"),"de");
        qunsetenv("MAPTILER_KEY");
        net.tomtom=R"({"addresses":[{"address":{"streetName":"Test Street","municipality":"Test City"}}]})";
        QCOMPARE(get(proxy,"/reverse-geocode?lat=52.52&lon=13.4").status,200);
        QCOMPARE(QUrlQuery(net.urls.last()).queryItemValue("language"),"de-DE");
    }
    void osmPoisDownloadInBackgroundAndSurviveRestart() {
        QTemporaryDir dir;Providers net;
        {
            MapProxy p(nullptr,&net,dir.path(),0);
            QCOMPARE(get(p,"/map-pois"+bbox).object()["pending"].toInt(),1);
            QTRY_COMPARE(net.urls.size(),1);
            QTRY_COMPARE(get(p,"/map-pois"+bbox).object()["pois"].toArray().size(),2);
            const auto result=get(p,"/map-pois"+bbox).object();QCOMPARE(result["pending"].toInt(),0);
            const auto fuel=result["pois"].toArray().first().toObject();QCOMPARE(fuel["name"].toString(),"Sample Fuel");QCOMPARE(fuel["icon"].toString(),"poi_gas_station.svg");
            QVERIFY(net.posts[0].contains("data="));QCOMPARE(net.urls.size(),1);
        }
        Providers offline;offline.osmStatus=503;MapProxy again(nullptr,&offline,dir.path(),0);
        QCOMPARE(get(again,"/map-pois"+bbox).object()["pois"].toArray().size(),2);QVERIFY(offline.urls.isEmpty());
    }
    void expiredDataIsMarkedAndFailuresDoNotHammerProvider() {
        QTemporaryDir dir;Providers net;MapProxy p(nullptr,&net,dir.path(),0);
        get(p,"/map-pois"+bbox);QTRY_COMPARE(get(p,"/map-pois"+bbox).object()["pois"].toArray().size(),2);
        auto files=QDir(dir.path()+"/places_v1").entryInfoList(QDir::Files);QCOMPARE(files.size(),1);
        QFile f(files[0].absoluteFilePath());QVERIFY(f.open(QIODevice::ReadWrite));QVERIFY(f.setFileTime(QDateTime::currentDateTime().addDays(-8),QFileDevice::FileModificationTime));f.close();
        net.osmStatus=503;
        auto result=get(p,"/map-pois"+bbox).object();QVERIFY(result["stale"].toBool());QCOMPARE(result["pois"].toArray().size(),2);
        QTRY_COMPARE(net.urls.size(),2);QTest::qWait(10);
        for(int i=0;i<5;++i)get(p,"/map-pois"+bbox);
        QCOMPARE(net.urls.size(),2);
    }
    void invalidCoordinatesAndOversizedRequestsNeverReachProviders() {
        QTemporaryDir dir;Providers net;MapProxy p(nullptr,&net,dir.path(),0);
        for(auto path:{"/map-pois?s=nan&w=0&n=1&e=1","/map-signals?s=-90&w=-180&n=90&e=180","/poi-search?q=test&lat=999&lon=0","/map-speedcams?lat=0&lon=0&r=nan","/weather-tile/7/128/1"})
            QCOMPARE(get(p,path).status,400);
        QVERIFY(net.urls.isEmpty());
    }
    void staticSignalsAndCameraMetadataMatchVoltaContract() {
        QTemporaryDir dir;Providers net;MapProxy p(nullptr,&net,dir.path(),0);
        get(p,"/map-signals"+bbox);QTRY_COMPARE(get(p,"/map-signals"+bbox).object()["items"].toArray().size(),2);
        auto controls=get(p,"/map-signals"+bbox).object()["items"].toArray();
        QCOMPARE(controls[0].toObject()["kind"].toString(),"signal");QVERIFY(!controls[0].toObject().contains("phase"));
        get(p,"/map-speedcams?lat=52.5201&lon=13.4041&r=300");
        QTRY_COMPARE(get(p,"/map-speedcams?lat=52.5201&lon=13.4041&r=300").object()["items"].toArray().size(),1);
        auto camera=get(p,"/map-speedcams?lat=52.5201&lon=13.4041&r=300").object()["items"].toArray()[0].toObject();
        QCOMPARE(camera["limit"].toInt(),48);QCOMPARE(camera["dir"].toDouble(),337.5);
        QVERIFY(VoltaPlaces::direction("forward").isNull());QVERIFY(VoltaPlaces::speed("50;70").isNull());QVERIFY(VoltaPlaces::speed("none").isNull());
    }
    void missingKeysAreUnavailableRatherThanSuccessfulEmptySearch() {
        QTemporaryDir dir;Providers net;MapProxy p(nullptr,&net,dir.path(),0);
        QCOMPARE(get(p,"/poi-search?q=Berlin&lat=52.52&lon=13.4").status,503);
        QCOMPARE(get(p,"/nearby-places?type=fuel&lat=52.52&lon=13.4").status,503);
        QCOMPARE(get(p,"/reverse-geocode?lat=52.52&lon=13.4").status,503);QVERIFY(net.urls.isEmpty());
        qputenv("TOMTOM_KEY","dummy-tomtom-key");
        QCOMPARE(get(p,"/poi-search?q=Berlin&lat=52.52&lon=13.4").array().size(),1);
    }
    void stalledBackgroundTilesLeaveSearchCapacityAvailable() {
        qputenv("TOMTOM_KEY","dummy-tomtom-key");QTemporaryDir dir;Providers net;net.holdBackground=true;MapProxy p(nullptr,&net,dir.path(),0);
        auto result=get(p,"/map-pois?s=52.5&w=13.3&n=52.6&e=13.5");
        QVERIFY(result.object()["pending"].toInt()>2);QCOMPARE(net.urls.size(),2);
        result=get(p,"/poi-search?q=Berlin&lat=52.52&lon=13.4");
        QCOMPARE(result.status,200);QCOMPARE(result.array().size(),1);QCOMPARE(net.urls.size(),3);
        QCOMPARE(net.urls.last().host(),"api.tomtom.com");
    }
    void searchEscapesTextAndKeepsLongDistanceDestinationsPossible() {
        qputenv("TOMTOM_KEY","dummy-tomtom-key");QTemporaryDir dir;Providers net;MapProxy p(nullptr,&net,dir.path(),0);
        const auto result=get(p,"/poi-search?q=Cafe%2F%26key%3Devil&lat=52.52&lon=13.4");
        QCOMPARE(result.status,200);QCOMPARE(result.array().size(),1);
        const auto item=result.array()[0].toObject();QCOMPARE(item["category"].toString(),"Cafe");QVERIFY(item["isBusiness"].toBool());
        QCOMPARE(net.urls[0].host(),"api.tomtom.com");const QUrlQuery query(net.urls[0]);
        QCOMPARE(query.queryItemValue("key"),"dummy-tomtom-key");QVERIFY(!query.hasQueryItem("radius"));
        get(p,"/poi-search?q=Cafe%2F%26key%3Devil&lat=52.52&lon=13.4");QCOMPARE(net.urls.size(),1);
    }
    void failedTomtomFallsBackToMaptilerAndNearbyUsesCorrectCategory() {
        qputenv("TOMTOM_KEY","dummy-tomtom-key");qputenv("MAPTILER_KEY","dummy-maptiler-key");
        QTemporaryDir dir;Providers net;net.tomtomStatus=403;MapProxy p(nullptr,&net,dir.path(),0);
        auto result=get(p,"/poi-search?q=Berlin&lat=52.52&lon=13.4");QCOMPARE(result.status,200);QCOMPARE(result.array()[0].toObject()["name"].toString(),"Sample Street");
        net.tomtomStatus=200;
        result=get(p,"/nearby-places?type=fuel&lat=52.52&lon=13.4");QCOMPARE(result.status,200);
        QCOMPARE(QUrlQuery(net.urls.last()).queryItemValue("categorySet"),"7311");QVERIFY(result.array()[0].toObject()["prices"].isNull());
        QCOMPARE(get(p,"/nearby-places?type=invalid&lat=52.52&lon=13.4").status,400);
    }
    void reverseGeocodingPrefersMunicipalityOverDistrict() {
        qputenv("MAPTILER_KEY","dummy-maptiler-key");QTemporaryDir dir;Providers net;MapProxy p(nullptr,&net,dir.path(),0);
        auto result=get(p,"/reverse-geocode?lat=52.52&lon=13.4");QCOMPARE(result.status,200);
        QCOMPARE(result.object()["street"].toString(),"Sample Street");QCOMPARE(result.object()["city"].toString(),"Berlin");
    }
    void radarNeverFollowsMetadataHostAndStyleOnlyAddsItWhenRequested() {
        QTemporaryDir dir;Providers net;MapProxy p(nullptr,&net,dir.path(),0);
        auto result=get(p,"/weather-tile/7/68/42");QCOMPARE(result.status,200);QCOMPARE(result.data,net.png);
        QCOMPARE(net.urls.size(),2);QCOMPARE(net.urls[1].host(),"tilecache.rainviewer.com");
        get(p,"/weather-tile/7/68/42");QCOMPARE(net.urls.size(),2);
        auto plain=get(p,"/style.json").object();QVERIFY(!plain["sources"].toObject().contains("weather-radar"));
        auto style=get(p,"/style.json?w=1").object();QVERIFY(style["sources"].toObject().contains("weather-radar"));
        QCOMPARE(style["sources"].toObject()["weather-radar"].toObject()["maxzoom"].toInt(),7);
        QCOMPARE(style["layers"].toArray()[0].toObject()["id"].toString(),"weather-layer");
    }
    void oldRadarFailsExplicitlyOrUsesConfiguredOpenWeather() {
        QTemporaryDir dir;Providers net;net.age=3*3600;MapProxy p(nullptr,&net,dir.path(),0);
        QCOMPARE(get(p,"/weather-tile/7/68/42").status,503);QCOMPARE(net.urls.size(),1);
        qputenv("OWM_KEY","dummy-weather-key");
        QCOMPARE(get(p,"/weather-tile/7/68/42").data,net.png);QCOMPARE(net.urls.last().host(),"tile.openweathermap.org");
    }
    void invalidProviderBodiesAreNotCachedAsSuccessfulData() {
        qputenv("TOMTOM_KEY","dummy-tomtom-key");QTemporaryDir dir;Providers net;net.html=true;MapProxy p(nullptr,&net,dir.path(),0);
        QCOMPARE(get(p,"/poi-search?q=Berlin&lat=52.52&lon=13.4").status,502);
        QVERIFY(QDir(dir.path()+"/places_v1").entryList(QDir::Files).isEmpty());
    }
};
QTEST_GUILESS_MAIN(PlacesTest)
#include "places_test.moc"
