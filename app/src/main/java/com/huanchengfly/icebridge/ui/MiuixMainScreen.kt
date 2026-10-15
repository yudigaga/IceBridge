package com.huanchengfly.icebridge.ui

import android.content.Intent
import com.huanchengfly.icebridge.ui.theme.setIceBridgeContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.huanchengfly.icebridge.activities.AboutActivity
import com.huanchengfly.icebridge.activities.EnhanceModeActivity
import com.huanchengfly.icebridge.activities.MainActivity
import com.huanchengfly.icebridge.activities.PermissionActivity
import com.huanchengfly.icebridge.activities.RuleConfigActivity
import com.huanchengfly.icebridge.activities.ThemeSettingsActivity
import com.huanchengfly.icebridge.engines.BaseEngine
import com.huanchengfly.icebridge.engines.EngineManager
import com.huanchengfly.icebridge.engines.IceBoxEngine
import com.huanchengfly.icebridge.engines.RootEngine
import com.huanchengfly.icebridge.engines.RootCommandRunner
import com.huanchengfly.icebridge.utils.PackageUtil
import com.huanchengfly.icebridge.ui.theme.ThemeCard as Card
import com.huanchengfly.icebridge.ui.theme.CardLevel
import com.huanchengfly.icebridge.ui.theme.IceBridgeTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object MiuixMainScreen {
    @JvmStatic
    fun install(activity: MainActivity) {
        activity.setIceBridgeContent { IceBridgeTheme { Home(activity) } }
    }
}

@Composable
private fun Home(activity: MainActivity) {
    var refreshToken by remember { mutableIntStateOf(0) }
    var foreground by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var rootProbeRequested by remember { mutableStateOf(false) }
    var rootNeedsCheck by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var engineStatus by remember { mutableIntStateOf(BaseEngine.STATUS_NOT_AVAILABLE) }
    val requests = remember { RefreshGeneration() }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { foreground = true; refreshToken++ }
                Lifecycle.Event.ON_PAUSE -> { foreground = false; requests.invalidate() }
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        foreground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { requests.invalidate(); activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(activity, foreground, refreshToken) {
        if (!foreground) return@LaunchedEffect
        val request = requests.begin()
        val probeRoot = rootProbeRequested
        rootProbeRequested = false
        checking = true
        try {
            // Re-select the engine after returning from service selection; never inspect providers
            // from composition. The existing refresh methods already perform their work off-thread.
            val engine = withContext(Dispatchers.IO) { EngineManager.getEngine(activity.applicationContext) }
            suspendCancellableCoroutine<Unit> { continuation ->
                val complete = Runnable { if (continuation.isActive) continuation.resume(Unit) }
                when (engine) {
                    is IceBoxEngine -> engine.refreshDiagnostic(complete)
                    // A passive resume must not launch su, even when it used to be authorized:
                    // permission could have been revoked and the resulting dialog resumes us again.
                    is RootEngine -> if (probeRoot) engine.refreshStatus {
                        complete.run()
                        // A permission dialog can pause/cancel this coroutine. Once it completes,
                        // schedule a passive reread of the current engine, never apply its old UI state.
                        if (!activity.isDestroyed && !activity.isFinishing) refreshToken++
                    } else complete.run()
                    else -> complete.run()
                }
            }
            val snapshot = withContext(Dispatchers.IO) {
                val unknownRoot = engine is RootEngine && RootCommandRunner.getStatus() == RootCommandRunner.Status.UNKNOWN
                val description = if (unknownRoot) "尚未检查 Root 授权；点击刷新状态或前往权限页检查"
                else engine.statusDescription?.toString().orEmpty() +
                    if (engine is RootEngine && !probeRoot) "\nRoot 状态来自上次检查，点击刷新重新检查" else ""
                Triple(engine.engineStatus, description, unknownRoot)
            }
            if (requests.accepts(request)) {
                engineStatus = snapshot.first
                status = snapshot.second
                rootNeedsCheck = snapshot.third
                checking = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (requests.accepts(request)) {
                engineStatus = BaseEngine.STATUS_NOT_AVAILABLE
                rootNeedsCheck = false
                status = "状态检查失败，请重试：${error.message ?: error.javaClass.simpleName}"
                checking = false
            }
        }
    }
    val statusLabel = if (checking) "正在检查" else if (rootNeedsCheck) "待检查" else when (engineStatus) {
        BaseEngine.STATUS_WORKING -> "\u5df2\u5c31\u7eea"
        BaseEngine.STATUS_NEED_PERMISSION -> "\u9700\u8981\u6388\u6743"
        else -> "\u4e0d\u53ef\u7528"
    }
    Scaffold(topBar = { TopAppBar(title = "IceBridge", largeTitle = "IceBridge") }) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(text = statusLabel, modifier = Modifier.padding(16.dp), fontSize = 20.sp)
                Text(
                    text = if (checking) "正在检查解冻服务状态" else status,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            SectionTitle("\u6865\u63a5")
            Card(modifier = Modifier.fillMaxWidth()) {
                SuperArrow(
                    title = "\u6865\u63a5\u4e0e\u81ea\u52a8\u5316",
                    summary = "\u94fe\u63a5\u6355\u83b7\u3001\u652f\u4ed8 SDK\u3001\u526a\u8d34\u677f\u3001\u89c4\u5219\u548c\u8bca\u65ad",
                    onClick = { activity.startActivity(Intent(activity, EnhanceModeActivity::class.java)) }
                )
                SuperArrow(
                    title = "\u89c4\u5219\u914d\u7f6e",
                    summary = "\u6309\u5e94\u7528\u7ba1\u7406\u9ed8\u8ba4\u3001\u5728\u7ebf\u548c\u624b\u52a8 URL \u89c4\u5219",
                    onClick = { activity.startActivity(Intent(activity, RuleConfigActivity::class.java)) }
                )
            }
            SectionTitle("外观")
            Card(modifier = Modifier.fillMaxWidth(), level = CardLevel.SMALL) {
                SuperArrow(
                    title = "主题与颜色",
                    summary = "固定颜色、随机换色与日夜外观",
                    onClick = { activity.startActivity(Intent(activity, ThemeSettingsActivity::class.java)) }
                )
            }
            SectionTitle("\u72b6\u6001\u4e0e\u4fe1\u606f")
            Card(modifier = Modifier.fillMaxWidth()) {
                SuperArrow(
                    title = "\u6743\u9650\u4e0e\u89e3\u51bb\u670d\u52a1",
                    summary = "\u68c0\u67e5 IceBox\u3001Root \u4e0e LSPosed \u72b6\u6001\uff0c\u5e76\u9009\u62e9\u89e3\u51bb\u670d\u52a1",
                    onClick = { activity.startActivity(Intent(activity, PermissionActivity::class.java)) }
                )
                SuperArrow(
                    title = "\u5237\u65b0\u4e3b\u9875\u72b6\u6001",
                    summary = "\u91cd\u65b0\u8bfb\u53d6\u5f53\u524d\u89e3\u51bb\u670d\u52a1\u7684\u72b6\u6001",
                    onClick = { rootProbeRequested = true; refreshToken++ }
                )
                SuperArrow(
                    title = "\u5173\u4e8e",
                    summary = "yudigaga - ${PackageUtil.getVersionName(activity)}",
                    onClick = { activity.startActivity(Intent(activity, AboutActivity::class.java)) }
                )
            }
            Text(
                text = "\u63a8\u8350\u5728 LSPosed \u4e2d\u4ec5\u52fe\u9009 system\uff0c\u4e0d\u8981\u52fe\u9009\u5fae\u4fe1\u3001QQ\u3001IceBox \u6216\u76ee\u6807\u5e94\u7528",
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 24.dp),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
        fontSize = 14.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
}
