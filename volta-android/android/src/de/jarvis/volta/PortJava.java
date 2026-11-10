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
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@SuppressWarnings({"deprecation", "MissingPermission"})
public class PortJava {

    // Kopiert eine SAF/content://-Datei ueber ContentResolver in Voltas privaten Cache.
    // QFile kann content://-URIs nicht wie normale Dateipfade oeffnen.
    public static boolean copyContentUri(Context c, String uriString, String targetPath) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            Uri uri = Uri.parse(uriString);
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            File target = new File(targetPath);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
            out = new FileOutputStream(target, false);
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.flush();
            return target.isFile() && target.length() > 0;
        } catch (Throwable t) {
            android.util.Log.e("VoltaPort", "copyContentUri failed: " + uriString, t);
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }


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

    private static BluetoothAdapter btAdapter(Context c) {
        android.bluetooth.BluetoothManager m = (android.bluetooth.BluetoothManager)c.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }
    private static boolean btPermission(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.getApplicationInfo().targetSdkVersion < 31 ||
            c.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }
    public static String btStatus(Context c) {
        JSONObject o = new JSONObject();
        try {
            BluetoothAdapter a = btAdapter(c);
            boolean permitted = btPermission(c);
            o.put("android", true);o.put("available", a != null); o.put("permission", permitted);
            o.put("poweredOn", a != null && permitted && a.isEnabled());
            o.put("discovering", a != null && permitted && a.isDiscovering());
            o.put("message", a == null ? "Android exposes no Bluetooth adapter. BlueStacks usually does not provide Bluetooth hardware to apps. Test Bluetooth on the radio." :
                !permitted ? "Allow Bluetooth access in Android app settings." : "");
        } catch (SecurityException t) {
            try { o.put("available", true);o.put("permission", false);o.put("poweredOn", false);o.put("discovering", false);o.put("message", "Android denied Bluetooth access. Check the app permissions."); } catch(Exception ignored){}
        } catch (Throwable t) { try {o.put("available", false);o.put("poweredOn",false);o.put("discovering",false);o.put("message", "Bluetooth service unavailable on this Android system.");}catch(Exception ignored){} }
        return o.toString();
    }
    public static String btSettings(Context c) {
        JSONObject o=new JSONObject();
        try { c.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));o.put("ok",true); }
        catch(Throwable t){try{o.put("ok",false);o.put("message","Android Bluetooth settings are unavailable.");}catch(Exception ignored){}}
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
            BluetoothAdapter a = btAdapter(c);
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
            BluetoothAdapter a = btAdapter(c);
            if (a == null) { o.put("ok", false);o.put("message", "No Bluetooth adapter is exposed by Android."); }
            else { boolean ok=on ? a.enable() : a.disable();o.put("ok",ok);if(!ok)o.put("message","Android could not change Bluetooth. Use Android Bluetooth settings."); }
        } catch (Throwable t) { try { o.put("ok", false); o.put("message", String.valueOf(t)); } catch (Exception ignored) {} }
        return o.toString();
    }

    public static String btScan(Context c, boolean on) {
        JSONObject o = new JSONObject();
        try {
            BluetoothAdapter a = btAdapter(c);
            if (a == null) { o.put("ok", false); o.put("message", "no Bluetooth"); return o.toString(); }
            if (!a.isEnabled()) {o.put("ok",false);o.put("message","Turn Bluetooth on first.");return o.toString();}
            if (on) {
                ensureReceiver(c);
                synchronized (PortJava.class) { found.clear(); foundRssi.clear(); }
                boolean ok=a.startDiscovery();o.put("ok",ok);if(!ok)o.put("message","Discovery was not started. Check location permission and Android location settings.");
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
            BluetoothAdapter a = btAdapter(c);
            if(a==null){o.put("ok",false);o.put("message","Android exposes no Bluetooth adapter.");return o.toString();}
            if(!BluetoothAdapter.checkBluetoothAddress(address)){o.put("ok",false);o.put("message","Invalid Bluetooth address.");return o.toString();}
            BluetoothDevice d = a.getRemoteDevice(address);
            boolean ok;
            String msg = "";
            if (action.equals("pair")) {
                a.cancelDiscovery();
                ok = d.createBond();
            } else if (action.equals("forget")) {
                ok = (Boolean) d.getClass().getMethod("removeBond").invoke(d);
            } else if (action.equals("connect") || action.equals("disconnect")) {
                JSONObject delegated=new JSONObject(btSettings(c));
                ok=delegated.optBoolean("ok",false);
                msg=ok ? "Complete the connection in Android Bluetooth settings; no connection is claimed until Android reports it." : delegated.optString("message");
            } else {
                ok = false;
                msg = "unknown";
            }
            o.put("ok", ok);
            o.put("message", msg);
        } catch (Throwable t) { try { o.put("ok", false); o.put("message", String.valueOf(t)); } catch (Exception ignored) {} }
        return o.toString();
    }

    public static void prepareWebViews(Context context) {
        if(!(context instanceof Activity)) return;
        final Activity activity=(Activity)context;
        activity.runOnUiThread(() -> hardenViews(activity.getWindow().getDecorView()));
    }
    private static void hardenViews(android.view.View view) {
        if(view instanceof android.webkit.WebView){
            android.webkit.WebView web=(android.webkit.WebView)view;
            web.getSettings().setGeolocationEnabled(false);
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false);
            web.getSettings().setAllowFileAccessFromFileURLs(false);
            web.getSettings().setAllowUniversalAccessFromFileURLs(false);
        }
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)hardenViews(group.getChildAt(i));}
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
        } catch (Throwable ignor…2533 tokens truncated…ED(action);
        return false;
#endif
    }

    Q_INVOKABLE void prepareWebViews() {
#ifdef Q_OS_ANDROID
        QJniEnvironment env;
        QJniObject::callStaticMethod<void>("de/jarvis/volta/PortJava","prepareWebViews","(Landroid/content/Context;)V",QNativeInterface::QAndroidApplication::context().object());
        env.checkAndClearExceptions();
#endif
    }

    Q_INVOKABLE void refresh()
    {
#ifdef Q_OS_ANDROID
        if (m_loading) return;
        m_loading = true;
        const QString iconDir = m_iconDir;
        const QPointer<AndroidPort> self(this);
        std::thread([self, iconDir]() {
            QVariantList list;
            QStringList logLines;
            {
                QJniEnvironment env;
                const QJniObject ctx = QNativeInterface::QAndroidApplication::context();
                const QJniObject pm = ctx.callObjectMethod("getPackageManager", "()Landroid/content/pm/PackageManager;");
                const QString own = ctx.callObjectMethod("getPackageName", "()Ljava/lang/String;").toString();
                const QJniObject intent("android/content/Intent", "(Ljava/lang/String;)V",
                                        jstr(QStringLiteral("android.intent.action.MAIN")).object());
                intent.callObjectMethod("addCategory", "(Ljava/lang/String;)Landroid/content/Intent;",
                                        jstr(QStringLiteral("android.intent.category.LAUNCHER")).object());
                const QJniObject res = pm.callObjectMethod("queryIntentActivities",
                                                           "(Landroid/content/Intent;I)Ljava/util/List;", intent.object(), jint(0));
                const int n = res.isValid() ? res.callMethod<jint>("size") : 0;
                QSet<QString> seen;
                for (int i = 0; i < n; ++i) {
                    const QJniObject ri = res.callObjectMethod("get", "(I)Ljava/lang/Object;", jint(i));
                    const QJniObject ai = ri.getObjectField("activityInfo", "Landroid/content/pm/ActivityInfo;");
                    const QString pkg = ai.getObjectField("packageName", "Ljava/lang/String;").toString();
                    if (pkg.isEmpty() || pkg == own || seen.contains(pkg)) continue;
                    seen.insert(pkg);
                    const QString name = ri.callObjectMethod("loadLabel", "(Landroid/content/pm/PackageManager;)Ljava/lang/CharSequence;",
                                                             pm.object())
                                             .callObjectMethod("toString", "()Ljava/lang/String;").toString();
                    if (env.checkAndClearExceptions()) continue;
                    const QString iconFile = iconDir + QLatin1Char('/') + pkg + QStringLiteral(".png");
                    if (!QFileInfo::exists(iconFile)) {
                        const QJniObject dr = ri.callObjectMethod("loadIcon", "(Landroid/content/pm/PackageManager;)Landroid/graphics/drawable/Drawable;",
                                                                  pm.object());
                        if (!env.checkAndClearExceptions()) {
                            const QByteArray png = drawableToPng(dr, 144);
                            QFile f(iconFile);
                            if (!png.isEmpty() && f.open(QIODevice::WriteOnly)) f.write(png);
                        }
                    }
                    list.append(QVariantMap{{QStringLiteral("id"), QStringLiteral("android:") + pkg},
                                            {QStringLiteral("pkg"), pkg},
                                            {QStringLiteral("name"), name.isEmpty() ? pkg : name},
                                            {QStringLiteral("icon"), QUrl::fromLocalFile(iconFile).toString()},
                                            {QStringLiteral("android"), true}});
                    logLines << (name + QStringLiteral(" = ") + pkg);
                }
            }
            std::sort(list.begin(), list.end(), [](const QVariant &a, const QVariant &b) {
                return a.toMap().value(QStringLiteral("name")).toString().compare(
                           b.toMap().value(QStringLiteral("name")).toString(), Qt::CaseInsensitive) < 0;
            });
            QMetaObject::invokeMethod(qApp, [self, list, logLines]() {
                if (!self) return;
                self->m_loading = false;
                if (list == self->m_apps) return;
                const bool first = self->m_apps.isEmpty();
                self->m_apps = list;
                if (first) qInfo().noquote() << "[apps] installed apps:" << logLines.join(QStringLiteral(" | "));
                self->applyDefaults();
                emit self->appsChanged();
            });
        }).detach();
#else
        // nur fuer die Kontroll-Bildschirmfotos auf Linux: erfundene Eintraege
        if (qEnvironmentVariableIsSet("VOLTA_PORT_FAKEAPPS") && m_apps.isEmpty()) {
            const char *names[] = {"ZLINK", "Bluetooth Phone", "Torque", "TPMS", "DVR", "USB Camera", "YouTube",
                                   "Dashboard W204", "Topgadget Voice", "Spotify", "Google Maps", "Waze"};
            for (const char *n : names) {
                const QString name = QString::fromLatin1(n);
                const QString pkg = QStringLiteral("demo.") + name.toLower().remove(QLatin1Char(' '));
                m_apps.append(QVariantMap{{QStringLiteral("id"), QStringLiteral("android:") + pkg}, {QStringLiteral("pkg"), pkg},
                                          {QStringLiteral("name"), name},
                                          {QStringLiteral("icon"), QStringLiteral("qrc:/VoltaApp/icons/settings.svg")},
                                          {QStringLiteral("android"), true}});
            }
            m_links.insert(QStringLiteral("androidauto"), QStringLiteral("demo.zlink"));
            m_links.insert(QStringLiteral("phone"), QStringLiteral("demo.bluetoothphone"));
            emit appsChanged();
            emit linksChanged();
        }
#endif
    }

signals:
    void appsChanged();
    void linksChanged();
    void mediaAccessChanged();

private:
    void saveLinks()
    {
        QVariantMap m;
        for (auto it = m_links.begin(); it != m_links.end(); ++it) m.insert(it.key(), it.value());
        QSettings().setValue(QStringLiteral("port/links"), m);
    }

    // Beim ersten Mal: CarPlay -> ZLINK, Telefon -> Telefon-App des Radios (wenn eindeutig erkennbar)
    void applyDefaults()
    {
        QSettings s;
        if (s.value(QStringLiteral("port/defaultsDone")).toBool()) return;
        auto find = [this](const QStringList &words) -> QString {
            for (const QVariant &v : m_apps) {
                const QVariantMap a = v.toMap();
                const QString hay = (a.value(QStringLiteral("name")).toString() + QLatin1Char(' ')
                                     + a.value(QStringLiteral("pkg")).toString()).toLower();
                for (const QString &w : words)
                    if (hay.contains(w)) return a.value(QStringLiteral("pkg")).toString();
            }
            return {};
        };
        const QString zlink = find({QStringLiteral("zlink"), QStringLiteral("carplay")});
        if (!zlink.isEmpty() && !m_links.contains(QStringLiteral("androidauto"))) m_links.insert(QStringLiteral("androidauto"), zlink);
        const QString phone = find({QStringLiteral("telefon"), QStringLiteral("phone"), QStringLiteral("dialer"),
                                    QStringLiteral("btphone"), QStringLiteral("bluetooth")});
        if (!phone.isEmpty() && !m_links.contains(QStringLiteral("phone"))) m_links.insert(QStringLiteral("phone"), phone);
        s.setValue(QStringLiteral("port/defaultsDone"), true);
        saveLinks();
        qInfo().noquote() << "[apps] default links: carplay ->" << (zlink.isEmpty() ? QStringLiteral("-") : zlink)
                          << ", phone ->" << (phone.isEmpty() ? QStringLiteral("-") : phone);
        emit linksChanged();
    }

    QVariantList m_apps;
    QHash<QString, QString> m_links;
    QString m_iconDir;
    bool m_loading = false;
    bool m_mediaAccess = true;
};

#ifdef Q_OS_ANDROID
// Launcher: die Zurueck-Taste darf Volta nicht schliessen
class BackKeyFilter : public QObject
{
public:
    using QObject::QObject;
    bool eventFilter(QObject *o, QEvent *e) override
    {
        if ((e->type() == QEvent::KeyPress || e->type() == QEvent::KeyRelease || e->type() == QEvent::ShortcutOverride)
            && static_cast<QKeyEvent *>(e)->key() == Qt::Key_Back)
            return true;
        return QObject::eventFilter(o, e);
    }
};
#endif

} // namespace

void voltaPortRegisterApps()
{
    // erst beim ersten Zugriff aus QML anlegen: dann ist die App sicher fertig gestartet
    qmlRegisterSingletonType<AndroidPort>("VoltaPort", 1, 0, "AndroidPort", [](QQmlEngine *, QJSEngine *) -> QObject * {
        static AndroidPort *port = nullptr;
        if (!port) port = new AndroidPort(QCoreApplication::instance());
        QQmlEngine::setObjectOwnership(port, QQmlEngine::CppOwnership);
        return port;
    });
    qmlRegisterType(QUrl(QStringLiteral("qrc:/volta_port/PortLinkPopup.qml")), "VoltaPort", 1, 0, "PortLinkPopup");
#ifdef Q_OS_ANDROID
    QTimer::singleShot(0, qApp, []() { qApp->installEventFilter(new BackKeyFilter(qApp)); });
#endif
}

#include "android_apps.moc"
