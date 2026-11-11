// Android-Port von Volta OS: Ersatz fuer den Kartenteil von services/map_proxy.py.
// Volta holt Kartenstil, Kacheln und Schriften ueber einen lokalen Dienst auf 127.0.0.1:9098
// (im Original ein Python/Flask-Programm, das es auf Android nicht gibt). Dieser Teil bildet
// die Routen nach, die die Karte selbst braucht:
//   /ping  /style.json  /tilejson/<id>  /tile/<id>/z/x/y  /sprite/<pfad>  /fonts/<pfad>
//   /sat-tile/z/x/y  /static/<datei>
// Mit MAPTILER_KEY (+ MAPBOX_TOKEN) wird derselbe Stil wie im Original geladen und genauso
// umgebaut. Ohne Schluessel gibt es als Ausweichloesung die freien OpenFreeMap-Stile
// (OpenStreetMap-Daten, kein Schluessel noetig) - sieht anders aus als Voltas eigener Stil.
// Places/radar routes: volta_places.h. Fuel prices are not included.
// GPL-3.0-or-later
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDebug>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QHostAddress>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QSaveFile>
#include <QSet>
#include <QStandardPaths>
#include <QTcpServer>
#include <QTcpSocket>
#include <QUrl>
#include <QUrlQuery>
#include <functional>
#include <memory>
#include "volta_places.h"

namespace {

const char kSelf[] = "http://127.0.0.1:9098";

QString md5(const QString &s)
{
    return QString::fromLatin1(QCryptographicHash::hash(s.toUtf8(), QCryptographicHash::Md5).toHex());
}

QString envKey(const char *name) { return QString::fromUtf8(qgetenv(name)).trimmed(); }

// Linienbreite vervielfachen: Zahl, altes {"stops": [[z, v], ...]} oder ["interpolate"/"step", ...]
QJsonValue scaleWidth(const QJsonValue &w, double k)
{
    if (w.isDouble()) return w.toDouble() * k;
    if (w.isObject() && w.toObject().contains(QLatin1String("stops"))) {
        QJsonObject o = w.toObject();
        QJsonArray out;
        const QJsonArray stops = o.value(QLatin1String("stops")).toArray();
        for (const QJsonValue &st : stops) {
            QJsonArray pair = st.toArray();
            if (pair.size() >= 2 && pair.at(1).isDouble()) pair[1] = pair.at(1).toDouble() * k;
            out.append(pair);
        }
        o[QLatin1String("stops")] = out;
        return o;
    }
    if (w.isArray()) {
        QJsonArray a = w.toArray();
        const QString head = a.isEmpty() ? QString() : a.at(0).toString();
        if (head == QLatin1String("interpolate") || head == QLatin1String("step")) {
            int start = head == QLatin1String("interpolate") ? 3 : 2;
            if (head == QLatin1String("step") && a.size() > 2 && a.at(2).isDouble()) {
                a[2] = a.at(2).toDouble() * k;
                start = 3;
            }
            for (int i = start + 1; i < a.size(); i += 2)
                if (a.at(i).isDouble()) a[i] = a.at(i).toDouble() * k;
            return a;
        }
    }
    return w;
}

// wie _compat_qt_maplibre(): Eigenschaften, die MapLibre fuer Qt noch nicht kennt
void compatQtMapLibre(QJsonObject &data)
{
    QJsonArray layers = data.value(QLatin1String("layers")).toArray();
    QSet<QString> used;
    for (int i = 0; i < layers.size(); ++i) {
        QJsonObject layer = layers.at(i).toObject();
        used.insert(layer.value(QLatin1String("source")).toString());
        if (!layer.value(QLatin1String("layout")).isObject()) continue;
        QJsonObject lay = layer.value(QLatin1String("layout")).toObject();
        bool changed = false;
        for (const char *kind : {"icon", "text"}) {
            const QString ov = QLatin1String(kind) + QLatin1String("-overlap");
            const QString allow = QLatin1String(kind) + QLatin1String("-allow-overlap");
            if (!lay.contains(ov)) continue;
            const QJsonValue v = lay.take(ov);
            changed = true;
            if (!lay.contains(allow) && v.isString()) lay[allow] = (v.toString() == QLatin1String("always"));
        }
        if (changed) {
            layer[QLatin1String("layout")] = lay;
            layers[i] = layer;
        }
    }
    data[QLatin1String("layers")] = layers;
    QJsonObject sources = data.value(QLatin1String("sources")).toObject();
    const QStringList names = sources.keys();
    for (const QString &name : names) {
        const QJsonObject src = sources.value(name).toObject();
        const QString type = src.value(QLatin1String("type")).toString();
        if ((type == QLatin1String("vector") || type == QLatin1String("raster"))
            && !src.contains(QLatin1String("tiles")) && !src.contains(QLatin1String("url")) && !used.contains(name))
            sources.remove(name);
    }
    data[QLatin1String("sources")] = sources;
}

// wie get_style(): baut den geladenen Stil so um, wie es das Original tut
QByteArray transformStyle(const QByteArray &raw, const QString &s, const QUrlQuery &q, bool keyed)
{
    QJsonParseError err;
    const QJsonDocument doc = QJsonDocument::fromJson(raw, &err);
    if (!doc.isObject()) {
        qWarning().noquote() << "[mapproxy] style is not JSON:" << err.errorString();
        return {};
    }
    QJsonObject data = doc.object();
    if (data.value("version").toInt() != 8 || !data.value("sources").isObject() || !data.value("layers").isArray()) {
        qWarning() << "[mapproxy] invalid map style schema";
        return {};
    }
    QJsonObject sources = data.value(QLatin1String("sources")).toObject();
    QJsonArray layers = data.value(QLatin1String("layers")).toArray();

    // Relief (raster-dem / hillshade): Qt MapLibre zeigt es nicht an -> entfernen
    QStringList dem;
    for (auto it = sources.begin(); it != sources.end(); ++it)
        if (it.key() == QLatin1String("terrain_rgb")
            || it.value().toObject().value(QLatin1String("type")).toString() == QLatin1String("raster-dem"))
            dem << it.key();
    if (!dem.isEmpty()) {
        for (const QString &n : dem) sources.remove(n);
        QJsonArray kept;
        for (const QJsonValue &lv : layers) {
            const QJsonObject l = lv.toObject();
            if (dem.contains(l.value(QLatin1String("source")).toString())) continue;
            if (l.value(QLatin1String("type")).toString() == QLatin1String("hillshade")) continue;
            kept.append(l);
        }
        layers = kept;
        data.remove(QLatin1String("terrain"));
    }

    // mapbox://-Quellen und -Schriften laufen ueber diesen Dienst (der den Schluessel anhaengt)
    for (auto it = sources.begin(); it != sources.end(); ++it) {
        QJsonObject src = it.value().toObject();
        const QString url = src.value(QLatin1String("url")).toString();
        if (url.startsWith(QLatin1String("mapbox://"))) {
            src[QLatin1String("url")] = QLatin1String(kSelf) + QLatin1String("/tilejson/") + url.mid(9);
            it.value() = src;
        }
    }
    const QString glyphs = data.value(QLatin1String("glyphs")).toString();
    if (glyphs.startsWith(QLatin1String("mapbox://fonts/")))
        data[QLatin1String("glyphs")] = QLatin1String(kSelf) + QLatin1String("/fonts/") + glyphs.mid(15);

    if (s == QLatin1String("sat") && keyed) {
        QJsonObject satSrc;
        satSrc[QLatin1String("type")] = QLatin1String("raster");
        satSrc[QLatin1String("tiles")] = QJsonArray{QLatin1String(kSelf) + QLatin1String("/sat-tile/{z}/{x}/{y}?src=maptiler")};
        satSrc[QLatin1String("tileSize")] = 256;
        satSrc[QLatin1String("attribution")] = QString::fromUtf8("\xC2\xA9 MapTiler");
        sources[QLatin1String("google-sat")] = satSrc;

        QJsonObject satPaint{{QLatin1String("raster-resampling"), QLatin1String("linear")},
                             {QLatin1String("raster-saturation"), 0.18}};
        const bool night = q.queryItemValue(QLatin1String("n")) == QLatin1String("1");
        if (night) {
            satPaint[QLatin1String("raster-brightness-min")] = 0.0;
            satPaint[QLatin1String("raster-brightness-max")] = 0.38;
            satPaint[QLatin1String("raster-saturation")] = 0.16;
            satPaint[QLatin1String("raster-contrast")] = 0.35;
        }
        const QJsonObject satLayer{{QLatin1String("id"), QLatin1String("google-sat-layer")},
                                   {QLatin1String("type"), QLatin1String("raster")},
                                   {QLatin1String("source"), QLatin1String("google-sat")},
                                   {QLatin1String("minzoom"), 0},
                                   {QLatin1String("maxzoom"), 22},
                                   {QLatin1String("paint"), satPaint}};
        QJsonArray out;
        out.append(satLayer);
        if (night)
            out.append(QJsonObject{{QLatin1String("id"), QLatin1String("night-tint")},
                                   {QLatin1String("type"), QLatin1String("background")},
                                   {QLatin1String("paint"), QJsonObject{{QLatin1String("background-color"), QLatin1String("#050D24")},
                                                                        {QLatin1String("background-opacity"), 0.24}}}});
        for (const QJsonValue &lv : layers) {
            QJsonObject layer = lv.toObject();
            const QString type = layer.value(QLatin1String("type")).toString();
            const QString id = layer.value(QLatin1String("id")).toString();
            if (type == QLatin1String("background") || type == QLatin1String("fill") || type == QLatin1String("fill-extrusion"))
                continue;
            QJsonObject paint = layer.value(QLatin1String("paint")).toObject();
            if (type == QLatin1String("line")) {
                if (id != QLatin1String("Road network")) continue;
                paint[QLatin1String("line-color")] = QLatin1String("#D3D3D3");
                paint[QLatin1String("line-opacity")] = 0.85;
                if (night) {
                    // breiter, schwacher Lichtschein unter der Strasse, davor die warme Linie
                    QJsonObject halo = layer;
                    halo[QLatin1String("id")] = (id.isEmpty() ? QStringLiteral("road") : id) + QLatin1String("-night-glow");
                    const QJsonValue baseW = paint.contains(QLatin1String("line-width")) ? paint.value(QLatin1String("line-width"))
                                                                                         : QJsonValue(1.5);
                    halo[QLatin1String("paint")] = QJsonObject{{QLatin1String("line-color"), QLatin1String("#FFB347")},
                                                               {QLatin1String("line-opacity"), 0.22},
                                                               {QLatin1String("line-blur"), 4},
                                                               {QLatin1String("line-width"), scaleWidth(baseW, 3)}};
                    out.append(halo);
                    paint[QLatin1String("line-color")] = QLatin1String("#FFD9A0");
                    paint[QLatin1String("line-opacity")] = 0.6;
                    paint[QLatin1String("line-blur")] = 0.6;
                }
                layer[QLatin1String("paint")] = paint;
            } else if (type == QLatin1String("symbol")) {
                if (night) {
                    paint[QLatin1String("text-color")] = QLatin1String("#F4F4F4");
                    paint[QLatin1String("text-halo-color")] = QLatin1String("#05070C");
                    paint[QLatin1String("text-halo-width")] = 1.8;
                    paint[QLatin1String("text-halo-blur")] = 0.4;
                } else {
                    paint[QLatin1String("text-color")] = QLatin1String("#FFFFFF");
                    paint[QLatin1String("text-halo-color")] = QLatin1String("#000000");
                    paint[QLatin1String("text-halo-width")] = 2.2;
                    paint[QLatin1String("text-halo-blur")] = 0;
                }
                layer[QLatin1String("paint")] = paint;
            }
            out.append(layer);
        }
        layers = out;
    }
    if (q.queryItemValue(QLatin1String("w")) == QLatin1String("1")) {
        sources["weather-radar"] = QJsonObject{{"type","raster"},{"tileSize",256},{"minzoom",0},{"maxzoom",7},
            {"tiles",QJsonArray{"http://127.0.0.1:9098/weather-tile/{z}/{x}/{y}"}},
            {"attribution","Weather radar: RainViewer / OpenWeather"}};
        QJsonObject layer{{"id","weather-layer"},{"type","raster"},{"source","weather-radar"},
            {"paint",QJsonObject{{"raster-opacity",0.65},{"raster-resampling","linear"},{"raster-fade-duration",0}}}};
        int index=layers.size();
        for(int i=0;i<layers.size();++i) if(layers[i].toObject()["type"]=="symbol"){index=i;break;}
        layers.insert(index,layer);
    }

    data[QLatin1String("sources")] = sources;
    data[QLatin1String("layers")] = layers;
    compatQtMapLibre(data);

    const bool poi = q.queryItemValue(QLatin1String("poi")) == QLatin1String("1");
    layers = data.value(QLatin1String("layers")).toArray();
    for (int i = 0; i < layers.size(); ++i) {
        QJsonObject layer = layers.at(i).toObject();
        if (layer.value(QLatin1String("type")).toString() != QLatin1String("symbol")) continue;
        const QString sl = layer.value(QLatin1String("source-layer")).toString().toLower();
        const QString id = layer.value(QLatin1String("id")).toString().toLower();
        if (!sl.contains(QLatin1String("poi")) && !id.contains(QLatin1String("poi"))) continue;
        QJsonObject lay = layer.value(QLatin1String("layout")).toObject();
        lay[QLatin1String("visibility")] = poi ? QLatin1String("visible") : QLatin1String("none");
        layer[QLatin1String("layout")] = lay;
        layers[i] = layer;
    }
    data[QLatin1String("layers")] = layers;
    return QJsonDocument(data).toJson(QJsonDocument::Compact);
}

class MapProxy : public QObject
{
public:
    explicit MapProxy(QObject *parent, QNetworkAccessManager *network = nullptr, const QString &cache = {}, quint16 port = 9098)
        : QObject(parent), m_net(network ? network : new QNetworkAccessManager(this))
    {
        m_cache = cache.isEmpty() ? QStandardPaths::writableLocation(QStandardPaths::CacheLocation) + QStringLiteral("/volta_mapproxy") : cache;
        QDir().mkpath(m_cache);
        m_places = new VoltaPlaces::Services(this,m_net,m_cache);
        connect(&m_srv, &QTcpServer::newConnection, this, [this]() {
            while (QTcpSocket *s = m_srv.nextPendingConnection()) accept(s);
        });
        if (m_srv.listen(QHostAddress::LocalHost, port))
            qInfo().noquote() << "[mapproxy] listening on 127.0.0.1:9098, cache" << m_cache;
        else
            qWarning().noquote() << "[mapproxy] cannot listen on 9098:" << m_srv.errorString();
    }

    quint16 port() const { return m_srv.serverPort(); }

private:
    using Done = std::function<void(int status, const QByteArray &data)>;

    void accept(QTcpSocket *s)
    {
        connect(s, &QTcpSocket::disconnected, s, &QObject::deleteLater);
        auto buf = std::make_shared<QByteArray>();
        auto handled = std::make_shared<bool>(false);
        connect(s, &QTcpSocket::readyRead, this, [this, s, buf, handled]() {
            if (*handled) { s->readAll(); return; }
            buf->append(s->readAll());
            const int end = buf->indexOf("\r\n\r\n");
            if (end < 0) {
                if (buf->size() > 32768) s->abort();
                return;
            }
            *handled = true;
            const QList<QByteArray> first = buf->left(buf->indexOf("\r\n")).split(' ');
            if (first.size() < 2) { reply(s, 400, "text/plain", "bad request"); return; }
            // Anfragen von Webseiten abweisen (wie local_security.py): nur die Oberflaeche darf fragen
            const QByteArray headers = buf->left(end).toLower();
            if (headers.contains("\r\nsec-fetch-site: cross-site") || headers.contains("\r\nsec-fetch-site: same-site")) {
                reply(s, 403, "text/plain", "forbidden");
                return;
            }
            if (first.at(0) != "GET") { reply(s, 404, "text/plain", "not available in the Android port"); return; }
            route(s, QUrl::fromEncoded("http://127.0.0.1:9098" + first.at(1)));
        });
    }

    static void reply(const QPointer<QTcpSocket> &s, int code, const QByteArray &type, const QByteArray &body,
                      const QByteArray &cacheControl = "no-store")
    {
        if (!s || s->state() != QAbstractSocket::ConnectedState) return;
        const char *text = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 403 ? "Forbidden"
                         : code == 400 ? "Bad Request" : code == 204 ? "No Content" : "Bad Gateway";
        QByteArray head = "HTTP/1.1 " + QByteArray::number(code) + ' ' + text + "\r\n";
        head += "Content-Type: " + type + "\r\n";
        head += "Content-Length: " + QByteArray::number(body.size()) + "\r\n";
        head += "Cache-Control: " + cacheControl + "\r\n";
        head += "Connection: close\r\n\r\n";
        s->write(head);
        s->write(body);
        s->disconnectFromHost();
    }

    // Holt eine Adresse, mit Ablage auf der Platte (wie _fetch_cached): frische Kopie direkt,
    // bei Netzfehler notfalls die alte Kopie.
    void fetch(const QString &kind, const QString &name, const QUrl &url, qint64 ttlSec, const Done &done)
    {
        const QString dir = m_cache + QLatin1Char('/') + kind;
        const QString path = dir + QLatin1Char('/') + name;
        const QFileInfo fi(path);
        if (fi.exists() && (ttlSec <= 0 || fi.lastModified().secsTo(QDateTime::currentDateTime()) < ttlSec)) {
            QFile f(path);
            if (f.open(QIODevice::ReadOnly)) { done(200, f.readAll()); return; }
        }
        QNetworkRequest req(url);
        req.setTransferTimeout(12000);
        req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
        req.setRawHeader("User-Agent", "VoltaOS-android-port");
        QNetworkReply *r = m_net->get(req);
        connect(r, &QNetworkReply::finished, this, [r, dir, path, done]() {
            r->deleteLater();
            const int status = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
            if (r->error() == QNetworkReply::NoError && status == 200) {
                const QByteArray data = r->readAll();
                QDir().mkpath(dir);
                QSaveFile f(path);
                if (f.open(QIODevice::WriteOnly)) { f.write(data); f.commit(); }
                done(200, data);
                return;
            }
            QFile old(path);
            if (old.open(QIODevice::ReadOnly)) { done(200, old.readAll()); return; }   // alte Kopie besser als nichts
            static int shown = 0;
            if (shown++ < 12)
                qWarning().noquote() << "[mapproxy] fetch failed:" << r->url().host() << r->url().path().left(60)
                                     << "status" << status << "network error" << int(r->error());
            done(status > 0 ? status : 502, {});
        });
    }

    static void offlineStyle(const QPointer<QTcpSocket> &socket, const QString &style)
    {
        // Valid, empty map style: network failure must not become a startup dependency.
        const QJsonObject background{{"id", "offline-background"}, {"type", "background"},
            {"paint", QJsonObject{{"background-color", style == "light" ? "#E5E5E5" : "#171717"}}}};
        const QJsonObject data{{"version", 8}, {"name", "Maps temporarily unavailable"},
            {"sources", QJsonObject()}, {"layers", QJsonArray{background}}};
        qWarning() << "[mapproxy] no map style reachable; showing background only (map data unavailable)";
        reply(socket, 200, "application/json", QJsonDocument(data).toJson(QJsonDocument::Compact));
    }

    void fallbackStyle(const QPointer<QTcpSocket> &socket, const QString &style, const QUrlQuery &query)
    {
        const QUrl url(style == "light" ? "https://tiles.openfreemap.org/styles/liberty"
                                           : "https://tiles.openfreemap.org/styles/dark");
        fetch("style", md5(url.toString()), url, 1800, [socket, style, query](int status, const QByteArray &raw) {
            const QByteArray data = status == 200 ? transformStyle(raw, style, query, false) : QByteArray();
            if (data.isEmpty()) offlineStyle(socket, style);
            else reply(socket, 200, "application/json", data, "no-store, no-cache, must-revalidate, max-age=0");
        });
    }

    void route(QTcpSocket *sock, const QUrl &url)
    {
        const QPointer<QTcpSocket> s(sock);
        const QString path = url.path();
        const QUrlQuery q(url);
        const QString mapbox = envKey("MAPBOX_TOKEN");
        const QString maptiler = envKey("MAPTILER_KEY");

        if (path == QLatin1String("/ping")) { reply(s, 200, "text/plain", "ok"); return; }
        if (m_places->handle(s,url)) return;

        if (path == QLatin1String("/style.json")) {
            const QString style = q.queryItemValue(QLatin1String("s")).isEmpty() ? QStringLiteral("dark")
                                                                                 : q.queryItemValue(QLatin1String("s"));
            if (style == QLatin1String("mars")) { reply(s, 404, "text/plain", "not available in the Android port"); return; }
            const bool keyed = !maptiler.isEmpty();
            QUrl src;
            if (keyed)
                src = QUrl(QStringLiteral("https://api.maptiler.com/maps/%1/style.json?key=%2")
                               .arg(style == QLatin1String("light") ? QStringLiteral("01a059f2-13fd-7093-8f14-673805645690")
                                                                    : QStringLiteral("01a06434-8dc2-7506-b480-5e73d816c10d"),
                                    maptiler));
            else
                src = QUrl(style == QLatin1String("light") ? QStringLiteral("https://tiles.openfreemap.org/styles/liberty")
                                                           : QStringLiteral("https://tiles.openfreemap.org/styles/dark"));
            if (!m_saidStyle) {
                m_saidStyle = true;
                qInfo().noquote() << "[mapproxy] map style:" << (keyed ? "Volta original (MapTiler key present)"
                                                                       : "OpenFreeMap fallback (no MAPTILER_KEY)")
                                  << "- Mapbox token" << (mapbox.isEmpty() ? "missing" : "present");
            }
            // A saved key does not grant access to Volta's private/custom style UUIDs.
            // Keep the UI usable when that style is missing, forbidden or malformed.
            const QString id = md5(src.toString());
            if (keyed && m_badStyles.contains(id)) { fallbackStyle(s, style, q); return; }
            fetch(QStringLiteral("style"), id, src, 1800, [this, s, style, q, keyed, id](int st, const QByteArray &raw) {
                const QByteArray out = st == 200 ? transformStyle(raw, style, q, keyed) : QByteArray();
                if (!out.isEmpty()) {
                    reply(s, 200, "application/json", out, "no-store, no-cache, must-revalidate, max-age=0");
                    return;
                }
                if (keyed) {
                    m_badStyles.insert(id);
                    qWarning() << "[mapproxy] keyed map style unavailable; using free map style for this session";
                    fallbackStyle(s, style, q);
                } else offlineStyle(s, style);
            });
            return;
        }

        const QStringList parts = path.split(QLatin1Char('/'), Qt::SkipEmptyParts);
        const QByteArray longCache = "public, max-age=604800";

        if (parts.size() == 2 && parts.at(0) == QLatin1String("tilejson")) {
            const QString id = parts.at(1);
            const QUrl src(QStringLiteral("https://api.mapbox.com/v4/%1.json?secure&access_token=%2").arg(id, mapbox));
            fetch(QStringLiteral("tj"), md5(id) + QStringLiteral(".json"), src, 86400, [s, id](int st, const QByteArray &raw) {
                if (st != 200) { reply(s, st, "application/json", "{\"error\":\"tilejson unreachable\"}"); return; }
                QJsonObject tj = QJsonDocument::fromJson(raw).object();
                if (tj.contains(QLatin1String("tiles")))
                    tj[QLatin1String("tiles")] = QJsonArray{QLatin1String(kSelf) + QLatin1String("/tile/") + id
                                                            + QLatin1String("/{z}/{x}/{y}")};
                reply(s, 200, "application/json", QJsonDocument(tj).toJson(QJsonDocument::Compact));
            });
            return;
        }
        if (parts.size() == 5 && parts.at(0) == QLatin1String("tile")) {
            const QString id = parts.at(1), z = parts.at(2), x = parts.at(3), y = parts.at(4);
            const QUrl src(QStringLiteral("https://api.mapbox.com/v4/%1/%2/%3/%4.vector.pbf?access_token=%5").arg(id, z, x, y, mapbox));
            fetch(QStringLiteral("vec"), QStringLiteral("%1_%2_%3_%4.pbf").arg(md5(id).left(8), z, x, y), src, 30 * 86400,
                  [s, longCache](int st, const QByteArray &d) {
                      if (st != 200) { reply(s, st == 404 ? 204 : st, "application/x-protobuf", {}); return; }
                      reply(s, 200, "application/x-protobuf", d, longCache);
                  });
            return;
        }
        if (parts.size() >= 2 && parts.at(0) == QLatin1String("sprite")) {
            const QString sp = path.mid(8);
            const bool json = sp.endsWith(QLatin1String(".json"));
            const QUrl src(QStringLiteral("https://api.mapbox.com/styles/v1/%1?access_token=%2").arg(sp, mapbox));
            fetch(QStringLiteral("sprite"), md5(sp) + (json ? QStringLiteral(".json") : QStringLiteral(".png")), src, 7 * 86400,
                  [s, json, longCache](int st, const QByteArray &d) {
                      if (st != 200) { reply(s, st, "text/plain", {}); return; }
                      reply(s, 200, json ? "application/json" : "image/png", d, longCache);
                  });
            return;
        }
        if (parts.size() >= 2 && parts.at(0) == QLatin1String("fonts")) {
            const QString fp = QString::fromUtf8(url.path(QUrl::FullyEncoded).mid(7).toUtf8());
            const QUrl src = QUrl::fromEncoded(("https://api.mapbox.com/fonts/v1/" + fp + "?access_token=" + mapbox).toUtf8());
            fetch(QStringLiteral("fonts"), md5(fp) + QStringLiteral(".pbf"), src, 0, [s, longCache](int st, const QByteArray &d) {
                if (st != 200) { reply(s, st, "text/plain", {}); return; }
                reply(s, 200, "application/x-protobuf", d, longCache);
            });
            return;
        }
        if (parts.size() == 4 && parts.at(0) == QLatin1String("sat-tile")) {
            const QString z = parts.at(1), x = parts.at(2), y = parts.at(3);
            const QUrl src(QStringLiteral("https://api.maptiler.com/tiles/satellite-v2/%1/%2/%3.jpg?key=%4").arg(z, x, y, maptiler));
            fetch(QStringLiteral("sat"), QStringLiteral("%1_%2_%3.jpg").arg(z, x, y), src, 60 * 86400,
                  [s, longCache](int st, const QByteArray &d) {
                      if (st != 200) { reply(s, st, "text/plain", {}); return; }
                      reply(s, 200, "image/jpeg", d, longCache);
                  });
            return;
        }
        if (parts.size() == 2 && parts.at(0) == QLatin1String("static") && !parts.at(1).contains(QLatin1String(".."))) {
            QFile f(QString::fromUtf8(qgetenv("VOLTA_DIR")) + QStringLiteral("/assets/static/") + parts.at(1));
            if (f.open(QIODevice::ReadOnly)) {
                const QString n = parts.at(1).toLower();
                reply(s, 200, n.endsWith(QLatin1String(".svg")) ? "image/svg+xml" : n.endsWith(QLatin1String(".png")) ? "image/png"
                            : n.endsWith(QLatin1String(".json")) ? "application/json" : "application/octet-stream",
                      f.readAll());
                return;
            }
        }
        // Remaining non-map services (fuel prices, phone destination sharing, etc.).
        if (!m_missing.contains(parts.value(0)) && m_missing.size() < 40) {
            m_missing.insert(parts.value(0));
            qInfo().noquote() << "[mapproxy] not ported yet:" << path;
        }
        reply(s, 404, "application/json", "{\"error\":\"not available in the Android port\"}");
    }

    QTcpServer m_srv;
    QNetworkAccessManager *m_net;
    VoltaPlaces::Services *m_places;
    QSet<QString> m_badStyles;
    QString m_cache;
    QSet<QString> m_missing;
    bool m_saidStyle = false;
};

} // namespace

void voltaPortStartMapProxy()
{
    static MapProxy *proxy = nullptr;
    if (!proxy) proxy = new MapProxy(QCoreApplication::instance());
}
