package io.github.xxtragicmagicxx.timesheet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The notification's Break and Resume buttons. They work without opening the app. */
public class ActionReceiver extends BroadcastReceiver {
    static final String BRK = "io.github.xxtragicmagicxx.timesheet.BREAK";
    static final String RES = "io.github.xxtragicmagicxx.timesheet.RESUME";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (BRK.equals(a)) TimerState.breakOrResume(c, true);
        else if (RES.equals(a)) TimerState.breakOrResume(c, false);
        MainActivity.poke();
    }
}
