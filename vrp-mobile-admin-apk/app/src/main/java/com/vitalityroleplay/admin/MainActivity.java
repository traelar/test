package com.vitalityroleplay.admin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
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
    private WebView webView;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(9, 9, 18));
        getWindow().setNavigationBarColor(Color.rgb(9, 9, 18));
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());

        String url = prefs.getString(URL_KEY, "");
        if (url.isEmpty()) showServerSetup();
        else webView.loadUrl(url);
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
                webView.loadUrl(url);
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

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
