package de.jarvis.volta;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Unsichtbare Zwischen-Activity: oeffnet die Android-Dateiauswahl fuer keys.env (USB-Stick, Downloads, ...),
 * liest den Inhalt und legt ihn fuer Qt bereit (take()). Die Datei selbst wird nicht veraendert.
 */
public final class KeysPicker extends Activity {
    private static final int REQ = 4711;
    private static final int MAX = 64 * 1024;
    private static volatile String pending;
    private static volatile String status = "";

    public static void open(Context ctx) {
        try {
            Intent i = new Intent(ctx, KeysPicker.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            status = "Datei auswählen …";
        } catch (Throwable t) { status = "Dateiauswahl nicht möglich: " + t.getMessage(); }
    }

    /** Gelesener Inhalt (einmalig), sonst null. */
    public static String take() { String p = pending; pending = null; return p; }
    public static String status() { return status; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) return;   // nach Drehen o.ae. laeuft die Auswahl schon
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(open, REQ); return; } catch (ActivityNotFoundException ignored) {}
        Intent get = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(Intent.createChooser(get, "keys.env auswählen"), REQ); }
        catch (Throwable t) { status = "Keine Dateiauswahl auf dem Radio – keys.env per USB in Android/data/de.jarvis.volta/files/ kopieren"; finish(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Uri uri = data == null ? null : data.getData();
        if (req != REQ || res != RESULT_OK || uri == null) { status = "Abgebrochen"; finish(); return; }
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("leer");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX) throw new Exception("Datei zu groß – ist das wirklich keys.env?");
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (!text.contains("=")) { status = "Keine Schlüssel in der Datei (Format: NAME=wert)"; }
            else { pending = text; status = "Datei gelesen"; }
        } catch (Throwable t) { status = "Datei nicht lesbar: " + t.getMessage(); }
        finish();
    }
}
