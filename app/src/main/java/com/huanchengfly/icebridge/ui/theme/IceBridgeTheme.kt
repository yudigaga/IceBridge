package com.huanchengfly.icebridge.ui.theme

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.ZonedDateTime
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalIceBridgePalette = staticCompositionLocalOf { ThemePalette(ThemePolicy.DEFAULT_SEED_COLOR, false) }

enum class CardLevel { MEDIUM, SMALL }

/** Old AppCompat can retain a Decor with stale owners when an Activity is recreated. */
fun AppCompatActivity.setIceBridgeContent(content: @Composable () -> Unit) {
    window.decorView.apply {
        setViewTreeLifecycleOwner(this@setIceBridgeContent)
        setViewTreeViewModelStoreOwner(this@setIceBridgeContent)
        setViewTreeSavedStateRegistryOwner(this@setIceBridgeContent)
    }
    val view = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent(content)
    }
    setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}

/** One palette supplies both UI libraries, including text/buttons in Material dialogs. */
@Composable
fun IceBridgeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    val store = remember(context.applicationContext) { ThemePreferencesStore.get(context) }
    var state by remember(store) { mutableStateOf(store.resolve()) }
    var foreground by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) }
    var clockVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(store, context, lifecycle) {
        val listener = Runnable {
            val update = Runnable { state = store.snapshot }
            if (activity != null) activity.runOnUiThread(update) else update.run()
        }
        store.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { foreground = true; state = store.resolve(); clockVersion++ }
                Lifecycle.Event.ON_PAUSE -> foreground = false
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                state = store.resolve()
                clockVersion++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        onDispose {
            store.removeListener(listener)
            lifecycle?.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    LaunchedEffect(foreground, state.isRandomEnabled, state.randomInterval, state.randomDate, clockVersion) {
        if (!foreground || !state.isRandomEnabled || state.randomInterval != ThemePolicy.INTERVAL_DAILY) return@LaunchedEffect
        while (true) {
            val now = ZonedDateTime.now()
            val nextDay = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            delay(Duration.between(now, nextDay).toMillis().coerceAtLeast(1_000))
            state = store.resolve()
        }
    }
    val systemDark = isSystemInDarkTheme()
    val dark = when (state.appearance) {
        ThemePolicy.APPEARANCE_LIGHT -> false
        ThemePolicy.APPEARANCE_DARK -> true
        else -> systemDark
    }
    val palette = remember(state.seedColor, dark) { ThemePalette(state.seedColor, dark) }
    CompositionLocalProvider(LocalIceBridgePalette provides palette, LocalContentColor provides palette.onSurface) {
        MaterialTheme(colorScheme = palette.material) {
            MiuixTheme(colorScheme = palette.miuix, content = content)
        }
    }
}

@Composable
fun ThemeCard(
    modifier: Modifier = Modifier,
    level: CardLevel = CardLevel.MEDIUM,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalIceBridgePalette.current
    Card(modifier = modifier, color = if (level == CardLevel.SMALL) palette.smallCard else palette.mediumCard, content = content)
}
