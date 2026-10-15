package com.huanchengfly.icebridge.activities;

import android.os.Bundle;

import com.huanchengfly.icebridge.ui.MiuixThemeSettingsScreen;

/** Appearance settings only; opening this screen never requests service or Root permissions. */
public class ThemeSettingsActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MiuixThemeSettingsScreen.install(this);
    }

    @Override
    protected int getLayoutId() {
        return NO_LAYOUT;
    }
}
