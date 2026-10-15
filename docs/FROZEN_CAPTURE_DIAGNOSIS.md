# 0.0.83：修复冻结后微信／QQ 链接被提前跳过

0.0.82 的独立模式把 Activity 的普通来源令牌误判为等待回传结果的请求，因此提前跳过了微信／QQ 内置浏览器的链接捕获。0.0.83 按 `requestCode` 与结果转发标志区分这两种情况。

升级后必须重启设备，让 `system_server` 加载新模块代码，再等待独立运行设置显示系统确认；仅替换 APK 不能证明原系统进程中的 Hook 已更新。

## 本次日志能证明什么

输入为用户提供的 `LSPosed_20261015_155707.zip`，设备是 Android 16／API 36、HyperOS，框架为 LSPosed IT 7912。原始日志保留在本地主机，不提交设备日志、数据库、URL 或应用私有数据。

| 时间 | 脱敏证据 | 结论 |
| --- | --- | --- |
| 15:55:42.661 | `autonomous synchronized: ready=true, rules=39, revision=1` | 系统已保存独立配置 |
| 15:55:59.458 | PM 将本体设置为 enabled state 3 | 本体被禁用 |
| 15:56:00.318 | 本体应用进程被结束 | 本体界面进程退出 |
| 15:56:34.223、15:56:38.468 | 微信 `MMWebViewUI` 启动，两次均有 `(sr=...)` | 普通来源 Activity 的 token 已传入系统 |
| 15:56:42.831 | 同一 system_server 仍报告 `autonomous-persisted`，开关开启 | 系统模块和配置仍在运行 |

日志没有出现对应捕获、派发或目标启动记录。它覆盖冻结后约 72 秒，不能据此归因于 120 秒缓存超时。QQ 后来才启动，日志中没有有效的 QQ 点击链接样本。

## 错误与修复

旧捕获入口在读取微信／QQ URL 前直接执行：

```java
if (request.resultTo != null) return null;
```

普通 `Activity.startActivity()` 也会经 `startActivityForResult(intent, -1)` 传入自己的 `mToken`。`resultTo` 非空本身不能说明调用方正在等待结果。AOSP 会把没有结果回传记录的来源打印为 `(sr=...)`，与此次日志吻合。

依据为 [Activity 的普通启动入口](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/app/Activity.java#L6107-L6114)、[Instrumentation 的 token 传递](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/app/Instrumentation.java#L1882-L1885) 和 [ActivityStarter 的来源／结果记录](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/wm/ActivityStarter.java#L1031-L1064)。

新捕获策略保留以下边界：

- 有来源 token、`requestCode < 0`：普通启动继续捕获，不修改原 Intent、来源 Activity 和原启动返回值。
- 有来源 token，且 `requestCode >= 0` 或无法读取：保留原调用链，不独立回放。
- 携带 `FLAG_ACTIVITY_FORWARD_RESULT`：保留结果转发关系，不独立启动。
- 转发给目标的 HTTP 请求仅包含公开链接；微信／QQ 的私有 extras 不转交目标。

捕获逻辑提取到 `SystemAutonomousCapture`，由系统 Hook 与 Android 回归测试共用。已知微信／QQ 浏览器请求会记录固定诊断原因，例如 `captured`、`result-token` 或 `no-rule`；日志不输出 URL 和私有参数。

## 可复查的反馈入口

新增 8 项 Android Intent／Bundle 测试。修复前实际运行得到 `Tests run: 8, Failures: 2`：微信与 QQ 的普通 token＋requestCode=-1 均被提前拒绝，而无 token 的相同规则和 URL 可以捕获。

修复后同一命令得到 `OK (8 tests)`，全量 Android 测试为 `OK (39 tests)`。隔离模拟器中，本体 disabled/hidden 且无应用进程时，`capture-wechat` 和 `capture-qq` 都经过捕获层、真实 PM 和启动器打开了对应测试链接页面；本体状态保持冻结。

```powershell
adb -s emulator-5556 shell am instrument -w -r -e class com.huanchengfly.icebridge.android16.SystemAutonomousCaptureTest io.github.yudigaga.icebridge.test/androidx.test.runner.AndroidJUnitRunner
```

debug Harness 新增 `capture-wechat` 和 `capture-qq`，通过相同捕获入口，再接真实系统存储、包状态和启动器。旧 Harness 的 `web` 命令直接构造事务，未覆盖这次出错的捕获层，因此旧组件测试不能代替本次回归。

本次用户日志未记录微信 extras 的具体值；修复提前拒绝后，真实 URL 提取、规则匹配与目标打开仍以用户设备新日志和页面为准。验证结果记录在 [RELEASE_VALIDATION.md](RELEASE_VALIDATION.md)。
