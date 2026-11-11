package de.jarvis.volta;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * Liest mit, was die MCU des Radios an Android schickt (android.qf.mcu.McuManager, Teil der Radio-Firmware).
 * Ueber diesen Weg laufen auch die unveraenderten Daten der CAN-Box (Befehl 0x41), also mehr als das,
 * was com.qf.vehicle daraus umpackt.
 *
 * NUR LESEN: es wird ausschliesslich RPC_RequestMcuInfoChangedListener aufgerufen.
 * Nie RPC_Send* (RPC_SendCANInfo, RPC_SendMcuMsgData, RPC_SendHeadLightState) - das wuerde zur MCU/CAN-Box schreiben.
 *
 * Rahmen MCU -> Android (aus com.qf.clientsdk.McuConstant):
 *   [0]=FF Start, [1]=FE Richtung MCU->ARM, [3]=Datenlaenge, [4]=Typ, [5]=Befehl, ab [6] Daten.
 *   Befehle: 0x20 Taste, 0x21 Radio, 0x22 Fahrlicht, 0x23 Handbremse, 0x24 Zuendung, 0x25 Version, 0x41 CAN-Box.
 */
final class McuTap {
    private static final String MANAGER = "android.qf.mcu.McuManager";
    private static final String LISTENER = "android.qf.mcu.McuListener";
    /** Einstellung, in die das Radio den Handbremsenstatus schreibt (com.qf.clientsdk.SettingsDBConstants, Tippfehler im Original). */
    private static final String HANDBRAKE_SETTING = "qf.vehilce.handbrake";

    private static Object listener; // starke Referenz, sonst raeumt der Speicherbereiniger den Rueckruf weg
    private static int frames = 0;

    private McuTap() {}

    static void start(final Context app) {
        watchHandbrake(app);
        try {
            Class<?> mgr = Class.forName(MANAGER);
            Class<?> lis = Class.forName(LISTENER);
            describe(mgr);
            describe(lis);
            if (!lis.isInterface()) {
                CanListener.logLine("MCU: McuListener ist keine Schnittstelle, Mitlesen nicht moeglich");
                return;
            }
            Method request = null;
            for (Method m : mgr.getMethods())
                if (m.getName().equals("RPC_RequestMcuInfoChangedListener") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == lis) request = m;
            if (request == null) { CanListener.logLine("MCU: RPC_RequestMcuInfoChangedListener fehlt"); return; }

            Object target = null;
            if (!Modifier.isStatic(request.getModifiers())) {
                target = instance(mgr, app);
                if (target == null) { CanListener.logLine("MCU: kein McuManager-Objekt erhalten"); return; }
            }
            listener = Proxy.newProxyInstance(lis.getClassLoader(), new Class<?>[]{lis}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method m, Object[] args) {
                    String n = m.getName();
                    if (n.equals("hashCode")) return System.identityHashCode(proxy);
                    if (n.equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                    if (n.equals("toString")) return "VoltaMcuTap";
                    if (args != null) for (Object a : args) if (a instanceof byte[]) onFrame((byte[]) a);
                    return defaultValue(m.getReturnType());
                }
            });
            request.invoke(target, listener);
            CanListener.logLine("MCU: Mitlesen angemeldet (" + (target == null ? "statisch" : target.getClass().getName()) + ")");
        } catch (ClassNotFoundException e) {
            CanListener.logLine("MCU: Schnittstelle nicht vorhanden (" + e.getMessage() + ")");
        } catch (Throwable t) {
            CanListener.logLine("MCU: Anmeldung fehlgeschlagen: " + t);
        }
    }

    private static void onFrame(byte[] b) {
        if (b == null) return;
        frames++;
        int cmd = b.length > 5 && (b[0] & 0xff) == 0xFF ? b[5] & 0xff : -1;
        // Schluessel: Befehl + erste Datenbytes, damit je Nachrichtenart nur Aenderungen ins Log gehen
        StringBuilder key = new StringBuilder("MCU:").append(cmd);
        for (int i = 6; i < Math.min(b.length, cmd == 0x41 ? 9 : 7); i++) key.append(':').append(b[i] & 0xff);
        String kind = cmd == 0x41 ? "MCU CAN-Box" : cmd < 0 ? "MCU ?" : String.format("MCU %02X", cmd);
        CanListener.logFrame(key.toString(), kind, b);
        if (cmd == 0x23 && b.length > 6) CanListener.putValue("handbrakeMcu", b[6] & 0xff);
        if (frames == 1) CanListener.logLine("MCU: erster Rahmen empfangen");
    }

    private static Object instance(Class<?> mgr, Context app) {
        String[] names = {"getInstance", "getDefault", "get"};
        for (String n : names) {
            for (Method m : mgr.getMethods()) {
                if (!m.getName().equals(n) || !Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 0) return m.invoke(null);
                    if (p.length == 1 && p[0].isAssignableFrom(Context.class)) return m.invoke(null, app);
                } catch (Throwable t) { CanListener.logLine("MCU: " + n + " fehlgeschlagen: " + t); }
            }
        }
        try { return mgr.getConstructor(Context.class).newInstance(app); } catch (Throwable ignored) {}
        try { return mgr.getConstructor().newInstance(); } catch (Throwable ignored) {}
        Object svc = app.getSystemService("mcu");
        if (mgr.isInstance(svc)) return svc;
        return null;
    }

    /** Einmal ins Log: welche Methoden die Firmware wirklich anbietet (fuer die naechste Auswertung). */
    private static void describe(Class<?> c) {
        StringBuilder sb = new StringBuilder("MCU-API ").append(c.getName()).append(c.isInterface() ? " (Schnittstelle):" : ":");
        for (Method m : c.getDeclaredMethods()) {
            sb.append("\n    ").append(Modifier.isStatic(m.getModifiers()) ? "static " : "").append(m.getName()).append('(');
            Class<?>[] p = m.getParameterTypes();
            for (int i = 0; i < p.length; i++) sb.append(i > 0 ? ", " : "").append(p[i].getSimpleName());
            sb.append(") -> ").append(m.getReturnType().getSimpleName());
        }
        CanListener.logLine(sb.toString());
    }

    private static void watchHandbrake(final Context app) {
        try {
            readHandbrake(app);
            app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(HANDBRAKE_SETTING), false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override public void onChange(boolean self) { readHandbrake(app); }
                    });
        } catch (Throwable t) { CanListener.logLine("Handbremse: Einstellung nicht lesbar: " + t); }
    }

    private static void readHandbrake(Context app) {
        String v = Settings.Global.getString(app.getContentResolver(), HANDBRAKE_SETTING);
        CanListener.logLine("Handbremse (" + HANDBRAKE_SETTING + ") = " + v);
        if (v == null) return;
        if ("true".equalsIgnoreCase(v) || "1".equals(v)) CanListener.putValue("handbrakeSetting", 1);
        else if ("false".equalsIgnoreCase(v) || "0".equals(v)) CanListener.putValue("handbrakeSetting", 0);
    }

    private static Object defaultValue(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class || t == short.class || t == byte.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        return null;
    }
}
