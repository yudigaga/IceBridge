package com.huanchengfly.icebridge.activities;

import android.os.Bundle;

import com.huanchengfly.icebridge.ui.MiuixSmallWindowSettingsScreen;

/** Edits a local layout preview; it never starts or thaws a destination application. */
public class SmallWindowSettingsActivity extends BaseActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MiuixSmallWindowSettingsScreen.install(this);
    }

    @Override protected int getLayoutId() { return NO_LAYOUT; }
}
