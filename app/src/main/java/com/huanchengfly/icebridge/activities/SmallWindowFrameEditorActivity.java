package com.huanchengfly.icebridge.activities;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.huanchengfly.icebridge.SystemServerConfigContract;
import com.huanchengfly.icebridge.android16.SystemWindowLayout;
import com.huanchengfly.icebridge.android16.TransitSessionPolicy;
import com.huanchengfly.icebridge.automation.LinkAutomationConfig;
import com.huanchengfly.icebridge.automation.SmallWindowLayout;
import com.huanchengfly.icebridge.ui.theme.ThemePolicy;
import com.huanchengfly.icebridge.ui.theme.ThemePreferencesStore;

import java.util.Set;

/** A non-exported transparent task over the real previous screen. No overlay permission is needed. */
public final class SmallWindowFrameEditorActivity extends Activity {
    private static final String DRAFT = "small_window_editor_draft";
    private SmallWindowFrameEditorView editor;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            window.setAttributes(attributes);
        }
        SharedPreferences preferences = preferences();
        String encoded = savedInstanceState != null ? savedInstanceState.getString(DRAFT)
                : preferences.getString(SystemServerConfigContract.KEY_SMALL_WINDOW_LAYOUT, null);
        SmallWindowLayout initial;
        try { initial = SmallWindowLayout.parse(encoded); }
        catch (IllegalArgumentException invalid) { initial = SmallWindowLayout.DEFAULT; }
        ThemePolicy.State theme = ThemePreferencesStore.get(this).getSnapshot();
        boolean dark = "dark".equals(theme.getAppearance()) || (!"light".equals(theme.getAppearance())
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
        TransitSessionPolicy.Palette palette = TransitSessionPolicy.Palette.fromSeed(theme.getSeedColor(), dark);
        Set<String> targets = preferences.getStringSet(SystemServerConfigContract.KEY_SMALL_WINDOW_PACKAGES,
                java.util.Collections.emptySet());
        String target = targets.isEmpty() ? "com.android.settings" : targets.iterator().next();
        editor = new SmallWindowFrameEditorView(this, initial, theme.getSeedColor(), dark, target);
        FrameLayout root = new FrameLayout(this);
        root.addView(editor, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.card);
        background.setCornerRadius(dp(18));
        background.setStroke(Math.max(1, dp(1)), palette.outline);
        controls.setBackground(background);
        controls.setElevation(dp(12));
        TextView explanation = new TextView(this);
        explanation.setText("拖动边框移动，拖动边缘或圆点缩放");
        explanation.setTextSize(12);
        explanation.setTextColor(palette.secondary);
        explanation.setGravity(Gravity.CENTER);
        controls.addView(explanation, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        addButton(buttons, "取消", palette.accent, this::finishAndRemoveTask);
        addButton(buttons, "恢复默认", palette.accent, editor::resetDraft);
        addButton(buttons, "保存", palette.accent, this::save);
        controls.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        Rect available = SystemWindowLayout.availableBounds(this);
        FrameLayout.LayoutParams toolbar = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        toolbar.leftMargin = toolbar.rightMargin = dp(8);
        toolbar.bottomMargin = Math.max(0, getResources().getDisplayMetrics().heightPixels - available.bottom) + dp(12);
        root.addView(controls, toolbar);
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            Rect usable = SystemWindowLayout.availableBounds(this);
            int inset = Math.max(0, location[1] + root.getHeight() - usable.bottom) + dp(12);
            FrameLayout.LayoutParams current = (FrameLayout.LayoutParams) controls.getLayoutParams();
            if (current.bottomMargin != inset) {
                current.bottomMargin = inset;
                controls.setLayoutParams(current);
            }
        });
        setContentView(root);
    }

    private void addButton(LinearLayout row, String label, int color, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(color);
        button.setTextSize(14);
        button.setMinWidth(dp(72));
        button.setMinimumWidth(dp(72));
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(-2, dp(44)));
    }

    private void save() {
        getSharedPreferences(SystemServerConfigContract.RUNTIME_STATUS_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false)
                .putBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, false)
                .putString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, "")
                .putString(SystemServerConfigContract.KEY_RUNTIME_MESSAGE, "布局已变化，等待系统重新确认").apply();
        // Commit before hiding: a persisted preview and the next system snapshot must agree.
        boolean saved = preferences().edit().putString(SystemServerConfigContract.KEY_SMALL_WINDOW_LAYOUT,
                editor.getDraft().encode()).commit();
        if (!saved) {
            SystemServerConfigContract.notifyChanged(this);
            Toast.makeText(this, "布局保存失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        SystemServerConfigContract.notifyChanged(this);
        finishAndRemoveTask();
    }

    public SmallWindowFrameEditorView getFrameEditor() { return editor; }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString(DRAFT, editor.getDraft().encode());
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() { finishAndRemoveTask(); }

    private SharedPreferences preferences() {
        return getSharedPreferences(LinkAutomationConfig.PREFS, Context.MODE_PRIVATE);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
