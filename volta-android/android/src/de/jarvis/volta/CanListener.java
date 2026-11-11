package de.jarvis.volta;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.provider.Settings;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * Hoert mit, was die CAN-Box des Radios (App com.qf.vehicle) ueber das Auto meldet.
 * NUR LESEN: es werden ausschliesslich Rueckruf-Anmeldungen (add...CallBack) gesendet,
 * nie forward... oder doCheckSumAndWriteData2Uart4CanBox (das wuerde zur CAN-Box schreiben).
 * Alles landet in Android/data/de.jarvis.volta/files/volta_can_log.txt.
 */
public final class CanListener {
    private static final String FEATURE = "com.qf.vehicle.service.ICanBusServiceFeature";
    private static final String CALLBACK = "com.qf.vehicle.service.ICanBusServiceCallback";
    // Transaktionsnummern aus ICanBusServiceFeature$Stub (com.qf.vehicle)
    private static final int ADD_AC = 3, ADD_DOOR = 5, ADD_SWC = 7, ADD_RADAR = 9, ADD_CARBODY = 11,
            ADD_ORIGINAL_STATE = 15, ADD_RAW = 21, ADD_TURN = 37;

    private static File file;
    private static boolean started = false;
    private static int lines = 0;
    private static final HashMap<String, String> last = new HashMap<>();
    private static final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY);

    public static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        final Context app = ctx.getApplicationContext();
        prefs = app.getSharedPreferences("volta_can", Context.MODE_PRIVATE);
        seen.addAll(prefs.getStringSet("seen", new HashSet<String>()));
        doorSwap = prefs.getBoolean("doorSwap", false);
        File dir = app.getExternalFilesDir(null);
        file = new File(dir != null ? dir : app.getFilesDir(), "volta_can_log.txt");
        if (file.length() > 3L * 1024 * 1024) {
            File alt = new File(file.getParentFile(), "volta_can_log_alt.txt");
            alt.delete();
            file.renameTo(alt);
        }
        log("---- Volta gestartet ----");
        logProps();
        logSettings(app);
        registerBroadcasts(app);
        bind(app);
        McuTap.start(app);
    }

    /** fuer McuTap: in dieselbe Datei schreiben, Aenderungen je Schluessel. */
    static void logLine(String s) { log(s); }
    static void logFrame(String key, String kind, byte[] data) { frameKeyed(key, kind, data); }
    static void putValue(String k, double v) { put(k, v); }

    private static synchronized void log(String s) {
        if (file == null || lines > 20000) return;
        lines++;
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(time.format(new Date()) + "  " + s + "\n");
        } catch (Throwable ignored) {}
    }

    private static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    /** Nur Aenderungen schreiben: je Art (Typ + erste Bytes) die letzte Zeile merken. */
    private static void frame(String kind, byte[] data) {
        String key = kind;
        if (data != null && data.length > 0) key += ":" + String.format("%02X", data[0]) + (data.length > 1 ? String.format("%02X", data[1]) : "")
                + (data.length > 2 ? String.format("%02X", data[2]) : "");
        frameKeyed(key, kind, data);
    }

    private static void frameKeyed(String key, String kind, byte[] data) {
        String h = hex(data);
        synchronized (last) {
            if (h.equals(last.get(key))) return;
            last.put(key, h);
        }
        log(kind + " [" + (data == null ? 0 : data.length) + "] " + h);
    }

    private static void logProps() {
        String[] keys = {"sys.can.connected", "sys.qf.is.acc.on", "sys.qf.backcar_state", "sys.qf.vehicle.headlight_state",
                "sys.qf.backlightkey.state", "persist.can.radio_type", "persist.can.video", "persist.can.backlight",
                "ro.qf.platform", "ro.qf.product.name"};
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class);
            StringBuilder sb = new StringBuilder("Eigenschaften:");
            for (String k : keys) sb.append(" ").append(k).append("=").append(get.invoke(null, k));
            log(sb.toString());
        } catch (Throwable t) { log("Eigenschaften nicht lesbar: " + t); }
    }

    private static void logSettings(Context app) {
        try {
            String pk = Settings.Global.getString(app.getContentResolver(), "KeyAllPackages");
            log("Datenfreigabe KeyAllPackages=" + pk);
            if (pk != null) for (String p : pk.split(",")) {
                if (p.isEmpty()) continue;
                StringBuilder sb = new StringBuilder("  " + p + ":");
                for (String k : new String[]{"KeyShareDoorWindow", "KeyShareCarbodyState", "KeyShareAcState",
                        "KeyShareRadarState", "KeyShareSwcAngle", "KeyShareOriginalCarState"})
                    sb.append(" ").append(k).append("=").append(Settings.Global.getString(app.getContentResolver(), p + k));
                log(sb.toString());
            }
        } catch (Throwable t) { log("Einstellungen nicht lesbar: " + t); }
    }

    private static void registerBroadcasts(Context app) {
        IntentFilter f = new IntentFilter();
        for (String a : new String[]{"com.qf.vehicle.action.DATA_SHARE", "com.qf.vehicle.action.OriginalCarState",
                "com.qf.vehicle.action.TEMP_STRING", "com.qf.vehicle.action.swa", "com.qf.vehicle.action.RADAR",
                "com.qf.vehicle.action.DOOR_WINDOW", "com.qf.vehicle.action.SHOW_CAR_WARNNING",
                "com.qf.action.BACKCAR_START", "com.qf.action.BACKCAR_STOP", "com.qf.action.ACC_ON", "com.qf.action.ACC_OFF",
                "com.qf.action.car_backlight_changed", "com.qf.action.READY_GO_SLEEP"})
            f.addAction(a);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction() == null ? "?" : i.getAction().replace("com.qf.vehicle.action.", "v.").replace("com.qf.action.", "q.");
                if ("com.qf.action.BACKCAR_START".equals(i.getAction())) put("reverseCam", 1);
                if ("com.qf.action.BACKCAR_STOP".equals(i.getAction())) put("reverseCam", 0);
                Bundle ex = i.getExtras();
                if (ex == null) { log("Broadcast " + a); return; }
                for (String k : ex.keySet()) {
                    Object v;
                    try { v = ex.get(k); } catch (Throwable t) { v = "<nicht lesbar: " + t.getClass().getSimpleName() + ">"; }
                    if (v instanceof byte[]) frame("Broadcast " + a + " " + k, (byte[]) v);
                    else log("Broadcast " + a + " " + k + "=" + v);
                }
            }
        }, f);
        log("Broadcast-Empfaenger aktiv");
    }

    /** Eigener Rueckruf mit derselben Schnittstelle wie com.qf.vehicle sie erwartet. */
    private static final class Callback extends Binder {
        private final String name;
        Callback(String name) { this.name = name; attachInterface(null, CALLBACK); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(CALLBACK); return true; }
            try {
                data.enforceInterface(CALLBACK);
                if (code == 1) frame(name + " roh", data.createByteArray());
                else if (code == 2) { byte[] b = data.createByteArray(); frame(name + " gepackt", b); parse(b); }
                if (reply != null) reply.writeNoException();
            } catch (Throwable t) { log(name + " Fehler: " + t); }
            return true;
        }
    }

    private static void bind(Context app) {
        Intent i = new Intent("com.qf.vehicle.service.ACTION_CAN_SERVICE");
        i.setPackage("com.qf.vehicle");
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) {
                log("CAN-Dienst verbunden: " + n.flattenToShortString());
                bound = true;
                register(b, ADD_RAW, "CAN");
                register(b, ADD_CARBODY, "Fahrzeug");
                register(b, ADD_DOOR, "Tueren");
                register(b, ADD_AC, "Klima");
                register(b, ADD_RADAR, "Radar");
                register(b, ADD_SWC, "Lenkwinkel");
                register(b, ADD_ORIGINAL_STATE, "Original");
                register(b, ADD_TURN, "Blinker");
            }
            @Override public void onServiceDisconnected(ComponentName n) { log("CAN-Dienst getrennt"); bound = false; }
        };
        try {
            boolean ok = app.bindService(i, conn, Context.BIND_AUTO_CREATE);
            log("CAN-Dienst anbinden: " + (ok ? "angefragt" : "abgelehnt (nicht freigegeben)"));
        } catch (Throwable t) { log("CAN-Dienst anbinden fehlgeschlagen: " + t); }
    }

    private static void register(IBinder service, int code, String name) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(FEATURE);
            data.writeStrongBinder(new Callback(name));
            service.transact(code, data, reply, 0);
            reply.readException();
            log("angemeldet: " + name);
        } catch (Throwable t) {
            log("Anmeldung " + name + " fehlgeschlagen: " + t);
        } finally { data.recycle(); reply.recycle(); }
    }

    // ── Auswertung der gepackten Pakete (Format QfSdkDataPack aus com.qf.vehicle) ──
    // Kopf: 98 <Typ> <Laenge>, danach die Daten, am Ende ein Pruefbyte.
    private static SharedPreferences prefs;
    private static volatile boolean bound = false, doorSwap = false;
    private static volatile long lastPacket = 0;
    private static final HashMap<String, Double> vals = new HashMap<>();
    private static final HashSet<String> seen = new HashSet<>();

    private static void put(String k, double v) {
        synchronized (vals) {
            vals.put(k, v);
            if (v != 0 && seen.add(k) && prefs != null) prefs.edit().putStringSet("seen", new HashSet<>(seen)).apply();
        }
    }
    private static int u(byte[] p, int i) { return i < p.length ? p[i] & 0xff : 0; }
    private static int bit(byte[] p, int i, int b) { return (u(p, i) >> b) & 1; }
    private static int u16(byte[] p, int i) { return (u(p, i) << 8) | u(p, i + 1); }

    private static void parse(byte[] p) {
        if (p == null || p.length < 4 || u(p, 0) != 0x98) return;
        int type = u(p, 1), n = u(p, 2), d = 3;
        if (p.length < d + n) return;
        lastPacket = android.os.SystemClock.elapsedRealtime();
        switch (type) {
            case 0x02: { // Fahrzeug
                put("highBeam", bit(p, d, 7)); put("lowBeam", bit(p, d, 6)); put("fogFront", bit(p, d, 5)); put("fogRear", bit(p, d, 4));
                put("turnRight", bit(p, d, 3)); put("turnLeft", bit(p, d, 2)); put("hazard", bit(p, d, 1)); put("mainLights", bit(p, d, 0));
                put("parkLights", bit(p, d + 1, 7)); put("widthLights", bit(p, d + 1, 6)); put("reverseLight", bit(p, d + 1, 5));
                put("brakeLight", bit(p, d + 1, 4)); put("warnLight", bit(p, d + 1, 3)); put("espLamp", bit(p, d + 1, 1));
                put("centralLock", bit(p, d + 2, 7)); put("lockFL", bit(p, d + 2, 6)); put("lockFR", bit(p, d + 2, 5));
                put("lockRL", bit(p, d + 2, 4)); put("lockRR", bit(p, d + 2, 3)); put("lockTrunk", bit(p, d + 2, 2));
                put("windowFL", bit(p, d + 2, 1)); put("windowFR", bit(p, d + 2, 0));
                put("windowRL", bit(p, d + 3, 7)); put("windowRR", bit(p, d + 3, 6)); put("sunroof", bit(p, d + 3, 5));
                put("beltDriver", bit(p, d + 3, 3)); put("beltPassenger", bit(p, d + 3, 2)); put("beltRearL", bit(p, d + 3, 1)); put("beltRearR", bit(p, d + 3, 0));
                put("ignition", bit(p, d + 4, 7)); put("clutch", bit(p, d + 4, 6)); put("reverseGear", bit(p, d + 4, 5)); put("handbrake", bit(p, d + 4, 4));
                put("washerLow", bit(p, d + 4, 3)); put("wiper", bit(p, d + 4, 2));
                int speed = u16(p, d + 5);
                if (speed <= 300) put("speed", speed);
                // oberste Bits sind Statusbits (z. B. 0x40 = Motor/Zuendung); 0x42F0 -> 752 U/min Leerlauf
                put("rpm", u16(p, d + 7) & 0x3FFF);
                put("rpmFlags", u(p, d + 7) >> 6);
                put("fuel", u16(p, d + 9));
                put("rest", u16(p, d + 11));
                put("odometerRaw", (u(p, d + 13) << 16) | u16(p, d + 14));
                put("range", u16(p, d + 16));
                put("trip1", u16(p, d + 18)); put("trip2", u16(p, d + 20)); put("trip3", u16(p, d + 22));
                put("consumptionNow", u16(p, d + 49));
                put("avgSpeed1", u16(p, d + 51));
                put("driveTime1", u16(p, d + 57));
                put("consumptionAvg1", u16(p, d + 63));
                break;
            }
            case 0x03: { // Tueren
                int fl = bit(p, d, 7), fr = bit(p, d, 6), rl = bit(p, d, 5), rr = bit(p, d, 4);
                if (doorSwap) { int t = fl; fl = fr; fr = t; t = rl; rl = rr; rr = t; }
                put("doorFL", fl); put("doorFR", fr); put("doorRL", rl); put("doorRR", rr);
                put("trunk", bit(p, d, 3)); put("hood", bit(p, d, 2));
                break;
            }
            case 0x01: { // Klima
                put("acPower", bit(p, d, 7)); put("acCompressor", bit(p, d, 6)); put("acAuto", bit(p, d, 2) | bit(p, d, 5));
                put("acRecirc", bit(p, d, 4)); put("acDual", bit(p, d, 0));
                put("defrostFront", bit(p, d + 1, 7)); put("defrostRear", bit(p, d + 1, 6));
                put("airUp", bit(p, d + 2, 7)); put("airMid", bit(p, d + 2, 6)); put("airDown", bit(p, d + 2, 5));
                put("fan", u(p, d + 2) & 0x0f);
                put("tempLeftRaw", u(p, d + 5)); put("tempRightRaw", u(p, d + 6));
                put("seatHeatL", u(p, d + 8) >> 4); put("seatHeatR", u(p, d + 8) & 0x0f);
                break;
            }
            case 0x04: { // Einparkhilfe: 6 vorne, 6 hinten (Stufen)
                int front = 0, rear = 0;
                for (int i = 0; i < 6; i++) { put("radarF" + i, u(p, d + i)); front = Math.max(front, u(p, d + i)); }
                for (int i = 0; i < 6; i++) { put("radarR" + i, u(p, d + 6 + i)); rear = Math.max(rear, u(p, d + 6 + i)); }
                put("radarFront", front); put("radarRear", rear);
                break;
            }
            default: break;
        }
    }

    public static void setDoorSwap(Context ctx, boolean on) {
        doorSwap = on;
        ctx.getApplicationContext().getSharedPreferences("volta_can", Context.MODE_PRIVATE).edit().putBoolean("doorSwap", on).apply();
    }

    private static String prop(String k) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return String.valueOf(sp.getMethod("get", String.class).invoke(null, k));
        } catch (Throwable t) { return ""; }
    }

    /** Aktueller Stand fuer Volta (JSON): values, seen (welche Felder das Auto schon geliefert hat). */
    public static String snapshot() {
        try {
            JSONObject o = new JSONObject(), v = new JSONObject(), s = new JSONObject();
            synchronized (vals) {
                for (java.util.Map.Entry<String, Double> e : vals.entrySet()) v.put(e.getKey(), e.getValue());
                for (String k : seen) s.put(k, true);
            }
            String rev = prop("sys.qf.backcar_state"), head = prop("sys.qf.vehicle.headlight_state");
            v.put("reverseProp", "1".equals(rev) || "true".equalsIgnoreCase(rev) ? 1 : 0);
            v.put("headlightProp", "1".equals(head) || "true".equalsIgnoreCase(head) ? 1 : 0);
            o.put("bound", bound);
            o.put("doorSwap", doorSwap);
            o.put("values", v);
            o.put("seen", s);
            return o.toString();
        } catch (Throwable t) { return "{}"; }
    }
}
