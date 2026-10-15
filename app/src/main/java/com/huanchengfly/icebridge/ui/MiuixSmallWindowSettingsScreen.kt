package com.huanchengfly.icebridge.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.icebridge.SystemServerConfigContract
import com.huanchengfly.icebridge.activities.SmallWindowSettingsActivity
import com.huanchengfly.icebridge.android16.SystemWindowLayout
import com.huanchengfly.icebridge.automation.LinkAutomationConfig
import com.huanchengfly.icebridge.automation.SmallWindowLayout
import com.huanchengfly.icebridge.ui.theme.CardLevel
import com.huanchengfly.icebridge.ui.theme.IceBridgeTheme
import com.huanchengfly.icebridge.ui.theme.LocalIceBridgePalette
import com.huanchengfly.icebridge.ui.theme.ThemeCard
import com.huanchengfly.icebridge.ui.theme.setIceBridgeContent
import kotlin.math.min
import kotlin.math.roundToInt
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
    val initial = remember {
        runCatching {
            SmallWindowLayout.parse(preferences.getString(SystemServerConfigContract.KEY_SMALL_WINDOW_LAYOUT, null))
        }
    }
    var saved by remember { mutableStateOf(initial.getOrDefault(SmallWindowLayout.DEFAULT)) }
    var needsRepair by rememberSaveable { mutableStateOf(initial.isFailure) }
    var width by rememberSaveable { mutableIntStateOf(saved.widthPercent) }
    var height by rememberSaveable { mutableIntStateOf(saved.heightPercent) }
    var horizontal by rememberSaveable { mutableIntStateOf(saved.horizontalPercent) }
    var vertical by rememberSaveable { mutableIntStateOf(saved.verticalPercent) }
    var savedMessage by rememberSaveable { mutableStateOf(false) }
    val draft = SmallWindowLayout(width, height, horizontal, vertical)
    val palette = LocalIceBridgePalette.current
    // Maximum metrics also describe the full display if settings itself is in a small window.
    val configuration = LocalConfiguration.current
    val display = remember(activity, configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        SystemWindowLayout.availableBounds(activity)
    }

    Scaffold(topBar = { TopAppBar(title = "小窗尺寸与位置", largeTitle = "小窗尺寸与位置") }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
          val previewHeight = (maxHeight * .28f).coerceIn(64.dp, 180.dp)
          Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("布局预览", fontSize = 18.sp)
                    Text("实时预览，保存后应用到已选小窗应用。",
                        fontSize = 13.sp, color = palette.onSurfaceVariant)
                    SmallWindowPreview(draft, display.width(), display.height(), previewHeight)
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LayoutSlider("小窗宽度", width, 30..95) { width = it; savedMessage = false }
                    LayoutSlider("小窗高度", height, 25..95) { height = it; savedMessage = false }
                    LayoutSlider("水平位置", horizontal, 0..100) { horizontal = it; savedMessage = false }
                    LayoutSlider("垂直位置", vertical, 0..100) { vertical = it; savedMessage = false }
                    Text("尺寸按可用屏幕比例设置。水平位置从左到右，垂直位置从上到下。",
                        fontSize = 13.sp, color = palette.onSurfaceVariant)
                }
            }
            ThemeCard(Modifier.fillMaxWidth(), level = CardLevel.SMALL) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = {
                            val defaults = SmallWindowLayout.DEFAULT
                            width = defaults.widthPercent; height = defaults.heightPercent
                            horizontal = defaults.horizontalPercent; vertical = defaults.verticalPercent
                            savedMessage = false
                        }) { Text("恢复默认") }
                        TextButton(enabled = needsRepair || draft != saved, onClick = {
                            activity.getSharedPreferences(SystemServerConfigContract.RUNTIME_STATUS_PREFS, Context.MODE_PRIVATE)
                                .edit().putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false)
                                .putBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, false)
                                .putString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, "")
                                .putString(SystemServerConfigContract.KEY_RUNTIME_MESSAGE, "布局已变化，等待系统重新确认").apply()
                            preferences.edit().putString(SystemServerConfigContract.KEY_SMALL_WINDOW_LAYOUT, draft.encode()).apply()
                            SystemServerConfigContract.notifyChanged(activity)
                            saved = draft
                            needsRepair = false
                            savedMessage = true
                        }) { Text("保存设置") }
                    }
                    if (savedMessage) Text("已保存，等待系统同步确认。", fontSize = 13.sp,
                        color = palette.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            }
          }
        }
    }
}

@Composable
private fun LayoutSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Text("$label：$value%", fontSize = 15.sp)
    Slider(value = value.toFloat(), onValueChange = {
        onChange(it.roundToInt().coerceIn(range))
    }, valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}

@Composable
private fun SmallWindowPreview(layout: SmallWindowLayout, displayWidth: Int, displayHeight: Int, previewHeight: Dp) {
    val palette = LocalIceBridgePalette.current
    Canvas(Modifier.fillMaxWidth().height(previewHeight).semantics {
        contentDescription = "小窗布局预览"
        stateDescription = "宽度${layout.widthPercent}%，高度${layout.heightPercent}%，水平${layout.horizontalPercent}%，垂直${layout.verticalPercent}%"
    }) {
        val scale = min((size.width - 16.dp.toPx()) / displayWidth, (size.height - 12.dp.toPx()) / displayHeight)
        val screenWidth = displayWidth * scale
        val screenHeight = displayHeight * scale
        val origin = Offset((size.width - screenWidth) / 2, (size.height - screenHeight) / 2)
        drawRoundRect(palette.smallCard, origin, Size(screenWidth, screenHeight), CornerRadius(8.dp.toPx()))
        drawRoundRect(palette.outline, origin, Size(screenWidth, screenHeight), CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
        val bounds = layout.resolve(0, 0, displayWidth, displayHeight)
        val windowOrigin = origin + Offset(bounds.left * scale, bounds.top * scale)
        val windowSize = Size(bounds.width() * scale, bounds.height() * scale)
        drawRoundRect(palette.primaryContainer, windowOrigin, windowSize, CornerRadius(4.dp.toPx()))
        drawRoundRect(palette.primary, windowOrigin, windowSize, CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
        val caption = min(14.dp.toPx(), windowSize.height * .1f)
        drawLine(palette.primary, windowOrigin + Offset(0f, caption), windowOrigin + Offset(windowSize.width, caption), 1.dp.toPx())
        val center = windowOrigin + Offset(windowSize.width / 2, windowSize.height / 2)
        val cardWidth = min(windowSize.width * .68f, 48.dp.toPx())
        val cardHeight = cardWidth * 72f / 156f
        drawRoundRect(palette.mediumCard, center - Offset(cardWidth / 2, cardHeight / 2),
            Size(cardWidth, cardHeight), CornerRadius(cardHeight / 5))
        val radius = cardHeight * .18f
        val source = center - Offset(cardWidth * .25f, 0f)
        val target = center + Offset(cardWidth * .25f, 0f)
        val line = Path().apply {
            moveTo(source.x, source.y - radius * 1.5f)
            cubicTo(source.x + cardWidth * .28f, source.y - cardHeight * .65f,
                target.x - cardWidth * .28f, target.y + cardHeight * .65f, target.x, target.y + radius * 1.5f)
        }
        drawPath(line, palette.primary, style = Stroke(maxOf(1.dp.toPx(), cardWidth / 45f)))
        drawCircle(palette.primary, radius, source)
        drawCircle(palette.onPrimaryContainer, radius, target)
    }
}
