package org.rocks.kernelcompare;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Chromium 版直接使用系统 WebView；显示手机实际提供者版本，不冒称内置 Chrome。 */
final class EngineFactory {
    private EngineFactory() {}

    @SuppressLint("SetJavaScriptEnabled") // 两份内置测试页都需要 requestAnimationFrame，不开放原生桥。
    static PageEngine create(Context context, String testUrl, PageEngine.Events events) {
        final WebView web = new WebView(context);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.getSettings().setTextZoom(100);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setGeolocationEnabled(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !NavigationPolicy.allowed(request.getUrl().toString(), testUrl);
            }
            @Override public void onPageFinished(WebView view, String url) { events.location(url); }
        });
        final PackageInfo provider = WebView.getCurrentWebViewPackage();
        return new PageEngine() {
            @Override public View view() { return web; }
            @Override public String description() {
                return "Chromium · " + (provider == null ? "WebView" : provider.packageName + " " + provider.versionName);
            }
            @Override public void load(String url) { web.loadUrl(url); }
            @Override public void reload() { web.reload(); }
            @Override public void back() { if (web.canGoBack()) web.goBack(); }
            @Override public void foreground(boolean active) { if (active) web.onResume(); else web.onPause(); }
            @Override public void close() { web.stopLoading(); web.destroy(); }
        };
    }
}
