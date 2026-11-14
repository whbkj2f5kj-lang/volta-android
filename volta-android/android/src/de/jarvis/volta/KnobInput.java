package de.jarvis.volta;

import android.app.Activity;
import android.content.Context;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import de.jarvis.w204.volta.VendorListeners;
import android.os.SystemClock;
import android.os.Handler;
import android.os.HandlerThread;
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
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoltaKeyLog"); t.setDaemon(true); return t;
    });
    private static final AtomicBoolean vendorStarted = new AtomicBoolean();
    private static WeakReference<Window.Callback> installedCallback = new WeakReference<>(null);
    private static final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY);

    private KnobInput() {}

    public static void install(final Activity a) {
        File dir = a.getExternalFilesDir(null);
        file = new File(dir != null ? dir : a.getFilesDir(), "volta_keys.txt");
        if (file.length() > 512 * 1024) file.delete();
        log("---- Volta gestartet ----");
        if (vendorStarted.compareAndSet(false, true)) {
            HandlerThread thread = new HandlerThread("VoltaKeyService", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            new Handler(thread.getLooper()).post(() -> listenToRadio(a.getApplicationContext()));
        }
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    final Window w = a.getWindow();
                    final Window.Callback orig = w.getCallback();
                    if (orig == null || installedCallback.get() == orig) return;
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
                    installedCallback = new WeakReference<>(cb);
                    log("Knopf-Eingabe aktiv");
                } catch (Throwable t) { log("Knopf-Eingabe nicht moeglich: " + VendorListeners.describe(t)); }
            }
        });
    }

    /**
     * Zweiter Weg: die Radio-Firmware verteilt Tasten auch ueber android.qf.util.UtilEventManager
     * (so bekommt z. B. com.qf.ai.bridge sie). Hier NUR mitschreiben, damit wir sehen, ob der Knopf
     * dort ankommt, falls er Volta nicht als normale Taste erreicht.
     */
    private static Object radioListener; // starke Referenz
    private static void listenToRadio(Context app) {
        try {
            Class<?> mgr = Class.forName("android.qf.util.UtilEventManager");
            Class<?> lis = Class.forName("android.qf.util.UtilEventListener");
            if (!lis.isInterface()) { log("UtilEventListener ist keine Schnittstelle"); return; }
            Method reg = null;
            for (Method m : mgr.getMethods())
                if (m.getName().equals("RPC_KeyEventChangedListener") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == lis) reg = m;
            if (reg == null) { log("RPC_KeyEventChangedListener fehlt"); return; }
            Object candidate = Proxy.newProxyInstance(lis.getClassLoader(), new Class<?>[]{lis}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("hashCode")) return System.identityHashCode(proxy);
                if (name.equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                if (name.equals("toString")) return "VoltaKnob";
                // Diagnostic path only: normal Android events remain the sole navigation source,
                // so firmware callbacks cannot cause duplicate clicks.
                if (args != null) for (Object arg : args) {
                    KeyEvent key = arg instanceof KeyEvent ? (KeyEvent)arg : null;
                    if (key == null && arg != null) try {
                        Object value = arg.getClass().getMethod("getKeyEventInfo").invoke(arg);
                        if (value instanceof KeyEvent) key = (KeyEvent)value;
                    } catch (ReflectiveOperationException ignored) {}
                    if (key != null && !key.isPrintingKey())
                        log("Radio meldet " + name + ": " + KeyEvent.keyCodeToString(key.getKeyCode())
                            + (key.getAction() == KeyEvent.ACTION_DOWN ? " gedrueckt" : " losgelassen"));
                    // Never log arbitrary vendor objects: they can contain typed text.
                }
                return defaultValue(method.getReturnType());
            });
            boolean ok = VendorListeners.subscribe(mgr, reg, candidate, app,
                new String[]{"util_service", "util_event", "utils"}, app::getSystemService,
                message -> log("Radio-Tasten: " + message));
            if (ok) radioListener = candidate;
        } catch (ClassNotFoundException error) {
            log("Radio-Tasten-API nicht vorhanden: " + error.getMessage());
        } catch (Throwable error) {
            log("Radio-Tasten: Anmeldung fehlgeschlagen: " + VendorListeners.describe(error));
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == byte.class) return (byte)0;
        if (type == short.class) return (short)0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char)0;
        return null;
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
        // Getippter Text (Buchstaben, Ziffern, Zeichen) gehoert nicht ins Log - es wird hochgeladen.
        if (e.isPrintingKey()) return;
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
        // Capture receipt time now; disk IO runs away from Android's input thread.
        final String line = time.format(new Date()) + "  " + s + "\n";
        final File target = file;
        io.execute(() -> {
            try (FileWriter writer = new FileWriter(target, true)) { writer.write(line); }
            catch (Throwable ignored) {}
        });
    }
}
