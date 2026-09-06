package com.huanchengfly.icebridge.ui

import android.content.Intent
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.icebridge.activities.AboutActivity
import com.huanchengfly.icebridge.activities.EnhanceModeActivity
import com.huanchengfly.icebridge.activities.MainActivity
import com.huanchengfly.icebridge.activities.PermissionActivity
import com.huanchengfly.icebridge.activities.RuleConfigActivity
import com.huanchengfly.icebridge.engines.BaseEngine
import com.huanchengfly.icebridge.engines.EngineManager
import com.huanchengfly.icebridge.utils.PackageUtil
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.theme.MiuixTheme

object MiuixMainScreen {
    @JvmStatic
    fun install(activity: MainActivity) {
        activity.setContent { MiuixTheme { Home(activity) } }
    }
}

@Composable
private fun Home(activity: MainActivity) {
    val engine = remember { EngineManager.getEngine(activity) }
    var refreshToken by remember { mutableIntStateOf(0) }
    val status = remember(refreshToken) { engine.statusDescription?.toString().orEmpty() }
    val statusLabel = when (engine.engineStatus) {
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
                    text = if (status.isBlank()) "\u6b63\u5728\u68c0\u67e5\u89e3\u51bb\u670d\u52a1\u72b6\u6001" else status,
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
                    onClick = { refreshToken++ }
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
