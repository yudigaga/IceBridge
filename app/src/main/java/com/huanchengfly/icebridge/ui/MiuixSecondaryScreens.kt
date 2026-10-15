package com.huanchengfly.icebridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.provider.Settings
import com.huanchengfly.icebridge.ui.theme.setIceBridgeContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catchingnow.icebox.sdk_client.IceBox
import com.huanchengfly.icebridge.activities.AboutActivity
import com.huanchengfly.icebridge.activities.BridgePreferencesActivity
import com.huanchengfly.icebridge.activities.EnhanceModeActivity
import com.huanchengfly.icebridge.activities.PermissionActivity
import com.huanchengfly.icebridge.activities.SmallWindowSettingsActivity
import com.huanchengfly.icebridge.databases.AppConfig
import com.huanchengfly.icebridge.SystemServerConfigContract
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.huanchengfly.icebridge.engines.RootCommandRunner
import com.huanchengfly.icebridge.fragments.intro.WorkModeFragment
import com.huanchengfly.icebridge.services.ClipboardLinkService
import com.huanchengfly.icebridge.utils.DiagnosticLogExporter
import com.huanchengfly.icebridge.utils.PackageUtil
import com.huanchengfly.icebridge.utils.RecentTaskVisibility
import com.huanchengfly.icebridge.utils.Util
import org.litepal.LitePal
import com.huanchengfly.icebridge.ui.theme.ThemeCard as Card
import com.huanchengfly.icebridge.ui.theme.CardLevel
import com.huanchengfly.icebridge.ui.theme.IceBridgeTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

object MiuixSecondaryScreens {
    @JvmStatic fun installBridgeSettings(a: BridgePreferencesActivity) = a.setIceBridgeContent { IceBridgeTheme { RetiredBridgeSettings() } }
    @JvmStatic fun installEnhanceSettings(a: EnhanceModeActivity) = a.setIceBridgeContent { IceBridgeTheme { EnhanceSettings(a) } }
    @JvmStatic fun installPermissionStatus(a: PermissionActivity) = a.setIceBridgeContent { IceBridgeTheme { PermissionStatus(a) } }
    @JvmStatic fun installAbout(a: AboutActivity) = a.setIceBridgeContent { IceBridgeTheme { About(a) } }
}
private const val PREFS = "preferences"
private const val KEY_ENHANCE = "enhance_mode_enabled"
private const val KEY_AUTO_BRIDGE = "xhs_resolve_recovery"
private const val KEY_AUTO_OPEN = "auto_open_links"
private const val KEY_NATIVE_CAPTURE = "native_link_capture"
private const val KEY_PAYMENT_BRIDGE = "payment_sdk_bridge"
private const val KEY_HIDE_RECENTS = "hide_bridge_from_recents"
private const val KEY_BROWSER_FALLBACK = "link_browser_fallback"
private const val KEY_CLIPBOARD = "clipboard_auto_open"
private const val KEY_DEBUG = "module_debug_logging"
private fun Context.preferenceStore() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
private fun Context.setPref(k: String, v: Boolean) { preferenceStore().edit().putBoolean(k, v).apply() }
private fun saveConfig(k: String, v: Boolean) { AppConfig(k, if (v) "1" else "0").saveOrUpdate("key = ?", k) }
private fun isConfigEnabled(k: String, f: Boolean): Boolean { val c = LitePal.where("key = ?", k).findFirst(AppConfig::class.java); return c?.value == "1" || (c == null && f) }

@Composable private fun RetiredBridgeSettings() = MiuixPage("桥接设置") { p -> SettingsColumn(p) { Card(Modifier.fillMaxWidth()) { InfoRow("设置已合并", "旧入口已合并到“桥接与自动化”，请从主页进入统一设置页。") } } }

@Composable private fun EnhanceSettings(a: EnhanceModeActivity) {
    val p = remember { a.preferenceStore() }
    var enhanced by remember { mutableStateOf(isConfigEnabled(KEY_ENHANCE, true)) }
    var bridge by remember { mutableStateOf(p.getBoolean(KEY_AUTO_BRIDGE, false)) }
    var auto by remember { mutableStateOf(p.getBoolean(KEY_AUTO_OPEN, false)) }
    var native by remember { mutableStateOf(p.getBoolean(KEY_NATIVE_CAPTURE, false)) }
    var payment by remember { mutableStateOf(p.getBoolean(KEY_PAYMENT_BRIDGE, true)) }
    var recents by remember { mutableStateOf(p.getBoolean(KEY_HIDE_RECENTS, true)) }
    var fallback by remember { mutableStateOf(p.getBoolean(KEY_BROWSER_FALLBACK, true)) }
    var clip by remember { mutableStateOf(p.getBoolean(KEY_CLIPBOARD, false)) }
    var debug by remember { mutableStateOf(p.getBoolean(KEY_DEBUG, false)) }
    var autonomous by remember { mutableStateOf(p.getBoolean(SystemServerConfigContract.KEY_AUTONOMOUS_RUNTIME, false)) }
    var transitCard by remember { mutableStateOf(p.getBoolean(SystemServerConfigContract.KEY_TRANSIT_CARD_ENABLED, false)) }
    var smallWindow by remember { mutableStateOf(p.getBoolean(SystemServerConfigContract.KEY_OPEN_LINKS_IN_SMALL_WINDOW, false)) }
    var smallWindowPackages by remember { mutableStateOf(p.getStringSet(SystemServerConfigContract.KEY_SMALL_WINDOW_PACKAGES, emptySet()).orEmpty().toSet()) }
    var showSmallWindowApps by remember { mutableStateOf(false) }
    val runtimeStatus = remember { a.getSharedPreferences(SystemServerConfigContract.RUNTIME_STATUS_PREFS, Context.MODE_PRIVATE) }
    var statusRevision by remember { mutableStateOf(0) }
    DisposableEffect(a, p, runtimeStatus) {
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            autonomous = p.getBoolean(SystemServerConfigContract.KEY_AUTONOMOUS_RUNTIME, false)
            transitCard = p.getBoolean(SystemServerConfigContract.KEY_TRANSIT_CARD_ENABLED, false)
            smallWindow = p.getBoolean(SystemServerConfigContract.KEY_OPEN_LINKS_IN_SMALL_WINDOW, false)
            smallWindowPackages = p.getStringSet(SystemServerConfigContract.KEY_SMALL_WINDOW_PACKAGES, emptySet()).orEmpty().toSet()
            statusRevision++
        }
        val lifecycleListener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) statusRevision++
        }
        p.registerOnSharedPreferenceChangeListener(preferenceListener)
        runtimeStatus.registerOnSharedPreferenceChangeListener(preferenceListener)
        a.lifecycle.addObserver(lifecycleListener)
        onDispose {
            p.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            runtimeStatus.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            a.lifecycle.removeObserver(lifecycleListener)
        }
    }
    val runtimeSummary = remember(statusRevision, autonomous) {
        val expected = runtimeStatus.getString(SystemServerConfigContract.KEY_RUNTIME_EXPECTED_FINGERPRINT, "")
        val confirmed = runtimeStatus.getString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, "")
        val boot = runCatching { Settings.Global.getInt(a.contentResolver, "boot_count", -1) }.getOrDefault(-1)
        val currentBoot = boot >= 0 && boot == runtimeStatus.getInt(SystemServerConfigContract.KEY_RUNTIME_BOOT_COUNT, -2)
        val persistedAck = runtimeStatus.getBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, false)
        val ready = autonomous && currentBoot && !expected.isNullOrBlank() && expected == confirmed &&
            persistedAck && runtimeStatus.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false)
        val revision = runtimeStatus.getLong(SystemServerConfigContract.KEY_RUNTIME_REVISION, 0)
        when {
            !autonomous && persistedAck && currentBoot && !expected.isNullOrBlank() && expected == confirmed ->
                "系统已确认关闭，独立运行配置已撤销。"
            !autonomous && currentBoot && !expected.isNullOrBlank() && expected == confirmed ->
                "系统尚未确认撤销，请保持本体解冻。" + (runtimeStatus.getString(SystemServerConfigContract.KEY_RUNTIME_MESSAGE, "") ?: "")
            !autonomous -> "未启用。开启并等待系统确认后，再用 IceBox 冻结 IceBridge。"
            ready -> "配置已由系统保存，可冻结 IceBridge。在线规则 revision $revision；本体冻结后仍由系统执行已启用的功能。"
            !currentBoot -> "等待本次开机的系统确认。请确认 LSPosed 已启用 system 作用域，暂时保持 IceBridge 解冻。"
            else -> runtimeStatus.getString(SystemServerConfigContract.KEY_RUNTIME_MESSAGE, "")
                .takeUnless { it.isNullOrBlank() } ?: "等待系统保存并确认当前配置，请暂时保持 IceBridge 解冻。"
        }
    }
    fun markConfigPending() {
        runtimeStatus.edit().putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false)
            .putBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, false)
            .putString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, "")
            .putString(SystemServerConfigContract.KEY_RUNTIME_MESSAGE, "配置已变化，等待系统重新确认").apply()
    }
    fun setv(k: String, v: Boolean) {
        markConfigPending()
        a.setPref(k, v); saveConfig(k, v); SystemServerConfigContract.notifyChanged(a)
    }
    fun prereq() { if (!bridge) { bridge = true; setv(KEY_AUTO_BRIDGE, true) }; if (!auto) { auto = true; setv(KEY_AUTO_OPEN, true) } }
    fun setBridge(v: Boolean) { bridge = v; setv(KEY_AUTO_BRIDGE, v); if (!v) { auto = false; native = false; payment = false; setv(KEY_AUTO_OPEN, false); setv(KEY_NATIVE_CAPTURE, false); setv(KEY_PAYMENT_BRIDGE, false) } }
    MiuixPage("桥接与自动化") { pad -> SettingsColumn(pad) {
        SectionTitle("基础"); Card(Modifier.fillMaxWidth()) {
            SuperSwitch(title="启用增强模式", summary="关闭后暂停所有链接自动处理", checked=enhanced, onCheckedChange={ enhanced=it; setv(KEY_ENHANCE,it) })
            SuperSwitch(title="启用链接桥接", summary="自动识别网址、解冻目标应用并打开", checked=bridge, onCheckedChange={ setBridge(it) })
            SuperSwitch(title="点击后自动打开应用", summary="不需要再点击“在 App 中打开”", checked=auto, onCheckedChange={ auto=it; setv(KEY_AUTO_OPEN,it) })
        }
        SectionTitle("冻结本体后运行"); Card(Modifier.fillMaxWidth()) {
            SuperSwitch(title="冻结 IceBridge 后独立运行", summary="将开关与完整规则同步到系统，在 IceBox 冻结本体后继续处理链接", checked=autonomous,
                onCheckedChange={ autonomous=it; setv(SystemServerConfigContract.KEY_AUTONOMOUS_RUNTIME,it) })
            InfoRow("系统同步状态", runtimeSummary)
            SuperArrow(title="请求系统同步", summary="重新发送配置，等待本次开机的系统确认", onClick={
                runtimeStatus.edit().putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false)
                    .putBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, false)
                    .putString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, "")
                    .putString(SystemServerConfigContract.KEY_RUNTIME_MESSAGE, "正在请求系统同步，请暂时保持 IceBridge 解冻").apply()
                SystemServerConfigContract.notifyChanged(a)
            })
            InfoRow("配置同步", "修改开关或规则后，请等待系统重新确认。关闭此功能也需要保持本体解冻，等系统同步后再退出。独立配置在重启后恢复；设备上的模块及 system 作用域需要保持启用。当前独立运行仅支持主用户（用户 0），工作资料与其他用户不处理。")
        }
        SectionTitle("触发来源"); Card(Modifier.fillMaxWidth()) {
            SuperSwitch(title="内置链接捕获", summary="通过 system 作用域捕获系统中的 HTTPS 链接", checked=native, onCheckedChange={ if(it) prereq(); native=it; setv(KEY_NATIVE_CAPTURE,it) })
            SuperSwitch(title="支付 SDK 桥接", summary="处理支付类 H5 拉起和已冻结支付应用", checked=payment, onCheckedChange={ if(it) prereq(); payment=it; setv(KEY_PAYMENT_BRIDGE,it) })
            SuperSwitch(title="剪贴板链接自动打开", summary="复制支持的链接后自动处理", checked=clip, onCheckedChange={ clip=it; setv(KEY_CLIPBOARD,it); ClipboardLinkService.update(a) })
        }
        SectionTitle("跳转行为"); Card(Modifier.fillMaxWidth()) {
            SuperSwitch(title="显示中转卡片", summary="独立运行时显示应用图标与流线；关闭不影响解冻和跳转", checked=transitCard,
                onCheckedChange={ transitCard=it; setv(SystemServerConfigContract.KEY_TRANSIT_CARD_ENABLED,it) })
            SuperSwitch(title="小窗打开", summary="独立运行时，仅对下方选中的链接目标请求系统小窗", checked=smallWindow,
                onCheckedChange={ smallWindow=it; setv(SystemServerConfigContract.KEY_OPEN_LINKS_IN_SMALL_WINDOW,it) })
            SuperArrow(title="小窗应用", summary=if(smallWindowPackages.isEmpty()) "未选择应用时不使用小窗" else "已选择 ${smallWindowPackages.size} 个应用", onClick={ showSmallWindowApps=true })
            SuperArrow(title="小窗尺寸与位置", summary="调整宽高和位置，实时预览小窗与中转卡片", onClick={ a.startActivity(Intent(a, SmallWindowSettingsActivity::class.java)) })
            InfoRow("规则内统一打开", "命中规则的普通链接始终由模块打开目标；冻结时先解冻，已解冻时直接打开。")
            SuperSwitch(title="不在最近任务中显示 IceBridge", summary="隐藏临时桥接页与 IceBridge 任务", checked=recents, onCheckedChange={ recents=it; a.setPref(KEY_HIDE_RECENTS,it); RecentTaskVisibility.apply(a,it) })
            SuperSwitch(title="跳转失败时回退浏览器", summary="目标应用无法打开时继续打开原网页", checked=fallback, onCheckedChange={ fallback=it; setv(KEY_BROWSER_FALLBACK,it) })
        }
        SectionTitle("权限与诊断"); Card(Modifier.fillMaxWidth()) {
            SuperArrow(title="权限与解冻服务", summary="检查 Root、IceBox、LSPosed，并选择解冻服务", onClick={ a.startActivity(Intent(a, PermissionActivity::class.java)) })
            SuperSwitch(title="LSPosed 模块日志", summary="仅在排查问题时开启", checked=debug, onCheckedChange={ debug=it; setv(KEY_DEBUG,it) })
            SuperArrow(title="导出诊断日志", summary="导出权限、模块和链接自动化诊断", onClick={ DiagnosticLogExporter.export(a) })
            SuperArrow(title="推荐作用域", summary="LSPosed 中仅勾选 system；不要勾选微信、QQ、IceBox 或目标应用", onClick={ })
        }
    } }
    if (showSmallWindowApps) SmallWindowAppsDialog(a, smallWindowPackages, onSave={ selected ->
        markConfigPending()
        smallWindowPackages = selected.toSet()
        p.edit().putStringSet(SystemServerConfigContract.KEY_SMALL_WINDOW_PACKAGES, selected.toSet()).apply()
        SystemServerConfigContract.notifyChanged(a)
        showSmallWindowApps = false
    }, onDismiss={ showSmallWindowApps=false })
}

@Composable private fun PermissionStatus(a: PermissionActivity) {
    val settings = remember { a.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val root = settings.getInt(WorkModeFragment.SP_WORK_MODE, WorkModeFragment.MODE_ICEBOX) == WorkModeFragment.MODE_ROOT
    val rootStatus = when (RootCommandRunner.getStatus()) { RootCommandRunner.Status.WORKING -> "已授权，可用"; RootCommandRunner.Status.NEED_PERMISSION -> "检测到 Root，但尚未授权"; RootCommandRunner.Status.NOT_AVAILABLE -> "未检测到可用 Root"; else -> "正在检查" }
    val ice = a.checkSelfPermission(IceBox.SDK_PERMISSION) == PackageManager.PERMISSION_GRANTED
    val state = a.checkSelfPermission("com.catchingnow.icebox.STATE") == PackageManager.PERMISSION_GRANTED
    var dialog by remember { mutableStateOf(false) }
    MiuixPage("权限与解冻服务") { p -> SettingsColumn(p) {
        SectionTitle("当前解冻服务"); Card(Modifier.fillMaxWidth()) { SuperArrow(title=if(root) "Root 模式" else "IceBox 模式", summary="点击切换解冻服务；切换不会自动请求 Root 授权", onClick={ dialog=true }) }
        SectionTitle("权限状态"); Card(Modifier.fillMaxWidth()) {
            SuperArrow(title="Root 权限", summary=rootStatus, onClick={ a.requestRootPermission() })
            SuperArrow(title="IceBox SDK 权限", summary=if(ice) "已授权" else "未授权，点击请求 IceBox SDK 权限", onClick={ a.requestIceBoxPermission() })
            InfoRow("IceBox 状态读取", if(state) "已授权" else "未授杆或 IceBox 未提供状态权限")
            InfoRow("LSPosed 模块", if(Util.isXposedActive()) "已激活" else "未检测到激活状态")
        }
        SectionTitle("说明"); Card(Modifier.fillMaxWidth()) { InfoRow("推荐配置", "使用 IceBox 时选择 IceBox 模式；仅需要通过 su 直接冻结或解冻时再选择 Root 模式。"); SuperArrow(title="刷新状态", summary="重新检查 Root、IceBox 与 LSPosed", onClick={ a.refreshPermissionStatus() }) }
    } }
    if(dialog) AlertDialog(onDismissRequest={dialog=false}, title={Text(text="选择解冻服务")}, text={Column { TextButton(onClick={a.selectWorkMode(WorkModeFragment.MODE_ICEBOX);dialog=false}) { Text(text="IceBox 模式（推荐）") }; TextButton(onClick={a.selectWorkMode(WorkModeFragment.MODE_ROOT);dialog=false}) { Text(text="Root 模式") } }}, confirmButton={TextButton(onClick={dialog=false}) { Text(text="取消") }})
}
@Composable private fun About(a: AboutActivity) = MiuixPage("关于") { p ->
    SettingsColumn(p) {
        Card(Modifier.fillMaxWidth()) {
            InfoRow("IceBridge", "IceBox 应用解冻、链接自动恢复与跳转工具")
            InfoRow("作者", "yudigaga")
            InfoRow("版本", PackageUtil.getVersionName(a))
            SuperArrow(
                title = "GitHub 源码",
                summary = GITHUB_REPOSITORY,
                onClick = { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPOSITORY))) }
            )
        }
        SectionTitle("使用说明")
        Card(Modifier.fillMaxWidth()) {
            InfoRow("推荐作用域", "LSPosed 中仅勾选 system")
            InfoRow("工作方式", "system 识别被冻结应用的链接，异步解冻后再恢复原始跳转。")
        }
    }
}

private const val GITHUB_REPOSITORY = "https://github.com/yudigaga/IceBridge"
@Composable private fun InfoRow(t: String, s: String) { Column(Modifier.fillMaxWidth().padding(horizontal=16.dp, vertical=14.dp)) { Text(text=t, fontSize=18.sp); Text(text=s, modifier=Modifier.padding(top=4.dp), fontSize=14.sp, color=MiuixTheme.colorScheme.onSurfaceVariantSummary) } }
@Composable private fun MiuixPage(t: String, c: @Composable (PaddingValues) -> Unit) = Scaffold(topBar={TopAppBar(title=t, largeTitle=t)}, content=c)
@Composable private fun SettingsColumn(p: PaddingValues, c: @Composable () -> Unit) { Column(Modifier.fillMaxSize().padding(p).padding(horizontal=16.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) { c() } }
@Composable private fun SectionTitle(t: String) { Text(text=t, modifier=Modifier.padding(start=12.dp, top=8.dp), fontSize=14.sp, color=MiuixTheme.colorScheme.onSurfaceVariantSummary) }
