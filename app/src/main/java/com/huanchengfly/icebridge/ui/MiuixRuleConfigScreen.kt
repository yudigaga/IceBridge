package com.huanchengfly.icebridge.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.icebridge.activities.RuleConfigActivity
import com.huanchengfly.icebridge.automation.LinkRuleEntry
import com.huanchengfly.icebridge.automation.OnlineLinkRuleRepository
import com.huanchengfly.icebridge.automation.RuleConfigRepository
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Target-app rule editor.
 *
 * The list is grouped by the application IceBridge will open. Every individual rule still keeps
 * its source package, host, URL conversion and note, so the routing model is unchanged.
 */

object MiuixRuleConfigScreen {
    @JvmStatic
    fun install(activity: RuleConfigActivity) = activity.setContent {
        MiuixTheme { RuleConfigPage(activity) }
    }
}

private data class DeviceRuleApp(val packageName: String, val label: String, val icon: Drawable?)

private data class EditableRule(
    val sourcePackage: String,
    val targetPackage: String = "",
    val hostsText: String = "",
    val conversion: String = LinkRuleEntry.CONVERSION_NONE,
    val note: String = ""
) {
    companion object {
        fun from(entry: LinkRuleEntry) = EditableRule(
            sourcePackage = entry.sourcePackage,
            targetPackage = entry.targetPackage,
            hostsText = entry.host,
            conversion = entry.urlConversion,
            note = entry.note
        )
    }
}

@Composable
private fun RuleConfigPage(activity: RuleConfigActivity) {
    var drafts by remember {
        mutableStateOf(RuleConfigRepository.sourceRuleEntries(activity).map(EditableRule::from))
    }
    var onlineVersion by remember { mutableIntStateOf(0) }
    var savedVersion by remember { mutableIntStateOf(0) }
    var editingTarget by remember { mutableStateOf<String?>(null) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var showEffectiveRules by remember { mutableStateOf(false) }
    var pullInProgress by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("\u89c4\u5219\u6309\u76ee\u6807\u5e94\u7528\u663e\u793a\uff1b\u586b\u5199\u57df\u540d\u6216 HTTP(S) URL \u540e\u4fdd\u5b58") }

    // PackageManager enumeration, labels and icons are comparatively expensive on vendor ROMs.
    // Load them once off the composition thread; the picker itself uses a lazy, searchable list.
    var deviceApps by remember { mutableStateOf<List<DeviceRuleApp>>(emptyList()) }
    var deviceAppsLoading by remember { mutableStateOf(true) }
    LaunchedEffect(activity) {
        deviceApps = withContext(Dispatchers.IO) { loadInstalledRuleApps(activity) }
        deviceAppsLoading = false
    }
    val appsByPackage = remember(deviceApps) { deviceApps.associateBy { it.packageName } }
    val effectiveEntries = remember(onlineVersion, savedVersion) {
        RuleConfigRepository.effectiveRuleEntries(activity)
    }
    // Built-in and online candidates mostly have a wildcard source. Grouping by source would
    // therefore collapse the entire catalog into one generic row. The UI groups by destination
    // application instead, while preserving source conditions in each rule editor row.
    // Only installed targets are shown; frozen/disabled but installed apps are intentionally kept.
    val targets = remember(drafts, effectiveEntries, appsByPackage) {
        (drafts.map { it.targetPackage } + effectiveEntries.map { it.targetPackage })
            .map { it.trim() }
            .filter { it.isNotEmpty() && appsByPackage.containsKey(it) }
            .distinct()
            .sortedWith(compareBy<String> {
                appsByPackage[it]?.label?.lowercase(Locale.ROOT) ?: it
            }.thenBy { it })
    }

    val saveRules = {
        val entries = ArrayList<LinkRuleEntry>()
        drafts.forEach { draft ->
            val source = draft.sourcePackage.trim().ifEmpty { "*" }
            val target = draft.targetPackage.trim()
            RuleConfigRepository.normalizeUserHosts(draft.hostsText).forEach { host ->
                entries.add(LinkRuleEntry(
                    source, host, target, LinkRuleEntry.SOURCE_MANUAL,
                    draft.conversion, draft.note.trim()
                ))
            }
        }
        val result = RuleConfigRepository.saveSourceRules(activity, entries)
        // Re-read the sanitized, de-duplicated manual entries so the editor accurately reflects
        // what was persisted (invalid package names and blank rows are intentionally dropped).
        drafts = RuleConfigRepository.sourceRuleEntries(activity).map(EditableRule::from)
        savedVersion++
        status = "已保存 ${result.effectiveHosts} 个生效域名；规则会在后续跳转中使用。"
    }

    val pullRules = {
        pullInProgress = true
        status = "正在拉取在线规则…"
        OnlineLinkRuleRepository.setEnabled(activity, true)
        OnlineLinkRuleRepository.refreshAsync(activity) { result ->
            pullInProgress = false
            onlineVersion++
            status = if (result.success) {
                "已拉取 ${result.count} 条在线规则；手动规则不会被覆盖，点击保存刷新快照。"
            } else {
                result.message
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = "\u89c4\u5219",
            largeTitle = "\u5e94\u7528\u89c4\u5219",
            actions = {
                RuleToolbarIcon(android.R.drawable.ic_menu_info_details, "\u67e5\u770b\u5f53\u524d\u751f\u6548\u89c4\u5219", true) {
                    showEffectiveRules = true
                }
                RuleToolbarIcon(android.R.drawable.ic_menu_save, "\u4fdd\u5b58", true, saveRules)
                RuleToolbarIcon(android.R.drawable.ic_popup_sync, "\u62c9\u53d6\u5728\u7ebf\u89c4\u5219", !pullInProgress, pullRules)
            }
        )
    }) { padding: PaddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        text = "\u89c4\u5219\u6309\u8981\u6253\u5f00\u7684\u76ee\u6807\u5e94\u7528\u5206\u7ec4\u3002\u6bcf\u6761\u89c4\u5219\u4ecd\u4f1a\u4fdd\u7559\u53d1\u8d77\u5e94\u7528\u3001\u57df\u540d / URL\u3001URL \u8f6c\u6362\u548c\u6765\u6e90\u4fe1\u606f\u3002",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                        text = "\u4f18\u5148\u7ea7\uff1a\u624b\u52a8\u89c4\u5219 > \u5728\u7ebf\u89c4\u5219 > \u5185\u7f6e\u89c4\u5219\uff1b\u540c\u4e00\u76ee\u6807\u5e94\u7528\u53ef\u4ee5\u6709\u591a\u6761\u89c4\u5219\u3002",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                        text = status,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }

                Text(
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                    text = "\u5df2\u6709\u89c4\u5219\u7684\u76ee\u6807\u5e94\u7528\uff08${targets.size}\uff09",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                if (targets.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            modifier = Modifier.padding(16.dp),
                            text = "\u6682\u65e0\u5df2\u5b89\u88c5\u76ee\u6807\u5e94\u7528\u7684\u89c4\u5219\u3002\u53ef\u70b9\u51fb\u53f3\u4e0b\u89d2 + \u6dfb\u52a0\u76ee\u6807\u5e94\u7528\u3002",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        targets.forEach { targetPackage ->
                            val rules = mergedRulesForTarget(drafts, effectiveEntries, targetPackage)
                            val hostCount = rules.flatMap {
                                RuleConfigRepository.normalizeUserHosts(it.hostsText)
                            }.distinct().size
                            val sourceCount = rules.map {
                                it.sourcePackage.trim().ifEmpty { "*" }
                            }.distinct().size
                            RuleAppRow(
                                app = appsByPackage.getValue(targetPackage),
                                summary = "${rules.size} \u6761\u89c4\u5219 \u00b7 $hostCount \u4e2a\u57df\u540d \u00b7 $sourceCount \u4e2a\u53d1\u8d77\u7aef",
                                onClick = { editingTarget = targetPackage }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.size(64.dp))
            }
            Card(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
                    .clickable { showTargetPicker = true }
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    text = "+ \u6dfb\u52a0\u76ee\u6807\u5e94\u7528",
                    fontSize = 15.sp
                )
            }
        }
    }

    if (showTargetPicker) {
        TargetPickerDialog(
            apps = deviceApps,
            loading = deviceAppsLoading,
            existingTargets = targets.toSet(),
            onChoose = { target ->
                showTargetPicker = false
                if (drafts.none { it.targetPackage == target }) {
                    // New targets start as global rules. The editor can narrow the source package.
                    drafts = drafts + EditableRule(sourcePackage = "*", targetPackage = target)
                }
                editingTarget = target
            },
            onDismiss = { showTargetPicker = false }
        )
    }

    val targetToEdit = editingTarget
    if (targetToEdit != null) {
        val app = appsByPackage[targetToEdit]
        TargetRuleEditorDialog(
            title = app?.label ?: targetToEdit,
            targetPackage = targetToEdit,
            initialRules = mergedRulesForTarget(drafts, effectiveEntries, targetToEdit),
            onConfirm = { rows ->
                // A destination editor owns one target. Do not let an edit silently move a rule.
                drafts = drafts.filterNot { it.targetPackage == targetToEdit } + rows.map {
                    it.copy(targetPackage = targetToEdit)
                }
                editingTarget = null
            },
            onDismiss = { editingTarget = null }
        )
    }

    if (showEffectiveRules) {
        EffectiveRulesDialog(
            entries = effectiveEntries,
            appsByPackage = appsByPackage,
            onDismiss = { showEffectiveRules = false }
        )
    }
}

private fun mergedRulesForTarget(
    drafts: List<EditableRule>,
    effectiveEntries: List<LinkRuleEntry>,
    targetPackage: String
): List<EditableRule> {
    // Manual rows go first. A destination can have different rules for different callers, so the
    // caller remains in the de-duplication key even though the page itself is target-centered.
    val all = ArrayList<EditableRule>()
    all.addAll(drafts.filter { it.targetPackage.trim() == targetPackage })
    all.addAll(effectiveEntries.filter { it.targetPackage.trim() == targetPackage }.map(EditableRule::from))
    val unique = LinkedHashMap<String, EditableRule>()
    all.forEach { rule ->
        val key = listOf(
            rule.sourcePackage.trim().ifEmpty { "*" },
            rule.targetPackage.trim(),
            RuleConfigRepository.normalizeUserHosts(rule.hostsText).joinToString("\n"),
            rule.conversion.trim(),
            rule.note.trim()
        ).joinToString("\u0000")
        if (!unique.containsKey(key)) unique[key] = rule
    }
    return unique.values.toList()
}

@Composable
private fun TargetPickerDialog(
    apps: List<DeviceRuleApp>,
    loading: Boolean,
    existingTargets: Set<String>,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val filteredApps = remember(apps, normalizedQuery) {
        if (normalizedQuery.isBlank()) apps else apps.filter { app ->
            app.label.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                app.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\u6dfb\u52a0\u76ee\u6807\u5e94\u7528") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "\u4ec5\u663e\u793a\u5f53\u524d\u8bbe\u5907\u5df2\u5b89\u88c5\u7684\u5e94\u7528\u3002\u9009\u62e9\u540e\u53ef\u4e3a\u8be5\u76ee\u6807\u5e94\u7528\u7f16\u8f91 IceBridge \u8df3\u8f6c\u89c4\u5219\u3002",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "\u641c\u7d22\u5e94\u7528\u6216\u5305\u540d",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (loading) {
                    Text(
                        "\u6b63\u5728\u52a0\u8f7d\u5df2\u5b89\u88c5\u5e94\u7528\u2026",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (filteredApps.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    "\u6ca1\u6709\u5339\u914d\u7684\u5e94\u7528",
                                    modifier = Modifier.padding(16.dp),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        } else {
                            items(filteredApps, key = { it.packageName }) { app ->
                                RuleAppRow(
                                    app,
                                    app.packageName + if (existingTargets.contains(app.packageName)) " \u00b7 \u5df2\u6709\u89c4\u5219" else "",
                                    onClick = { onChoose(app.packageName) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("\u5173\u95ed") } }
    )
}

@Composable
private fun TargetRuleEditorDialog(
    title: String,
    targetPackage: String,
    initialRules: List<EditableRule>,
    onConfirm: (List<EditableRule>) -> Unit,
    onDismiss: () -> Unit
) {
    var rows by remember(targetPackage) {
        mutableStateOf(
            if (initialRules.isEmpty()) {
                listOf(EditableRule(sourcePackage = "*", targetPackage = targetPackage))
            } else {
                initialRules.map { it.copy(targetPackage = targetPackage) }
            }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\u7f16\u8f91$title") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "\u6bcf\u6761\u89c4\u5219\u53ef\u6307\u5b9a\u53d1\u8d77\u7aef\u3001\u57df\u540d\u6216 HTTP(S) URL\u3002\u53d1\u8d77\u7aef\u586b\u5199 * \u8868\u793a\u4efb\u4f55\u5e94\u7528\uff1b\u76ee\u6807\u5e94\u7528\u56fa\u5b9a\u4e3a\u5f53\u524d\u9875\u9762\u3002",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                rows.forEachIndexed { index, row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("\u89c4\u5219 ${index + 1}", fontSize = 15.sp)
                            TextField(
                                value = row.sourcePackage,
                                onValueChange = { value ->
                                    rows = rows.replaceAt(index, row.copy(sourcePackage = value))
                                },
                                label = "\u53d1\u8d77\u5e94\u7528\u5305\u540d\uff08* \u4e3a\u4efb\u610f\uff09",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Text(
                                "\u76ee\u6807\u5e94\u7528\uff1a$targetPackage",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            TextField(
                                value = row.hostsText,
                                onValueChange = { value ->
                                    rows = rows.replaceAt(index, row.copy(hostsText = value))
                                },
                                label = "\u57df\u540d\u6216 HTTP(S) URL",
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 7
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "URL \u8f6c\u6362\uff1a${if (row.conversion == LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK) "\u56fa\u5b9a Deep Link" else "\u4fdd\u7559 HTTP(S) URL"}",
                                    modifier = Modifier.padding(end = 8.dp),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                TextButton(onClick = {
                                    val conversion = if (row.conversion == LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK) {
                                        LinkRuleEntry.CONVERSION_NONE
                                    } else {
                                        LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK
                                    }
                                    rows = rows.replaceAt(index, row.copy(conversion = conversion))
                                }) { Text("\u5207\u6362") }
                            }
                            TextField(
                                value = row.note,
                                onValueChange = { value ->
                                    rows = rows.replaceAt(index, row.copy(note = value))
                                },
                                label = "\u5907\u6ce8\uff08\u53ef\u9009\uff09",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            TextButton(onClick = {
                                rows = rows.filterIndexed { rowIndex, _ -> rowIndex != index }
                            }) { Text("\u5220\u9664\u89c4\u5219") }
                        }
                    }
                }
                TextButton(onClick = {
                    rows = rows + EditableRule(sourcePackage = "*", targetPackage = targetPackage)
                }) { Text("+ \u6dfb\u52a0\u89c4\u5219") }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(rows) }) { Text("\u4fdd\u5b58") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("\u53d6\u6d88") } }
    )
}

@Composable
private fun EffectiveRulesDialog(
    entries: List<LinkRuleEntry>,
    appsByPackage: Map<String, DeviceRuleApp>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("当前生效规则（${entries.size}）") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (entries.isEmpty()) {
                    Text("暂无可生效规则。保存手动规则或拉取在线规则后再查看。", fontSize = 13.sp)
                } else {
                    entries.forEach { rule ->
                        val sourceLabel = if (rule.sourcePackage == "*") "通用规则" else {
                            appsByPackage[rule.sourcePackage]?.label ?: rule.sourcePackage
                        }
                        val targetLabel = appsByPackage[rule.targetPackage]?.label ?: rule.targetPackage
                        val origin = when (rule.source) {
                            LinkRuleEntry.SOURCE_MANUAL -> "手动"
                            LinkRuleEntry.SOURCE_ONLINE -> "在线"
                            else -> "内置"
                        }
                        val conversion = if (rule.hasFixedUrlConversion()) "固定 Deep Link" else "保留 HTTP(S) URL"
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(rule.host, fontSize = 15.sp)
                                Text("$sourceLabel → $targetLabel", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                Text("来源：$origin · URL 转换：$conversion", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                if (rule.note.isNotBlank()) {
                                    Text("备注：${rule.note}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun RuleToolbarIcon(drawableId: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                contentDescription = description
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageResource(drawableId)
            }
        },
        update = { view ->
            view.contentDescription = description
            view.setImageResource(drawableId)
            view.alpha = if (enabled) 1f else 0.38f
            view.isEnabled = enabled
        },
        modifier = Modifier.size(48.dp).clickable(enabled = enabled, onClick = onClick).padding(12.dp)
    )
}

@Composable
private fun RuleAppRow(app: DeviceRuleApp, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AndroidView(
            factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
            update = { view -> view.setImageDrawable(app.icon ?: view.context.getDrawable(android.R.drawable.sym_def_app_icon)) },
            modifier = Modifier.size(44.dp)
        )
        Column(modifier = Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(app.label, fontSize = 16.sp)
            Text(summary, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Text("›", fontSize = 28.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

private fun loadInstalledRuleApps(context: Context): List<DeviceRuleApp> {
    val manager = context.packageManager
    val flags = PackageManager.MATCH_DISABLED_COMPONENTS
    val result = ArrayList<DeviceRuleApp>()
    try {
        manager.getInstalledApplications(flags).forEach { info ->
            // Disabled/frozen apps are installed and must remain editable; uninstalled placeholders are not.
            if (info.flags and ApplicationInfo.FLAG_INSTALLED == 0) return@forEach
            val label = try { info.loadLabel(manager).toString() } catch (_: Throwable) { info.packageName }
            val icon = try { info.loadIcon(manager) } catch (_: Throwable) { null }
            result.add(DeviceRuleApp(info.packageName, label.ifBlank { info.packageName }, icon))
        }
    } catch (_: Throwable) {
        // The empty-state card remains usable if a vendor package manager rejects the query.
    }
    return result.sortedWith(compareBy<DeviceRuleApp> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = mapIndexed { current, old ->
    if (current == index) value else old
}

