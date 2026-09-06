# IceBridge

IceBridge is an Android LSPosed module that restores a frozen app before the system launches it. It supports both IceBox SDK mode and Root (`su`) mode.

## Automatic link bridge

The automatic-link path is designed for the following flow:

```text
WeChat / QQ built-in browser activity -> system-side IceBridge observation extracts an HTTPS URL
-> IceBridge identifies a supported target package
-> only if that package is frozen, IceBridge restores it
-> IceBridge opens the original URL in that target app
```

Supported built-in HTTPS hosts include Xiaohongshu, Weibo, Zhihu, Alipay, X/Twitter, YouTube, Instagram, and Telegram. Additional mappings can be entered in Settings as `domain.example=com.example.app`.

For setup and the recommended LSPosed scope, see `docs/NATIVE_LINK_CAPTURE.md`. The default scope is `system` only; no WeChat, QQ, browser, or target-app scope is required for the system and clipboard paths. The optional WebView observer can only run in a host process that the user explicitly adds to LSPosed scope, and it hooks only the framework `android.webkit.WebView.loadUrl` API.

## Build

```text
./gradlew test
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/IceBridge-v0.0.73-debug.apk`.

