# Native link capture

## Enable the system path

Enable the IceBridge module with the `system` LSPosed scope, select and authorize IceBox or Root, download the owned online rules or add manual rules, then enable automatic links and native capture. Restart the device after installing an updated module so `system_server` loads the matching code and configuration contract.

The default path observes Activity requests in `ActivityStartInterceptor` and `ActivityStarter`. It recognizes the WeChat `MMWebViewUI` and QQ `QQBrowserActivity` requests without injecting into those apps. For a recognized frozen target, the hook replaces the request with IceBridge's foreground bridge Activity. The bridge restores the package and opens the destination; failure preserves the supported source flow. The Hook uses an in-memory configuration snapshot for routing and never downloads rules.

Payment SDK requests have a separate path that preserves the original Intent and callback chain. Do not treat them as ordinary HTTP redirects or rebuild their extras. Replay tokens and the handoff between the two Activity hooks prevent repeated interception of the same request.

System observation cannot see every WebView navigation that stays within an existing Activity. Legacy receiver and optional WebView helpers in the source are not evidence that those paths are installed by the current default entry point.

## Routing and configuration

Manual rules and the owned online feed use the same source-aware matcher as the rule preview. The system snapshot preserves source package, host, target, rule origin and the reviewed conversion enum. Manual rules take priority; within the same origin, exact sources and more specific domains take priority over wildcard sources and broader domains. Clipboard events use wildcard sources only. System-side thaw selection avoids guessing when equally preferred rules point to more than one frozen target.

HTTP(S) mappings are maintained in [online-rules](../online-rules/README.md). The client downloads a small manifest and its referenced full snapshot, validates SHA-256 and revision, and falls back to the last validated cache on failure. The GitHub source and jsDelivr fallback refer to the same owned repository. A newer full snapshot can remove rules; it is not merged indefinitely with older online data. Manual rules remain separate.

Configuration changes notify a worker-thread observer. Notifications are coalesced before refreshing; a 60-second poll remains a fallback. A configuration older than two minutes is no longer used to replace requests if refresh keeps failing. Logs distinguish an unavailable or expired configuration from an empty matching result. The intended sub-second response to normal notifications still requires device measurement.

## Android users and clipboard

The snapshot identifies its Android user. Native and clipboard requests must match that user; unknown or unsupported user contexts keep the original flow rather than using another user's frozen-package list. Root package-state commands specify the app's current user. This is a conservative isolation policy, not a claim of complete work-profile support.

The system clipboard hook discovers supported framework methods at runtime and bounds captured text. Duplicate claims have a fixed lifetime and capacity and retain a fingerprint rather than the full clipboard text as a map key. The older app-side foreground service remains a compatibility path. A running foreground service does not prove background clipboard access; verify the actual path and result on the target Android release and ROM.

## Validate and diagnose

Use the rule preview to check source and URL matching without opening or thawing an app. Then test a real frozen target and inspect the destination page. A hook installation message, successful package-state operation, or accepted Activity launch request alone is not an end-to-end success.

Export diagnostics after reproduction and record the APK SHA-256, rule revision, API level, ROM, Android user, source and target. Redact personal link contents before sharing. Follow [the release validation checklist](RELEASE_VALIDATION.md) for failures, repeated events, lifecycle changes, multiple users and the API 34/35/36 device matrix.

The release verifier checks the expected certificate and matching application/LSPosed version metadata. Keep signing material outside Git. R8 and resource shrinking remain disabled until device coverage also validates reflection, LSPosed entry points, Gson and LitePal.
