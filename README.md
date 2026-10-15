# IceBridge

IceBridge is an Android LSPosed module that restores a frozen app before the system launches it. It supports both IceBox SDK mode and Root (`su`) mode.

## Automatic link bridge

The automatic-link path is designed for the following flow:

```text
WeChat / QQ built-in browser activity request -> system-side IceBridge extracts an HTTPS URL
-> IceBridge identifies a supported target package
-> only if that package is frozen, IceBridge restores it
-> IceBridge opens the original URL in that target app
```

HTTP(S) mappings come from manual rules and the project's [owned online rule feed](https://raw.githubusercontent.com/yudigaga/IceBridge/rules/manifest.json). In the rule editor, select the source application, domain and target application; `*` matches any source. Clipboard routing uses wildcard rules only.

For setup and the recommended LSPosed scope, see [native link capture](docs/NATIVE_LINK_CAPTURE.md). The default scope is `system` only; no WeChat, QQ, browser, or target-app scope is required. System-side capture observes Activity requests and does not provide visibility into every in-process WebView navigation.

## Run while IceBridge itself is frozen

In **Bridge and automation**, enable **Run independently after freezing IceBridge**. Keep the app unfrozen until the system confirms that the current configuration has been saved, then freeze it with IceBox. The opt-in system runtime retains rules and enabled features, reads live target package state, thaws and launches targets without an IceBridge Activity, and updates the owned online rule feed. It never thaws IceBridge itself.

Use 0.0.83 or later: it fixes ordinary WeChat/QQ Activity starts being mistaken for result requests. After upgrading, restart the device to load the new system module; see the [diagnosis and regression](docs/FROZEN_CAPTURE_DIAGNOSIS.md).

Version 0.0.87 adds **Show transit card** under **Bridge and automation → Launch behavior**, off by default. Enable it to show a compact centered card containing only the source/destination icons and a moving stream. It has no visible text or arrow, follows the synchronized day/night theme, and closes when the target's splash or app window is shown. Turning it off also dismisses an active card and leaves thawing and routing enabled. It adds no fixed launch delay; [progress and timing](docs/TRANSIT_PROGRESS.md) distinguishes target presentation from content loading.

Installing the new module version requires a device restart to reload its system code. Later changes to the card switch need no restart: unfreeze IceBridge, change the switch, wait for system confirmation, then freeze it again. The system retains this preference while IceBridge is frozen and across reboots.

The separate **Open in small window** switch is also off by default. Choose destinations in **Small-window apps**; in independent mode, only selected ordinary link targets request a system freeform window. An empty selection applies to no apps. The ROM chooses size and placement. It does not change payment/result flows or the replay of an original source page. Unsupported systems may keep the normal window; the module does not force global freeform settings or retry an already submitted target launch. Both switches and the selected app list synchronize live after the initial module upgrade and reboot.

For supported ordinary WeChat/QQ clicks, independent mode now takes over the original WebView launch after checking the route, so it opens only the target. If takeover fails, it attempts the original source request once. Payments, result requests and unsupported cases preserve their original flow.

This mode currently supports Android user 0 and disabled/hidden packages. Keep the LSPosed module and `system` scope enabled. After changing rules or disabling the mode, wait for a fresh system acknowledgement before freezing the app again. Installation, ROM restrictions, actual framework injection after reboot and payment callbacks require device verification; see [setup and validation boundaries](docs/AUTONOMOUS_RUNTIME.md).

## Appearance

Open **Appearance → Theme and colors** on the home screen to choose a fixed preset or a custom `#RRGGBB` color. Random colors can change daily or on each fresh app launch. Light and dark appearances share the same color family, with separate tones for page backgrounds, medium/small cards, dialogs and controls. See [theme behavior and validation](docs/THEMES.md).

## Maintain online rules

The initial snapshot extracts the supported declarative subset of `nyehueh/shortx-Fluid_Cloud` into an independently maintained feed. It contains 32 mappings over 22 hosts at revision 1. No upstream scripts are executed or published. [Maintenance instructions and provenance](online-rules/README.md) explain the excluded formats and how to review future upstream candidates.

Edit `online-rules/rules.json`, increment `revision`, then validate and build the complete snapshot:

```text
python tools/rules/manage_rules.py validate
python tools/rules/manage_rules.py build
python -m unittest discover -s tools/rules -p "test_*.py"
```

The client verifies the manifest, checksum and revision, retains a validated backup on failure, and permits rule removal through a newer complete snapshot. GitHub is preferred, with a same-repository jsDelivr fallback. After merging reviewed rule changes, run the **Publish owned online rules** workflow on the default branch, or follow the documented subtree command. Publishing updates only the `rules` branch.

## Package name change in v0.0.79

The application ID is now `io.github.yudigaga.icebridge`, which meets the LSPosed module repository ownership rules. The previous `com.huanchengfly.icebridge` build cannot be updated in place because Android treats the new ID as a separate app. Before removing the old app, export any custom link rules, then import them into the new app. Other app settings must be configured again. Disable the old LSPosed module, enable the new one with the recommended `system` scope, and restart the device.

## Build

```text
./gradlew test
./gradlew assembleDebug
./gradlew lintDebug assembleRelease assembleDebugAndroidTest
```

Version name, code and application ID are defined in `version.properties`. Gradle generates matching LSPosed metadata and APK names. Outputs are under `app/build/outputs/apk/`; instrumentation APK compilation does not replace running tests on a device.

For a production release, verify the expected signing identity using Android build tools on PATH:

```powershell
./tools/test-release-validation.ps1
. ./tools/release-validation.ps1
$release = Read-ReleaseVersion ./version.properties
$apk = "app/build/outputs/apk/release/IceBridge-v$($release.versionName)-release.apk"
./tools/verify-release.ps1 -Apk $apk -ExpectedCertificateSha256 $env:ICEBRIDGE_RELEASE_CERT_SHA256
```

`-AllowUnpinnedCertificate` is reserved for the disposable CI test key. Device-only checks and the current validation scope are recorded in [the release checklist](docs/RELEASE_VALIDATION.md).

