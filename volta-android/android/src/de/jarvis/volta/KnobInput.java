package de.jarvis.volta;

import android.app.Activity;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Dreh-Druck-Steller des W204 (COMAND-Knopf): die CAN-Box des Radios meldet ihn als Android-Tasten.
 * Hier wird jeder Tastendruck und jede Drehbewegung mitgeschrieben (volta_keys.txt), damit wir sehen,
 * was genau ankommt. Einheitlich gemacht fuer Volta (Qt):
 *   Bestaetigen-Varianten (DPAD_CENTER, NUMPAD_ENTER, ...) -> ENTER
 *   NAVIGATE_NEXT / NAVIGATE_PREVIOUS                      -> TAB / Shift+TAB
 *   Drehgeber (MotionEvent ACTION_SCROLL, kein Zeiger)     -> TAB / Shift+TAB je Raste
 * Alles andere geht unveraendert an Qt weiter.
 */
public final class KnobInput {
    private static File file;
    private static int lines = 0;
    private static final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY);

    private KnobInput() {}

    public static void install(final Activity a) {
        File dir = a.getExternalFilesDir(null);
        file = new File(dir != null ? dir : a.getFilesDir(), "volta_keys.txt");
        if (file.length() > 512 * 1024) file.delete();
        log("---- Volta gestartet ----");
        listenToRadio(a);
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    final Window w = a.getWindow();
                    final Window.Callback orig = w.getCallback();
                    if (orig == null || Proxy.isProxyClass(orig.getClass())) return;
                    Window.Callback cb = (Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                            new Class<?>[]{Window.Callback.class}, new InvocationHandler() {
                                @Override public Object invoke(Object p, Method m, Object[] args) throws Throwable {
                                    String n = m.getName();
                                    if (n.equals("dispatchKeyEvent") && args != null && args[0] instanceof KeyEvent) {
                                        KeyEvent e = (KeyEvent) args[0];
                                        log(e);
                                        KeyEvent r = remap(e);
                                        if (r != e) return orig.dispatchKeyEvent(r);
                                    } else if (n.equals("dispatchGenericMotionEvent") && args != null && args[0] instanceof MotionEvent) {
                                        MotionEvent e = (MotionEvent) args[0];
                                        if (e.getAction() == MotionEvent.ACTION_SCROLL && !e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
                                            float v = e.getAxisValue(MotionEvent.AXIS_SCROLL);
                                            if (v == 0) v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                                            log("Drehung " + v + " Quelle=0x" + Integer.toHexString(e.getSource()) + " Geraet=" + deviceName(e.getDeviceId()));
                                            if (v != 0) {
                                                int steps = Math.max(1, Math.round(Math.abs(v)));
                                                for (int i = 0; i < steps; i++) tab(orig, v > 0);
                                                return true;
                                            }
                                        }
                                    }
                                    try { return m.invoke(orig, args); }
                                    catch (InvocationTargetException ex) { throw ex.getCause() != null ? ex.getCause() : ex; }
                                }
                            });
                    w.setCallback(cb);
                    log("Knopf-Eingabe aktiv");
                } catch (Throwable t) { log("Knopf-Eingabe nicht moeglich: " + t); }
            }
        });
    }

    /**
     * Zweiter Weg: die Radio-Firmware verteilt Tasten auch ueber android.qf.util.UtilEventManager
     * (so bekommt z. B. com.qf.ai.bridge sie). Hier NUR mitschreiben, damit wir sehen, ob der Knopf
     * dort ankommt, falls er Volta nicht als normale Taste erreicht.
     */
    private static Object radioListener; // starke Referenz
    private static void listenToRadio(Activity a) {
        try {
            Class<?> mgr = Class.forName("android.qf.util.UtilEventManager");
            Class<?> lis = Class.forName("android.qf.util.UtilEventListener");
            StringBuilder sb = new StringBuilder("Radio-Tasten-API:");
            for (Method m : mgr.getDeclaredMethods()) sb.append(' ').append(m.getName()).append('/').append(m.getParameterTypes().length);
            log(sb.toString());
            if (!lis.isInterface()) { log("UtilEventListener ist keine Schnittstelle"); return; }
            Method reg = null;
            for (Method m : mgr.getMethods())
                if (m.getName().equals("RPC_KeyEventChangedListener") && m.getParameterTypes().length == 1) reg = m;
            if (reg == null) { log("RPC_KeyEventChangedListener fehlt"); return; }
            Object target = null;
            if (!java.lang.reflect.Modifier.isStatic(reg.getModifiers())) {
                for (Method m : mgr.getMethods()) {
                    if (target != null || !java.lang.reflect.Modifier.isStatic(m.getModifiers()) || m.getReturnType() != mgr) continue;
                    Class<?>[] p = m.getParameterTypes();
                    try {
                        if (p.length == 0) target = m.invoke(null);
                        else if (p.length == 1 && p[0].isAssignableFrom(android.content.Context.class)) target = m.invoke(null, a.getApplicationContext());
                    } catch (Throwable ignored) {}
                }
                if (target == null) try { target = mgr.getConstructor(android.content.Context.class).newInstance(a.getApplicationContext()); } catch (Throwable ignored) {}
                if (target == null) try { target = mgr.getConstructor().newInstance(); } catch (Throwable ignored) {}
                if (target == null) { log("kein UtilEventManager-Objekt"); return; }
            }
            radioListener = Proxy.newProxyInstance(lis.getClassLoader(), new Class<?>[]{lis}, new InvocationHandler() {
                @Override public Object invoke(Object p, Method m, Object[] args) {
                    String n = m.getName();
                    if (n.equals("hashCode")) return System.identityHashCode(p);
                    if (n.equals("equals")) return args != null && args[0] == p;
                    if (n.equals("toString")) return "VoltaKnob";
                    StringBuilder sb = new StringBuilder("Radio meldet " + n + ":");
                    if (args != null) for (Object o : args) {
                        KeyEvent k = null;
                        if (o != null) try { Object r = o.getClass().getMethod("getKeyEventInfo").invoke(o); if (r instanceof KeyEvent) k = (KeyEvent) r; } catch (Throwable ignored) {}
                        if (k != null) sb.append(' ').append(KeyEvent.keyCodeToString(k.getKeyCode())).append(" (").append(k.getKeyCode())
                                .append(k.getAction() == KeyEvent.ACTION_DOWN ? " gedrueckt)" : " losgelassen)");
                        else sb.append(' ').append(o);
                    }
                    log(sb.toString());
                    Class<?> rt = m.getReturnType();
                    return rt == boolean.class ? Boolean.FALSE : rt == int.class ? Integer.valueOf(0) : null;
                }
            });
            reg.invoke(target, radioListener);
            log("Radio-Tasten: mitlesen angemeldet");
        } catch (ClassNotFoundException e) {
            log("Radio-Tasten-API nicht vorhanden: " + e.getMessage());
        } catch (Throwable t) {
            log("Radio-Tasten: Anmeldung fehlgeschlagen: " + t);
        }
    }

    private static KeyEvent remap(KeyEvent e) {
        int c = e.getKeyCode(), to = c, meta = e.getMetaState();
        switch (c) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                to = KeyEvent.KEYCODE_ENTER; break;
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:
                to = KeyEvent.KEYCODE_TAB; break;
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
                to = KeyEvent.KEYCODE_TAB; meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON; break;
            default: return e;
        }
        return new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), to, e.getRepeatCount(), meta,
                e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
    }

    /** Eine Raste: TAB (vor) oder Shift+TAB (zurueck) als Druck + Loslassen. */
    private static void tab(Window.Callback cb, boolean forward) {
        long t = SystemClock.uptimeMillis();
        int meta = forward ? 0 : (KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON);
        cb.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, meta));
        cb.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, meta));
    }

    private static void log(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return;
        log("Taste " + KeyEvent.keyCodeToString(e.getKeyCode()) + " (" + e.getKeyCode() + ") scan=" + e.getScanCode()
                + " Quelle=0x" + Integer.toHexString(e.getSource()) + " Geraet=" + deviceName(e.getDeviceId()));
    }

    private static String deviceName(int id) {
        try { InputDevice d = InputDevice.getDevice(id); return d == null ? String.valueOf(id) : d.getName(); }
        catch (Throwable t) { return String.valueOf(id); }
    }

    private static synchronized void log(String s) {
        if (file == null || lines > 3000) return;
        lines++;
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(time.format(new Date()) + "  " + s + "\n");
        } catch (Throwable ignored) {}
    }
}
