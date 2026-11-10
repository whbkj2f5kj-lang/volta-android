// Android-Port von Volta OS: Zugriff auf Android-Funktionen, die Volta auf Linux ueber
// NetworkManager, BlueZ, PulseAudio und MPRIS erledigt. Aufgerufen aus C++ (android_system.cpp,
// android_media.cpp); jede Methode liefert JSON als Text. GPL-3.0-or-later
package de.jarvis.volta;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@SuppressWarnings({"deprecation", "MissingPermission"})
public class PortJava {

    // ═══════════════════════ WLAN ═══════════════════════

    private static WifiManager wifi(Context c) {
        return (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    private static String unquote(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        if (s.equals("<unknown ssid>")) return "";
        return s;
    }

    public static String wifiStatus(Context c) {
        JSONObject o = new JSONObject();
        try {
            WifiManager w = wifi(c);
            if (w == null) { o.put("available", false); o.put("radioOn", false); return o.toString(); }
            o.put("available", true);
            boolean on = w.isWifiEnabled();
            o.put("radioOn", on);
            WifiInfo info = on ? w.getConnectionInfo() : null;
            String ssid = info != null && info.getNetworkId() != -1 ? unquote(info.getSSID()) : "";
            o.put("connected", ssid.isEmpty() ? JSONObject.NULL : ssid);
            o.put("signal", info != null && !ssid.isEmpty() ? WifiManager.calculateSignalLevel(info.getRssi(), 101) : JSONObject.NULL);
        } catch (Throwable t) {
            try { o.put("available", false); o.put("error", String.valueOf(t)); } catch (Exception ignored) {}
        }
        return o.toString();
    }

    public static String wifiScan(Context c) {
        JSONObject o = new JSONObject();
        JSONArray nets = new JSONArray();
        try {
            WifiManager w = wifi(c);
            if (w == null) { o.put("networks", nets); o.put("error", "no Wi-Fi"); return o.toString(); }
            w.startScan();
            WifiInfo info = w.getConnectionInfo();
            String current = info != null && info.getNetworkId() != -1 ? unquote(info.getSSID()) : "";
            List<ScanResult> res = w.getScanResults();
            Map<String, JSONObject> best = new HashMap<>();
            if (res != null) for (ScanResult r : res) {
                if (r.SSID == null || r.SSID.isEmpty()) continue;
                int sig = WifiManager.calculateSignalLevel(r.level, 101);
                JSONObject n = best.get(r.SSID);
                if (n != null && n.optInt("signal") >= sig) continue;
                n = new JSONObject();
                n.put("ssid", r.SSID);
                n.put("signal", sig);
                String cap = r.capabilities == null ? "" : r.capabilities;
                n.put("secured", cap.contains("WEP") || cap.contains("PSK") || cap.contains("EAP") || cap.contains("SAE"));
                n.put("connected", r.SSID.equals(current));
                best.put(r.SSID, n);
            }
            for (JSONObject n : best.values()) nets.put(n);
            o.put("networks", sortBySignal(nets));
        } catch (Throwable t) {
            try { o.put("networks", nets); o.put("error", String.valueOf(t)); } catch (Exception ignored) {}
        }
        return o.toString();
    }

    private static JSONArray sortBySignal(JSONArray a) throws Exception {
        java.util.ArrayList<JSONObject> l = new java.util.ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(a.getJSONObject(i));
        java.util.Collections.sort(l, (x, y) -> y.optInt("signal") - x.optInt("signal"));
        JSONArray out = new JSONArray();
        for (JSONObject j : l) out.put(j);
        return out;
    }

    public static String wifiToggle(Context c, boolean on) {
        JSONObject o = new JSONObject();
        try {
            boolean ok = wifi(c).setWifiEnabled(on);
            o.put("ok", ok);
            if (!ok) o.put("message", "Android did not allow it");
        } catch (Throwable t) { try { o.put("ok", false); o.put("message", String.valueOf(t)); } catch (Exception ignored) {} }
        return o.toString();
    }

    public static String wifiConnect(Context c, String ssid, String password) {
        JSONObject o = new JSONObject();
        try {
            WifiManager w = wifi(c);
            int id = -1;
            List<WifiConfiguration> saved = w.getConfiguredNetworks();
            if (saved != null) for (WifiConfiguration cfg : saved)
                if (ssid.equals(unquote(cfg.SSID))) { id = cfg.networkId; break; }
            if (password != null && !password.isEmpty() || id == -1) {
                WifiConfiguration cfg = new WifiConfiguration();
                cfg.SSID = "\"" + ssid + "\"";
                if (password == null || password.isEmpty()) {
                    cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                } else {
                    cfg.preSharedKey = "\"" + password + "\"";
                }
                int nid = w.addNetwork(cfg);
                if (nid != -1) id = nid;
            }
            if (id == -1) { o.put("ok", false); o.put("message", "network not accepted"); return o.toString(); }
            w.disconnect();
            boolean ok = w.enableNetwork(id, true);
            w.reconnect();
            o.put("ok", ok);
            if (!ok) o.put("message", "connection refused");
        } catch (Throwable t) { try { o.put("ok", false); o.put("message", String.valueOf(t)); } catch (Exception ignored) {} }
        return o.toString();
    }

    public static String wifiForget(Context c, String ssid) {
        JSONObject o = new JSONObject();
        try {
            WifiManager w = wifi(c);
            boolean ok = false;
            List<WifiConfiguration> saved = w.getConfiguredNetworks();
            if (saved != null) for (WifiConfiguration cfg : saved)
                if (ssid.equals(unquote(cfg.SSID))) ok = w.removeNetwork(cfg.networkId) || ok;
            o.put("ok", ok);
            // Android erlaubt nur Netze zu loeschen, die diese App selbst angelegt hat
            if (!ok) o.put("message", "Android only lets Volta forget networks it added itself");
        } catch (Throwable t) { try { o.put("ok", false); o.put("message", String.valueOf(t)); } catch (Exception ignored) {} }
        return o.toString();
    }

    public static String wifiDisconnect(Context c) {
        JSONObject o = new JSONObject();
        try { o.put("ok", wifi(c).disconnect()); } catch (Throwable t) { try { o.put("ok", false); } catch (Exception ignored) {} }
        return o.toString();
    }

    // ═══════════════════════ BLUETOOTH ═══════════════════════

    private static final Map<String, BluetoothDevice> found = new HashMap<>();
    private static final Map<String, Integer> foundRssi = new HashMap<>();
    private static boolean receiverOn = false;

    private static synchronized void ensureReceiver(Context c) {
        if (receiverOn) return;
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        c.getApplicationContext().registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                synchronized (PortJava.class) {
                    found.put(d.getAddress(), d);
                    foundRssi.put(d.getAddress(), (int) i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE));
                }
            }
        }, f);
        receiverOn = true;
    }

    public static String btStatus(Context c) {
        JSONObject o = new JSONObject();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            o.put("available", a != null);
            o.put("poweredOn", a != null && a.isEnabled());
            o.put("discovering", a != null && a.isDiscovering());
        } catch (Throwable t) { try { o.put("available", false); o.put("poweredOn", false); o.put("discovering", false); } catch (Exception ignored) {} }
        return o.toString();
    }

    private static String iconFor(BluetoothDevice d) {
        BluetoothClass bc = d.getBluetoothClass();
        if (bc == null) return "";
        switch (bc.getMajorDeviceClass()) {
            case BluetoothClass.Device.Major.PHONE: return "phone";
            case BluetoothClass.Device.Major.AUDIO_VIDEO: return "audio-card";
            case BluetoothClass.Device.Major.COMPUTER: return "computer";
            default: return "";
        }
    }

    public static synchronized String btDevices(Context c) {
        JSONObject o = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            HashSet<String> seen = new HashSet<>();
            if (a != null) {
                for (BluetoothDevice d : a.getBondedDevices()) {
                    list.put(devJson(d, true, null));
                    seen.add(d.getAddress());
                }
                for (Map.Entry<String, BluetoothDevice> e : found.entrySet()) {
                    if (seen.contains(e.getKey())) continue;
                    list.put(devJson(e.getValue(), false, foundRssi.get(e.getKey())));
                }
            }
            o.put("devices", list);
        } catch (Throwable t) { try { o.put("devices", list); o.put("error", String.valueOf(t)); } catch (Exception ignored) {} }
        return o.toString();
    }

    private static JSONObject devJson(BluetoothDevice d, boolean paired, Integer rssi) throws Exception {
        JSONObject j = new JSONObject();
        String name = d.getName();
        j.put("address", d.getAddress());
        j.put("name", name == null || name.isEmpty() ? d.getAddress() : name);
        j.put("paired", paired || d.getBondState() == BluetoothDevice.BOND_BONDED);
        j.put("connected", isConnected(d));
        j.put("trusted", paired);
        j.put("rssi", rssi == null || rssi == Short.MIN_VALUE ? JSONObject.NULL : rssi);
        j.put("icon", iconFor(d));
        j.put("path", d.getAddress());
        return j;
    }

    private static boolean isConnected(BluetoothDevice d) {
        try { return (Boolean) d.getClass().getMethod("isConnected").invoke(d); } catch (Throwable t) { return false; }
    }

    public static String btToggle(Context c, boolean on) {
        JSONObject o = new JSONObject();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            o.put("ok", a != null && (on ? a.enable() : a.disable()));
        } catch (Throwable t) { try { o.put("ok", false); o.put("message", String.valueOf(t)); } catch (Exception ignored) {} }
        return o.toString();
    }

    public static String btScan(Context c, boolean on) {
        JSONObject o = new JSONObject();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) { o.put("ok", false); o.put("message", "no Bluetooth"); return o.toString(); }
            if (on) {
                ensureReceiver(c);
                synchronized (PortJava.class) { found.clear(); foundRssi.clear(); }
                o.put("ok", a.startDiscovery());
            } else {
                o.put("ok", a.cancelDiscovery());
            }
        } catch (Throwable t) { try { o.put("ok", false); o.put("message", String.valueOf(t)); } catch (Exception ignored) {} }
        return o.toString();
    }

    // pair / forget gehen; verbinden/trennen der Profile (Telefon, Musik) macht die Bluetooth-App des Radios
    public static String btAction(Context c, String action, String address) {
        JSONObject o = new JSONObject();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice d = a.getRemoteDevice(address);
            boolean ok;
            String msg = "";
            if (action.equals("pair")) {
                a.cancelDiscovery();
                ok = d.createBond();
            } else if (action.equals("forget")) {
                ok = (Boolean) d.getClass().getMethod("removeBond").invoke(d);
            } else if (action.equals("connect") || action.equals("disconnect")) {
                ok = false;
                msg = "Use the radio's Bluetooth app to connect the phone";
            } else {
                ok = false;
                msg = "unknown";
            }
            o.put("ok", ok);
            o.put("message", msg);
        } catch (Throwable t) { try { o.put("ok", false); o.put("message", String.valueOf(t)); } catch (Exception ignored) {} }
        return o.toString();
    }

    // ═══════════════════════ LAUTSTAERKE / HELLIGKEIT ═══════════════════════

    public static int getVolume(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return max > 0 ? Math.round(100f * am.getStreamVolume(AudioManager.STREAM_MUSIC) / max) : -1;
        } catch (Throwable t) { return -1; }
    }

    public static void setVolume(Context c, int percent) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int idx = Math.max(0, Math.min(max, Math.round(percent * max / 100f)));
            am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0);
        } catch (Throwable ignored) {}
    }

    // Systemhelligkeit, wenn erlaubt (sonst nur fuer Voltas Fenster). Rueckgabe: "system", "window" oder "none"
    public static String setBrightness(Context c, int percent) {
        final float f = Math.max(0.02f, Math.min(1f, percent / 100f));
        String mode = "none";
        try {
            if (Settings.System.canWrite(c)) {
                Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.round(f * 255));
                mode = "system";
            }
        } catch (Throwable ignored) {}
        if (c instanceof Activity) {
            final Activity act = (Activity) c;
            final boolean system = mode.equals("system");
            act.runOnUiThread(() -> {
                WindowManager.LayoutParams lp = act.getWindow().getAttributes();
                lp.screenBrightness = system ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : f;
                act.getWindow().setAttributes(lp);
            });
            if (!system) mode = "window";
        }
        return mode;
    }

    public static boolean canWriteSettings(Context c) {
        try { return Settings.System.canWrite(c); } catch (Throwable t) { return false; }
    }

    // ═══════════════════════ MUSIK (Android-Mediensitzungen) ═══════════════════════

    private static String lastArtKey = "";

    public static boolean mediaAccess(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(c.getPackageName());
    }

    private static MediaController pick(Context c) {
        MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        List<MediaController> list = msm.getActiveSessions(new ComponentName(c, MediaListener.class));
        if (list == null || list.isEmpty()) return null;
        for (MediaController m : list) {
            PlaybackState s = m.getPlaybackState();
            if (s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING)) return m;
        }
        return list.get(0);   // die zuletzt aktive Sitzung steht vorne
    }

    // aktueller Titel als JSON; {"access":false} ohne Benachrichtigungszugriff, {} ohne Sitzung
    public static String mediaState(Context c, String artDir) {
        JSONObject o = new JSONObject();
        try {
            if (!mediaAccess(c)) { o.put("access", false); return o.toString(); }
            o.put("access", true);
            MediaController m = pick(c);
            if (m == null) return o.toString();
            String pkg = m.getPackageName();
            o.put("pkg", pkg);
            String label = pkg;
            try {
                PackageManager pm = c.getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = String.valueOf(pm.getApplicationLabel(ai));
            } catch (Throwable ignored) {}
            o.put("app", label);
            MediaMetadata md = m.getMetadata();
            String title = "", artist = "", album = "";
            long dur = 0;
            if (md != null) {
                title = str(md.getString(MediaMetadata.METADATA_KEY_TITLE));
                if (title.isEmpty()) title = str(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                artist = str(md.getString(MediaMetadata.METADATA_KEY_ARTIST));
                if (artist.isEmpty()) artist = str(md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
                if (artist.isEmpty()) artist = str(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
                album = str(md.getString(MediaMetadata.METADATA_KEY_ALBUM));
                dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                String key = pkg + "|" + title + "|" + artist;
                File art = new File(artDir, "cover_" + Integer.toHexString(key.hashCode()) + ".png");
                if (!art.exists()) {
                    Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                    if (b != null) {
                        File dir = new File(artDir);
                        dir.mkdirs();
                        if (!key.equals(lastArtKey)) {
                            File[] old = dir.listFiles();
                            if (old != null && old.length > 40) for (File f : old) f.delete();
                        }
                        Bitmap s = b.getWidth() > 512 ? Bitmap.createScaledBitmap(b, 512, 512 * b.getHeight() / Math.max(1, b.getWidth()), true) : b;
                        try (FileOutputStream out = new FileOutputStream(art)) { s.compress(Bitmap.CompressFormat.PNG, 100, out); }
                    }
                }
                lastArtKey = key;
                if (art.exists()) o.put("art", art.getAbsolutePath());
            }
            o.put("title", title);
            o.put("artist", artist);
            o.put("album", album);
            o.put("durationMs", Math.max(0, dur));
            PlaybackState st = m.getPlaybackState();
            int state = st == null ? PlaybackState.STATE_NONE : st.getState();
            boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
            o.put("playing", playing);
            o.put("state", state);
            long pos = 0;
            long actions = 0;
            if (st != null) {
                pos = st.getPosition();
                if (playing && st.getLastPositionUpdateTime() > 0)
                    pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                actions = st.getActions();
            }
            o.put("positionMs", Math.max(0, dur > 0 ? Math.min(pos, dur) : pos));
            o.put("canSeek", (actions & PlaybackState.ACTION_SEEK_TO) != 0);
            o.put("canNext", (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
            o.put("canPrev", (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
        } catch (SecurityException se) {
            try { o.put("access", false); } catch (Exception ignored) {}
        } catch (Throwable t) {
            try { o.put("error", String.valueOf(t)); } catch (Exception ignored) {}
        }
        return o.toString();
    }

    private static String str(String s) { return s == null ? "" : s; }

    public static boolean mediaCommand(Context c, String cmd, long arg) {
        try {
            MediaController m = pick(c);
            if (m == null) return false;
            MediaController.TransportControls t = m.getTransportControls();
            switch (cmd) {
                case "play": t.play(); break;
                case "pause": t.pause(); break;
                case "toggle": {
                    PlaybackState s = m.getPlaybackState();
                    if (s != null && s.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
                    break;
                }
                case "next": t.skipToNext(); break;
                case "prev": t.skipToPrevious(); break;
                case "seek": t.seekTo(arg); break;
                default: return false;
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    // ═══════════════════════ Einstellungsseiten von Android ═══════════════════════

    public static void openSettings(Context c, String action) {
        try {
            Intent i = new Intent(action);
            if (action.equals(Settings.ACTION_MANAGE_WRITE_SETTINGS))
                i.setData(android.net.Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) {}
    }
}
