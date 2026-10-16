package com.huanchengfly.icebridge.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.huanchengfly.icebridge.SystemServerConfigContract
import com.huanchengfly.icebridge.activities.SmallWindowSettingsActivity
import com.huanchengfly.icebridge.automation.LinkAutomationConfig
import com.huanchengfly.icebridge.automation.SmallWindowLayout
import com.huanchengfly.icebridge.ui.theme.IceBridgeTheme
import com.huanchengfly.icebridge.ui.theme.LocalIceBridgePalette
import com.huanchengfly.icebridge.ui.theme.ThemeCard
import com.huanchengfly.icebridge.ui.theme.setIceBridgeContent
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

object MiuixSmallWindowSettingsScreen {
    @JvmStatic
    fun install(activity: SmallWindowSettingsActivity) = activity.setIceBridgeContent {
        IceBridgeTheme { SmallWindowSettingsPage(activity) }
    }
}

@Composable
private fun SmallWindowSettingsPage(activity: SmallWindowSettingsActivity) {
    val preferences = remember { activity.getSharedPreferences(LinkAutomationConfig.PREFS, Context.MODE_PRIVATE) }
    fun readLayout() = runCatching {
        SmallWindowLayout.parse(preferences.getString(SystemServerConfigContract.KEY_SMALL_WINDOW_LAYOUT, null))
    }.getOrDefault(SmallWindowLayout.DEFAULT)
    var saved by remember { mutableStateOf(readLayout()) }
    DisposableEffect(activity, preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SystemServerConfigContract.KEY_SMALL_WINDOW_LAYOUT) saved = readLayout()
        }
        val lifecycle = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) saved = readLayout()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        activity.lifecycle.addObserver(lifecycle)
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            activity.lifecycle.removeObserver(lifecycle)
        }
    }
    val palette = LocalIceBridgePalette.current
    Scaffold(topBar = { TopAppBar(title = "小窗尺寸与位置", largeTitle = "小窗尺寸与位置") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("在真实屏幕上调节", fontSize = 18.sp)
                    Text("拖动线框移动位置，拖动边缘或圆点调整大小。中转卡片始终跟随线框中心。",
                        fontSize = 14.sp, color = palette.onSurfaceVariant)
                    Text("保存后线框和调节工具立即隐藏；取消或返回不保存修改。",
                        fontSize = 14.sp, color = palette.onSurfaceVariant)
                    TextButton(onClick = activity::launchLayoutEditor) { Text("开始屏幕调节") }
                }
            }
            ThemeCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("已保存的布局", fontSize = 18.sp)
                    Text("宽度 ${saved.widthPercent}% · 高度 ${saved.heightPercent}%", fontSize = 14.sp)
                    Text("水平 ${saved.horizontalPercent}% · 垂直 ${saved.verticalPercent}%", fontSize = 14.sp)
                    Text("布局应用到已选的小窗目标。保存后等待系统同步确认，再冻结 IceBridge。",
                        fontSize = 13.sp, color = palette.onSurfaceVariant)
                }
            }
        }
    }
}
