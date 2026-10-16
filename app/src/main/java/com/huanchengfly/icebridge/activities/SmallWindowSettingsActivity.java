package com.huanchengfly.icebridge.activities;

import android.os.Bundle;
import android.content.Intent;

import com.huanchengfly.icebridge.ui.MiuixSmallWindowSettingsScreen;

/** Opens the real-screen layout editor without starting or thawing a destination application. */
public class SmallWindowSettingsActivity extends BaseActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MiuixSmallWindowSettingsScreen.install(this);
    }

    @Override protected int getLayoutId() { return NO_LAYOUT; }

    public void launchLayoutEditor() {
        startActivity(new Intent(this, SmallWindowFrameEditorActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        // Hide the opaque origin task while the separate translucent editor covers the real screen.
        moveTaskToBack(true);
    }
}
