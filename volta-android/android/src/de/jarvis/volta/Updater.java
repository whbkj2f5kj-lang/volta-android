package de.jarvis.volta;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Installiert eine heruntergeladene Volta-APK ueber den Android-Paketinstaller. */
public final class Updater {
    private static final String ACTION = "de.jarvis.volta.UPDATE_STATUS";
    private static boolean registered = false;

    public static boolean canInstall(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    public static void askPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    /** Liefert "" bei Erfolg (Android zeigt dann die Bestaetigung), sonst eine Fehlermeldung. */
    public static String install(Context ctx, String path) {
        try {
            final Context app = ctx.getApplicationContext();
            synchronized (Updater.class) {
                if (!registered) {
                    app.registerReceiver(new BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent intent) {
                            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                                if (confirm != null) {
                                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    c.startActivity(confirm);
                                }
                            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                                Log.w("VoltaUpdate", "install status " + status + ": "
                                        + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                            }
                        }
                    }, new IntentFilter(ACTION));
                    registered = true;
                }
            }
            File apk = new File(path);
            PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(app.getPackageName());
            params.setSize(apk.length());
            int id = pi.createSession(params);
            PackageInstaller.Session s = pi.openSession(id);
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("volta.apk", 0, apk.length())) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent cb = new Intent(ACTION).setPackage(app.getPackageName());
            PendingIntent pend = PendingIntent.getBroadcast(app, id, cb, PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
            s.close();
            return "";
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }
}
