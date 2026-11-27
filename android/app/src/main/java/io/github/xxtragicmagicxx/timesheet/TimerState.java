package io.github.xxtragicmagicxx.timesheet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The app's own copy of the running timer, so the notification's Break / Resume buttons work while
 * the app is closed. The page sends its timer state through AndroidApp.timer(); taps on the
 * notification are queued as events ({type: brk | res | stop, t}) that the page applies the next
 * time it runs, or straight away if it is open, and it then sends its state back.
 */
final class TimerState {
    private static final String P = "timer";

    private TimerState() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    static void save(Context c, JSONObject o) {
        prefs(c).edit().putString("state", o.toString()).apply();
    }

    static JSONObject load(Context c) {
        try {
            String s = prefs(c).getString("state", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** Shows (or clears) the notification and the "still running" alarm for timer state o. */
    static void show(Context c, JSONObject o) {
        if (o == null || o.optInt("on", 0) == 0) {
            Notif.cancelTimer(c);
            return;
        }
        String name = o.optString("name", "Timer");
        long start = o.optLong("start"), bs = o.optLong("brkStart"), bm = o.optLong("brkMs"), snz = o.optLong("snz");
        double wh = o.optDouble("warnH", 0);
        long now = System.currentTimeMillis();
        boolean onBreak = bs > 0;
        long work = Math.max(0, (onBreak ? bs : now) - start - bm);
        Notif.running(c, name, now - work, onBreak);
        long at = wh > 0 ? Math.max(start + (long) (wh * 3600000L), snz) : 0;
        Notif.schedule(c, name, start, at);
    }

    static synchronized void event(Context c, String type, long t) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("events", "[]"));
            a.put(new JSONObject().put("type", type).put("t", t));
            prefs(c).edit().putString("events", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** The queued events as a JSON array, emptying the queue. */
    static synchronized String take(Context c) {
        String s = prefs(c).getString("events", "[]");
        prefs(c).edit().remove("events").apply();
        return s;
    }

    /** Break or Resume tapped in the notification: update the copy and the notification, and queue it for the page. */
    static void breakOrResume(Context c, boolean toBreak) {
        JSONObject o = load(c);
        if (o == null || o.optInt("on", 0) == 0) {
            Notif.cancelTimer(c);
            return;
        }
        long now = System.currentTimeMillis(), bs = o.optLong("brkStart");
        try {
            if (toBreak && bs == 0) {
                o.put("brkStart", now);
                event(c, "brk", now);
            } else if (!toBreak && bs > 0) {
                o.put("brkMs", o.optLong("brkMs") + Math.max(0, now - bs));
                o.put("brkStart", 0);
                event(c, "res", now);
            }
        } catch (Exception ignored) {
        }
        save(c, o);
        show(c, o);
    }
}
