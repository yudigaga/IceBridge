# 本体冻结后独立运行

0.0.82 增加可选的系统独立运行模式。IceBridge 被 IceBox 禁用或隐藏后，已注入 `system_server` 的模块使用系统保存的配置处理链接、剪贴板、支付预解冻和在线规则更新，整个处理过程不需要解冻或启动 IceBridge 本体。

推荐使用 0.0.85 或更新版本。0.0.83 修复普通微信／QQ 跳转的来源令牌误判；0.0.84 增加系统侧居中中转进度卡片，0.0.85 改为来源图标经流线箭头指向目标图标，沿用同步主题并等待目标窗口出现后收起。[故障原因与回归](FROZEN_CAPTURE_DIAGNOSIS.md)和[卡片及计时](TRANSIT_PROGRESS.md)说明了具体行为。覆盖安装后重启设备，等待新系统模块确认配置，再冻结本体。

当前只接收用户 0 的配置。工作资料和其他用户不会继承该配置；目标被挂起（suspended）、卸载或状态无法确认时不解冻。实际 ROM 的系统接口、SELinux 和冻结后重启注入仍需单独验收。

## 启用与修改

1. 在 LSPosed 中启用当前 IceBridge 模块，仅勾选 `system`，按框架要求重启。
2. 打开 IceBridge 的“桥接与自动化”，启用需要的增强、链接捕获、自动打开、剪贴板或支付功能，配置手动和在线规则。
3. 开启“冻结 IceBridge 后独立运行”，等待“配置已由系统保存，可冻结 IceBridge”。如果尚未确认，保持本体解冻，使用“请求系统同步”并检查模块日志。
4. 使用 IceBox 冻结 IceBridge。本体仍保持冻结；系统侧仅解冻规则选中的目标应用。
5. 修改配置时，先手动解冻本体，保存后等待系统重新确认。关闭独立模式时也要等待“系统已确认关闭”，确保持久配置已撤销。

确认状态绑定当前开机、Android 用户、完整配置指纹及系统持久化结果。打开开关本身不代表系统已准备好；保存失败、旧确认和旧规则版本均不能显示“可冻结”。关闭增强或某个触发来源后，系统保留配置但停止对应功能。

冻结后的后台功能不需要本体界面进程。修改主题、规则和设置仍需手动解冻本体进入界面。

## 框架加载约束

禁用或隐藏 Android 包和在 LSPosed 中停用模块是不同操作。核查的 [LSPosed 固定版本](https://github.com/LSPosed/LSPosed/blob/df74d83eb03a44cc6ad268841ac2ada28d077c77/daemon/src/main/java/org/lsposed/lspd/service/LSPosedService.java) 与 [Vector 固定版本](https://github.com/JingMatrix/Vector/blob/ddeed8ca1ffedfaba7f4341b911089906862d5c4/daemon/src/main/kotlin/org/matrix/vector/daemon/VectorService.kt) 不监听标准 hide 触发的 PACKAGE_REMOVED 来移除模块；彻底卸载另行处理，包变更也不直接清除启用状态或作用域。其系统模块加载按框架记录的 APK 路径和 `system` 作用域执行。源码上没有发现标准 disabled/hidden 必然阻断下一次加载的机制。

以上是固定源码的结论，不能代替所用 LSPosed 分支、版本、IceBox 工作模式和 ROM 的重启测试。框架未注入、模块被停用或 `system` 作用域被取消时，系统独立运行也不会启动；本体开关不能代替框架启用。

## 系统如何执行

| 工作 | 执行位置与行为 |
| --- | --- |
| 同步配置 | 本体可用时，由 SYSTEM_UID 访问受限 ConfigProvider；传递完整来源规则和在线版本元数据，不传大块 raw 数据 |
| 保存与恢复 | 系统工作线程写 `/data/system/icebridge/user-0.json`，目录 0700、文件 0600、属主 system；原子写入、已知有效备份和独立撤销标记 |
| HTTP 链接 | 已识别的微信／QQ 普通 WebView 点击在确认可路由后拦截原页面，再由系统工作线程打开目标；不支持的请求保留原流程。直接 ACTION_VIEW 请求继续由原链路优先处理 |
| 明确目标和支付 | 在已核实的 ATMS 公共 Binder 入口、尚未持有启动锁时修复目标包状态，然后执行原请求；不改变 SDK caller、resultTo、签名参数和返回码 |
| 剪贴板 | Hook 只复制有界文本；系统工作线程仅匹配通配来源规则，解冻并打开目标；已选目标实际失败且开启回退时才尝试明确浏览器 |
| 在线规则 | 单独的有界网络线程使用自有 manifest／快照，核验格式、哈希和版本后原子替换在线集合；保留手动规则 |

每次操作都按请求用户读取目标的 installed、enabled、hidden、suspended 状态；解冻后再次读取，确认可启动后才提交启动。系统启动器检查 ATMS 实际返回码，不能把 void API 返回或日志里的请求提交当作成功。

原有普通模式继续使用短期冻结状态快照；独立模式的已保存配置不受旧 120 秒 Provider 缓存期限影响，目标状态也不会沿用旧冻结列表。系统检测到本体隐藏或禁用时不访问其 Provider，不调用 IceBox SDK、Root 命令或本体桥接 Activity。

默认模式仍可选择 IceBox SDK 或 Root 引擎。独立模式通过已授权的系统模块直接修复包的 disabled/hidden 状态，不要求冻结后的本体再持有运行中的 SDK 或 su 进程。

## 在线更新和撤回

系统线程使用与应用相同的 HTTPS 下载与校验实现：主源预算 18 秒、主备总预算 30 秒、禁止重定向、有界 UTF-8 文本。健康检查间隔为一周，失败使用 15 分钟至 6 小时退避；每次系统进程恢复后会重新检查一次。网络线程不会阻塞链接和配置工作线程。

较高 revision 完整替换在线规则，空集合可撤回全部在线规则。相同 revision 修改内容、低版本回退或哈希不符均拒绝。合并超过 768 条规则或 96 个域名时拒绝该次更新并保留原配置。冻结期间下载的新版本不会被解冻后的旧应用缓存覆盖；来自应用的新手动规则和开关会合并进去。

关闭在线更新只停止调度，不删除已接受的规则。关闭独立运行会先写撤销标记，再清理快照，避免损坏主文件后误从旧备份恢复已撤销配置。卸载本体后不继续处理旧配置。

## 验证方法与边界

主机回归覆盖配置编解码、关闭防复活、备份恢复、用户隔离、来源优先级、实时状态读回、歧义目标、在线撤回／回滚、并发手动修改、启动失败与本体保护。Android instrumentation 覆盖 Intent 构造、SDK 预解冻和浏览器选择；这些检查不代替实际系统 Hook。

`tools/autonomous-fixture/build.ps1` 可编译独立测试目标。debug APK 包含 UID 1000 限定的 `AutonomousRuntimeHarness`，可在自有测试设备用 `app_process` 验证真实 PM、系统持久存储与启动器，不增加导出的组件或生产配置接口。它使用 `example.com` 和专用 fixture 包名，不调用本体 Provider。只应在隔离测试设备运行，不能向日常设备写入 fixture 配置。

以下命令仅用于具备 `adb root` 的隔离 Android 测试实例；先构建 debug APK，并设置主机 `ANDROID_HOME`、`JAVA_HOME`。先保存 fixture 配置，再禁用／隐藏本体和目标，等待超过 120 秒后运行 `web`。`online` 会真实下载公开 feed；`status` 在新进程读取持久结果；`disable` 撤销测试配置。

```powershell
./tools/autonomous-fixture/build.ps1 -SdkRoot $env:ANDROID_HOME -JdkRoot $env:JAVA_HOME
adb -s emulator-5556 root
adb -s emulator-5556 install -r app/build/outputs/apk/debug/IceBridge-v0.0.85-debug.apk
adb -s emulator-5556 install -r tools/autonomous-fixture/build/icebridge-autonomous-fixture.apk
adb -s emulator-5556 push app/build/outputs/apk/debug/IceBridge-v0.0.85-debug.apk /data/local/tmp/icebridge-autonomous.apk
adb -s emulator-5556 shell 'su 1000 sh -c "CLASSPATH=/data/local/tmp/icebridge-autonomous.apk app_process /system/bin com.huanchengfly.icebridge.android16.AutonomousRuntimeHarness save"'
adb -s emulator-5556 shell pm disable-user --user 0 io.github.yudigaga.icebridge
adb -s emulator-5556 shell pm hide --user 0 io.github.yudigaga.icebridge
adb -s emulator-5556 shell pm disable-user --user 0 io.github.icebridge.fixture
adb -s emulator-5556 shell pm hide --user 0 io.github.icebridge.fixture
adb -s emulator-5556 shell 'su 1000 sh -c "CLASSPATH=/data/local/tmp/icebridge-autonomous.apk app_process /system/bin com.huanchengfly.icebridge.android16.AutonomousRuntimeHarness web https://example.com/frozen-after-cache"'
adb -s emulator-5556 shell pm disable-user --user 0 io.github.icebridge.fixture
adb -s emulator-5556 shell pm hide --user 0 io.github.icebridge.fixture
adb -s emulator-5556 shell 'su 1000 sh -c "CLASSPATH=/data/local/tmp/icebridge-autonomous.apk app_process /system/bin com.huanchengfly.icebridge.android16.AutonomousRuntimeHarness capture-wechat https://example.com/wechat-source"'
adb -s emulator-5556 shell 'su 1000 sh -c "CLASSPATH=/data/local/tmp/icebridge-autonomous.apk app_process /system/bin com.huanchengfly.icebridge.android16.AutonomousRuntimeHarness online"'
```

使用 `dumpsys activity`、页面截图和目标收到的 URI 核对实际页面；同时检查本体仍为 disabled/hidden 且 `pidof` 没有本体进程。Harness 通过 UID 校验，但运行在独立 `app_process`，其 SELinux 域和真实 system_server 不同；不能据此宣称系统 Hook 或所有 ROM 的 SELinux 权限已验证。

`capture-wechat`／`capture-qq` 在真实 PM 和启动器之前运行共享捕获层，使用来源 token＋requestCode=-1 的 Activity 请求形态。每次重新测试前应再次冻结 fixture 目标；这两个命令用于覆盖普通 `web` 命令没有执行的捕获过滤阶段。

真实 IceBox／LSPosed 验收应记录本体 disabled/hidden 状态和进程、目标内容页、超过 120 秒、重新冻结目标后的第二次点击、冻结状态重启后恢复、冻结期间在线规则更新、配置关闭后不复活，以及支付 SDK 原始回调。AOSP PM 测试不能证明 IceBox 管理界面的显示已同步，也不能证明设备管理器的 suspension 可撤销。

本次具体结果见 [发布验证](RELEASE_VALIDATION.md)。
