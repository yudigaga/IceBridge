package com.huanchengfly.icebridge.activities;

import android.os.Bundle;

import androidx.annotation.Nullable;

import com.huanchengfly.icebridge.ui.MiuixSecondaryScreens;

public class AboutActivity extends BaseActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MiuixSecondaryScreens.installAbout(this);
    }

    @Override
    protected int getLayoutId() {
        return NO_LAYOUT;
    }
}
