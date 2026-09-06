# Native link capture

IceBridge has two separate capture paths with different scope requirements:

## Default path: system only

The default `system` LSPosed scope observes system-side activity launches and the system clipboard. It recognizes the built-in browser activities used by WeChat and QQ from `system_server`, but it does **not** inject into or hook WeChat, QQ, Nagram, Chrome, IceBox, or any target application.

- WeChat: `com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI`
- QQ: `com.tencent.mobileqq.activity.QQBrowserActivity`

The hook extracts HTTP(S) values from the in-memory activity intent, posts a small URL event to the IceBridge app process, and immediately returns to Android's normal activity-start path. IceBox/root work and target-app launch stay outside `system_server`.

## Optional public WebView observation

A WebView navigation is executed in the host application's process, so `system` scope alone cannot observe `WebView.loadUrl`. If this optional feature is enabled, the user must explicitly add that host application to LSPosed scope. IceBridge then hooks only the Android framework methods:

- `android.webkit.WebView.loadUrl(String)`
- `android.webkit.WebView.loadUrl(String, Map)`

It does **not** hook the host application's Activity, WebViewClient subclass, JavaScript, business methods, or private Chromium implementation. The current implementation is observation-only: it records a redacted diagnostic event and leaves the original WebView navigation untouched. It does not call `stopLoading()`, rewrite URLs, thaw packages, or launch another app.

This means the strict no-app-scope configuration is intentionally safe but cannot provide global WebView capture. The system Activity and clipboard paths remain available without adding any app scope.

Enable **Built-in link capture (WeChat / QQ)** together with automatic link opening for the system path. Enable the optional public WebView switch only after explicitly scoping the host application and confirming the diagnostic events.