// Bedienung mit dem Dreh-Druck-Steller des W204 (COMAND-Knopf), ohne Volta-Seiten einzeln umzubauen:
// alle antippbaren Elemente auf dem Bildschirm werden gesammelt, ein Rahmen markiert das aktuelle.
//   Drehen (TAB / Shift+TAB)        -> naechstes / vorheriges Element in Lesereihenfolge
//   Schieben (Pfeiltasten)          -> naechstes Element in diese Richtung
//   Druecken (Enter/Return/Select)  -> Tipp in die Mitte des Elements
// Texteingaben behalten ihre Tasten. Ein echter Fingertipp blendet den Rahmen aus.
#include <QCoreApplication>
#include <QDebug>
#include <QGuiApplication>
#include <QKeyEvent>
#include <QMouseEvent>
#include <QPainter>
#include <QPointer>
#include <QQuickItem>
#include <QQuickPaintedItem>
#include <QQuickWindow>
#include <QTimer>
#include <QSet>
#include <algorithm>
#include <functional>
#include <cmath>
#ifdef Q_OS_ANDROID
#include <QJniEnvironment>
#include <QJniObject>
#endif

namespace {

class KnobFrame final : public QQuickPaintedItem
{
public:
    explicit KnobFrame(QQuickItem *parent) : QQuickPaintedItem(parent)
    {
        setZ(1e9);
        setAntialiasing(true);
        setAcceptedMouseButtons(Qt::NoButton);
        setVisible(false);
    }
    void paint(QPainter *p) override
    {
        p->setRenderHint(QPainter::Antialiasing);
        QPen pen(QColor(0x4F, 0xC3, 0xF7), 4);
        p->setPen(pen);
        p->setBrush(QColor(0x4F, 0xC3, 0xF7, 40));
        p->drawRoundedRect(QRectF(2, 2, width() - 4, height() - 4), 12, 12);
    }
};

class KnobNavigator final : public QObject
{
public:
    explicit KnobNavigator(QObject *parent) : QObject(parent)
    {
        m_hide.setSingleShot(true);
        m_hide.setInterval(12000);
        connect(&m_hide, &QTimer::timeout, this, [this]() { showFrame(false); });
        m_follow.setInterval(150);
        connect(&m_follow, &QTimer::timeout, this, [this]() { placeFrame(); });
    }

    bool eventFilter(QObject *o, QEvent *e) override
    {
        auto *w = qobject_cast<QQuickWindow *>(o);
        if (!w) return QObject::eventFilter(o, e);
        switch (e->type()) {
        case QEvent::KeyPress: {
            auto *k = static_cast<QKeyEvent *>(e);
            logKey(k);
            if (handle(w, k)) { m_swallow.insert(k->key()); return true; }
            break;
        }
        case QEvent::KeyRelease: {
            auto *k = static_cast<QKeyEvent *>(e);
            if (!k->isAutoRepeat() && m_swallow.remove(k->key())) return true;
            if (k->isAutoRepeat() && m_swallow.contains(k->key())) return true;
            break;
        }
        case QEvent::MouseButtonPress:
        case QEvent::TouchBegin:
            if (!m_synth) showFrame(false);   // Finger benutzt -> Rahmen weg
            break;
        default:
            break;
        }
        return QObject::eventFilter(o, e);
    }

private:
    static bool textFocus(QQuickWindow *w)
    {
        QQuickItem *f = w->activeFocusItem();
        return f && (f->inherits("QQuickTextInput") || f->inherits("QQuickTextEdit"));
    }

    bool handle(QQuickWindow *w, QKeyEvent *k)
    {
        const int key = k->key();
        enum { None, Next, Prev, Up, Down, Left, Right, Press } act = None;
        switch (key) {
        case Qt::Key_Tab: act = (k->modifiers() & Qt::ShiftModifier) ? Prev : Next; break;
        case Qt::Key_Backtab: act = Prev; break;
        case Qt::Key_Up: act = Up; break;
        case Qt::Key_Down: act = Down; break;
        case Qt::Key_Left: act = Left; break;
        case Qt::Key_Right: act = Right; break;
        case Qt::Key_Enter: case Qt::Key_Return: case Qt::Key_Select: act = Press; break;
        default: return false;
        }
        if (textFocus(w)) return false;
        if (k->isAutoRepeat() && act == Press) return true;
        m_win = w;

        const QList<QQuickItem *> items = candidates(w);
        if (items.isEmpty()) return false;
        const bool wasVisible = m_frame && m_frame->isVisible();
        QQuickItem *cur = m_cur && items.contains(m_cur.data()) ? m_cur.data() : nullptr;

        if (act == Press) {
            if (cur && wasVisible) click(w, cur);
            else select(nearestTo(items, m_lastPos));
            return true;
        }
        // erster Dreh/Schub nach einer Pause: nur den Rahmen zeigen, nicht gleich springen
        if (!cur || !wasVisible) { select(cur ? cur : nearestTo(items, m_lastPos)); return true; }

        if (act == Next || act == Prev) {
            QList<QQuickItem *> order = items;
            std::stable_sort(order.begin(), order.end(), [](QQuickItem *a, QQuickItem *b) {
                const QPointF ca = centre(a), cb = centre(b);
                const int ra = int(ca.y() / 48), rb = int(cb.y() / 48);
                return ra != rb ? ra < rb : ca.x() < cb.x();
            });
            const int i = int(order.indexOf(cur));
            const int n = int(order.size());
            select(order.at((i + (act == Next ? 1 : -1) + n) % n));
            return true;
        }
        const QPointF dir = act == Up ? QPointF(0, -1) : act == Down ? QPointF(0, 1) : act == Left ? QPointF(-1, 0) : QPointF(1, 0);
        const QPointF c = centre(cur);
        QQuickItem *best = nullptr;
        double bestScore = 1e18;
        for (QQuickItem *it : items) {
            if (it == cur) continue;
            const QPointF d = centre(it) - c;
            const double along = d.x() * dir.x() + d.y() * dir.y();
            if (along < 8) continue;
            const double across = std::abs(d.x() * dir.y() - d.y() * dir.x());
            const double score = along + 2.5 * across;
            if (score < bestScore) { bestScore = score; best = it; }
        }
        if (best) select(best);
        else restartHide();
        return true;
    }

    static QPointF centre(QQuickItem *it) { return it->mapToScene(QPointF(it->width() / 2, it->height() / 2)); }
    static QRectF sceneRect(QQuickItem *it) { return it->mapRectToScene(QRectF(0, 0, it->width(), it->height())); }

    static QQuickItem *nearestTo(const QList<QQuickItem *> &items, QPointF p)
    {
        QQuickItem *best = nullptr;
        double bd = 1e18;
        for (QQuickItem *it : items) {
            const QPointF d = centre(it) - p;
            const double v = d.x() * d.x() + d.y() * d.y();
            if (v < bd) { bd = v; best = it; }
        }
        return best;
    }

    static bool clickable(QQuickItem *it)
    {
        if (it->inherits("QQuickMouseArea")) {
            const QVariant b = it->property("acceptedButtons");
            return !b.isValid() || b.toInt() != 0;
        }
        if (it->inherits("QQuickAbstractButton")) return true;
        for (QObject *c : it->children())
            if (c->inherits("QQuickTapHandler") && c->property("enabled").toBool()) return true;
        return false;
    }

    // Oberstes "sichtbares" Element an einem Punkt (fuer verdeckte Elemente)
    static QQuickItem *topAt(QQuickItem *it, const QPointF &scenePt)
    {
        QList<QQuickItem *> kids = it->childItems();
        std::stable_sort(kids.begin(), kids.end(), [](QQuickItem *a, QQuickItem *b) { return a->z() < b->z(); });
        for (int i = int(kids.size()) - 1; i >= 0; --i) {
            QQuickItem *c = kids.at(i);
            if (!c->isVisible() || c->opacity() < 0.05 || qobject_cast<KnobFrame *>(c)) continue;
            if (!c->contains(c->mapFromScene(scenePt))) { if (c->clip()) continue; }
            if (QQuickItem *hit = topAt(c, scenePt)) return hit;
        }
        if (!it->contains(it->mapFromScene(scenePt))) return nullptr;
        if (it->inherits("QQuickRectangle")) {
            const QColor col = it->property("color").value<QColor>();
            if (col.alphaF() > 0.5) return it;
        }
        if (it->inherits("QQuickImage") || it->inherits("QQuickText") || clickable(it)) return it;
        return nullptr;
    }

    static bool ancestorOf(QQuickItem *a, QQuickItem *b)
    {
        for (QQuickItem *p = b; p; p = p->parentItem()) if (p == a) return true;
        return false;
    }

    static void collect(QQuickItem *it, const QRectF &screen, QList<QQuickItem *> &out)
    {
        if (!it->isVisible() || !it->isEnabled() || it->opacity() < 0.05) return;
        if (qobject_cast<KnobFrame *>(it)) return;
        if (clickable(it)) {
            const QRectF r = sceneRect(it);
            if (r.width() >= 20 && r.height() >= 20 && screen.contains(r.center())
                && r.width() * r.height() < 0.45 * screen.width() * screen.height())
                out << it;
        }
        for (QQuickItem *c : it->childItems()) collect(c, screen, out);
    }

    QList<QQuickItem *> candidates(QQuickWindow *w) const
    {
        QQuickItem *root = w->contentItem();
        const QRectF screen(0, 0, w->width(), w->height());
        // offenes Popup/Menue (Overlay): nur dessen Inhalt
        QQuickItem *scope = root;
        std::function<QQuickItem *(QQuickItem *)> overlay = [&](QQuickItem *it) -> QQuickItem * {
            if (it->inherits("QQuickOverlay")) {
                for (QQuickItem *c : it->childItems())
                    if (c->isVisible() && c->width() > 0 && c->height() > 0) return it;
                return nullptr;
            }
            for (QQuickItem *c : it->childItems()) if (QQuickItem *o = overlay(c)) return o;
            return nullptr;
        };
        if (QQuickItem *o = overlay(root)) scope = o;
        QList<QQuickItem *> all;
        collect(scope, screen, all);
        QList<QQuickItem *> out;
        for (QQuickItem *it : all) {
            const QPointF c = centre(it);
            QQuickItem *top = topAt(root, c);
            if (!top || ancestorOf(it, top) || ancestorOf(top, it)
                || (it->parentItem() && ancestorOf(it->parentItem(), top)))
                out << it;
        }
        return out;
    }

    void select(QQuickItem *it)
    {
        if (!it) return;
        m_cur = it;
        m_lastPos = centre(it);
        showFrame(true);
    }

    void showFrame(bool on)
    {
        if (on) {
            if (!m_win) return;
            if (!m_frame || m_frame->parentItem() != m_win->contentItem()) {
                delete m_frame;
                m_frame = new KnobFrame(m_win->contentItem());
            }
            placeFrame();
            m_frame->setVisible(true);
            m_follow.start();
            restartHide();
        } else {
            if (m_frame) m_frame->setVisible(false);
            m_follow.stop();
        }
    }

    void restartHide() { m_hide.start(); }

    void placeFrame()
    {
        if (!m_frame) return;
        if (!m_cur || !m_cur->isVisible()) { showFrame(false); return; }
        const QRectF r = sceneRect(m_cur).adjusted(-6, -6, 6, 6);
        const QPointF p = m_frame->parentItem()->mapFromScene(r.topLeft());
        m_frame->setPosition(p);
        m_frame->setSize(r.size());
        m_frame->update();
    }

    void click(QQuickWindow *w, QQuickItem *it)
    {
        const QPointF p = centre(it);
        const QPointF g = w->mapToGlobal(p);
        m_synth = true;
        QMouseEvent press(QEvent::MouseButtonPress, p, p, g, Qt::LeftButton, Qt::LeftButton, Qt::NoModifier);
        QCoreApplication::sendEvent(w, &press);
        QMouseEvent release(QEvent::MouseButtonRelease, p, p, g, Qt::LeftButton, Qt::NoButton, Qt::NoModifier);
        QCoreApplication::sendEvent(w, &release);
        m_synth = false;
        restartHide();
        // nach dem Tipp kann sich die Seite aendern: Rahmen kurz danach neu setzen
        QTimer::singleShot(400, this, [this]() {
            if (!m_win) return;
            const QList<QQuickItem *> items = candidates(m_win);
            if (m_cur && items.contains(m_cur.data())) placeFrame();
            else if (!items.isEmpty()) select(nearestTo(items, m_lastPos));
        });
    }

    void logKey(QKeyEvent *k)
    {
        if (k->isAutoRepeat() || m_logged > 300) return;
        ++m_logged;
        qInfo().noquote() << QStringLiteral("[knob] Taste 0x%1 text='%2' mods=0x%3 nativeKey=%4 scan=%5")
                                 .arg(k->key(), 0, 16).arg(k->text()).arg(k->modifiers().toInt(), 0, 16)
                                 .arg(k->nativeVirtualKey()).arg(k->nativeScanCode());
    }

    QPointer<QQuickWindow> m_win;
    QPointer<QQuickItem> m_cur;
    QPointer<KnobFrame> m_frame;
    QPointF m_lastPos{0, 0};
    QTimer m_hide, m_follow;
    QSet<int> m_swallow;
    bool m_synth = false;
    int m_logged = 0;
};

} // namespace

void voltaPortRegisterKnob()
{
    QTimer::singleShot(0, qApp, []() { qApp->installEventFilter(new KnobNavigator(qApp)); });
#ifdef Q_OS_ANDROID
    QTimer::singleShot(3000, qApp, []() {
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>("de/jarvis/volta/KnobInput", "install", "(Landroid/app/Activity;)V",
                                           QNativeInterface::QAndroidApplication::context().object());
        if (env.checkAndClearExceptions()) qWarning() << "[knob] key logging could not start";
        else qInfo() << "[knob] knob input active (volta_keys.txt)";
    });
#endif
}
