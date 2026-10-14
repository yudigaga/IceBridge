package com.huanchengfly.icebridge.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.icebridge.activities.RuleConfigActivity
import com.huanchengfly.icebridge.automation.LinkRuleEntry
import com.huanchengfly.icebridge.automation.LinkAutomationConfig
import com.huanchengfly.icebridge.automation.LinkAutomationDiagnostics
import com.huanchengfly.icebridge.automation.OnlineLinkRuleRepository
import com.huanchengfly.icebridge.automation.RuleConfigRepository
import com.huanchengfly.icebridge.automation.RuleConfigTransfer
import com.huanchengfly.icebridge.automation.RuleEditorValidation
import com.huanchengfly.icebridge.automation.ScopedRuleResolver
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

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

private val EditableRulesSaver = listSaver<List<EditableRule>, String>(
    save = { DraftRowsCodec.encode(it) }, restore = { DraftRowsCodec.decode(it) }
)

@Composable
private fun RuleConfigPage(activity: RuleConfigActivity) {
    var drafts by rememberSaveable(stateSaver = EditableRulesSaver) {
        mutableStateOf(RuleConfigRepository.sourceRuleEntries(activity).map(EditableRule::from))
    }
    var onlineVersion by remember { mutableIntStateOf(0) }
    var savedVersion by remember { mutableIntStateOf(0) }
    var editingTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var showTargetPicker by rememberSaveable { mutableStateOf(false) }
    var showEffectiveRules by remember { mutableStateOf(false) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var pullInProgress by remember { mutableStateOf(false) }
    var transferInProgress by remember { mutableStateOf(false) }
    var savingTarget by remember { mutableStateOf(false) }
    var editorSaveError by remember { mutableStateOf<String?>(null) }
    val refreshRequests = remember { RefreshGeneration() }
    DisposableEffect(activity) { onDispose { refreshRequests.invalidate() } }
    var status by rememberSaveable { mutableStateOf("点击目标应用编辑；编辑器中的“保存并生效”会立即保存规则") }
    DisposableEffect(activity) {
        val preferences = activity.getSharedPreferences(LinkAutomationConfig.PREFS, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // A refresh started by the previous Activity may finish after recreation. Observe
            // persisted inputs as well as the current request, without touching editor rows.
            if (key == "rule_config_source_rules_v1" || key?.startsWith("online_") == true) {
                savedVersion++
                onlineVersion++
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // PackageManager enumeration, labels and icons are comparatively expensive on vendor ROMs.
    // Load them once off the composition thread; the picker itself uses a lazy, searchable list.
    var deviceApps by remember { mutableStateOf<List<DeviceRuleApp>>(emptyList()) }
    var deviceAppsLoading by remember { mutableStateOf(true) }
    LaunchedEffect(activity) {
        deviceApps = withContext(Dispatchers.IO) { loadInstalledRuleApps(activity) }
        deviceAppsLoading = false
    }
    val appsByPackage = remember(deviceApps) { deviceApps.associateBy { it.packageName } }
    var effectiveEntries by remember { mutableStateOf<List<LinkRuleEntry>>(emptyList()) }
    var rulesLoading by remember { mutableStateOf(true) }
    LaunchedEffect(activity, onlineVersion, savedVersion) {
        val snapshot = withContext(Dispatchers.IO) {
            RuleConfigRepository.sourceRuleEntries(activity).map(EditableRule::from) to
                RuleConfigRepository.effectiveRuleEntries(activity)
        }
        drafts = snapshot.first
        effectiveEntries = snapshot.second
        rulesLoading = false
    }
    // Manual and online candidates mostly have a wildcard source. Grouping by source would
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

    val transferScope = rememberCoroutineScope()

    fun transferError(error: Throwable): String {
        if (error is CancellationException) throw error
        val cause = error.cause
        val detail = buildString {
            append(error.javaClass.simpleName)
            error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            if (cause != null && cause !== error) {
                append("; cause=").append(cause.javaClass.simpleName)
                cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
        }
        return detail.replace('\n', ' ').replace('\r', ' ')
    }

    fun documentsUiIntent(action: String, mimeType: String, fileName: String? = null, preferDocumentsUi: Boolean = true): Intent {
        val intent = Intent(action)
            .setType(mimeType)
            .addCategory(Intent.CATEGORY_OPENABLE)
        if (fileName != null) intent.putExtra(Intent.EXTRA_TITLE, fileName)
        // Some Android 16 ROMs reject the MIME extras generated by the standard contracts.
        // Prefer the user's DocumentsUI package when it can resolve the request, then fall back
        // to a normal implicit intent so OEM file pickers remain supported.
        val explicit = Intent(intent).setPackage("com.google.android.documentsui")
        return if (preferDocumentsUi && activity.packageManager.resolveActivity(explicit, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            explicit
        } else {
            intent
        }
    }

    fun processImportedRules(uri: Uri, source: String) {
        if (editingTarget != null || savingTarget) {
            status = "请先保存或取消当前编辑，再重新导入规则"
            return
        }
        transferInProgress = true
        transferScope.launch {
            try {
            val text = try {
                withContext(Dispatchers.IO) { readTransferText(activity, uri) }
            } catch (error: Throwable) {
                val detail = transferError(error)
                LinkAutomationDiagnostics.record(activity, "rule_import_read_failed", uri.toString(), "$source $detail")
                status = "\u5bfc\u5165\u5931\u8d25\uff1a\u65e0\u6cd5\u8bfb\u53d6\u6587\u4ef6\uff08$detail\uff09"
                return@launch
            }
            val imported = try {
                withContext(Dispatchers.IO) { RuleConfigTransfer.importJson(text) }
            } catch (error: Throwable) {
                val detail = transferError(error)
                LinkAutomationDiagnostics.record(activity, "rule_import_parse_failed", uri.toString(), "$source $detail")
                status = "\u5bfc\u5165\u5931\u8d25\uff1a\u89c4\u5219\u6587\u4ef6\u65e0\u6548\uff08$detail\uff09"
                return@launch
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    RuleConfigRepository.mergeImportedManualRules(activity, imported)
                }
                drafts = RuleConfigRepository.sourceRuleEntries(activity).map(EditableRule::from)
                savedVersion++
                LinkAutomationDiagnostics.record(activity, "rule_import_result", uri.toString(),
                    "$source merge_ok count=${imported.size}")
                status = "\u5df2\u5bfc\u5165 ${imported.size} \u6761\u89c4\u5219\uff1b\u5f53\u524d\u5171 ${result.manualApps} \u6761\u624b\u52a8\u89c4\u5219"
            } catch (error: Throwable) {
                val detail = transferError(error)
                LinkAutomationDiagnostics.record(activity, "rule_import_merge_failed", uri.toString(), "$source $detail")
                status = "\u5bfc\u5165\u5931\u8d25\uff1a\u65e0\u6cd5\u4fdd\u5b58\u89c4\u5219\uff08$detail\uff09"
            }
            } finally { transferInProgress = false }
        }
    }

    fun writeExportedRules(uri: Uri, source: String) {
        transferScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = RuleConfigTransfer.exportJson(
                        RuleConfigRepository.manualRuleEntriesForTransfer(activity)
                    )
                    activity.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("\u65e0\u6cd5\u6253\u5f00\u76ee\u6807\u6587\u4ef6")
                }
                LinkAutomationDiagnostics.record(activity, "rule_export_result", null, "$source write_ok")
                status = "\u5df2\u5bfc\u51fa\u624b\u52a8\u89c4\u5219"
            } catch (error: Throwable) {
                val detail = transferError(error)
                LinkAutomationDiagnostics.record(activity, "rule_export_write_failed", null, "$source $detail")
                status = "\u5bfc\u51fa\u5931\u8d25\uff1a$detail"
            }
        }
    }

    // Avoid ActivityResultRegistry here. On the target Android 16 ROM it generates a requestCode
    // outside the legacy Activity API's 16-bit range, so even StartActivityForResult fails before
    // the file picker can be launched. RuleConfigActivity uses fixed, valid request codes instead.
    DisposableEffect(activity) {
        activity.setRuleTransferResultListener(object : RuleConfigActivity.RuleTransferResultListener {
            override fun onRuleTransferResult(requestCode: Int, resultCode: Int, data: Intent?) {
                val uri = data?.data
                if (requestCode == RuleConfigActivity.REQUEST_EXPORT_RULES) {
                    if (uri == null) {
                        LinkAutomationDiagnostics.record(activity, "rule_export_result", null,
                            "direct canceled resultCode=$resultCode")
                        status = "\u5df2\u53d6\u6d88\u5bfc\u51fa"
                    } else {
                        LinkAutomationDiagnostics.record(activity, "rule_export_result", null,
                            "direct uri_received")
                        writeExportedRules(uri, "direct")
                    }
                } else if (requestCode == RuleConfigActivity.REQUEST_IMPORT_RULES) {
                    if (uri == null) {
                        LinkAutomationDiagnostics.record(activity, "rule_import_result", null,
                            "direct canceled resultCode=$resultCode")
                        status = "\u5df2\u53d6\u6d88\u5bfc\u5165"
                    } else {
                        LinkAutomationDiagnostics.record(activity, "rule_import_uri_received", uri.toString(), "direct")
                        processImportedRules(uri, "direct")
                    }
                }
            }
        })
        onDispose { activity.setRuleTransferResultListener(null) }
    }

    val pullRules = {
        val request = refreshRequests.begin()
        pullInProgress = true
        status = "正在拉取在线规则…"
        OnlineLinkRuleRepository.setEnabled(activity, true)
        OnlineLinkRuleRepository.refreshAsync(activity) { result ->
            if (refreshRequests.accepts(request)) {
                pullInProgress = false
                onlineVersion++
                status = if (result.success) {
                    "已拉取 ${result.count} 条在线规则；手动规则保留，正在编辑的内容不会被替换。"
                } else {
                    result.message
                }
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
                RuleToolbarIcon(android.R.drawable.ic_menu_upload, "\u5bfc\u51fa\u89c4\u5219", true) {
                    LinkAutomationDiagnostics.record(activity, "rule_export_launch_requested", null, "primary")
                    try {
                        activity.launchRuleExport(
                            documentsUiIntent(Intent.ACTION_CREATE_DOCUMENT, "application/json", "icebridge-rules.json")
                        )
                        LinkAutomationDiagnostics.record(activity, "rule_export_launch_started", null, "primary")
                    } catch (error: Throwable) {
                        val detail = transferError(error)
                        LinkAutomationDiagnostics.record(activity, "rule_export_launch_failed", null, "primary $detail")
                        try {
                            LinkAutomationDiagnostics.record(activity, "rule_export_launch_requested", null, "fallback")
                            activity.launchRuleExport(
                                documentsUiIntent(
                                    Intent.ACTION_CREATE_DOCUMENT,
                                    "application/json",
                                    "icebridge-rules.json",
                                    preferDocumentsUi = false
                                )
                            )
                            LinkAutomationDiagnostics.record(activity, "rule_export_launch_started", null, "fallback")
                        } catch (fallbackError: Throwable) {
                            val fallbackDetail = transferError(fallbackError)
                            LinkAutomationDiagnostics.record(activity, "rule_export_launch_failed", null, "fallback $fallbackDetail")
                            status = "\u5bfc\u51fa\u5931\u8d25\uff1a\u6587\u4ef6\u9009\u62e9\u5668\u4e0d\u53ef\u7528\uff08$fallbackDetail\uff09"
                        }
                    }
                }
                RuleToolbarIcon(android.R.drawable.ic_menu_add, "\u5bfc\u5165\u89c4\u5219", editingTarget == null && !transferInProgress && !savingTarget) {
                    LinkAutomationDiagnostics.record(activity, "rule_import_launch_requested", null, "primary")
                    try {
                        activity.launchRuleImport(documentsUiIntent(Intent.ACTION_GET_CONTENT, "*/*"))
                        LinkAutomationDiagnostics.record(activity, "rule_import_launch_started", null, "primary")
                    } catch (error: Throwable) {
                        val detail = transferError(error)
                        LinkAutomationDiagnostics.record(activity, "rule_import_launch_failed", null, "primary $detail")
                        try {
                            LinkAutomationDiagnostics.record(activity, "rule_import_launch_requested", null, "fallback")
                            activity.launchRuleImport(
                                documentsUiIntent(
                                    Intent.ACTION_GET_CONTENT,
                                    "*/*",
                                    preferDocumentsUi = false
                                )
                            )
                            LinkAutomationDiagnostics.record(activity, "rule_import_launch_started", null, "fallback")
                        } catch (fallbackError: Throwable) {
                            val fallbackDetail = transferError(fallbackError)
                            LinkAutomationDiagnostics.record(activity, "rule_import_launch_failed", null, "fallback $fallbackDetail")
                            status = "\u5bfc\u5165\u5931\u8d25\uff1a\u6587\u4ef6\u9009\u62e9\u5668\u4e0d\u53ef\u7528\uff08$fallbackDetail\uff09"
                        }
                    }
                }
                RuleToolbarIcon(android.R.drawable.ic_menu_search, "测试链接规则", true) { showPreview = true }
                RuleToolbarIcon(android.R.drawable.ic_popup_sync, "\u62c9\u53d6\u5728\u7ebf\u89c4\u5219", !pullInProgress && editingTarget == null && !transferInProgress, pullRules)
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
                if (deviceAppsLoading || rulesLoading) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text("正在加载应用和规则…", modifier = Modifier.padding(16.dp), fontSize = 14.sp)
                    }
                } else if (targets.isEmpty()) {
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
                                onClick = { if (!transferInProgress) { editorSaveError = null; editingTarget = targetPackage } }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.size(64.dp))
            }
            Card(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
                    .clickable(enabled = !transferInProgress && !rulesLoading) { showTargetPicker = true }
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
                editorSaveError = null
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
            saving = savingTarget,
            saveError = editorSaveError,
            onConfirm = { rows ->
                savingTarget = true
                editorSaveError = null
                transferScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val edited = rows.flatMap { row ->
                                val validated = RuleEditorValidation.validate(row.sourcePackage, targetToEdit,
                                    row.hostsText, row.conversion, row.note)
                                require(validated.isValid) { validated.errors.joinToString("；") }
                                validated.entries
                            }
                            // Replace only this target in the latest persisted set, so an import or
                            // another target saved since opening this dialog cannot be lost.
                            val entries = RuleEditorValidation.replaceTarget(
                                RuleConfigRepository.manualRuleEntriesForTransfer(activity), targetToEdit, edited
                            )
                            RuleEditorValidation.validateCapacity(entries)?.let { throw IllegalArgumentException(it) }
                            RuleConfigRepository.saveSourceRules(activity, entries)
                        }
                        drafts = RuleConfigRepository.sourceRuleEntries(activity).map(EditableRule::from)
                        savedVersion++
                        editingTarget = null
                        status = "已保存 ${rows.size} 条编辑规则，当前 ${result.effectiveHosts} 个生效域名"
                    } catch (error: Throwable) {
                        editorSaveError = "保存失败：${transferError(error)}"
                    } finally { savingTarget = false }
                }
            },
            onDismiss = { editingTarget = null }
        )
    }

    if (showPreview) RulePreviewDialog(effectiveEntries, appsByPackage) { showPreview = false }

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
    onDismiss: () -> Unit,
    forSource: Boolean = false
) {
    var query by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val filteredApps = remember(apps, normalizedQuery) {
        if (normalizedQuery.isBlank()) apps else apps.filter { app ->
            app.label.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                app.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (forSource) "选择发起应用" else "添加目标应用") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (forSource) "选择打开该链接的发起应用。" else "仅显示已安装应用；冻结的应用也可选择。",
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
    saving: Boolean,
    saveError: String?,
    onConfirm: (List<EditableRule>) -> Unit,
    onDismiss: () -> Unit
) {
    val originalRows = rememberSaveable(targetPackage, saver = EditableRulesSaver) {
        if (initialRules.isEmpty()) listOf(EditableRule(sourcePackage = "*", targetPackage = targetPackage))
        else initialRules.map { it.copy(targetPackage = targetPackage) }
    }
    var rows by rememberSaveable(targetPackage, stateSaver = EditableRulesSaver) { mutableStateOf(originalRows) }
    var attemptedSave by rememberSaveable(targetPackage) { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable(targetPackage) { mutableStateOf(false) }
    val validations = remember(rows) {
        rows.map { RuleEditorValidation.validate(it.sourcePackage, it.targetPackage, it.hostsText, it.conversion, it.note) }
    }
    fun requestDismiss() {
        if (!saving) {
            if (rows != originalRows) confirmDiscard = true else onDismiss()
        }
    }
    BackHandler { requestDismiss() }
    AlertDialog(
        onDismissRequest = { requestDismiss() },
        title = { Text("\u7f16\u8f91$title") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "发起应用填 * 表示通用规则。保存后立即生效；编辑在线规则会形成手动覆盖。删除手动覆盖后，对应在线规则仍可生效。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                if (rows != originalRows) Text("有未保存的修改", fontSize = 13.sp)
                if (saveError != null) Text(saveError, color = Color(0xFFB3261E), fontSize = 13.sp)
                rows.forEachIndexed { index, row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("\u89c4\u5219 ${index + 1}", fontSize = 15.sp)
                            if (attemptedSave) validations[index].errors.forEach { error ->
                                Text(error, color = Color(0xFFB3261E), fontSize = 12.sp)
                            }
                            TextField(
                                value = row.sourcePackage,
                                onValueChange = { value ->
                                    if (!saving) rows = rows.replaceAt(index, row.copy(sourcePackage = value))
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
                                    if (!saving) rows = rows.replaceAt(index, row.copy(hostsText = value))
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
                                TextButton(enabled = !saving, onClick = {
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
                                    if (!saving) rows = rows.replaceAt(index, row.copy(note = value))
                                },
                                label = "\u5907\u6ce8\uff08\u53ef\u9009\uff09",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            TextButton(enabled = !saving, onClick = {
                                rows = rows.filterIndexed { rowIndex, _ -> rowIndex != index }
                            }) { Text("\u5220\u9664\u89c4\u5219") }
                        }
                    }
                }
                TextButton(enabled = !saving, onClick = {
                    rows = rows + EditableRule(sourcePackage = "*", targetPackage = targetPackage)
                }) { Text("+ \u6dfb\u52a0\u89c4\u5219") }
            }
        },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            attemptedSave = true
            if (validations.all { it.isValid }) onConfirm(rows)
        }) { Text(if (saving) "正在保存…" else "保存并生效") } },
        dismissButton = { TextButton(enabled = !saving, onClick = { requestDismiss() }) { Text("取消") } }
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("放弃未保存的修改？") },
        text = { Text("编辑内容尚未生效，离开后会丢弃这些修改。") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } }
    )
}

@Composable
private fun RulePreviewDialog(
    entries: List<LinkRuleEntry>,
    appsByPackage: Map<String, DeviceRuleApp>,
    onDismiss: () -> Unit
) {
    var source by rememberSaveable { mutableStateOf("*") }
    var url by rememberSaveable { mutableStateOf("") }
    var showSourcePicker by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val preview = remember(source, url, entries, attempted) {
        if (!attempted) null else runCatching {
            val host = RuleEditorValidation.previewHost(source, url)
            ScopedRuleResolver.matchingRules(entries, source.trim(), host)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("测试链接规则") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("预览已保存规则的匹配顺序；实际打开还取决于目标应用的冻结状态和系统解析结果。", fontSize = 13.sp)
                TextField(value = source, onValueChange = { source = it },
                    label = "发起应用包名（剪贴板填 *）", singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { showSourcePicker = true }) {
                    Text(appsByPackage[source.trim()]?.let { "发起应用：${it.label}" } ?: "选择发起应用")
                }
                TextField(value = url, onValueChange = { url = it }, label = "HTTP(S) URL",
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
                preview?.fold(onSuccess = { matches ->
                    if (matches.isEmpty()) Text("没有匹配规则", fontSize = 14.sp)
                    matches.forEachIndexed { index, rule ->
                        val target = appsByPackage[rule.targetPackage]?.label ?: rule.targetPackage
                        val origin = when (rule.source) {
                            LinkRuleEntry.SOURCE_MANUAL -> "手动"
                            LinkRuleEntry.SOURCE_ONLINE -> "在线"
                            else -> "旧规则"
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${index + 1}. $target", fontSize = 15.sp)
                                Text(rule.targetPackage, fontSize = 12.sp)
                                Text("${rule.sourcePackage} → ${rule.host} · $origin", fontSize = 12.sp)
                                Text(if (rule.hasFixedUrlConversion()) "转换：固定 Deep Link" else "转换：保留原 URL", fontSize = 12.sp)
                                if (!appsByPackage.containsKey(rule.targetPackage)) Text("当前设备未检测到此应用", fontSize = 12.sp)
                            }
                        }
                    }
                }, onFailure = { error -> Text(error.message.orEmpty(), color = Color(0xFFB3261E), fontSize = 13.sp) })
            }
        },
        confirmButton = { TextButton(onClick = { attempted = true }) { Text("查看匹配") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
    if (showSourcePicker) TargetPickerDialog(
        apps = appsByPackage.values.toList(), loading = false, existingTargets = emptySet(),
        onChoose = { source = it; showSourcePicker = false },
        onDismiss = { showSourcePicker = false }, forSource = true
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
                            else -> "旧规则"
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


private fun readTransferText(context: Context, uri: Uri): String {
    val maxBytes = RuleConfigTransfer.MAX_BYTES
    val buffer = ByteArray(8192)
    val output = ByteArrayOutputStream()
    context.contentResolver.openInputStream(uri)?.use { input ->
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) {
                throw RuleConfigTransfer.TransferException("\u6587\u4ef6\u8d85\u8fc7 1 MB \u9650\u5236")
            }
            output.write(buffer, 0, count)
        }
    } ?: throw RuleConfigTransfer.TransferException("\u65e0\u6cd5\u8bfb\u53d6\u6587\u4ef6")
    return output.toString(Charsets.UTF_8.name())
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

