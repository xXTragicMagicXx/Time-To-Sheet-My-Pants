package io.github.xxtragicmagicxx.timesheet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires when a running timer passes the user's "warn me after" limit. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String name = i.getStringExtra("name");
        Notif.longRun(c, name == null || name.isEmpty() ? "Your timer" : name,
                i.getLongExtra("start", System.currentTimeMillis()));
    }
}
