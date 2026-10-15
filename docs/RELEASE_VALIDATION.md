# 发布验证

自动测试、构建和验包验证代码与产物；它们不能证明 LSPosed 在具体 ROM 上已经正确解冻并打开目标页面。0.0.81 的主题界面和 0.0.82 的独立系统组件已在 Android 16 模拟器验证；实际 IceBox、系统 Hook、ROM 和支付回调检查仍须在对应真实环境执行。

## 0.0.82 本体冻结后独立运行

- JVM：35 个测试套件、268 项测试，0 失败、0 错误；另有 12 项规则工具测试及发布验证脚本回归通过。
- 最终构建：debug、release、androidTest 成功；Lint 0 错误、145 个警告。正式包 v2 签名、既有证书连续性、0.0.82 版本与 LSPosed 元数据、ZIP 对齐校验通过。
- release APK SHA-256：`68E3B15784CADFE6BA72C4B443E306B78833A8CCE97704804984096537142EDC`。
- Android 16／API 36 隔离只读模拟器：31 项 instrumentation 测试全部通过，覆盖启动 Intent、URI 授权边界、支付参数保持、浏览器选择、固定转换和原有主题重建。
- UID 1000 独立 `app_process`：从真实系统私有 Store 加载配置，使用真实 PM 和 ATMS 启动器。本体同时 `disabled-user`（enabled=3）和 hidden=true，持续 329 秒后成功解冻测试目标并显示原始链接内容页。本体前后保持冻结，`pidof` 无本体应用进程。
- 目标再次冻结后，新的独立进程再次完成解冻和显式打开；未使用旧冻结列表。目标已经启用时会保持原流程。
- 本体冻结期间真实下载自有在线 revision 1，校验 SHA-256 `e840f50919b022b085621c34394c7d09fde1373e22467e490bbb8c8633a05203`，保存 32 条在线与 1 条手动测试规则。
- 设备重启后，本体仍保持 disabled/hidden；新进程恢复 33 条规则，目标重新冻结后再次打开。撤销配置后，另一个新进程不能恢复旧配置。这里验证的是持久数据及组件，不是系统 Hook 自动注入恢复。
- 设置交互：开启独立模式后，在无运行中框架的环境只显示“等待本次开机的系统确认”，没有误显示“可冻结”；手动规则和状态确认使用独立配置指纹。
- 存储观察：目录属主 system、0700；快照与 last-good 属主 system、0600。Harness 运行在 UID 1000、SELinux `su` 域，不能替代真实 `system_server` 域的 ROM 权限验收。

本模拟器有旧 Magisk／LSPosed 安装文件，但本次启动没有 daemon、Zygisk 或 system_server 注入；旧日志不是本轮成功证据。因此仍未验证真实 LSPosed Hook、IceBox 实际冻结动作与管理器状态、冻结状态重启后框架自动加载、真实微信／QQ 跳转、支付回调和具体 ROM 的 SELinux。在线定时调度及新 revision 的真机长期更新也未作设备端验收；版本替换、空撤回与回滚保护由纯状态回归覆盖。

用法、维护边界和复现命令见 [AUTONOMOUS_RUNTIME.md](AUTONOMOUS_RUNTIME.md)。本次模拟器测试完成后已关闭，不修改原 AVD 的用户数据。

## 0.0.81 主题结果

- JVM：25 个测试套件、170 项测试，0 失败、0 错误；新增策略、颜色对比度和启动会话回归。
- Android 16 模拟器：10 项 instrumentation 测试全部通过，包括日夜切换、Activity 重建、随机启动色保持、自定义输入保存、未提交输入恢复和非法输入阻止保存。
- 交互检查：预设色保存、关闭随机恢复固定色、页面切换不换色、冷启动换色、同日重启保持、前一日缓存重新取色、系统日夜切换保持色系均通过。
- 颜色：同一主题色生成日夜与中小卡片层次；正文、摘要、主题操作文字按至少 4.5:1 校验；中性色保持灰阶。
- 构建：debug、release、androidTest 均成功；Lint 为 0 错误、145 个警告；正式包签名与原证书一致，版本、元数据和对齐检查通过。
- release APK SHA-256：`D1337C3CD4396261B14C1E96F673C3F414C63B3540DCEB62A22442F3F8950C62`。
- 本次仅新增主题与渲染验证，没有据此宣称真实 ROM 或 LSPosed 解冻功能已经通过验收。

主题行为和维护入口见 [THEMES.md](THEMES.md)。设备测试也可在安装 debug 与 androidTest APK 后执行：

```powershell
adb -s emulator-5556 shell am instrument -w -r io.github.yudigaga.icebridge.test/androidx.test.runner.AndroidJUnitRunner
```

本轮使用只读模拟器，结束后已关闭。主机内存不足时，将模拟器测试与 Release 打包分开运行；不能把设备测试 APK 编译成功视为设备断言通过。

## 0.0.80 本次结果

- JVM：22 个测试套件、141 项测试，0 失败、0 错误。
- 规则维护工具：12 项 Python 测试通过；发布文件与客户端解析器契约测试通过。
- 构建：debug、release 和 androidTest APK 均生成；最终 Gradle 验证成功。
- Lint：0 错误、145 个警告。未通过禁用检查隐藏警告；仍包含旧资源、兼容判断和反射等待整理项目。
- 验包：v2 签名、与已有 0.0.79 包相同的证书、版本及 LSPosed 元数据、ZIP 对齐均通过。
- release APK SHA-256：`BACE329C79978C49A3F6A771BD408965B16C3F7B1E43BDC840E4DF48EBB59E7D`。
- 在线源：revision 1、32 条映射、22 个域名、10 个目标包；公开下载后 SHA-256 与 manifest 一致。
- 未执行：设备端 instrumentation、实际系统 Hook、真机解冻/目标页打开、ROM 与多用户矩阵。

本次应用源码保存在 `codex/owned-rules-optimization` 分支。自有规则数据已发布到远端 `rules` 分支；应用代码和 APK 尚未发布为 GitHub 正式版本。

## 主机检查

```powershell
python tools/rules/manage_rules.py validate
python tools/rules/manage_rules.py build
python -m unittest discover -s tools/rules -p 'test_*.py'
./tools/test-release-validation.ps1
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest
```

正式 APK 必须再使用已知签名证书的 SHA-256 调用 `tools/verify-release.ps1`。脚本检查 applicationId、versionName、versionCode、实际 v2 签名结果、证书、ZIP 对齐、禁止条目和 LSPosed 元数据。CI 使用临时签名证书，其 APK 不属于正式升级包。

规则发布后重新读取 `https://raw.githubusercontent.com/yudigaga/IceBridge/rules/manifest.json` 和其中 `file` 指向的快照，按实际字节校验 SHA-256。客户端的完整快照允许删除规则；恢复旧规则内容时需要使用更高 revision。

## 设备检查

记录 Android API、ROM、LSPosed 版本、Android 用户、引擎、APK SHA-256、规则 revision 和脱敏结果。至少覆盖实际使用的 ROM；API 34、35、36 的 AOSP 与 Xiaomi/HyperOS 作为兼容矩阵，不能从单台设备推广为全部支持。

| 场景 | 操作 | 通过条件 |
| --- | --- | --- |
| 来源隔离 | 同一域名配置微信到 A、QQ 到 B | 分别只解冻并打开对应目标 |
| 剪贴板 | 精确来源规则和通配规则并存 | 只使用通配规则；一次复制不重复跳转 |
| 设置刷新 | 关闭捕获、修改规则、重新冻结 | 变更及时生效；无旧规则继续触发 |
| 失败恢复 | 撤销 Root/IceBox 授权、目标未安装、Provider 暂不可用 | 失败有原因；不循环跳转；不覆盖有效规则缓存 |
| 支付回放 | 使用正常测试环境的 SDK 调起钱包 | 原始请求与回调链保持完整 |
| 多用户 | 主用户和工作资料中的同包设置不同冻结状态 | 不跨用户误解冻；未支持的用户请求保持原流程 |
| 保存与重建 | 编辑、保存、返回；编辑时旋转/重建 Activity | 已保存内容持久；未提交编辑可恢复或有明确提示 |
| 引擎切换 | IceBox 与 Root 切换后返回主页 | 展示新引擎的真实检查结果 |
| 生命周期 | 在线拉取中离开页面，文件选择器取消 | 无崩溃、过期回调覆盖或草稿静默丢失 |
| 端到端 | 冻结目标后由来源应用点击真实链接 | 目标内容页实际可见；不能只凭日志里的启动请求判定成功 |

设备连接后可以运行 `./gradlew.bat connectedDebugAndroidTest`。这仍需要结合上述真实 LSPosed、冻结状态和目标页面检查；普通 instrumentation 测试不会自动验证系统进程 Hook。
