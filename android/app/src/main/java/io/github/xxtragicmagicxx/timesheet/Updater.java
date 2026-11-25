package io.github.xxtragicmagicxx.timesheet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Looks for a newer release of this app on GitHub and downloads it. Releases are tagged apk-N where
 * N is the build number, which is also this app's versionCode. Only files from this repository's
 * release downloads are ever fetched, and Android refuses to install an APK signed with another key.
 */
final class Updater {
    static final String REPO = "xXTragicMagicXx/Time-To-Sheet-My-Pants";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String DL_PREFIX = "https://github.com/" + REPO + "/releases/download/";
    static final String PAGE = "https://github.com/" + REPO + "/releases/latest";

    static final class Info {
        final int n;
        final String ver, url;

        Info(int n, String url) {
            this.n = n;
            this.ver = "1.0." + n;
            this.url = url;
        }
    }

    private Updater() {
    }

    /** Network call, run it off the main thread. The newest release if it is newer than this app, else null; throws if it cannot be read. */
    static Info check() throws Exception {
        HttpURLConnection u = (HttpURLConnection) new URL(API).openConnection();
        try {
            u.setConnectTimeout(10000);
            u.setReadTimeout(15000);
            u.setRequestProperty("Accept", "application/vnd.github+json");
            u.setRequestProperty("User-Agent", "TimesheetApp");
            if (u.getResponseCode() != 200) throw new IOException("HTTP " + u.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(u.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
            }
            JSONObject o = new JSONObject(sb.toString());
            String tag = o.optString("tag_name", "");
            if (!tag.startsWith("apk-")) return null;
            int n = Integer.parseInt(tag.substring(4));
            if (n <= BuildConfig.VERSION_CODE) return null;
            JSONArray as = o.optJSONArray("assets");
            String url = null;
            if (as != null) {
                for (int i = 0; i < as.length(); i++) {
                    JSONObject a = as.getJSONObject(i);
                    if ("Timesheet.apk".equals(a.optString("name"))) url = a.optString("browser_download_url");
                }
            }
            if (url == null || !url.startsWith(DL_PREFIX)) return null;
            return new Info(n, url);
        } finally {
            u.disconnect();
        }
    }

    static void save(Context c, Info i) {
        SharedPreferences.Editor e = c.getSharedPreferences("upd", Context.MODE_PRIVATE).edit();
        if (i == null) e.clear();
        else e.putInt("n", i.n).putString("url", i.url);
        e.apply();
    }

    /** The update found earlier, if it is still newer than this app. */
    static Info saved(Context c) {
        SharedPreferences p = c.getSharedPreferences("upd", Context.MODE_PRIVATE);
        int n = p.getInt("n", 0);
        String url = p.getString("url", null);
        return n > BuildConfig.VERSION_CODE && url != null && url.startsWith(DL_PREFIX) ? new Info(n, url) : null;
    }

    /** JSON for the page: {v, cur, url}, or an empty string when there is no update. */
    static String json(Info i) {
        if (i == null) return "";
        JSONObject o = new JSONObject();
        try {
            o.put("v", i.ver);
            o.put("cur", BuildConfig.VERSION_NAME);
            o.put("url", i.url);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    static File download(String url, File dir) throws Exception {
        if (url == null || !url.startsWith(DL_PREFIX)) throw new IOException("Unexpected download address");
        dir.mkdirs();
        File f = new File(dir, "Timesheet-update.apk");
        HttpURLConnection u = (HttpURLConnection) new URL(url).openConnection();
        try {
            u.setConnectTimeout(15000);
            u.setReadTimeout(30000);
            u.setRequestProperty("User-Agent", "TimesheetApp");
            if (u.getResponseCode() != 200) throw new IOException("HTTP " + u.getResponseCode());
            try (InputStream in = u.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } finally {
            u.disconnect();
        }
        if (f.length() < 100000) throw new IOException("Download incomplete");
        return f;
    }
}
