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

## Security and user binding

The two exported receivers are protocol endpoints, not general broadcast APIs. They accept only:

- the exact action and explicit IceBridge receiver component;
- a caller running as `system_server` (`SYSTEM_UID`);
- a payload user ID equal to `BroadcastReceiver.getSendingUserId()`;
- a valid source package/activity and a fresh HTTP(S) URL;
- for recovery, an original Intent whose package/component and URL match the signed request fields.

Missing or mismatched fields are rejected before `goAsync()`. Every asynchronous path has a single-completion guard and a 10-second timeout. The receiver does not start an Activity on Android 16; `system_server` owns the follow-up launch.

## Compatibility matrix

Run the following matrix before a release and attach logs and the release SHA-256 to the change record:

| API | Platform | User contexts | Required checks |
| --- | --- | --- | --- |
| 34 | AOSP and Xiaomi | owner, secondary, work profile | capture, thaw, launch, clipboard |
| 35 | AOSP and Xiaomi | owner, secondary, work profile | notification permission, background limits, FGS |
| 36 | AOSP and Xiaomi | owner, secondary, work profile | receiver dispatch, `system_server` delay, BAL launch |

The repository tests and lint run in CI, but physical API 34/35/36 and Xiaomi validation is still a release-gate activity rather than a host-side test.

## Permissions and operations

`QUERY_ALL_PACKAGES` is retained because IceBox integration enumerates managed packages. The release checklist must confirm that this use is still required. `POST_NOTIFICATIONS` is declared for Android 13+; the UI must report denial without treating it as root or hook failure. `ClipboardLinkService` uses `dataSync` only for clipboard observation and must be checked against Android 14+ foreground-service restrictions and battery impact.

For LSPosed, keep the `system` scope for the built-in system path. Add a host app scope only for optional public WebView observation. IceBox state changes and root actions use fixed structured operations; arbitrary text is never passed to `su -c`.

## Rules, rollback and diagnostics

Online rules must remain pinned by revision and checksum, keep a last-known-good cache, reject malformed or downgraded revisions, and record source, revision, checksum, activation result and rollback reason. A production hardening follow-up should add signed rule packages, key rotation, anti-rollback, staged rollout and an auditable release record.

Use the in-app diagnostic export after reproducing a failure. Include API level, ROM, Android user/profile, source activity, target package, event result and the release SHA-256; redact URLs and clipboard contents before sharing. Do not collect arbitrary command output or personal clipboard data.

Signing certificates are release identity. Store the keystore outside the repository, record the expected certificate SHA-256 in CI secrets, and treat a certificate change as a migration requiring an explicit upgrade plan. R8/resource shrinking remains disabled until keep rules and regression coverage cover LSPosed entry points, reflection, Gson, LitePal and manifest components.
