# IceBridge 自有在线规则

由本仓库维护完整规则快照；客户端读取 [manifest.json](https://raw.githubusercontent.com/yudigaga/IceBridge/rules/manifest.json) 后校验版本和 SHA-256。`rules` 发布分支只包含本目录内容，原始第三方脚本不会发布。

## 修改并生成新版本

在项目根目录执行，需要 Python 3.10 或以上，无第三方 Python 依赖：

```powershell
python tools/rules/manage_rules.py validate
python tools/rules/manage_rules.py build
python -m unittest discover -s tools/rules -p 'test_*.py'
```

维护 [rules.json](rules.json)：新增、修改或删除规则后，将 `revision` 增加，再执行上述命令。快照是完整替换，删除的规则不会与旧版无限合并；`rules: []` 可撤回全部在线规则。需要回滚时，用更大的 revision 发布旧内容。

`build` 生成不可改写的 `revisions/rules-000001.json` 等文件，以及指向该快照的 [manifest.json](manifest.json)。同 revision 的不同内容会报错；重新构建相同内容可重复执行。SHA-256 基于实际发布文件的 UTF-8 字节计算。

每条规则的五个字段均必填：

| 字段 | 示例 | 约束 |
| --- | --- | --- |
| `sourcePackage` | `*`、`com.tencent.mm` | 通配来源或合法包名，最长 255 字符 |
| `host` | `example.com` | 小写纯域名，最长 128 字符；不接受 URL 或通用正则 |
| `package` | `com.example.app` | 目标包名，最长 255 字符 |
| `conversion` | `none` | 仅 `none`、`fixed_deep_link`；不接受脚本或转换表达式 |
| `note` | `""` | 说明，最长 512 个 UTF-16 单元，与 Android 一致 |

一个快照最多 768 条规则、96 个不同域名，revision 范围为 1 至 9223372036854775807。超限会明确失败，不会静默截断。规则顺序保留为候选顺序；同一来源、域名、包名、转换的重复项会被拒绝。

## 重新参考上游

首版参考 [nyehueh/shortx-Fluid_Cloud](https://github.com/nyehueh/shortx-Fluid_Cloud/tree/c343298036121b3841e7d16024e68317ba63c541/rules)，固定提交 `c343298036121b3841e7d16024e68317ba63c541`。全部 27 个原始文件存于工作区外的 `../upstream-rules/c343298036121b3841e7d16024e68317ba63c541/`，`archive.json` 记录 Git blob SHA 与 SHA-256。首版保留 32 条规则、22 个不同域名、10 个目标包；排除 16 个非 `pkg` 文件、1 个非法包名文件，以及 2 个非精确域名触发式，详见 [import-report.json](import-report.json)。

仅提取 `type=pkg`、合法 `pkg` 和 `tigger` 内精确域名；转义点 `\.` 会转换成普通点。其他字段全部丢弃，既不执行也不复制上游 JavaScript。首版转换全部为 `none`，不声称与上游脚本等价；例如源数据中的 `youto.be` 原样保留，没有猜测修正域名。

下面使用固定提交创建可审查候选，不会覆盖自有 `rules.json`。后续更新时明确换成要审查的新完整提交 SHA，并设置下一个 revision：

```powershell
$upstreamCommit = 'c343298036121b3841e7d16024e68317ba63c541'
$archiveDirectory = Join-Path (Split-Path (Get-Location).Path -Parent) "upstream-rules/$upstreamCommit"
python tools/rules/manage_rules.py import-upstream --commit $upstreamCommit --archive $archiveDirectory --candidate online-rules/upstream-candidate.json --report online-rules/import-report.json --revision 1
python tools/rules/manage_rules.py validate online-rules/upstream-candidate.json
git diff --no-index -- online-rules/rules.json online-rules/upstream-candidate.json
```

审查候选后，人工选择要采纳、修改或删除的条目写入自有规则。导入会检查全部文件，不采用“排序后取前 64 项”的方式；已有候选、报告或原始记录内容不同时拒绝覆盖，下一次导入应选新的候选/报告文件名。原件仅作来源记录；不要将其复制进本目录。

如果 GitHub 匿名 API 返回 403，先用已登录的 GitHub CLI 缓存目录树，再重跑导入：

```powershell
New-Item -ItemType Directory -Path $archiveDirectory -Force | Out-Null
$treeJson = gh api "repos/nyehueh/shortx-Fluid_Cloud/git/trees/${upstreamCommit}?recursive=1"
if ($LASTEXITCODE -ne 0) { throw 'GitHub tree download failed' }
Set-Content -LiteralPath (Join-Path $archiveDirectory 'github-tree.json') -Value $treeJson -Encoding utf8
```

## 发布到规则分支

在项目根目录确认本次自有规则已校验后，选择性提交并发布：

```powershell
git add -- online-rules tools/rules
git commit -m 'Update owned online rules'
$rulesCommit = git subtree split --prefix=online-rules HEAD
if ($LASTEXITCODE -ne 0) { throw 'Rules subtree split failed' }
git push origin "${rulesCommit}:refs/heads/rules"
```

不使用强制推送。发布分支将包含完整快照、清单和维护说明；应用源码、维护脚本及工作区外原始文件不在该分支中。发布后应重新下载清单和它引用的文件，核对 revision、相对文件路径和 SHA-256。
