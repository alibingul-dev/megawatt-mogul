package games.baseload.megawattmogul;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Calendar;

/* Daily report, entirely on the phone: the page hands over one line of text when the player
   leaves; an alarm shows it once if they stay away 8+ hours. Never at night, at most once a day,
   off unless the player turns it on. Nothing leaves the device. */
public class Reminder extends BroadcastReceiver {

    static final String PREFS = "mm_reminder";
    private static final String CH = "daily";
    private static final long AWAY = 8L * 3600 * 1000;
    private static final long GAP = 20L * 3600 * 1000;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, Reminder.class);
        return PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /* the first moment 8 hours from now that is not between 22:00 and 10:00 */
    static long when(long now) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(now + AWAY);
        int h = k.get(Calendar.HOUR_OF_DAY);
        if (h >= 22 || h < 10) {
            if (h >= 22) k.add(Calendar.DAY_OF_MONTH, 1);
            k.set(Calendar.HOUR_OF_DAY, 10);
            k.set(Calendar.MINUTE, 0);
            k.set(Calendar.SECOND, 0);
        }
        return k.getTimeInMillis();
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(alarmIntent(c));
        if (!prefs(c).getBoolean("on", false)) return;
        long now = System.currentTimeMillis();
        long t = Math.max(when(now), prefs(c).getLong("last", 0) + GAP);
        am.set(AlarmManager.RTC, t, alarmIntent(c));   // inexact on purpose: no exact-alarm permission
    }

    static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent(c));
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        SharedPreferences p = prefs(c);
        String text = p.getString("text", "");
        if (!p.getBoolean("on", false) || text == null || text.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Daily report", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(c, CH);
        } else {
            b = new Notification.Builder(c);
        }
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(R.drawable.ic_stat)
         .setContentTitle("Megawatt Mogul")
         .setContentText(text)
         .setStyle(new Notification.BigTextStyle().bigText(text))
         .setContentIntent(pi)
         .setAutoCancel(true);
        nm.notify(1, b.build());
        p.edit().putLong("last", System.currentTimeMillis()).apply();
    }
}
