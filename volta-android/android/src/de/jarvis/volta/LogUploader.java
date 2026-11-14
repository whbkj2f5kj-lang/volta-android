package de.jarvis.volta;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Laedt Voltas Diagnose-Dateien (*.txt in Android/data/de.jarvis.volta/files) in ein PRIVATES GitHub-Repo,
 * damit sie nicht von Hand vom Radio geholt werden muessen. Nur geaenderte Dateien, alle 5 Minuten.
 * Der Schluessel (fine-grained token, nur "Contents" fuer das Log-Repo) liegt nur auf dem Radio.
 * Ist das Repo oeffentlich, wird NICHT hochgeladen (die Logs enthalten GPS-Positionen).
 */
public final class LogUploader {
    private static final String PREFS = "volta_upload";
    private static final String DEFAULT_REPO = "whbkj2f5kj-lang/volta-logs";
    private static final long PERIOD_MS = 5 * 60 * 1000L;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private static Context app;
    private static SharedPreferences prefs;
    private static Handler worker;
    private static volatile String status = "Aus";
    private static volatile boolean busy = false;
    private static final SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY);
    private static final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.GERMANY);

    private LogUploader() {}

    public static synchronized void start(Context ctx) {
        if (app != null) return;
        app = ctx.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HandlerThread t = new HandlerThread("volta-upload");
        t.start();
        worker = new Handler(t.getLooper());
        status = !enabled() ? "Aus" : token().isEmpty() ? "Kein Schlüssel eingetragen" : "Wartet auf ersten Upload";
        worker.postDelayed(tick, 60_000);
    }

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            try { uploadAll(); } catch (Throwable t) { status = "Fehler: " + t.getMessage(); }
            worker.postDelayed(this, PERIOD_MS);
        }
    };

    static boolean enabled() { return prefs != null && prefs.getBoolean("enabled", true); }
    static String token() { return prefs == null ? "" : prefs.getString("token", ""); }
    public static String repo() { return prefs == null ? DEFAULT_REPO : prefs.getString("repo", DEFAULT_REPO); }

    /** JSON fuer Qt: enabled, hasToken, repo, status */
    public static String snapshot() {
        try {
            return new JSONObject().put("enabled", enabled()).put("hasToken", !token().isEmpty())
                    .put("repo", repo()).put("status", status).put("busy", busy).toString();
        } catch (Throwable t) { return "{}"; }
    }

    public static void setEnabled(boolean on) {
        if (prefs == null) return;
        prefs.edit().putBoolean("enabled", on).apply();
        status = on ? "Eingeschaltet" : "Aus";
        if (on) uploadNow();
    }

    public static void setRepo(String r) {
        if (prefs == null || r == null) return;
        r = r.trim();
        if (!r.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) { status = "Repo bitte als besitzer/name angeben"; return; }
        prefs.edit().putString("repo", r).remove("shas").apply();
        status = "Repo gespeichert";
    }

    /** Schluessel aus der Zwischenablage uebernehmen (kein Abtippen auf dem Radio). */
    public static String tokenFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            String s = clip == null || clip.getItemCount() == 0 ? "" : String.valueOf(clip.getItemAt(0).coerceToText(app));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,})").matcher(s);
            if (!m.find()) { status = "Kein GitHub-Schlüssel in der Zwischenablage"; return status; }
            prefs.edit().putString("token", m.group(1)).apply();
            status = "Schlüssel gespeichert";
            uploadNow();
            return status;
        } catch (Throwable t) { status = "Zwischenablage nicht lesbar: " + t.getMessage(); return status; }
    }

    /** Aus keys.env (beim Start oder per Dateiauswahl): Schluessel und/oder Repo speichern, auch vor start(). */
    public static void storeKey(Context ctx, String tok, String repoName) {
        SharedPreferences p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        if (tok != null && !tok.isEmpty()) e.putString("token", tok.trim());
        if (repoName != null && repoName.trim().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
                && !repoName.trim().equals(p.getString("repo", DEFAULT_REPO))) e.putString("repo", repoName.trim()).remove("shas");
        e.apply();
        status = "Schlüssel aus keys.env übernommen";
        if (worker != null && enabled()) uploadNow();
    }

    public static void clearToken() {
        if (prefs == null) return;
        prefs.edit().remove("token").apply();
        status = "Schlüssel gelöscht";
    }

    public static void uploadNow() {
        if (worker == null) return;
        worker.post(new Runnable() { @Override public void run() {
            try { uploadAll(); } catch (Throwable t) { status = "Fehler: " + t.getMessage(); }
        } });
    }

    private static void uploadAll() throws Exception {
        if (!enabled()) { status = "Aus"; return; }
        String tok = token();
        if (tok.isEmpty()) { status = "Kein Schlüssel eingetragen"; return; }
        File dir = app.getExternalFilesDir(null);
        if (dir == null) dir = app.getFilesDir();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".txt"));
        if (files == null || files.length == 0) { status = "Keine Logs gefunden"; return; }
        busy = true;
        try {
            // Sicherheit: nur in ein PRIVATES Repo
            Resp info = http("GET", "https://api.github.com/repos/" + repo(), tok, null);
            if (info.code == 401) { status = "Schlüssel ungültig oder abgelaufen"; return; }
            if (info.code == 404) { status = "Repo " + repo() + " nicht gefunden (oder Schlüssel ohne Zugriff)"; return; }
            if (info.code != 200) { status = "GitHub antwortet " + info.code; return; }
            if (!new JSONObject(info.body).optBoolean("private", false)) { status = "Repo ist öffentlich – Upload gestoppt"; return; }

            Arrays.sort(files);
            String today = day.format(new Date());
            int sent = 0, same = 0;
            JSONObject shas = new JSONObject(prefs.getString("shas", "{}"));
            for (File f : files) {
                String stamp = f.length() + ":" + f.lastModified();
                String path = "logs/" + today + "/" + f.getName();
                if (stamp.equals(prefs.getString("stamp:" + path, ""))) { same++; continue; }
                byte[] data = tail(f);
                JSONObject body = new JSONObject()
                        .put("message", "Radio " + today + " " + clock.format(new Date()) + ": " + f.getName())
                        .put("content", Base64.encodeToString(data, Base64.NO_WRAP));
                String sha = shas.optString(path, "");
                if (!sha.isEmpty()) body.put("sha", sha);
                String url = "https://api.github.com/repos/" + repo() + "/contents/" + path;
                Resp r = http("PUT", url, tok, body.toString());
                if (r.code == 409 || r.code == 422) {   // sha unbekannt/veraltet: aktuellen holen und nochmal
                    Resp g = http("GET", url, tok, null);
                    if (g.code == 200) body.put("sha", new JSONObject(g.body).optString("sha"));
                    else body.remove("sha");
                    r = http("PUT", url, tok, body.toString());
                }
                if (r.code != 200 && r.code != 201) { status = "Upload " + f.getName() + " fehlgeschlagen (" + r.code + ")"; return; }
                shas.put(path, new JSONObject(r.body).getJSONObject("content").getString("sha"));
                prefs.edit().putString("shas", shas.toString()).putString("stamp:" + path, stamp).apply();
                sent++;
            }
            status = "Zuletzt " + clock.format(new Date()) + ": " + sent + " hochgeladen, " + same + " unverändert";
        } finally { busy = false; }
    }

    /** Ganze Datei, bei sehr grossen nur das Ende. */
    private static byte[] tail(File f) throws Exception {
        long len = f.length();
        if (len <= MAX_BYTES) {
            try (InputStream in = new FileInputStream(f)) { return readAll(in); }
        }
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[MAX_BYTES];
            r.seek(len - MAX_BYTES);
            r.readFully(b);
            return b;
        }
    }

    private static final class Resp { int code; String body = ""; }

    private static Resp http(String method, String url, String tok, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("Authorization", "Bearer " + tok);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "volta-radio");
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) try (InputStream i = in) { r.body = new String(readAll(i), StandardCharsets.UTF_8); }
        c.disconnect();
        return r;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }
}
