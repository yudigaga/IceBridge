package com.huanchengfly.icebridge.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.Nullable;

import com.huanchengfly.icebridge.engines.RootCommandRunner;
import com.huanchengfly.icebridge.fragments.intro.WorkModeFragment;
import com.huanchengfly.icebridge.ui.MiuixSecondaryScreens;
import com.huanchengfly.icebridge.utils.Util;

/** Permission and engine selection are intentionally kept together instead of forcing a first-run mode wizard. */
public class PermissionActivity extends BaseActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        render();
        refreshPermissionStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
    }

    private void render() {
        MiuixSecondaryScreens.installPermissionStatus(this);
    }

    public void refreshPermissionStatus() {
        RootCommandRunner.refreshStatus(result -> {
            if (!isFinishing() && !isDestroyed()) {
                render();
            }
        });
    }

    public void requestRootPermission() {
        if (RootCommandRunner.getStatus() == RootCommandRunner.Status.NOT_AVAILABLE) {
            startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
            return;
        }
        refreshPermissionStatus();
    }

    public void requestIceBoxPermission() {
        Util.requestPermission(this, granted -> refreshPermissionStatus());
    }

    public void selectWorkMode(int mode) {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit()
                .putInt(WorkModeFragment.SP_WORK_MODE, mode)
                .apply();
        render();
    }

    @Override
    protected int getLayoutId() {
        return NO_LAYOUT;
    }
}
