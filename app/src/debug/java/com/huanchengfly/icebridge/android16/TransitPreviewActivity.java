package com.huanchengfly.icebridge.android16;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/** Non-exported debug visual fixture; the production presenter still requires system UID. */
public final class TransitPreviewActivity extends Activity {
    private SystemTransitPresenter presenter;
    private View background;
    private int backgroundClicks;
    private Runnable backgroundDownCallback;
    private Runnable backgroundClickCallback;
    private String sourcePackage = "com.android.settings";
    private String targetPackage = "com.android.calendar";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        boolean dark = getIntent().getBooleanExtra("preview_dark", false);
        sourcePackage = previewPackage("preview_source", sourcePackage);
        targetPackage = previewPackage("preview_target", targetPackage);
        root.setBackgroundColor(dark ? 0xff11131a : 0xffeef1f8);
        background = new View(this);
        background.setFocusableInTouchMode(true);
        background.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && backgroundDownCallback != null) {
                backgroundDownCallback.run();
            }
            return false;
        });
        background.setOnClickListener(view -> {
            backgroundClicks++;
            if (backgroundClickCallback != null) backgroundClickCallback.run();
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(background, params);
        setContentView(root);
        background.requestFocus();
        // Post from the decor so the application token exists before preview addView.
        getWindow().getDecorView().post(() -> {
            if (isFinishing() || isDestroyed()) return;
            String stage = getIntent().getStringExtra("preview_stage");
            show(1, "opening".equals(stage) ? SystemTransitPresenter.Stage.OPENING
                    : SystemTransitPresenter.Stage.THAWING,
                    getIntent().getIntExtra("preview_seed", 0xff7e57c2), dark ? "dark" : "light");
        });
    }

    public void show(long id, SystemTransitPresenter.Stage stage, int seed, String appearance) {
        if (presenter == null) presenter = SystemTransitPresenter.createPreview(this, message -> { });
        presenter.onStage(id, 0, sourcePackage, targetPackage, stage, seed, appearance);
    }

    public void submitted(long id) { presenter.onSubmitted(id); }
    public void targetDrawn(long id) { presenter.onTargetDrawn(id, 0, targetPackage); }
    public void fail(long id) { presenter.finish(id, "preview-failure"); }
    public View card() { return presenter == null ? null : presenter.previewCard(); }
    public View backgroundView() { return background; }
    public int backgroundClicks() { return backgroundClicks; }
    public void observeBackgroundInput(Runnable onDown, Runnable onClick) {
        backgroundDownCallback = onDown;
        backgroundClickCallback = onClick;
    }

    private String previewPackage(String extra, String fallback) {
        String value = getIntent().getStringExtra(extra);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    @Override protected void onDestroy() {
        if (presenter != null) presenter.cancelAll("preview-destroyed");
        super.onDestroy();
    }
}
