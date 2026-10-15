package com.huanchengfly.icebridge.ui

import com.huanchengfly.icebridge.ui.theme.setIceBridgeContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.icebridge.activities.ThemeSettingsActivity
import com.huanchengfly.icebridge.ui.theme.IceBridgeTheme
import com.huanchengfly.icebridge.ui.theme.LocalIceBridgePalette
import com.huanchengfly.icebridge.ui.theme.ThemeCard
import com.huanchengfly.icebridge.ui.theme.ThemePalette
import com.huanchengfly.icebridge.ui.theme.ThemePolicy
import com.huanchengfly.icebridge.ui.theme.ThemePreferencesStore
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperSwitch
import java.util.Locale

object MiuixThemeSettingsScreen {
    @JvmStatic
    fun install(activity: ThemeSettingsActivity) = activity.setIceBridgeContent {
        IceBridgeTheme { ThemeSettingsPage(activity) }
    }
}

@Composable
private fun ThemeSettingsPage(activity: ThemeSettingsActivity) {
    val store = remember(activity) { ThemePreferencesStore.get(activity) }
    var settings by remember(store) { mutableStateOf(store.snapshot) }
    var customColor by rememberSaveable { mutableStateOf(colorHex(settings.fixedSeedColor)) }
    val inputColor = parseThemeColor(customColor)
    val palette = LocalIceBridgePalette.current

    DisposableEffect(store, activity) {
        val listener = Runnable {
            activity.runOnUiThread { settings = store.snapshot }
        }
        store.addListener(listener)
        settings = store.snapshot
        onDispose { store.removeListener(listener) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = "主题与颜色",
            largeTitle = "主题与颜色",
            actions = { TextButton(onClick = { activity.finish() }) { Text("返回") } }
        )
    }) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ThemeCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("主题预览", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "${if (settings.isRandomEnabled) "当前随机色" else "当前固定色"} ${colorHex(settings.seedColor)} · 日夜保持同一色系",
                        fontSize = 13.sp,
                        color = palette.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemePreview(settings.seedColor, false, Modifier.weight(1f))
                        ThemePreview(settings.seedColor, true, Modifier.weight(1f))
                    }
                }
            }

            ThemeSectionTitle("日夜模式")
            ThemeCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeChoiceRow(
                        options = listOf(
                            ThemePolicy.APPEARANCE_SYSTEM to "跟随系统",
                            ThemePolicy.APPEARANCE_LIGHT to "浅色",
                            ThemePolicy.APPEARANCE_DARK to "深色"
                        ),
                        selected = settings.appearance,
                        onSelect = { store.setAppearance(it) }
                    )
                    ThemeDescription("浅色与深色使用相同主题色，调整背景、文字和卡片的深浅。")
                }
            }

            ThemeSectionTitle("随机主题色")
            ThemeCard(modifier = Modifier.fillMaxWidth()) {
                SuperSwitch(
                    title = "随机主题色",
                    summary = "开启后按所选频率自动换色；关闭后恢复固定颜色",
                    checked = settings.isRandomEnabled,
                    onCheckedChange = { store.setRandomEnabled(it) }
                )
                if (settings.isRandomEnabled) {
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ThemeChoiceRow(
                            options = listOf(
                                ThemePolicy.INTERVAL_DAILY to "每天换色",
                                ThemePolicy.INTERVAL_LAUNCH to "每次启动"
                            ),
                            selected = settings.randomInterval,
                            onSelect = { store.setRandomInterval(it) }
                        )
                        ThemeDescription(
                            if (settings.randomInterval == ThemePolicy.INTERVAL_DAILY)
                                "按本地日期每天换一次；同一天切换日夜模式、切换页面或返回应用时保持当前色系。"
                            else "重新启动应用时换色，切换页面和临时离开后返回不变。"
                        )
                    }
                }
            }

            ThemeSectionTitle(if (settings.isRandomEnabled) "保留的固定颜色" else "固定主题色")
            ThemeCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeDescription(
                        if (settings.isRandomEnabled)
                            "已保留 ${colorHex(settings.fixedSeedColor)}。下方修改会保存为固定颜色，关闭随机后生效。"
                        else "选择喜欢的颜色后立即应用，也可输入自定义颜色。"
                    )
                    ThemeColorPresets(settings.fixedSeedColor) { color ->
                        customColor = colorHex(color)
                        store.setFixedSeedColor(color)
                    }
                    TextField(
                        value = customColor,
                        onValueChange = { customColor = it },
                        label = "自定义颜色 · #RRGGBB",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (inputColor == null && customColor.isNotBlank()) {
                        Text(
                            text = "请输入 #RRGGBB 格式的 6 位颜色值，例如 #2979FF",
                            color = palette.material.error,
                            fontSize = 12.sp
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(24.dp).background(
                                if (inputColor != null) Color(inputColor) else palette.smallCard,
                                CircleShape
                            )
                        )
                        TextButton(
                            enabled = inputColor != null,
                            onClick = {
                                inputColor?.let { color ->
                                    store.setFixedSeedColor(color)
                                    customColor = colorHex(color)
                                }
                            }
                        ) {
                            Text(if (settings.isRandomEnabled) "保存固定颜色" else "应用自定义颜色")
                        }
                    }
                }
            }
            ThemeDescription(
                "设置自动保存，立即应用于页面背景、按钮和大中小卡片。",
                Modifier.padding(start = 8.dp, end = 8.dp, bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun ThemePreview(seed: Int, dark: Boolean, modifier: Modifier = Modifier) {
    val colors = remember(seed, dark) { ThemePalette(seed, dark) }
    Column(
        modifier.background(colors.background, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(if (dark) "夜间" else "白天", color = colors.onSurface, fontSize = 14.sp)
        Column(
            Modifier.fillMaxWidth().background(colors.mediumCard, RoundedCornerShape(12.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("中卡片", color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("同一色系", color = colors.onSurfaceVariant, fontSize = 12.sp)
            Box(Modifier.fillMaxWidth().height(4.dp).background(colors.primary, CircleShape))
        }
        Box(
            Modifier.fillMaxWidth().background(colors.smallCard, RoundedCornerShape(10.dp)).padding(10.dp)
        ) {
            Text("小卡片", color = colors.onSurface, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ThemeColorPresets(selectedColor: Int, onSelect: (Int) -> Unit) {
    val colors = remember { ThemePolicy.seedColors().toList() }
    val labels = listOf("晴蓝", "青绿", "森林", "薰紫", "鸢尾", "玫红", "珊瑚", "琥珀", "橄榄", "雾蓝")
    val palette = LocalIceBridgePalette.current
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.withIndex().chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (index, color) ->
                    val selected = selectedColor == color
                    Column(
                        Modifier.weight(1f).heightIn(min = 64.dp)
                            .selectable(selected, role = Role.RadioButton, onClick = { onSelect(color) })
                            .semantics { contentDescription = "${labels[index]} ${colorHex(color)}" }
                            .padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            Modifier.size(38.dp)
                                .border(if (selected) 2.dp else 0.dp, if (selected) palette.primary else Color.Transparent, CircleShape)
                                .padding(4.dp).background(Color(color), CircleShape)
                        )
                        Text(
                            text = labels[index],
                            color = if (selected) palette.primary else palette.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeChoiceRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val palette = LocalIceBridgePalette.current
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .background(if (active) palette.primaryContainer else palette.smallCard, RoundedCornerShape(12.dp))
                    .border(if (active) 1.dp else 0.dp, if (active) palette.primary else Color.Transparent, RoundedCornerShape(12.dp))
                    .selectable(active, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (active) palette.onPrimaryContainer else palette.onSurface,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ThemeSectionTitle(text: String) {
    Text(text, modifier = Modifier.padding(start = 12.dp, top = 8.dp), fontSize = 14.sp,
        color = LocalIceBridgePalette.current.onSurfaceVariant)
}

@Composable
private fun ThemeDescription(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 13.sp, color = LocalIceBridgePalette.current.onSurfaceVariant)
}

private fun colorHex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0x00ffffff)

private fun parseThemeColor(value: String): Int? {
    val hex = value.trim()
    if (!hex.matches(Regex("#[0-9a-fA-F]{6}"))) return null
    return (0xff000000L or hex.substring(1).toLong(16)).toInt()
}
