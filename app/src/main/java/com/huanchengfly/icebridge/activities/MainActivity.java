package com.huanchengfly.icebridge.activities;

import android.content.Intent;
import android.os.Bundle;

import com.huanchengfly.icebridge.R;
import com.huanchengfly.icebridge.utils.BridgeUtil;
import com.huanchengfly.icebridge.ui.MiuixMainScreen;

public class MainActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSharedPreferences("app_data", MODE_PRIVATE).getBoolean("first", true)) {
            startActivity(new Intent(this, IntroActivity.class));
            finish();
            return;
        }
        init();
        MiuixMainScreen.install(this);
    }

    private void init() {
        BridgeUtil.init(this);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_main;
    }
}

