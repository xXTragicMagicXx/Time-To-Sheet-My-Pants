package io.github.xxtragicmagicxx.timesheet;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Notifications: the running-timer notification, the "timer still running" alert (fired by an
 * inexact alarm) and the "app update available" notice. All of them open the app when tapped.
 * If the user has not allowed notifications, Android simply drops them.
 */
final class Notif {
    static final int ID_RUN = 1, ID_ALERT = 2, ID_UPDATE = 3;
    private static final String CH_RUN = "run", CH_ALERT = "alert", CH_UPDATE = "update";
    private static final String ACT_LONGRUN = "io.github.xxtragicmagicxx.timesheet.LONGRUN";

    private Notif() {
    }

    private static NotificationManagerCompat nm(Context c) {
        NotificationManagerCompat m = NotificationManagerCompat.from(c);
        m.createNotificationChannel(new NotificationChannelCompat.Builder(CH_RUN, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Running timer").setDescription("Shown while a timer is running").build());
        m.createNotificationChannel(new NotificationChannelCompat.Builder(CH_ALERT, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Timer still running").setDescription("Asks whether you forgot to stop a timer").build());
        m.createNotificationChannel(new NotificationChannelCompat.Builder(CH_UPDATE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("App updates").setDescription("A new version of the app is available").build());
        return m;
    }

    private static PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Ongoing notification with a chronometer that shows the work time so far; a plain "On break" while on break. */
    static void running(Context c, String name, long whenMs, boolean onBreak) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_RUN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(name)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open(c));
        if (onBreak) b.setContentText("On break").setShowWhen(false);
        else b.setContentText("Timer running").setUsesChronometer(true).setWhen(whenMs).setShowWhen(true);
        nm(c).notify(ID_RUN, b.build());
    }

    static void longRun(Context c, String name, long start) {
        long mins = Math.max(0, (System.currentTimeMillis() - start) / 60000);
        String t = (mins / 60) + ":" + (mins % 60 < 10 ? "0" : "") + (mins % 60);
        String text = name + " has been running " + t + ". Tap to stop it or set the time you finished.";
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Timer still running")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open(c));
        nm(c).notify(ID_ALERT, b.build());
    }

    static void update(Context c, String version) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_UPDATE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Timesheet app update available")
                .setContentText("Version " + version + " is ready. Tap to open the app and install it.")
                .setAutoCancel(true)
                .setContentIntent(open(c));
        nm(c).notify(ID_UPDATE, b.build());
    }

    private static PendingIntent alarm(Context c, String name, long start) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACT_LONGRUN)
                .putExtra("name", name).putExtra("start", start);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** (Re)schedules the "still running" alert for time at (ms); at = 0 or in the past only cancels it. */
    static void schedule(Context c, String name, long start, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarm(c, name, start);
        am.cancel(pi);
        if (at > System.currentTimeMillis() + 60000) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancelTimer(Context c) {
        schedule(c, "", 0, 0);
        NotificationManagerCompat m = NotificationManagerCompat.from(c);
        m.cancel(ID_RUN);
        m.cancel(ID_ALERT);
    }
}
