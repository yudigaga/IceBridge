# 发布验证

自动测试、构建和验包验证代码与产物；它们不能证明 LSPosed 在具体 ROM 上已经正确解冻并打开目标页面。0.0.81 的主题界面和 0.0.82 的独立系统组件已在 Android 16 模拟器验证；实际 IceBox、系统 Hook、ROM 和支付回调检查仍须在对应真实环境执行。

## 0.0.85 应用流线与原始点击接管

- JVM：41 个套件、341 项测试，0 失败、0 错误。覆盖只读资格准备、来源身份、精确请求认领、路由失败的一次回放、执行中取消／重复调用、回放令牌跨两个 Hook 的请求作用域，以及来源会话隔离。
- Android 16／API 36 只读模拟器：最终 APK 的 56 项 instrumentation 测试全部通过，包含真实动画帧推进、图标／名称兜底、阶段切换复用、关闭后停止动画，以及原有触摸穿透与主题交互。该测试仍只对 instrumentation 进程使用 `--no-hidden-api-checks` 访问同步输入的测试接口，未修改全局策略。
- 最终 debug、release、androidTest 构建成功；Lint 0 错误、151 个警告。v2 签名、既有升级证书、版本与 LSPosed 元数据、ZIP 对齐检查通过；release DEX 不含预览 Activity、Harness、系统 API 探针或 fixture 引用。
- release APK SHA-256：`0A369A911D3ACB7760316AA89938596D2FB857C95C0AD229BFEAF1AD6315CE63`。
- 居中卡片显示实际来源与目标图标／名称，亮色尾迹沿图标环和曲线箭头移动。解冻、打开阶段共用视图，隐藏／移除停止动画，系统关闭动画时保留静态流线。浅／深色均沿用同一种子配色。
- 最终浅／深色预览使用模拟器中实际安装的“设置 → Chrome”图标，截图已检查，另保存 4 秒真实录屏。UID 1000 的独立 `app_process` 探针成功反射查询前台预览 UID，返回 importance=100；这只验证前台查询 API 与该测试身份的权限，不代表真实 LSPosed 注入验收。
- 独立模式对可确认来源的微信／QQ 普通点击，确认路由后拦截原 WebView；原始外层框架执行与清理保留，解冻／目标启动在之后执行。来源 UID、前台状态、目标和配置重新验证；未认领或不支持的请求保留原流程，不通过同时打开两个页面补偿。
- 接管票据上限 16；失败或派发失败最多回放一次原来源请求，取消未接管票据不回放。私有 extras 只交回来源，公开链接路由与原件分开；配置关闭后的回放也不会被第二个 Hook 重复桥接。
- 初轮模拟器回归被开机阶段已有的 SystemUI ANR 弹窗占用焦点影响。日志记录该 ANR 发生在安装／显示卡片之前，不能当作卡片错误或忽略断言；后续在没有编译并发的只读模拟器上排除该弹窗后重新验证。
- 验证边界：原生预览和纯状态测试不能代替真实 system_server 的 Hook 与系统窗口权限；用户手机的原页面是否被拦截、目标页面、返回栈、支付回调与失败回退仍需更新后复测。
- 所有设备操作限定于 API 36 只读隔离实例，验证结束后已关闭；不修改原 AVD 用户数据。原始日志、录屏及本地探针不提交仓库。

行为和框架依据见 [TRANSIT_PROGRESS.md](TRANSIT_PROGRESS.md)。

## 0.0.84 居中进度卡片与分段计时

- JVM：38 个套件、319 项测试全部通过。Android 16／API 36 只读模拟器：54 项 instrumentation 测试全部通过，包含进度状态、窗口关联、同色系明暗卡片及真实输入穿透。
- 最终 debug、release、androidTest 构建成功；Lint 0 错误、151 个警告。正式包 v2 签名、既有升级证书、0.0.84 版本、LSPosed 元数据与 ZIP 对齐检查通过；release DEX 不含 debug 预览、Harness 或 fixture 引用。
- release APK SHA-256：`AF04FCD115E90071758A7673FDBC227BCB6E8B4390D18FE0E8EE60DFB7B11E05`。
- 日志基线：六次捕获到启动调用返回为 117～389 ms，六个目标主进程均冷启动。有目标首帧的样本为 2468～5339 ms；酷安的 5670 ms 是权限框，不能当作帖子完成；首条抖音缺首帧记录，不能补造成功时长。
- 独立运行新增系统侧原生卡片，按实际阶段显示解冻／打开。启动接口返回不收卡，目标窗口绘制或已绘制页面恢复才收卡；多级路由、热重用、旧帧、旧超时和权限提示分别处理。
- 没有为统一观感增加固定启动等待。新的事务 id 与 elapsedRealtime 分段日志区分原请求、排队、解冻、启动提交和窗口就绪，不能据旧日志宣称已加快第三方应用冷启动。
- 卡片同步固定／已解析随机主题色与明暗偏好。schema 8／codec 1 保持，旧配置缺新主题字段时保留规则、在线备份和高水位；主题变更会重新同步并撤销旧 ready。
- 回归先红：协调器进度 30 项中 6 项失败；冷绘制回调 10 项中 2 项失败；热重用丢失新标记 1 项失败。随后按正确阶段与 AOSP 绘制标记提交顺序修复。原生预览最初使用错误的顶层窗口 token，真实设备测试暴露 BadToken 后改为 Activity 子窗口，生产系统窗口路径未改为测试窗口。
- 原生浅色解冻／深色打开截图已目视检查；卡片居中、同色系、无全屏 dim。当前模拟器只读，测试完关闭，不改原 AVD 用户数据。
- 输入穿透测试显式等待窗口焦点、布局与输入事务就绪，再验证真实 DOWN／UP 和一次点击回调；未使用固定睡眠、重试或取消断言。该轮 instrumentation 使用 `--no-hidden-api-checks` 访问平台的 `UiAutomation.syncInputTransactions()` 测试接口，仅作用于测试进程，未修改设备全局隐藏 API 策略。
- 验证边界：预览使用真实 Activity 子窗口，不能证明真实 system_server 的 TYPE_SYSTEM_DIALOG 权限或该 ROM 的显示结果；当前 AVD 未运行 LSPosed 注入且平台证书不匹配公开开发证书，未尝试签名绕过或改系统。手机上的生产系统窗口仍需更新后复测。

行为、脱敏基线和计时字段见 [TRANSIT_PROGRESS.md](TRANSIT_PROGRESS.md)。

## 0.0.83 微信／QQ 捕获修复

- 最终 JVM：35 个套件、268 项测试全部通过；debug、release、androidTest 构建成功，Lint 0 错误、145 个警告。
- 正式包 v2 签名、既有升级证书、0.0.83 版本及 LSPosed 元数据、ZIP 对齐校验通过；SHA-256：`48BF6C1B8032243EA00888695B0CFA87CB965FF388E65B186D17314A501EBC4E`。
- 用户日志证明：本体禁用并退出后，system_server 和 39 条独立规则仍在；两次微信 MMWebViewUI 普通来源请求均被捕获前的错误 token 保护跳过。日志未记录真实 URL extras，QQ 未有有效点击样本。
- 先保留旧判断运行新增 Android 回归：8 项中 2 项失败，分别为普通微信／QQ 来源 token＋requestCode=-1；相同 URL 和规则在无来源 token 时通过。修复后 8 项全部通过；全量 Android instrumentation 为 39 项通过。
- 新 Harness `capture-wechat`／`capture-qq` 使用同一捕获层，再接真实系统存储、PM 与 ATMS。API 36 隔离模拟器上，本体持续 enabled=3、hidden=true、无本体应用进程；两种请求形态均捕获、解冻 fixture 并显示对应测试链接页面。原 Intent 不修改，来源私有 extras 不转交。
- 真正等待结果、结果码未知及 FORWARD_RESULT 请求继续保持原链路。共享捕获层、来源识别重用和脱敏原因日志用于防止静默跳过再次漏测。
- 验证边界：模拟器为共享组件与请求形态测试，不能代替用户手机上的实际 Hook、微信 extras、网络和原内容页复测。原始日志、数据库和 URL 不提交仓库。

原因、AOSP 依据与复现命令见 [FROZEN_CAPTURE_DIAGNOSIS.md](FROZEN_CAPTURE_DIAGNOSIS.md)。

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
