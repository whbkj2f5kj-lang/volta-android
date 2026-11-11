// Android implementation of VoltaOS services/map_proxy.py's place and radar routes.
// Original: Omardigio/VoltaOS, pinned by volta.yml. GPL-3.0-or-later.
#pragma once
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonValue>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QSaveFile>
#include <QTcpSocket>
#include <QUrlQuery>
#include <QQueue>
#include <QLocale>
#include <QRegularExpression>
#include <cmath>
#include <functional>
#include <algorithm>

namespace VoltaPlaces {
inline bool coordinate(double lat, double lon) {
    return std::isfinite(lat) && std::isfinite(lon) && std::abs(lat) <= 90 && std::abs(lon) <= 180;
}
inline bool number(const QUrlQuery &q, const QString &key, double &out) {
    bool ok = false; out = q.queryItemValue(key).toDouble(&ok); return ok && std::isfinite(out);
}
inline bool position(const QUrlQuery &q, double &lat, double &lon) {
    return number(q,"lat",lat) && number(q,"lon",lon) && coordinate(lat,lon);
}
inline double distance(double lat, double lon, double toLat, double toLon) {
    constexpr double rad = 3.141592653589793 / 180;
    const double a = std::pow(std::sin((toLat-lat)*rad/2),2) + std::cos(lat*rad)*std::cos(toLat*rad)*std::pow(std::sin((toLon-lon)*rad/2),2);
    return 12742 * std::asin(std::sqrt(qBound(0.,a,1.)));
}
struct Category { QString icon, name; int tier = 4; };
inline Category category(const QJsonObject &tags) {
    static const QHash<QString,Category> amenity = {
        {"fuel", {"poi_gas_station.svg","Gas station",1}},
        {"charging_station", {"poi_ev_station.svg","Charging station",1}},
        {"pharmacy", {"poi_pharmacy.svg","Pharmacy",1}},
        {"hospital", {"poi_hospital.svg","Hospital",1}},
        {"clinic", {"poi_hospital.svg","Clinic",2}},
        {"doctors", {"poi_hospital.svg","Doctor's office",3}},
        {"dentist", {"poi_dentist.svg","Dentist",3}},
        {"restaurant", {"poi_restaurant.svg","Restaurant",2}},
        {"fast_food", {"poi_fastfood.svg","Fast food",2}},
        {"cafe", {"poi_cafe.svg","Cafe",2}},
        {"ice_cream", {"poi_cafe.svg","Ice cream shop",2}},
        {"bar", {"poi_bar.svg","Bar",2}},
        {"pub", {"poi_bar.svg","Pub",2}},
        {"biergarten", {"poi_bar.svg","Beer garden",3}},
        {"cinema", {"poi_theater.svg","Cinema",2}},
        {"theatre", {"poi_theater.svg","Theater",2}},
        {"school", {"poi_school.svg","School",3}},
        {"university", {"poi_school.svg","University",2}},
        {"college", {"poi_school.svg","University",3}},
        {"place_of_worship", {"poi_church.svg","Church",3}},
        {"car_wash", {"poi_car_wash.svg","Car wash",3}},
        {"bus_station", {"poi_bus.svg","Bus station",2}},
        {"marketplace", {"poi_grocery.svg","Market",3}}
    };
    static const QHash<QString,Category> shop = {
        {"supermarket", {"poi_grocery.svg","Supermarket",1}},
        {"mall", {"poi_mall.svg","Shopping mall",1}},
        {"department_store", {"poi_mall.svg","Department store",1}},
        {"convenience", {"poi_convenience_store.svg","Convenience store",3}},
        {"bakery", {"poi_bakery.svg","Bakery",2}},
        {"pastry", {"poi_bakery.svg","Pastry shop",2}},
        {"clothes", {"poi_clothing.svg","Clothing",3}},
        {"shoes", {"poi_clothing.svg","Shoes",3}},
        {"car_repair", {"poi_car_repair.svg","Car repair",3}},
        {"tyres", {"poi_car_repair.svg","Tire shop",3}},
        {"car", {"poi_car_repair.svg","Car dealership",3}},
        {"greengrocer", {"poi_grocery.svg","Greengrocer",3}},
        {"butcher", {"poi_storefront.svg","Butcher",3}}
    };
    static const QHash<QString,Category> tourism = {
        {"hotel", {"poi_hotel.svg","Hotel",2}},
        {"motel", {"poi_hotel.svg","Motel",2}},
        {"hostel", {"poi_guesthouse.svg","Hostel",3}},
        {"guest_house", {"poi_guesthouse.svg","B&B",3}},
        {"apartment", {"poi_guesthouse.svg","Apartments",3}},
        {"museum", {"poi_attractions.svg","Museum",1}},
        {"gallery", {"poi_attractions.svg","Gallery",3}},
        {"attraction", {"poi_attractions.svg","Attraction",1}},
        {"viewpoint", {"poi_attractions.svg","Viewpoint",2}},
        {"zoo", {"poi_attractions.svg","Zoo",1}},
        {"theme_park", {"poi_attractions.svg","Theme park",1}},
        {"aquarium", {"poi_attractions.svg","Aquarium",1}}
    };
    static const QHash<QString,Category> leisure = {
        {"park", {"poi_park.svg","Park",2}},
        {"garden", {"poi_park.svg","Garden",3}},
        {"stadium", {"poi_stadium.svg","Stadium",1}},
        {"sports_centre", {"poi_stadium.svg","Sports center",3}},
        {"fitness_centre", {"poi_gym.svg","Gym",3}},
        {"beach_resort", {"poi_beach.svg","Beach",2}}
    };
    if (tags["aeroway"] == "aerodrome") return {"poi_airport.svg","Airport",0};
    if (tags["railway"] == "station" && tags["station"] != "subway") return {"poi_train.svg","Station",1};
    auto value = tags["amenity"].toString();
    if (amenity.contains(value)) {
        if (value == "restaurant" && tags["cuisine"].toString().contains("pizza")) return {"poi_pizza.svg","Pizzeria",2};
        if (value == "place_of_worship" && tags["religion"] == "muslim") return {"poi_mosque.svg","Mosque",3};
        return amenity[value];
    }
    value = tags["shop"].toString();
    if (!value.isEmpty()) return shop.value(value, {"poi_storefront.svg","Shop",4});
    value = tags["tourism"].toString(); if (tourism.contains(value)) return tourism[value];
    value = tags["leisure"].toString(); if (leisure.contains(value)) return leisure[value];
    if (tags["natural"] == "beach") return {"poi_beach.svg","Beach",2};
    if (!tags["historic"].toString().isEmpty()) return {"poi_attractions.svg","Historic site",2};
    return {};
}
inline QJsonValue direction(QString text) {
    const QStringList compass = {"N","NNE","NE","ENE","E","ESE","SE","SSE","S","SSW","SW","WSW","W","WNW","NW","NNW"};
    text = text.trimmed().toUpper(); int i = compass.indexOf(text);
    if (i >= 0) return i * 22.5;
    bool ok = false; double d = text.replace(',', '.').toDouble(&ok);
    if (!ok || !std::isfinite(d)) return QJsonValue::Null;
    return std::fmod(std::fmod(d,360.)+360.,360.);
}
inline QJsonValue speed(QString text) {
    auto match = QRegularExpression("^([0-9]{1,3})(?:\\s*(mph|km/h))?$").match(text.trimmed().toLower());
    if (!match.hasMatch()) return QJsonValue::Null;
    int v = qRound(match.captured(1).toInt() * (match.captured(2)=="mph" ? 1.609344 : 1));
    return v >= 5 && v <= 200 ? QJsonValue(v) : QJsonValue(QJsonValue::Null);
}
inline QJsonArray osmItems(const QByteArray &bytes, const QString &kind) {
    QJsonArray result;
    for (const auto &entry : QJsonDocument::fromJson(bytes).object()["elements"].toArray()) {
        const auto el = entry.toObject(), tags = el["tags"].toObject();
        const auto point = el.contains("lat") ? el : el["center"].toObject();
        if (!point["lat"].isDouble() || !point["lon"].isDouble()) continue;
        double lat = point["lat"].toDouble(), lon = point["lon"].toDouble();
        if (!coordinate(lat,lon)) continue;
        QJsonObject item{{"lat",lat},{"lon",lon}};
        if (kind == "pois") {
            const auto c = category(tags); if (c.icon.isEmpty()) continue;
            const auto name = tags["name"].toString(tags["brand"].toString(c.name));
            item["id"] = el["type"].toString() + "/" + QString::number(qint64(el["id"].toDouble()));
            item["name"] = name; item["icon"] = c.icon; item["category"] = c.name; item["tier"] = c.tier;
            item["minz"] = c.tier <= 1 ? 14.0 : c.tier == 2 ? 15.2 : c.tier == 3 ? 16.2 : 17.2;
        } else if (kind == "signals") {
            const auto highway = tags["highway"].toString();
            if (highway != "traffic_signals" && highway != "stop") continue;
            item["kind"] = highway == "stop" ? "stop" : "signal";
        } else {
            const auto enforcement = tags["enforcement"].toString();
            QString type = enforcement == "average_speed" ? "tutor" : enforcement == "traffic_signals" ? "redlight" :
                (tags["highway"] == "speed_camera" || enforcement == "maxspeed") ? "speed" : "";
            if (type.isEmpty()) continue;
            item["kind"] = type; item["limit"] = speed(tags["maxspeed"].toString());
            item["dir"] = direction(tags["direction"].toString(tags["camera:direction"].toString()));
        }
        result.append(item);
    }
    return result;
}

class Services : public QObject {
    using Socket = QPointer<QTcpSocket>;
    using Done = std::function<void(int,const QByteArray&)>;
    struct Job { QString key; QUrl url; QByteArray post; bool osm; bool png; };
    QNetworkAccessManager *net;
    QString cacheDir;
    QQueue<Job> queue;
    QHash<QString,QList<Done>> waiters;
    QHash<QString,qint64> failedAt;
    int active = 0, activeOsm = 0, server = 0;

    static QString hash(const QByteArray &s) { return QString::fromLatin1(QCryptographicHash::hash(s,QCryptographicHash::Sha256).toHex()); }
    QByteArray cached(const QString &key, int ttl) const {
        QFile file(cacheDir + "/" + key); QFileInfo info(file);
        const auto age = info.lastModified().secsTo(QDateTime::currentDateTime());
        if (age < 0 || age > ttl || info.size() > 4*1024*1024 || !file.open(QIODevice::ReadOnly)) return {};
        return file.readAll();
    }
    void save(const QString &key, const QByteArray &bytes) {
        QDir dir(cacheDir); auto files = dir.entryInfoList(QDir::Files,QDir::Time | QDir::Reversed);
        qint64 size = bytes.size(); for (const auto &f: files) size += f.size();
        while (!files.isEmpty() && (files.size() >= 160 || size > 24*1024*1024)) {
            auto f=files.takeFirst(); size-=f.size(); QFile::remove(f.absoluteFilePath());
        }
        QSaveFile out(dir.filePath(key)); if (out.open(QIODevice::WriteOnly)) { out.write(bytes); out.commit(); }
    }
    void pump() {
        while (active < 4 && !queue.isEmpty()) {
            int index = -1;
            for (int i=0;i<queue.size();++i) if (!queue[i].osm) { index=i; break; }
            if (index < 0 && activeOsm < 2) index=0;
            if (index < 0) return;
            Job job=queue.takeAt(index); ++active; if(job.osm) ++activeOsm;
            QNetworkRequest req(job.url); req.setTransferTimeout(job.osm ? 12000 : 8000);
            req.setRawHeader("User-Agent","VoltaOS-Android/places (github.com/whbkj2f5kj-lang/volta-android)");
            req.setAttribute(QNetworkRequest::RedirectPolicyAttribute,QNetworkRequest::ManualRedirectPolicy);
            req.setHeader(QNetworkRequest::ContentTypeHeader,"application/x-www-form-urlencoded");
            auto *r=job.post.isEmpty() ? net->get(req) : net->post(req,job.post);
            connect(r,&QNetworkReply::readyRead,this,[r](){ if(r->bytesAvailable()>4*1024*1024) r->abort(); });
            connect(r,&QNetworkReply::finished,this,[this,r,job](){
                --active; if(job.osm) --activeOsm;
                int status=r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
                QByteArray bytes=r->readAll(); r->deleteLater();
                const auto doc=QJsonDocument::fromJson(bytes);
                bool valid=job.png ? bytes.startsWith(QByteArray::fromHex("89504e470d0a1a0a")) : !doc.isNull();
                if (job.osm) valid=doc.isObject() && doc.object()["elements"].isArray() && !doc.object().contains("remark");
                if(job.key.startsWith("search-")) valid=doc.object()[job.url.host()=="api.tomtom.com" ? "results" : "features"].isArray();
                if(job.key.startsWith("reverse-")) valid=doc.object()[job.url.host()=="api.tomtom.com" ? "addresses" : "features"].isArray();
                if(job.key=="radarmeta") valid=!doc.object()["radar"].toObject()["past"].toArray().isEmpty();
                if(r->error()!=QNetworkReply::NoError || status!=200 || !valid || bytes.size()>4*1024*1024) {
                    status = status>=400 ? status : 502; bytes.clear();
                    if(failedAt.size()>256) failedAt.clear(); failedAt[job.key]=QDateTime::currentSecsSinceEpoch();
                    qWarning()<<"[places] provider request failed"<<job.key.section('-',0,0)<<status;
                } else { save(job.key,bytes); failedAt.remove(job.key); }
                const auto callbacks=waiters.take(job.key);
                for (const auto &done: callbacks) done(status,bytes);
                pump();
            });
        }
    }
    void request(const QString &key, const QUrl &url, const QByteArray &post, int ttl, const Done &done, bool osm=false, bool png=false) {
        const auto hit=cached(key,ttl); if(!hit.isEmpty()) { done(200,hit); return; }
        if(waiters.contains(key)) { if(waiters[key].size()<32) waiters[key].append(done); else done(503,{}); return; }
        if (waiters.size()>=36 || QDateTime::currentSecsSinceEpoch()-failedAt.value(key,0)<60) { done(503,{}); return; }
        waiters[key]={done}; queue.enqueue({key,url,post,osm,png}); pump();
    }
    static void send(const Socket &s,int status,const QByteArray &bytes,const QByteArray &mime="application/json") {
        if(!s || s->state()!=QAbstractSocket::ConnectedState) return;
        QByteArray head="HTTP/1.1 "+QByteArray::number(status)+(status==200 ? " OK\r\n" : " Unavailable\r\n");
        head+="Content-Type: "+mime+"\r\nContent-Length: "+QByteArray::number(bytes.size())+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        s->write(head);s->write(bytes);s->disconnectFromHost();
    }
    static void json(const Socket &s,int status,const QJsonArray &a) { send(s,status,QJsonDocument(a).toJson(QJsonDocument::Compact)); }
    static void json(const Socket &s,int status,const QJsonObject &o) { send(s,status,QJsonDocument(o).toJson(QJsonDocument::Compact)); }
    static int tileX(double lon,int zoom) { return qBound(0,int(std::floor((lon+180)/360*(1<<zoom))),(1<<zoom)-1); }
    static int tileY(double lat,int zoom) {
        constexpr double pi=3.141592653589793;
        lat=qBound(-85.051128,lat,85.051128)*pi/180;
        return qBound(0,int(std::floor((1-std::asinh(std::tan(lat))/pi)/2*(1<<zoom))),(1<<zoom)-1);
    }
    static double tileLat(int y,int zoom) { return std::atan(std::sinh(3.141592653589793*(1.-2.*y/(1<<zoom))))*180/3.141592653589793; }
    static QString osmQuery(const QString &kind,int x,int y,int zoom) {
        const QString box=QString("(%1,%2,%3,%4)").arg(tileLat(y+1,zoom),0,'f',6).arg(x*360./(1<<zoom)-180,0,'f',6).arg(tileLat(y,zoom),0,'f',6).arg((x+1)*360./(1<<zoom)-180,0,'f',6);
        QString selectors;
        if(kind=="signals") selectors="node[highway~\"^(traffic_signals|stop)$\"]"+box+";";
        else if(kind=="cams") selectors="node[highway=speed_camera]"+box+";node[enforcement~\"^(maxspeed|average_speed|traffic_signals)$\"]"+box+";";
        else selectors="nwr[amenity~\"^(restaurant|fast_food|cafe|bar|pub|biergarten|ice_cream|fuel|charging_station|pharmacy|hospital|clinic|doctors|dentist|cinema|theatre|school|university|college|place_of_worship|car_wash|bus_station|marketplace)$\"]"+box+";nwr[shop]"+box+";nwr[tourism]"+box+";nwr[leisure]"+box+";nwr[historic]"+box+";nwr[natural=beach]"+box+";nwr[railway=station]"+box+";nwr[aeroway=aerodrome]"+box+";";
        return "[out:json][timeout:10];("+selectors+");out center 2500;";
    }
    void osm(const Socket &socket,const QUrlQuery &q,const QString &kind) {
        double south,west,north,east,lat=0,lon=0,radius=3500;
        if(kind=="cams") {
            if(!position(q,lat,lon) || (q.hasQueryItem("r") && !number(q,"r",radius))) { json(socket,400,QJsonObject{{"items",QJsonArray()},{"pending",0}});return; }
            radius=qBound(300.,radius,6000.); double dy=radius/111320.,dx=radius/(111320*std::max(.1,std::cos(lat*3.141592653589793/180)));
            south=qMax(-85.051128,lat-dy);north=qMin(85.051128,lat+dy);west=qMax(-180.,lon-dx);east=qMin(180.,lon+dx);
        } else if(!number(q,"s",south)||!number(q,"w",west)||!number(q,"n",north)||!number(q,"e",east)||!coordinate(south,west)||!coordinate(north,east)||south>=north||west>=east||north-south>2||east-west>3) {
            json(socket,400,QJsonObject{{kind=="pois" ? "pois" : "items",QJsonArray()},{"pending",0}});return;
        }
        const int zoom=kind=="cams" ? 12 : 14;
        const int x0=tileX(west,zoom),x1=tileX(east,zoom),y0=tileY(north,zoom),y1=tileY(south,zoom);
        QList<QPair<int,int>> tiles;
        const int cx=(x0+x1)/2,cy=(y0+y1)/2;
        // Bound both iteration and network cost for a very large or polar viewport.
        for(int y=qMax(y0,cy-3);y<=qMin(y1,cy+3);++y) for(int x=qMax(x0,cx-3);x<=qMin(x1,cx+3);++x) tiles.append({x,y});
        std::sort(tiles.begin(),tiles.end(),[cx,cy](auto a,auto b){return std::abs(a.first-cx)+std::abs(a.second-cy)<std::abs(b.first-cx)+std::abs(b.second-cy);});
        bool truncated=tiles.size()>12 || (x1-x0+1)*(y1-y0+1)>12;
        QJsonArray all;int pending=0;bool stale=false;
        const int ttl=kind=="signals" ? 30*86400 : 7*86400;
        for(const auto &[x,y]: tiles.mid(0,12)) {
            const QString key=kind+"-"+QString::number(x)+"-"+QString::number(y);
            auto bytes=cached(key,ttl);
            if(bytes.isEmpty()) {
                ++pending; bytes=cached(key,kind=="signals" ? 60*86400 : 14*86400);stale=stale||!bytes.isEmpty();
                const auto query=osmQuery(kind,x,y,zoom); QUrlQuery post;post.addQueryItem("data",query);
                const QStringList servers={"https://overpass-api.de/api/interpreter","https://overpass.kumi.systems/api/interpreter","https://overpass.private.coffee/api/interpreter"};
                request(key,QUrl(servers[server++%servers.size()]),post.query(QUrl::FullyEncoded).toUtf8(),ttl,[](int,const QByteArray&){},true);
            }
            for(const auto &entry:osmItems(bytes,kind)) {
                const auto item=entry.toObject();double a=item["lat"].toDouble(),b=item["lon"].toDouble();
                if(a<south||a>north||b<west||b>east||(kind=="cams"&&distance(lat,lon,a,b)*1000>radius)) continue;
                all.append(item);
            }
        }
        QList<QJsonObject> sorted;for(const auto &v:all) sorted.append(v.toObject());
        std::sort(sorted.begin(),sorted.end(),[](const auto &a,const auto &b){return a["tier"].toInt()<b["tier"].toInt();});
        QJsonArray out;QSet<QString> seen,cells;
        for(const auto &item: sorted) {
            const auto key=item["id"].toString(item["kind"].toString()+QString::number(item["lat"].toDouble(),'f',6)+","+QString::number(item["lon"].toDouble(),'f',6));
            if(seen.contains(key)) continue; seen.insert(key);
            if(kind=="pois") {
                const QString cell=QString::number(int(std::floor(item["lat"].toDouble()*4000)))+":"+QString::number(int(std::floor(item["lon"].toDouble()*4000)))+":"+QString::number(item["tier"].toInt());
                if(cells.contains(cell)) continue;cells.insert(cell);
            }
            out.append(item);if(out.size()>=(kind=="pois" ? 500 : 400)) break;
        }
        json(socket,200,QJsonObject{{kind=="pois" ? "pois" : "items",out},{"pending",pending},{"stale",stale},{"truncated",truncated},{"source","OpenStreetMap"}});
    }

    static QUrl parameters(QUrl url,const QList<QPair<QString,QString>> &items) {
        QUrlQuery query;for(const auto &p:items) query.addQueryItem(p.first,p.second);url.setQuery(query);return url;
    }
    static QString key(const char *name) {return QString::fromUtf8(qgetenv(name)).trimmed();}
    static QJsonArray places(const QByteArray &bytes,bool tomtom,double lat,double lon) {
        QJsonArray out;
        const auto root=QJsonDocument::fromJson(bytes).object();
        for(const auto &entry:root[tomtom ? "results" : "features"].toArray()) {
            const auto p=entry.toObject(),poi=p["poi"].toObject(),address=p["address"].toObject();
            double a,b;QString name,addr;
            if(tomtom) {
                const auto point=p["position"].toObject();if(!point["lat"].isDouble()||!point["lon"].isDouble()) continue;
                a=point["lat"].toDouble();b=point["lon"].toDouble();
                name=poi["name"].toString();addr=address["freeformAddress"].toString();
                if(name.isEmpty()) name=p["type"]=="Geography" ? address["municipality"].toString(addr) : addr;
            } else {
                auto center=p["center"].toArray();if(center.size()<2||!center[0].isDouble()||!center[1].isDouble()) continue;
                b=center[0].toDouble();a=center[1].toDouble();name=p["text"].toString();addr=p["place_name"].toString(name);
            }
            if(!coordinate(a,b)||name.trimmed().isEmpty()) continue;
            QString category;
            const auto classifications=poi["classifications"].toArray();
            if(!classifications.isEmpty()) {
                const auto names=classifications[0].toObject()["names"].toArray();
                if(!names.isEmpty()) category=names[0].toObject()["name"].toString();
            }
            out.append(QJsonObject{{"name",name.trimmed()},{"address",addr},{"lat",a},{"lon",b},
                {"distanceKm",std::round(distance(lat,lon,a,b)*10)/10},{"category",category},
                {"phone",poi["phone"].toString()},{"website",poi["url"].toString()},
                {"isBusiness",!poi.isEmpty()},{"prices",QJsonValue::Null}});
            if(out.size()>=20) break;
        }
        return out;
    }
    void maptilerSearch(const Socket &s,const QString &text,double lat,double lon) {
        const auto api=key("MAPTILER_KEY");if(api.isEmpty()){json(s,503,QJsonArray());return;}
        const auto base=QUrl::fromEncoded("https://api.maptiler.com/geocoding/"+QUrl::toPercentEncoding(text)+".json");
        auto url=parameters(base,{{"key",api},{"proximity",QString::number(lon,'f',5)+","+QString::number(lat,'f',5)},
            {"limit","10"},{"language",QLocale::system().name().section('_',0,0)}});
        request("search-"+hash(url.toEncoded()),url,{},600,[this,s,lat,lon](int status,const QByteArray &bytes){
            const auto root=QJsonDocument::fromJson(bytes).object();
            json(s,status==200 && root["features"].isArray() ? 200 : status==200 ? 502 : status,places(bytes,false,lat,lon));
        });
    }
    void search(const Socket &s,const QUrlQuery &q,bool nearby) {
        double lat,lon;if(!position(q,lat,lon)){json(s,400,QJsonArray());return;}
        const QString text=q.queryItemValue("q").trimmed(),kind=q.queryItemValue("type");
        if(!nearby && (text.size()<2||text.size()>200)){json(s,text.size()>200 ? 400 : 200,QJsonArray());return;}
        if(nearby && kind!="fuel" && kind!="restaurant"){json(s,400,QJsonArray());return;}
        const auto api=key("TOMTOM_KEY");
        if(api.isEmpty()){if(nearby) json(s,503,QJsonArray());else maptilerSearch(s,text,lat,lon);return;}
        const QUrl base=nearby ? QUrl("https://api.tomtom.com/search/2/nearbySearch/.json") : QUrl::fromEncoded("https://api.tomtom.com/search/2/search/"+QUrl::toPercentEncoding(text)+".json");
        auto items=QList<QPair<QString,QString>>{{"key",api},{"lat",QString::number(lat,'f',5)},{"lon",QString::number(lon,'f',5)},
            {"limit","20"},{"language",QLocale::system().bcp47Name()}};
        if(nearby){items.emplaceBack("radius","15000");items.emplaceBack("categorySet",kind=="fuel" ? "7311" : "7315");}
        else items.emplaceBack("typeahead","true");
        const auto url=parameters(base,items);
        request("search-"+hash(url.toEncoded()),url,{},600,[this,s,text,lat,lon,nearby](int status,const QByteArray &bytes){
            if(status!=200 && !nearby && !key("MAPTILER_KEY").isEmpty()){maptilerSearch(s,text,lat,lon);return;}
            const auto root=QJsonDocument::fromJson(bytes).object();
            json(s,status==200 && root["results"].isArray() ? 200 : status==200 ? 502 : status,places(bytes,true,lat,lon));
        });
    }
    void reverse(const Socket &s,const QUrlQuery &q) {
        double lat,lon;const QJsonObject empty{{"street",""},{"city",""}};
        if(!position(q,lat,lon)){json(s,400,empty);return;}
        const auto mt=key("MAPTILER_KEY"),tt=key("TOMTOM_KEY");
        if(mt.isEmpty()&&tt.isEmpty()){json(s,503,empty);return;}
        const bool maptiler=!mt.isEmpty();
        auto url=parameters(QUrl(maptiler ? QString("https://api.maptiler.com/geocoding/%1,%2.json").arg(lon,0,'f',4).arg(lat,0,'f',4) :
            QString("https://api.tomtom.com/search/2/reverseGeocode/%1,%2.json").arg(lat,0,'f',4).arg(lon,0,'f',4)),
            {{"key",maptiler ? mt : tt},{"language",QLocale::system().name().section('_',0,0)}});
        request("reverse-"+hash(url.toEncoded()),url,{},600,[this,s,maptiler,empty](int status,const QByteArray &bytes){
            if(status!=200){json(s,status,empty);return;}
            const auto root=QJsonDocument::fromJson(bytes).object();QString street,city;
            if(maptiler) {
                const auto features=root["features"].toArray();
                if(!root["features"].isArray()){json(s,502,empty);return;}
                if(!features.isEmpty()) {
                    const auto top=features[0].toObject();street=top["text"].toString();
                    for(const auto &prefix:{"municipality","place"}) for(const auto &v:top["context"].toArray()) {
                        const auto c=v.toObject();if(city.isEmpty()&&c["id"].toString().startsWith(prefix))city=c["text"].toString();
                    }
                }
            } else {
                const auto addresses=root["addresses"].toArray();
                if(!root["addresses"].isArray()){json(s,502,empty);return;}
                if(!addresses.isEmpty()){const auto a=addresses[0].toObject()["address"].toObject();street=a["streetName"].toString();city=a["municipality"].toString();}
            }
            json(s,200,QJsonObject{{"street",street},{"city",city}});
        });
    }
    void radarFallback(const Socket &s,int z,int x,int y) {
        const auto api=key("OWM_KEY");if(api.isEmpty()){send(s,503,{} ,"image/png");return;}
        const auto url=parameters(QUrl(QString("https://tile.openweathermap.org/map/precipitation_new/%1/%2/%3.png").arg(z).arg(x).arg(y)),{{"appid",api}});
        request("radar-"+hash(url.toEncoded()+QByteArray::number(QDateTime::currentSecsSinceEpoch()/300)),url,{},300,
            [s](int status,const QByteArray &bytes){send(s,status,bytes,"image/png");},false,true);
    }
    void radar(const Socket &s,const QString &path) {
        const auto match=QRegularExpression("^/weather-tile/([0-7])/([0-9]{1,3})/([0-9]{1,3})$").match(path);
        if(!match.hasMatch()){send(s,400,{});return;}
        const int z=match.captured(1).toInt(),x=match.captured(2).toInt(),y=match.captured(3).toInt();
        if(x>=(1<<z)||y>=(1<<z)){send(s,400,{});return;}
        request("radarmeta",QUrl("https://api.rainviewer.com/public/weather-maps.json"),{},120,
            [this,s,z,x,y](int status,const QByteArray &bytes){
                auto past=QJsonDocument::fromJson(bytes).object()["radar"].toObject()["past"].toArray();
                if(status!=200||past.isEmpty()){radarFallback(s,z,x,y);return;}
                const auto frame=past.last().toObject();const auto framePath=frame["path"].toString();
                const double age=QDateTime::currentSecsSinceEpoch()-frame["time"].toDouble();
                // Provider metadata is data, never permission to fetch an arbitrary host/path.
                if(!QRegularExpression("^/v2/radar/[A-Za-z0-9_-]{8,64}$").match(framePath).hasMatch()||age< -300||age>7200){radarFallback(s,z,x,y);return;}
                const auto url=QUrl(QString("https://tilecache.rainviewer.com%1/256/%2/%3/%4/2/1_0.png").arg(framePath).arg(z).arg(x).arg(y));
                request("radar-"+hash(url.toEncoded()),url,{},300,[this,s,z,x,y](int code,const QByteArray &data){
                    if(code==200)send(s,200,data,"image/png");else radarFallback(s,z,x,y);
                },false,true);
            });
    }
public:
    Services(QObject *parent,QNetworkAccessManager *network,const QString &cache):QObject(parent),net(network),cacheDir(cache+"/places_v1") { QDir().mkpath(cacheDir); }
    bool handle(const Socket &s,const QUrl &url) {
        const auto p=url.path();const QUrlQuery q(url);
        if(p=="/map-pois"||p=="/map-signals"||p=="/map-speedcams") { osm(s,q,p=="/map-pois" ? "pois" : p=="/map-signals" ? "signals" : "cams");return true; }
        if(p=="/poi-search"||p=="/nearby-places") { search(s,q,p=="/nearby-places");return true; }
        if(p=="/reverse-geocode") { reverse(s,q);return true; }
        if(p.startsWith("/weather-tile/")) { radar(s,p);return true; }
        return false;
    }
};
}
