package com.huanchengfly.icebridge.activities;

import android.os.Bundle;

import com.huanchengfly.icebridge.ui.MiuixRuleConfigScreen;

public class RuleConfigActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MiuixRuleConfigScreen.install(this);
    }

    @Override
    protected int getLayoutId() {
        return NO_LAYOUT;
    }
}
