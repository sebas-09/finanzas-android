package ec.finanzas.locales;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import java.nio.charset.StandardCharsets;

/** Este proceso independiente usa solo clases Android, sin depender del runtime de la app bajo prueba. */
public final class TestSourceReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        int id = intent.getIntExtra("event_id", -1);
        if (id < 0) {
            notifications.cancelAll();
        } else if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notifications.createNotificationChannel(new NotificationChannel("f1-test", "Prueba sintetica F1", NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Builder builder = new Notification.Builder(context, "f1-test")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Transferencia de prueba")
                .setContentText(new String(Base64.decode(intent.getStringExtra("message_b64"), Base64.DEFAULT), StandardCharsets.UTF_8))
                .setOnlyAlertOnce(true);
            if (intent.getBooleanExtra("grouped", false) || intent.getBooleanExtra("group_summary", false)) {
                builder.setGroup("f5-tests").setGroupSummary(intent.getBooleanExtra("group_summary", false));
            }
            notifications.notify(id, builder.build());
        }
    }
}
