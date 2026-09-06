package com.huanchengfly.icebridge.activities;

import android.os.Bundle;

import com.huanchengfly.icebridge.ui.MiuixSecondaryScreens;

public class EnhanceModeActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MiuixSecondaryScreens.installEnhanceSettings(this);
    }

    @Override
    protected int getLayoutId() {
        return NO_LAYOUT;
    }
}
