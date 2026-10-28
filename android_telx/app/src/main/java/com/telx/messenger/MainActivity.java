package com.telx.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.Manifest;
import android.content.pm.PackageManager;

/**
 * TelX — MainActivity
 * Full-screen WebView shell around the TelX web messenger.
 * BASE_URL points at the Alwaysdata deployment.
 */
public class MainActivity extends AppCompatActivity {

    public static final String BASE_URL = "https://mybackup-test.alwaysdata.net/telx/";
    private static final int FILE_CHOOSER_CODE = 101;
    private static final int PERMS_CODE = 102;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private SwipeRefreshLayout swipeRefresh;

    /** JS bridge: lets any page hosted inside this WebView hand its session token to the native layer. */
    private class NativeBridge {
        @android.webkit.JavascriptInterface
        public void saveToken(String token) {
            if (token == null || token.length() < 16) return;
            getSharedPreferences(NotificationService.PREFS, MODE_PRIVATE)
                    .edit().putString("token", token).apply();
            NotificationService.start(MainActivity.this);
        }
    }

    /** Hooks window.fetch: whenever an API response contains a token, forward it to native. */
    private static final String TOKEN_HOOK_JS =
            "(function(){" +
            "  if (window.__telxHooked) return; window.__telxHooked = true;" +
            "  try {" +
            "    var of = window.fetch;" +
            "    window.fetch = function() {" +
            "      return of.apply(this, arguments).then(function(res) {" +
            "        try { res.clone().json().then(function(j) {" +
            "          if (j && j.token && window.TelXNative) window.TelXNative.saveToken(j.token);" +
            "        }).catch(function(){}); } catch (e) {}" +
            "        return res;" +
            "      });" +
            "    };" +
            "  } catch (e) {}" +
            "})();";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        swipeRefresh = findViewById(R.id.swipe);
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.tg_blue));
        swipeRefresh.setOnRefreshListener(() -> webView.reload());

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString("TelX-Android/1.0 " + s.getUserAgentString());
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost() == null ? "" : uri.getHost();
                // keep the user inside TelX hosts; open external links in browser
                if (host.contains("alwaysdata.net") || host.contains("telx")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                swipeRefresh.setRefreshing(false);
                view.evaluateJavascript(TOKEN_HOOK_JS, null);
                // ask the web app for its theme so the status bar matches
                view.evaluateJavascript(
                        "document.documentElement.classList.contains('dark') ? 'dark' : 'light'",
                        value -> {
                            boolean dark = value != null && value.contains("dark");
                            getWindow().setStatusBarColor(
                                    ContextCompat.getColor(MainActivity.this,
                                            dark ? R.color.tg_dark_panel : R.color.tg_blue));
                        });
            }
        });

        webView.addJavascriptInterface(new NativeBridge(), "TelXNative");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                requestRuntimePerms();
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, params.getAcceptTypes() != null
                        && params.getAcceptTypes().length > 0 ? params.getAcceptTypes()
                        : new String[]{"*/*"});
                try {
                    startActivityForResult(Intent.createChooser(intent, "انتخاب فایل"), FILE_CHOOSER_CODE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String disposition,
                                        String mime, long size) {
                try {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    String name = Uri.parse(url).getLastPathSegment();
                    if (name == null || name.isEmpty()) name = "telx_file";
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) dm.enqueue(req);
                } catch (Exception ignored) { }
            }
        });

        // notifications service (no Firebase) — foreground, polls the PHP API
        requestRuntimePerms();
        NotificationService.start(this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack();
                else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl(BASE_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void requestRuntimePerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.POST_NOTIFICATIONS,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.CAMERA}, PERMS_CODE);
                return;
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, PERMS_CODE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_CODE) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        NotificationService.setAppVisible(true);
    }

    @Override
    protected void onPause() {
        webView.onPause();
        NotificationService.setAppVisible(false);
        super.onPause();
    }
}
