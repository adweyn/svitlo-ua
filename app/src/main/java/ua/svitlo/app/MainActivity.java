package ua.svitlo.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import android.widget.LinearLayout;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import java.io.File;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private AdView bannerAdView;
    private static final String CHANNEL_ID = "svitlo_channel";
    private static final int NOTIF_ID_BASE = 1000;
    private static final int PERM_REQUEST_NOTIF = 101;
    private static final int REQUEST_INSTALL_PERM = 102;
    private int notifCounter = 0;
    private long otaDownloadId = -1;
    private File otaFile = null;
    private boolean receiverRegistered = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable otaProgressPoller = null;
    private String pendingInstallPath = null;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        try { createNotificationChannel(); } catch (Exception ignored) {}

        webView = findViewById(R.id.webview);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("file://")) { view.loadUrl(url); return true; }
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) {}
                    return true;
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // If OTA update file fails to load, delete it and fall back to bundled asset
                if (failingUrl != null && failingUrl.contains("update/index.html")) {
                    File broken = new File(getFilesDir(), "update/index.html");
                    if (broken.exists()) broken.delete();
                    view.loadUrl("file:///android_asset/index.html");
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        // Check for local OTA update file, validate it before loading
        File localUpdate = new File(getFilesDir(), "update/index.html");
        if (localUpdate.exists() && localUpdate.canRead() && localUpdate.length() > 100) {
            webView.loadUrl("file://" + localUpdate.getAbsolutePath());
        } else {
            // Delete broken/empty OTA file if it exists
            if (localUpdate.exists()) localUpdate.delete();
            webView.loadUrl("file:///android_asset/index.html");
        }


        // ── AdMob banner ───────────────────────────────────
        MobileAds.initialize(this, status -> {});
        try {
            bannerAdView = new AdView(this);
            bannerAdView.setAdUnitId("ca-app-pub-8032804432919055/2745798706");
            bannerAdView.setAdSize(AdSize.BANNER);
            LinearLayout adContainer = findViewById(R.id.ad_container);
            if (adContainer != null) {
                adContainer.addView(bannerAdView);
                bannerAdView.loadAd(new AdRequest.Builder().build());
            }
        } catch (Exception e) {}
        registerOtaReceiver();
    }

    private void registerOtaReceiver() {
        try {
            IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(otaReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else
                registerReceiver(otaReceiver, f);
            receiverRegistered = true;
        } catch (Exception e) { receiverRegistered = false; }
    }

    @Override
    protected void onPause() {
        if (bannerAdView != null) bannerAdView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bannerAdView != null) bannerAdView.resume();
        // If user returned from "allow install from unknown sources" screen
        if (pendingInstallPath != null) {
            String path = pendingInstallPath;
            pendingInstallPath = null;
            handler.postDelayed(() -> performInstall(path), 300);
        }
    }

    @Override
    protected void onDestroy() {
        if (bannerAdView != null) bannerAdView.destroy();
        super.onDestroy();
        stopOtaProgressPoller();
        if (receiverRegistered) try { unregisterReceiver(otaReceiver); } catch (Exception e) {}
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == PERM_REQUEST_NOTIF) {
            boolean ok = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
            notifyJs("if(window.onNotifPermResult) window.onNotifPermResult(" + ok + ")");
        }
    }

    // ── OTA RECEIVER ──────────────────────────────────────
    private final BroadcastReceiver otaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (intent == null) return;
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != otaDownloadId || id == -1) return;
            stopOtaProgressPoller();
            try {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) return;
                Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(id));
                if (cursor == null) return;
                if (cursor.moveToFirst()) {
                    int statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int status = statusCol >= 0 ? cursor.getInt(statusCol) : -1;
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        // Get path from DownloadManager
                        int uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                        if (uriCol >= 0) {
                            String uriStr = cursor.getString(uriCol);
                            if (uriStr != null) {
                                try { otaFile = new File(Uri.parse(uriStr).getPath()); } catch (Exception e) {}
                            }
                        }
                        // Verify the file exists, fallback to known path
                        if (otaFile == null || !otaFile.exists()) {
                            otaFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "svitlo-ua-update.apk");
                        }
                        notifyJs("if(window.onOtaDownloadComplete) window.onOtaDownloadComplete(true,'" +
                            (otaFile != null ? otaFile.getAbsolutePath() : "") + "')");
                    } else {
                        int reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                        int reason = reasonCol >= 0 ? cursor.getInt(reasonCol) : -1;
                        notifyJs("if(window.onOtaDownloadComplete) window.onOtaDownloadComplete(false,'error:" + reason + "')");
                    }
                }
                cursor.close();
            } catch (Exception e) {
                notifyJs("if(window.onOtaDownloadComplete) window.onOtaDownloadComplete(false,'exception')");
            }
        }
    };

    private void startOtaProgressPoller(final long downloadId) {
        stopOtaProgressPoller();
        otaProgressPoller = new Runnable() {
            @Override public void run() {
                try {
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm == null) return;
                    Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId));
                    if (c != null && c.moveToFirst()) {
                        int statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int status = statusCol >= 0 ? c.getInt(statusCol) : -1;

                        int totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                        int doneCol  = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        long total = totalCol >= 0 ? c.getLong(totalCol) : -1;
                        long done  = doneCol  >= 0 ? c.getLong(doneCol)  : 0;

                        if (status == DownloadManager.STATUS_FAILED) {
                            int reasonCol = c.getColumnIndex(DownloadManager.COLUMN_REASON);
                            int reason = reasonCol >= 0 ? c.getInt(reasonCol) : -1;
                            stopOtaProgressPoller();
                            notifyJs("if(window.onOtaDownloadComplete) window.onOtaDownloadComplete(false,'failed:" + reason + "')");
                        } else if (total > 0 && done >= 0) {
                            // Known size — show real %
                            int pct = (int) Math.min(98, done * 100 / total);
                            notifyJs("if(window.onOtaProgress) window.onOtaProgress(" + pct + "," + done + "," + total + ")");
                        } else if (done > 0) {
                            // Size unknown (server didn't send Content-Length) — show bytes downloaded
                            notifyJs("if(window.onOtaProgress) window.onOtaProgress(-1," + done + ",0)");
                        } else {
                            // Just started — send pulse so UI knows it's alive
                            notifyJs("if(window.onOtaProgress) window.onOtaProgress(-1,0,0)");
                        }
                        c.close();
                    }
                } catch (Exception ignored) {}
                handler.postDelayed(this, 500);
            }
        };
        handler.postDelayed(otaProgressPoller, 400);
    }

    private void stopOtaProgressPoller() {
        if (otaProgressPoller != null) {
            handler.removeCallbacks(otaProgressPoller);
            otaProgressPoller = null;
        }
    }

    // ── INSTALL APK ───────────────────────────────────────
    private void performInstall(String pathHint) {
        File apk = otaFile;
        if (apk == null || !apk.exists())
            apk = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "svitlo-ua-update.apk");
        if (apk == null || !apk.exists()) {
            notifyJs("if(window.onInstallError) window.onInstallError('Файл не знайдено: " + (apk != null ? apk.getAbsolutePath() : "null") + "')");
            showToast("APK не знайдено");
            return;
        }

        // Android 8+: check unknown sources permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                // Save path, open settings, install when user returns
                pendingInstallPath = apk.getAbsolutePath();
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    notifyJs("if(window.onInstallError) window.onInstallError('needs_permission')");
                } catch (Exception e) {
                    notifyJs("if(window.onInstallError) window.onInstallError('Відкрийте Налаштування → Додатки → Встановлення невідомих додатків')");
                }
                return;
            }
        }

        try {
            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            } else {
                apkUri = Uri.fromFile(apk);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            notifyJs("if(window.onInstallStarted) window.onInstallStarted()");
        } catch (Exception e) {
            notifyJs("if(window.onInstallError) window.onInstallError('" + e.getMessage() + "')");
            showToast("Помилка: " + e.getMessage());
        }
    }

    private void notifyJs(final String js) {
        if (webView != null) webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Стан світла", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Сповіщення про відключення світла");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 250, 100, 250});
            ch.enableLights(true);
            ch.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ══════════════════════════════════════════════════════
    // ANDROID BRIDGE
    // ══════════════════════════════════════════════════════
    public class AndroidBridge {
        private final Context ctx;
        AndroidBridge(Context c) { this.ctx = c; }

        @JavascriptInterface
        public void requestNotifPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED) {
                    notifyJs("if(window.onNotifPermResult) window.onNotifPermResult(true)");
                } else {
                    ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, PERM_REQUEST_NOTIF);
                }
            } else {
                notifyJs("if(window.onNotifPermResult) window.onNotifPermResult(true)");
            }
        }

        @JavascriptInterface
        public boolean isNotifPermissionGranted() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        @JavascriptInterface
        public void showNotification(String title, String body, String type) {
            try {
                boolean isOff = "r".equals(type);
                Intent openApp = new Intent(ctx, MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
                PendingIntent pi = PendingIntent.getActivity(ctx, notifCounter, openApp, piFlags);

                NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setColor(isOff ? 0xFFFF3D5A : 0xFF25D46A)
                    .setAutoCancel(true)
                    .setVibrate(new long[]{0, 250, 100, 250})
                    .setContentIntent(pi)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setDefaults(NotificationCompat.DEFAULT_ALL);

                NotificationManagerCompat.from(ctx).notify(NOTIF_ID_BASE + (notifCounter++ % 20), b.build());
            } catch (Exception e) {
                showToast("Notif err: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void testNotification() {
            showNotification("⚡ Тест сповіщень",
                "Якщо ви бачите це повідомлення — сповіщення працюють правильно!", "r");
        }

        @JavascriptInterface
        public void downloadApk(String url) {
            try {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) {
                    notifyJs("if(window.onOtaDownloadComplete) window.onOtaDownloadComplete(false,'no_dm')");
                    return;
                }
                // Delete old
                File old = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "svitlo-ua-update.apk");
                if (old.exists()) old.delete();

                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                    .setTitle("Світло UA — оновлення")
                    .setDescription("Завантаження нової версії...")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "svitlo-ua-update.apk")
                    .setMimeType("application/vnd.android.package-archive")
                    .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE)
                    .setAllowedOverRoaming(true);

                otaDownloadId = dm.enqueue(req);
                startOtaProgressPoller(otaDownloadId);
            } catch (Exception e) {
                notifyJs("if(window.onOtaDownloadComplete) window.onOtaDownloadComplete(false,'" + e.getMessage() + "')");
            }
        }

        @JavascriptInterface
        public void installApk(String pathHint) {
            performInstall(pathHint);
        }

        @JavascriptInterface
        public boolean canInstallApks() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                return getPackageManager().canRequestPackageInstalls();
            return true;
        }

        @JavascriptInterface
        public void openInstallPermissionSettings() {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) { showToast("Відкрийте Налаштування → Додатки вручну"); }
        }

        @JavascriptInterface
        public String getDeviceInfo() {
            return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")";
        }

        @JavascriptInterface
        public String getApkPath() {
            File f = otaFile;
            if (f == null) f = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "svitlo-ua-update.apk");
            return f.getAbsolutePath() + " · exists:" + f.exists() + " · size:" + (f.exists() ? f.length() : 0);
        }

        @JavascriptInterface
        public String getPlatform() { return "android"; }

        @JavascriptInterface
        public String getAppVersion() { return "5.0"; }

        @JavascriptInterface
        public void updateWebAssets(String downloadUrl) {
            new Thread(() -> {
                try {
                    java.net.URL url = new java.net.URL(downloadUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.connect();
                    if (conn.getResponseCode() == 200) {
                        java.io.InputStream is = conn.getInputStream();
                        File updateDir = new File(getFilesDir(), "update");
                        if (!updateDir.exists()) updateDir.mkdirs();
                        File updateFile = new File(updateDir, "index.html");
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(updateFile);
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                        fos.close();
                        is.close();
                        runOnUiThread(() -> {
                            webView.loadUrl("file://" + updateFile.getAbsolutePath());
                            Toast.makeText(MainActivity.this, "Додаток успішно оновлено!", Toast.LENGTH_SHORT).show();
                        });
                    } else {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Помилка сервера при оновленні", Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Помилка оновлення: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        @JavascriptInterface
        public void clearWebAssets() {
            File updateFile = new File(getFilesDir(), "update/index.html");
            if (updateFile.exists()) {
                updateFile.delete();
            }
            runOnUiThread(() -> {
                webView.loadUrl("file:///android_asset/index.html");
                Toast.makeText(MainActivity.this, "Скинуто до заводської версії", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public String getCustomWebPath() {
            File updateFile = new File(getFilesDir(), "update/index.html");
            return updateFile.exists() ? updateFile.getAbsolutePath() : "";
        }

    }

    private void showToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
