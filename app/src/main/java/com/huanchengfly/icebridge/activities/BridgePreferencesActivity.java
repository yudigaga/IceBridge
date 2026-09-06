package com.huanchengfly.icebridge.activities;

import android.os.Bundle;

import com.huanchengfly.icebridge.ui.MiuixSecondaryScreens;

public class BridgePreferencesActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MiuixSecondaryScreens.installBridgeSettings(this);
    }

    @Override
    protected int getLayoutId() {
        return NO_LAYOUT;
    }
}
