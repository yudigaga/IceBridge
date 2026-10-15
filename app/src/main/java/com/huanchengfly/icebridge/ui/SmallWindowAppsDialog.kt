package com.huanchengfly.icebridge.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.icebridge.automation.RuleConfigRepository
import com.huanchengfly.icebridge.automation.SystemRuntimeConfig
import com.huanchengfly.icebridge.automation.SystemRuntimeConfig.MAX_SMALL_WINDOW_PACKAGES
import com.huanchengfly.icebridge.ui.theme.CardLevel
import com.huanchengfly.icebridge.ui.theme.ThemeCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

private data class SmallWindowApp(val packageName: String, val label: String, val visible: Boolean)
private val SmallWindowSelectionSaver = listSaver<Set<String>, String>(
    save = { it.toList() }, restore = { it.toSet() }
)

/** Editing a draft never changes routing until the caller receives the confirmed selection. */
@Composable
internal fun SmallWindowAppsDialog(
    context: Context,
    selectedPackages: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by rememberSaveable(selectedPackages, stateSaver = SmallWindowSelectionSaver) {
        mutableStateOf(selectedPackages.filter { selectableSmallWindowPackage(context, it) }.toSet())
    }
    var query by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<SmallWindowApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(context) {
        apps = withContext(Dispatchers.IO) { loadSmallWindowApps(context, selectedPackages) }
        loading = false
    }
    val filtered = remember(apps, query) {
        val term = query.trim()
        apps.filter { term.isEmpty() || it.label.contains(term, ignoreCase = true) ||
            it.packageName.contains(term, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择小窗应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索应用名称或包名",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("已选择 ${draft.size} 个应用 · 最多 $MAX_SMALL_WINDOW_PACKAGES 个", fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (loading) {
                    Text("正在加载应用…", fontSize = 14.sp)
                } else if (filtered.isEmpty()) {
                    Text(if (query.isBlank()) "没有可选择的应用" else "没有匹配的应用", fontSize = 14.sp)
                } else {
                    ThemeCard(modifier = Modifier.fillMaxWidth(), level = CardLevel.SMALL) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            items(filtered, key = { it.packageName }) { app ->
                                val checked = app.packageName in draft
                                val enabled = checked || draft.size < MAX_SMALL_WINDOW_PACKAGES
                                Row(
                                    modifier = Modifier.fillMaxWidth().toggleable(
                                        value = checked, enabled = enabled, role = Role.Checkbox,
                                        onValueChange = { selected ->
                                            draft = if (selected) draft + app.packageName else draft - app.packageName
                                        }
                                    ).padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(app.label, fontSize = 15.sp)
                                        Text(app.packageName, fontSize = 12.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        if (!app.visible) Text("应用暂不可见，保留包名", fontSize = 12.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = draft.size <= MAX_SMALL_WINDOW_PACKAGES,
                onClick = { onSave(draft.toSet()) }) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** No package state is changed while discovering frozen or vendor-hidden rule destinations. */
private fun loadSmallWindowApps(context: Context, selected: Set<String>): List<SmallWindowApp> {
    val manager = context.packageManager
    val flags = PackageManager.MATCH_DISABLED_COMPONENTS
    val known = linkedMapOf<String, ApplicationInfo?>()
    try {
        manager.getInstalledApplications(flags).forEach { info ->
            if (info.flags and ApplicationInfo.FLAG_INSTALLED != 0 &&
                selectableSmallWindowPackage(context, info.packageName)) known[info.packageName] = info
        }
    } catch (_: Exception) {
        // Direct lookups below still make known rule destinations and saved selections editable.
    }
    val candidates = selected.toMutableSet()
    try { RuleConfigRepository.effectiveRuleEntries(context).forEach { candidates += it.targetPackage } }
    catch (_: Exception) { /* Existing selections remain editable if rule loading fails. */ }
    candidates.filter { selectableSmallWindowPackage(context, it) }.forEach { packageName ->
        if (!known.containsKey(packageName)) {
            known[packageName] = try { manager.getApplicationInfo(packageName, flags) }
            catch (_: Exception) { null }
        }
    }
    return known.map { (packageName, info) ->
        val label = try { info?.loadLabel(manager)?.toString()?.takeIf { it.isNotBlank() } }
            catch (_: Exception) { null }
        SmallWindowApp(packageName, label ?: packageName, info != null)
    }.sortedWith(compareByDescending<SmallWindowApp> { it.packageName in selected }
        .thenBy { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
}

private fun selectableSmallWindowPackage(context: Context, packageName: String): Boolean {
    if (packageName == context.packageName) return false
    return try {
        SystemRuntimeConfig.normalizeSmallWindowPackages(arrayOf(packageName))
        true
    } catch (_: IllegalArgumentException) { false }
}
