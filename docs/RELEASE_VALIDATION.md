# 发布验证

自动测试、构建和验包验证代码与产物；它们不能证明 LSPosed 在具体 ROM 上已经正确解冻并打开目标页面。2026-10-15 的工作环境没有连接 Android 设备，下面的设备检查仍须执行后才能记录为通过。

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
