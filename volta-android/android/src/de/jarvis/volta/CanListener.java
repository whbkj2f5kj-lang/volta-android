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
import java.util.Locale;

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
    }

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
                else if (code == 2) frame(name + " gepackt", data.createByteArray());
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
                register(b, ADD_RAW, "CAN");
                register(b, ADD_CARBODY, "Fahrzeug");
                register(b, ADD_DOOR, "Tueren");
                register(b, ADD_AC, "Klima");
                register(b, ADD_RADAR, "Radar");
                register(b, ADD_SWC, "Lenkwinkel");
                register(b, ADD_ORIGINAL_STATE, "Original");
                register(b, ADD_TURN, "Blinker");
            }
            @Override public void onServiceDisconnected(ComponentName n) { log("CAN-Dienst getrennt"); }
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
}
