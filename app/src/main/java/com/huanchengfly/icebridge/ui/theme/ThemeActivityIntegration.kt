package com.huanchengfly.icebridge.ui.theme

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.huanchengfly.icebridge.R

/** Android window/legacy views use the same seed and tones as Compose. */
object ThemeActivityIntegration {
    private val launches = ThemeLaunchGate()

    @JvmStatic
    fun beforeCreate(activity: AppCompatActivity, userFacing: Boolean) {
        val store = ThemePreferencesStore.get(activity)
        if (launches.beginIfUserFacing(userFacing)) store.beginUserSession() else store.resolve()
        val listener = Runnable {
            activity.runOnUiThread {
                if (!activity.isDestroyed && !activity.isFinishing) {
                    applyWindow(activity, store.snapshot)
                }
            }
        }
        store.addListener(listener)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { applyWindow(activity, store.resolve()) }
            override fun onResume(owner: LifecycleOwner) { applyWindow(activity, store.resolve()) }
            override fun onDestroy(owner: LifecycleOwner) { store.removeListener(listener) }
        })
    }

    private fun isDark(context: Context, state: ThemePolicy.State): Boolean =
        when (state.appearance) {
            ThemePolicy.APPEARANCE_LIGHT -> false
            ThemePolicy.APPEARANCE_DARK -> true
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

    class LegacyCardColors(@JvmField val background: Int, @JvmField val title: Int, @JvmField val subtitle: Int)

    @JvmStatic fun legacyCardColors(context: Context, selected: Boolean): LegacyCardColors {
        val state = ThemePreferencesStore.get(context).snapshot
        val palette = ThemePalette(state.seedColor, isDark(context, state))
        return if (selected) LegacyCardColors(palette.primaryContainer.toArgb(), palette.onPrimaryContainer.toArgb(), palette.onPrimaryContainer.toArgb())
        else LegacyCardColors(palette.smallCard.toArgb(), palette.onSurface.toArgb(), palette.onSurfaceVariant.toArgb())
    }

    @JvmStatic fun configurationChanged(activity: AppCompatActivity) {
        applyWindow(activity, ThemePreferencesStore.get(activity).resolve())
    }

    @JvmStatic fun applyFrozenBadge(view: View?, context: Context) {
        if (view == null) return
        val state = ThemePreferencesStore.get(context).snapshot
        val palette = ThemePalette(state.seedColor, isDark(context, state))
        view.backgroundTintList = ColorStateList.valueOf(palette.primary.toArgb())
        fun textColor(child: View) {
            if (child is TextView) child.setTextColor(palette.onPrimary.toArgb())
            if (child is ViewGroup) for (i in 0 until child.childCount) textColor(child.getChildAt(i))
        }
        textColor(view)
    }

    @JvmStatic fun applyDialog(dialog: Dialog) {
        val state = ThemePreferencesStore.get(dialog.context).snapshot
        val palette = ThemePalette(state.seedColor, isDark(dialog.context, state))
        val window = dialog.window ?: return
        window.setBackgroundDrawable(rounded(dialog.context, palette.dialog.toArgb(), 24f))
        tintLegacy(window.decorView, palette)
    }

    private fun applyWindow(activity: Activity, state: ThemePolicy.State) {
        val dark = when (state.appearance) {
            ThemePolicy.APPEARANCE_LIGHT -> false
            ThemePolicy.APPEARANCE_DARK -> true
            else -> (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val palette = ThemePalette(state.seedColor, dark)
        val translucent = activity is com.huanchengfly.icebridge.activities.BridgeActivity ||
            activity is com.huanchengfly.icebridge.activities.ShareBridgeActivity
        val window = activity.window
        if (!translucent) {
            window.decorView.setBackgroundColor(palette.background.toArgb())
            window.statusBarColor = palette.background.toArgb()
            window.navigationBarColor = palette.background.toArgb()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        activity.findViewById<View>(R.id.bridge_panel)?.background = rounded(activity, palette.mediumCard.toArgb(), 28f)
        for (id in intArrayOf(R.id.bridge_source_icon, R.id.bridge_target_icon)) {
            activity.findViewById<View>(id)?.background = rounded(activity, palette.smallCard.toArgb(), 20f)
        }
        activity.findViewById<TextView>(R.id.bridge_arrow)?.setTextColor(palette.primary.toArgb())
        activity.findViewById<View>(R.id.bottom_bar)?.setBackgroundColor(palette.mediumCard.toArgb())
        // Intro and the transient bridge use Android Views. Compose draws its own themed tree.
        if (activity is com.huanchengfly.icebridge.activities.BaseIntroActivity || translucent) {
            val content = activity.findViewById<View>(android.R.id.content)
            content?.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int,
                                            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    v.removeOnLayoutChangeListener(this)
                    tintLegacy(v, palette)
                }
            })
            tintLegacy(content, palette)
        }
    }

    private fun rounded(context: Context, color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * context.resources.displayMetrics.density
    }

    private fun tintLegacy(view: View?, palette: ThemePalette) {
        // Picker adapters assign selected/unselected and badge colors themselves.
        if (view is androidx.recyclerview.widget.RecyclerView) return
        when (view) {
            is Button -> view.setTextColor(palette.primary.toArgb())
            is TextView -> view.setTextColor(when (view.id) {
                R.id.bridge_arrow -> palette.primary.toArgb()
                R.id.subtitle -> palette.onSurfaceVariant.toArgb()
                else -> palette.onSurface.toArgb()
            })
            is ProgressBar -> view.indeterminateTintList = ColorStateList.valueOf(palette.primary.toArgb())
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) tintLegacy(view.getChildAt(i), palette)
    }
}
