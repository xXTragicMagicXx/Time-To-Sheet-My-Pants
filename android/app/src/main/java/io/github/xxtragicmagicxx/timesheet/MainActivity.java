package io.github.xxtragicmagicxx.timesheet;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A thin shell around the Timesheet web app (BuildConfig.SITE_URL). The page does all the work;
 * this class supplies what a plain WebView lacks: file and camera pickers, location permission,
 * saving and sharing generated files (via the AndroidApp JavaScript object), and opening
 * outside links in other apps.
 */
public class MainActivity extends Activity {
    private static final int RC_FILE = 1, RC_GEO = 2, RC_NOTIF = 3;
    private static final String SITE = BuildConfig.SITE_URL;

    /** True while the app is on screen; the daily update check only posts a notification when it is not. */
    static boolean visible;
    /** Intent extra set by the notification's Stop button. */
    static final String EXTRA_SHELL = "shell";
    private static WeakReference<MainActivity> live;

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private File camFile;
    private Uri camUri;
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cleanOutDir();
        web = new WebView(this);
        setContentView(web);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString() + " TimesheetApp/" + BuildConfig.VERSION_NAME);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        if (state == null || web.restoreState(state) == null) web.loadUrl(SITE);
        live = new WeakReference<>(this);
        if (state == null) handleShell(getIntent());
        checkNow(false);
        scheduleDaily();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleShell(i);
    }

    @Override
    protected void onDestroy() {
        if (live != null && live.get() == this) live = null;
        super.onDestroy();
    }

    /** Stop tapped in the notification: queue it with the time of the tap; the page stops the timer and opens the entry sheet. */
    private void handleShell(Intent i) {
        if (i == null || !"stop".equals(i.getStringExtra(EXTRA_SHELL))) return;
        i.removeExtra(EXTRA_SHELL);
        TimerState.event(this, "stop", System.currentTimeMillis());
        poke();
    }

    /** Asks the open page, if there is one, to apply the queued notification taps now. */
    static void poke() {
        final MainActivity a = live == null ? null : live.get();
        if (a == null || a.web == null) return;
        a.runOnUiThread(() -> a.web.evaluateJavascript("window.applyShellEvents&&applyShellEvents()", null));
    }

    /** Looks for a newer app version in the background and tells the page about it. */
    private void checkNow(final boolean report) {
        new Thread(() -> {
            String state;
            try {
                Updater.Info i = Updater.check();
                Updater.save(MainActivity.this, i);
                state = i == null ? "none" : "new";
            } catch (Exception e) {
                state = "error";
            }
            final String st = state;
            final String json = Updater.json(Updater.saved(MainActivity.this));
            runOnUiThread(() -> {
                if (!st.equals("error"))
                    web.evaluateJavascript("window.onAppUpdate&&onAppUpdate(" + JSONObject.quote(json) + ")", null);
                if (report)
                    web.evaluateJavascript("window.onUpdateChecked&&onUpdateChecked('" + st + "')", null);
            });
        }).start();
    }

    private void scheduleDaily() {
        try {
            JobScheduler js = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(1) != null) return;
            js.schedule(new JobInfo.Builder(1, new ComponentName(this, UpdateJob.class))
                    .setPeriodic(24L * 3600 * 1000)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build());
        } catch (Exception ignored) {
        }
    }

    /** Android 13+: ask once, the first time a timer is started. If it is refused the timer works as before, minus the notifications. */
    private void askNotifPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        if (getPreferences(MODE_PRIVATE).getBoolean("askedNotif", false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotif", true).apply();
        runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, RC_NOTIF));
    }

    private void installApk(File f) {
        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            toast("No app can open this link.");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    /** Back closes the open sheet or page inside the app first (the page tracks those as history steps). */
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private File outDir() {
        File d = new File(getCacheDir(), "out");
        d.mkdirs();
        return d;
    }

    /** Shared files and camera shots are only needed briefly; drop ones older than a day. */
    private void cleanOutDir() {
        File[] fs = outDir().listFiles();
        if (fs == null) return;
        long old = System.currentTimeMillis() - 24L * 3600 * 1000;
        for (File f : fs) if (f.lastModified() < old) f.delete();
    }

    private Uri share(File f) {
        return FileProvider.getUriForFile(this, getPackageName() + ".files", f);
    }

    private void toast(final String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    private boolean isSite(Uri u) {
        String h = Uri.parse(SITE).getHost();
        return h != null && h.equalsIgnoreCase(u.getHost());
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (isSite(u)) return false;
            // Maps, mailto: and any other outside link open in their own app.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (ActivityNotFoundException e) {
                toast("No app can open this link.");
            }
            return true;
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (!r.isForMainFrame()) return;
            // Only reached before the first successful load; after that the page works offline.
            String html = "<meta name=viewport content='width=device-width,initial-scale=1'>"
                    + "<body style='font:17px system-ui,sans-serif;padding:40px 24px;text-align:center'>"
                    + "<h2>Can't reach Timesheet</h2><p>The first start needs an internet connection. "
                    + "After that it works offline.</p><p><button style='font:inherit;padding:12px 24px' "
                    + "onclick=\"location.replace('" + SITE + "')\">Try again</button></p>";
            v.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                cb.invoke(origin, true, false);
                return;
            }
            geoOrigin = origin;
            geoCb = cb;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, RC_GEO);
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            // Photos: offer the picker plus the camera. Anything else (CSV, backup): any file,
            // because file managers label those types inconsistently.
            boolean img = false;
            for (String t : p.getAcceptTypes()) if (t != null && t.startsWith("image/")) img = true;
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType(img ? "image/*" : "*/*");
            if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            Intent chooser = Intent.createChooser(pick, img ? "Add photos" : "Choose a file");
            camFile = null;
            if (img) {
                try {
                    camFile = new File(outDir(), "photo-" + System.currentTimeMillis() + ".jpg");
                    camUri = share(camFile);
                    Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, camUri);
                    cam.setClipData(ClipData.newRawUri("photo", camUri));
                    cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                } catch (Exception e) {
                    camFile = null;
                }
            }
            try {
                startActivityForResult(chooser, RC_FILE);
            } catch (ActivityNotFoundException e) {
                fileCb = null;
                cb.onReceiveValue(null);
                toast("No app can pick files.");
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int rc, int result, Intent data) {
        super.onActivityResult(rc, result, data);
        if (rc != RC_FILE || fileCb == null) return;
        Uri[] out = null;
        if (result == RESULT_OK) {
            if (data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                ClipData c = data.getClipData();
                out = new Uri[c.getItemCount()];
                for (int i = 0; i < out.length; i++) out[i] = c.getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) {
                out = new Uri[]{data.getData()};
            } else if (camFile != null && camFile.length() > 0) {
                out = new Uri[]{camUri};
            }
        }
        fileCb.onReceiveValue(out);
        fileCb = null;
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] results) {
        super.onRequestPermissionsResult(rc, perms, results);
        if (rc == RC_NOTIF) {
            // allowed just now: show the notification for a timer that is already running
            web.evaluateJavascript("window.pushTimer&&pushTimer()", null);
            return;
        }
        if (rc != RC_GEO || geoCb == null) return;
        boolean ok = false;
        for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
        geoCb.invoke(geoOrigin, ok, false);
        geoCb = null;
    }

    /**
     * window.AndroidApp in the page. A file is sent as begin(name), append(base64 chunk)..., then
     * share(...) or save(...). Chunks keep big backups from hitting the bridge's string limits.
     * These run on a WebView worker thread, so anything touching the UI hops to the main thread.
     */
    private class Bridge {
        private File cur;
        private FileOutputStream os;

        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        /** The page reports the timer: {on, name, start, brkStart, brkMs, warnH, snz}. Shows or clears the notification and the "still running" alarm. */
        @JavascriptInterface
        public void timer(String json) {
            try {
                JSONObject o = new JSONObject(json);
                TimerState.save(MainActivity.this, o);
                if (o.optInt("on", 0) != 0) askNotifPermission();
                TimerState.show(MainActivity.this, o);
            } catch (Exception ignored) {
            }
        }

        /** Break / Resume / Stop taps from the notification that the page has not applied yet, as a JSON array; empties the queue. */
        @JavascriptInterface
        public String takeEvents() {
            return TimerState.take(MainActivity.this);
        }

        /** The update found earlier as JSON {v, cur, url}, or an empty string. */
        @JavascriptInterface
        public String updateInfo() {
            return Updater.json(Updater.saved(MainActivity.this));
        }

        /** Looks again now; the page hears back through onAppUpdate / onUpdateChecked. */
        @JavascriptInterface
        public void checkUpdate() {
            checkNow(true);
        }

        /**
         * Downloads the newer APK and opens Android's installer. Installing from this app needs a one-time
         * permission; if it is missing the settings page for it opens. If the download fails the release page opens instead.
         */
        @JavascriptInterface
        public void installUpdate() {
            final Updater.Info i = Updater.saved(MainActivity.this);
            if (i == null) {
                toast("No update found. Tap Check for updates.");
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                runOnUiThread(() -> {
                    toast("Allow installs from Timesheet, then come back and tap Install again.");
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                });
                return;
            }
            new Thread(() -> {
                try {
                    final File f = Updater.download(i.url, outDir());
                    runOnUiThread(() -> installApk(f));
                } catch (Exception e) {
                    toast("Download failed. Opening the download page.");
                    runOnUiThread(() -> openUrl(Updater.PAGE));
                }
            }).start();
        }

        @JavascriptInterface
        public synchronized boolean begin(String name) {
            try {
                if (os != null) os.close();
                String safe = name == null ? "file" : name.replaceAll("[^A-Za-z0-9._ -]", "_");
                cur = new File(outDir(), safe);
                os = new FileOutputStream(cur);
                return true;
            } catch (Exception e) {
                os = null;
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean append(String b64) {
            try {
                os.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private File finish() {
            try {
                if (os != null) os.close();
            } catch (Exception ignored) {
            }
            os = null;
            return cur;
        }

        /** Opens the Android share sheet with the file attached; email is prefilled for mail apps. */
        @JavascriptInterface
        public synchronized void share(String mime, String subject, String text, String email) {
            final File f = finish();
            if (f == null) return;
            runOnUiThread(() -> {
                try {
                    Uri u = MainActivity.this.share(f);
                    Intent i = new Intent(Intent.ACTION_SEND).setType(mime == null || mime.isEmpty() ? "*/*" : mime);
                    i.putExtra(Intent.EXTRA_STREAM, u);
                    if (subject != null && !subject.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, subject);
                    if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
                    if (email != null && !email.isEmpty()) i.putExtra(Intent.EXTRA_EMAIL, new String[]{email});
                    i.setClipData(ClipData.newRawUri(f.getName(), u));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Send " + f.getName()));
                } catch (Exception e) {
                    toast("Could not share the file.");
                }
            });
        }

        /** Copies the file into the phone's Downloads folder (Android 10+); older phones get the share sheet. */
        @JavascriptInterface
        public synchronized void save(String mime) {
            final File f = finish();
            if (f == null) return;
            if (Build.VERSION.SDK_INT < 29) {
                cur = f;
                share(mime, "", "", "");
                return;
            }
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                try (InputStream in = new FileInputStream(f); OutputStream out = getContentResolver().openOutputStream(u)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                toast("Saved to Downloads: " + f.getName());
            } catch (Exception e) {
                toast("Could not save the file. Try Share instead.");
            }
        }

        /** Tints the system bars to match the page's theme (top = page background, bottom = tab bar). */
        @JavascriptInterface
        public void bars(final String top, final String bottom, final boolean dark) {
            runOnUiThread(() -> {
                try {
                    Window w = getWindow();
                    w.setStatusBarColor(Color.parseColor(top));
                    w.setNavigationBarColor(Color.parseColor(bottom));
                    View d = w.getDecorView();
                    int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
                    int f = d.getSystemUiVisibility();
                    d.setSystemUiVisibility(dark ? f & ~light : f | light);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
