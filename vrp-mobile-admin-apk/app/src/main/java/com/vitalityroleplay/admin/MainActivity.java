package com.vitalityroleplay.admin;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String PREFS = "vitality_admin";
    private static final String URL_KEY = "server_url";
    private static final String CHANNEL_ID = "vitality_admin_alerts";
    private static final int NOTIFICATION_PERMISSION = 901;
    private WebView webView;
    private SharedPreferences prefs;
    private String pendingUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(9, 9, 18));
        getWindow().setNavigationBarColor(Color.rgb(9, 9, 18));
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        createNotificationChannel();
        requestNotificationPermission();
        showApp();
    }

    private void showApp() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(9, 9, 18));
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new VitalityBridge(), "VitalityNative");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());

        String url = prefs.getString(URL_KEY, "");
        if (url.isEmpty()) showServerSetup();
        else authenticateAndLoad(url);
    }

    private void authenticateAndLoad(String url) {
        pendingUrl = url;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            webView.loadUrl(url);
            return;
        }
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) {
            webView.loadUrl(url);
            return;
        }
        runBiometric(() -> webView.loadUrl(pendingUrl));
    }

    private void runBiometric(Runnable success) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            success.run();
            return;
        }
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
            .setTitle("Unlock Vitality Admin")
            .setSubtitle("Verify your identity to access server administration");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setDeviceCredentialAllowed(true);
        } else {
            builder.setNegativeButton("Cancel", getMainExecutor(), (dialog, which) -> {});
        }
        BiometricPrompt prompt = builder.build();
        prompt.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                success.run();
            }
        });
    }

    private void showServerSetup() {
        final EditText input = new EditText(this);
        input.setHint("https://admin.yourdomain.com");
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView help = new TextView(this);
        help.setText("Enter the HTTPS address for your Vitality Mobile Admin server. You only need to do this once.");
        help.setTextColor(Color.LTGRAY);
        help.setPadding(0, 0, 0, pad / 2);
        box.addView(help);
        box.addView(input);

        new AlertDialog.Builder(this)
            .setTitle("Connect Vitality Admin")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Connect", (dialog, which) -> {
                String url = normalize(input.getText().toString());
                if (url == null) { showServerSetup(); return; }
                prefs.edit().putString(URL_KEY, url).apply();
                authenticateAndLoad(url);
            })
            .setNegativeButton("Exit", (dialog, which) -> finish())
            .show();
    }

    private String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (!s.startsWith("https://")) return null;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? null : s;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Vitality Admin Alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("New reports, watchlist joins and important Vitality admin alerts");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
    }

    private void postNotification(String title, String body) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new android.app.Notification.Builder(this, CHANNEL_ID)
            : new android.app.Notification.Builder(this);
        b.setSmallIcon(R.drawable.vitality_logo)
            .setContentTitle(title == null ? "Vitality Admin" : title)
            .setContentText(body == null ? "" : body)
            .setStyle(new android.app.Notification.BigTextStyle().bigText(body == null ? "" : body))
            .setAutoCancel(true)
            .setContentIntent(pi);
        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int)(System.currentTimeMillis() & 0x7fffffff), b.build());
    }

    public class VitalityBridge {
        @JavascriptInterface public String deviceName() {
            return Build.MANUFACTURER + " " + Build.MODEL + " • Vitality Admin";
        }
        @JavascriptInterface public void notify(String title, String body) {
            runOnUiThread(() -> postNotification(title, body));
        }
        @JavascriptInterface public void unlock() {
            runOnUiThread(() -> runBiometric(() -> webView.evaluateJavascript("window.vitalityUnlocked && window.vitalityUnlocked()", null)));
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
