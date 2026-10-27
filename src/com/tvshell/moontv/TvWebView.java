package com.tvshell.moontv;

import android.graphics.Bitmap;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebResourceResponse;

import java.io.InputStream;

/**
 * 统一 WebView 抽象层。
 *
 * 系统 WebView（android.webkit.WebView）与 X5 WebView（com.tencent.smtt.sdk.WebView）
 * 是两套互不相容的 API 类型。本抽象层把二者收敛成同一组方法，活动层只面向 TvWebView 编程，
 * 切换内核时无需改动任何业务代码。
 *
 * 两个实现：
 *   SysWebView  - 包装 android.webkit.WebView（降级路径）
 *   X5WebView   - 包装 com.tencent.smtt.sdk.WebView（X5 内核 Chromium 121）
 */
public interface TvWebView {

    /** 应用 MOONTV 播放所需的基础设置（JS / DOM / 媒体自动播放 / 混合内容 / UA 等）。 */
    void applyMoontvSettings(String ua);

    /** 切换 UA。 */
    void setUserAgentString(String ua);

    String getUserAgentString();

    void loadUrl(String url);

    /** 以指定 baseUrl 载入 HTML 文本（本地播放器页用，避免 file:// 限制）。 */
    void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding, String historyUrl);

    /** 停止当前页面加载（换台清场用）。 */
    void stopLoading();

    /** 注入 JS。cb 为 android.webkit.ValueCallback（X5 实现内部会转成 smtt 的 ValueCallback）。 */
    void evaluateJavascript(String js, ValueCallback<String> cb);

    String getUrl();

    void setVisibility(int visibility);

    void destroy();

    void onPause();

    void onResume();

    boolean canGoBack();

    void goBack();

    void setWebViewClient(TvWebViewClient client);

    void setWebChromeClient(TvWebChromeClient client);

    void setOnTouchListener(View.OnTouchListener l);

    void setOnKeyListener(View.OnKeyListener l);

    /** 注入 JS 桥对象（addJavascriptInterface）。 */
    void addJavascriptInterface(Object obj, String name);

    /**
     * 给 WebView 写入 Cookie。
     *
     * 【为什么必须有】LunaTV 的 middleware 对除白名单外的所有路径（含 /api/proxy/*）
     * 都要 auth cookie。直播播放地址必须走服务端代理 /api/proxy/m3u8，
     * X5 内核的媒体请求 / hls.js 的 XHR 都不会自动带上我们 java.net 侧存的 cookie，
     * 所以必须在建 WebView 之后显式把 auth cookie 写进内核的 CookieManager，
     * 否则代理返回 401 → 直播黑屏。
     */
    void setCookie(String url, String value);

    /** 底层真实 View（用于视图树插入 / 替换）。 */
    View getView();

    /** 是否为 X5 内核。 */
    boolean isX5();
}

/**
 * 中性 WebViewClient 抽象。活动层实现它，两个引擎各自转成自己的原生 Client 类型。
 */
abstract class TvWebViewClient {
    public boolean shouldOverrideUrlLoading(TvWebView view, String url) { return false; }
    /** 返回 null 表示放行；返回 TvWebResp 表示由我们提供响应体。 */
    public TvWebResp shouldInterceptRequest(TvWebView view, String url) { return null; }
    public void onPageStarted(TvWebView view, String url, Bitmap favicon) {}
    public void onPageFinished(TvWebView view, String url) {}
    public void onReceivedError(TvWebView view, int errorCode, String description, String failingUrl) {}
}

/** 中性 WebChromeClient 抽象。 */
abstract class TvWebChromeClient {
    /** @return true 表示已消费该 console 消息。 */
    public boolean onConsoleMessage(String level, String message, String sourceId, int lineNumber) { return false; }
    public void onProgressChanged(TvWebView view, int newProgress) {}
    public void onShowCustomView(View view, TvCustomViewCallback cb) {}
    public void onHideCustomView() {}
}

/** 原生全屏视图的隐藏回调。 */
interface TvCustomViewCallback {
    void onCustomViewHidden();
}

/** 中性响应体（两个引擎各自转成自己的 WebResourceResponse）。 */
class TvWebResp {
    public final String mimeType;
    public final String encoding;
    public final InputStream data;
    public TvWebResp(String mimeType, String encoding, InputStream data) {
        this.mimeType = mimeType;
        this.encoding = encoding;
        this.data = data;
    }
}

/** 系统 WebView 实现（android.webkit.WebView）。 */
class SysWebView implements TvWebView {
    private final android.webkit.WebView wv;

    SysWebView(android.webkit.WebView wv) { this.wv = wv; }

    @Override public void applyMoontvSettings(String ua) {
        android.webkit.WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        try { wv.setLongClickable(false); } catch (Throwable ignore) {}
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        s.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(ua);
    }

    @Override public void setUserAgentString(String ua) { wv.getSettings().setUserAgentString(ua); }
    @Override public String getUserAgentString() { return wv.getSettings().getUserAgentString(); }
    @Override public void loadUrl(String url) { wv.loadUrl(url); }
    @Override public void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding, String historyUrl) { wv.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl); }
    @Override public void stopLoading() { wv.stopLoading(); }
    @Override public void evaluateJavascript(String js, ValueCallback<String> cb) { wv.evaluateJavascript(js, cb); }
    @Override public String getUrl() { return wv.getUrl(); }
    @Override public void setVisibility(int v) { wv.setVisibility(v); }
    @Override public void destroy() { wv.destroy(); }

    @Override public void onPause() { wv.onPause(); }
    @Override public void onResume() { wv.onResume(); }
    @Override public boolean canGoBack() { return wv.canGoBack(); }
    @Override public void goBack() { wv.goBack(); }

    @Override public void setWebViewClient(final TvWebViewClient c) {
        wv.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView v, String url) {
                return c.shouldOverrideUrlLoading(SysWebView.this, url);
            }
            @Override public WebResourceResponse shouldInterceptRequest(android.webkit.WebView v, String url) {
                TvWebResp r = c.shouldInterceptRequest(SysWebView.this, url);
                return r == null ? null : new WebResourceResponse(r.mimeType, r.encoding, r.data);
            }
            @Override public void onPageStarted(android.webkit.WebView v, String url, Bitmap f) {
                c.onPageStarted(SysWebView.this, url, f);
            }
            @Override public void onPageFinished(android.webkit.WebView v, String url) {
                c.onPageFinished(SysWebView.this, url);
            }
            @Override public void onReceivedError(android.webkit.WebView v, int code, String d, String fu) {
                c.onReceivedError(SysWebView.this, code, d, fu);
            }
        });
    }

    @Override public void setWebChromeClient(final TvWebChromeClient c) {
        wv.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                return c.onConsoleMessage(
                        m.messageLevel() == null ? "" : m.messageLevel().name(),
                        m.message(), m.sourceId(), m.lineNumber());
            }
            @Override public void onProgressChanged(android.webkit.WebView v, int p) {
                c.onProgressChanged(SysWebView.this, p);
            }
            @Override public void onShowCustomView(View view, android.webkit.WebChromeClient.CustomViewCallback cb) {
                c.onShowCustomView(view, new TvCustomViewCallback() {
                    @Override public void onCustomViewHidden() { cb.onCustomViewHidden(); }
                });
            }
            @Override public void onHideCustomView() { c.onHideCustomView(); }
        });
    }

    @Override public void setOnTouchListener(View.OnTouchListener l) { wv.setOnTouchListener(l); }
    @Override public void setOnKeyListener(View.OnKeyListener l) { wv.setOnKeyListener(l); }
    @Override public void addJavascriptInterface(Object obj, String name) { wv.addJavascriptInterface(obj, name); }
    @Override public void setCookie(String url, String value) {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setCookie(url, value);
            cm.flush();
        } catch (Throwable ignore) {
        }
    }
    @Override public View getView() { return wv; }
    @Override public boolean isX5() { return false; }
}

/** X5 WebView 实现（com.tencent.smtt.sdk.WebView）。 */
class X5WebView implements TvWebView {
    private final com.tencent.smtt.sdk.WebView x5;

    X5WebView(android.content.Context ctx) {
        x5 = new com.tencent.smtt.sdk.WebView(ctx);
    }

    @Override public void applyMoontvSettings(String ua) {
        com.tencent.smtt.sdk.WebSettings s = x5.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        try { x5.getView().setLongClickable(false); } catch (Throwable ignore) {}
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        s.setCacheMode(com.tencent.smtt.sdk.WebSettings.LOAD_DEFAULT);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(ua);
    }

    @Override public void setUserAgentString(String ua) { x5.getSettings().setUserAgentString(ua); }
    @Override public String getUserAgentString() { return x5.getSettings().getUserAgentString(); }
    @Override public void loadUrl(String url) { x5.loadUrl(url); }
    @Override public void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding, String historyUrl) { x5.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl); }
    @Override public void stopLoading() { x5.stopLoading(); }
    @Override public void evaluateJavascript(final String js, final ValueCallback<String> cb) {
        x5.evaluateJavascript(js, new com.tencent.smtt.sdk.ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                if (cb != null) cb.onReceiveValue(value);
            }
        });
    }
    @Override public String getUrl() { return x5.getUrl(); }
    @Override public void setVisibility(int v) { x5.setVisibility(v); }
    @Override public void destroy() { x5.destroy(); }

    @Override public void onPause() { x5.onPause(); }
    @Override public void onResume() { x5.onResume(); }
    @Override public boolean canGoBack() { return x5.canGoBack(); }
    @Override public void goBack() { x5.goBack(); }

    @Override public void setWebViewClient(final TvWebViewClient c) {
        x5.setWebViewClient(new com.tencent.smtt.sdk.WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(com.tencent.smtt.sdk.WebView v, String url) {
                return c.shouldOverrideUrlLoading(X5WebView.this, url);
            }
            @Override public com.tencent.smtt.export.external.interfaces.WebResourceResponse shouldInterceptRequest(
                    com.tencent.smtt.sdk.WebView v, String url) {
                TvWebResp r = c.shouldInterceptRequest(X5WebView.this, url);
                if (r == null) return null;
                return new com.tencent.smtt.export.external.interfaces.WebResourceResponse(
                        r.mimeType, r.encoding, r.data);
            }
            @Override public void onPageStarted(com.tencent.smtt.sdk.WebView v, String url, Bitmap f) {
                c.onPageStarted(X5WebView.this, url, f);
            }
            @Override public void onPageFinished(com.tencent.smtt.sdk.WebView v, String url) {
                c.onPageFinished(X5WebView.this, url);
            }
            @Override public void onReceivedError(com.tencent.smtt.sdk.WebView v, int code, String d, String fu) {
                c.onReceivedError(X5WebView.this, code, d, fu);
            }
        });
    }

    @Override public void setWebChromeClient(final TvWebChromeClient c) {
        x5.setWebChromeClient(new com.tencent.smtt.sdk.WebChromeClient() {
            @Override public boolean onConsoleMessage(
                    com.tencent.smtt.export.external.interfaces.ConsoleMessage m) {
                return c.onConsoleMessage(
                        m.messageLevel() == null ? "" : m.messageLevel().name(),
                        m.message(), m.sourceId(), m.lineNumber());
            }
            @Override public void onProgressChanged(com.tencent.smtt.sdk.WebView v, int p) {
                c.onProgressChanged(X5WebView.this, p);
            }
            @Override public void onShowCustomView(android.view.View view,
                    com.tencent.smtt.export.external.interfaces.IX5WebChromeClient.CustomViewCallback cb) {
                c.onShowCustomView(view, new TvCustomViewCallback() {
                    @Override public void onCustomViewHidden() { cb.onCustomViewHidden(); }
                });
            }
            @Override public void onHideCustomView() { c.onHideCustomView(); }
        });
    }

    @Override public void setOnTouchListener(View.OnTouchListener l) { x5.setOnTouchListener(l); }
    @Override public void setOnKeyListener(View.OnKeyListener l) { x5.setOnKeyListener(l); }
    @Override public void addJavascriptInterface(Object obj, String name) { x5.addJavascriptInterface(obj, name); }
    @Override public void setCookie(String url, String value) {
        try {
            com.tencent.smtt.sdk.CookieManager cm = com.tencent.smtt.sdk.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setCookie(url, value);
            cm.flush();
        } catch (Throwable ignore) {
        }
    }
    @Override public View getView() { return x5; }
    @Override public boolean isX5() { return true; }
}
