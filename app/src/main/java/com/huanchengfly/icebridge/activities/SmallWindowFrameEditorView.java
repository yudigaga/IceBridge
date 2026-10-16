package com.huanchengfly.icebridge.activities;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import com.huanchengfly.icebridge.android16.SmallWindowCardPreview;
import com.huanchengfly.icebridge.android16.SystemWindowLayout;
import com.huanchengfly.icebridge.automation.SmallWindowLayout;

/** Transparent, display-coordinate frame. All edits remain local until the Activity saves. */
public final class SmallWindowFrameEditorView extends ViewGroup {
    private static final int LEFT = 1, TOP = 2, RIGHT = 4, BOTTOM = 8, MOVE = 16;
    private static final int WIDER = 0x01020001, NARROWER = 0x01020002;
    private static final int TALLER = 0x01020003, SHORTER = 0x01020004;
    private static final int MOVE_LEFT = 0x01020005, MOVE_RIGHT = 0x01020006;
    private static final int MOVE_UP = 0x01020007, MOVE_DOWN = 0x01020008;
    private final Rect available = new Rect();
    private final RectF frame = new RectF();
    private final RectF dragStart = new RectF();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SmallWindowCardPreview card;
    private SmallWindowLayout draft;
    private int screenX, screenY, dragMode, activePointer = -1;
    private float downX, downY;

    public SmallWindowFrameEditorView(Context context, SmallWindowLayout initial, int seed,
                                      boolean dark, String targetPackage) {
        super(context);
        draft = initial;
        setWillNotDraw(false);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("真实屏幕小窗边框，拖动内部移动，拖动边缘或圆点调整尺寸");
        line.setColor(seed | 0xff000000);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2));
        handle.setColor(seed | 0xff000000);
        card = new SmallWindowCardPreview(context, seed, dark, targetPackage);
        card.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        addView(card);
        updateDescription();
    }

    public SmallWindowLayout getDraft() { return draft; }

    public void resetDraft() {
        draft = SmallWindowLayout.DEFAULT;
        resolveDraft();
    }

    /** Physical display coordinates, identical to the launch-bounds contract. */
    public Rect getFrameBoundsOnScreen() {
        return new Rect(Math.round(frame.left) + screenX, Math.round(frame.top) + screenY,
                Math.round(frame.right) + screenX, Math.round(frame.bottom) + screenY);
    }

    public Rect getAvailableBoundsOnScreen() {
        Rect result = new Rect(available);
        result.offset(screenX, screenY);
        return result;
    }

    public Rect getCardBoundsOnScreen() {
        return new Rect(card.getLeft() + screenX, card.getTop() + screenY,
                card.getRight() + screenX, card.getBottom() + screenY);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int[] origin = new int[2];
        getLocationOnScreen(origin);
        Rect next = SystemWindowLayout.availableBounds(getContext());
        next.offset(-origin[0], -origin[1]);
        // Never let a display rectangle extend past the actual transparent Activity window.
        if (!next.intersect(0, 0, getWidth(), getHeight())) next.set(0, 0, getWidth(), getHeight());
        boolean geometryChanged = screenX != origin[0] || screenY != origin[1] || !available.equals(next);
        screenX = origin[0];
        screenY = origin[1];
        available.set(next);
        if (geometryChanged || frame.isEmpty()) resolveFrame();
        layoutCard();
    }

    private void resolveFrame() {
        if (available.isEmpty()) return;
        SmallWindowLayout.Bounds resolved = draft.resolve(available.left, available.top,
                available.width(), available.height());
        frame.set(resolved.left, resolved.top, resolved.right, resolved.bottom);
    }

    private void resolveDraft() {
        resolveFrame();
        updateDescription();
        if (!frame.isEmpty()) layoutCard();
        requestLayout();
        invalidate();
    }

    private void layoutCard() {
        float scale = Math.min(1f, Math.min(frame.width() / dp(156), frame.height() / dp(72)));
        int width = Math.max(1, Math.round(dp(156) * scale));
        int height = Math.max(1, Math.round(dp(72) * scale));
        card.setFrameScale(scale);
        card.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        int x = Math.round(frame.centerX() - width / 2f);
        int y = Math.round(frame.centerY() - height / 2f);
        card.layout(x, y, x + width, y + height);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRoundRect(frame, dp(12), dp(12), line);
        drawHandle(canvas, frame.left, frame.top);
        drawHandle(canvas, frame.right, frame.top);
        drawHandle(canvas, frame.left, frame.bottom);
        drawHandle(canvas, frame.right, frame.bottom);
        drawHandle(canvas, frame.centerX(), frame.top);
        drawHandle(canvas, frame.centerX(), frame.bottom);
        drawHandle(canvas, frame.left, frame.centerY());
        drawHandle(canvas, frame.right, frame.centerY());
    }

    private void drawHandle(Canvas canvas, float x, float y) {
        canvas.drawCircle(x, y, dp(4), handle);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float x = event.getX(), y = event.getY(), hit = dp(24);
            if (available.isEmpty() || x < frame.left - hit || x > frame.right + hit
                    || y < frame.top - hit || y > frame.bottom + hit) return false;
            requestFocus();
            dragMode = 0;
            if (Math.abs(x - frame.left) <= hit) dragMode |= LEFT;
            else if (Math.abs(x - frame.right) <= hit) dragMode |= RIGHT;
            if (Math.abs(y - frame.top) <= hit) dragMode |= TOP;
            else if (Math.abs(y - frame.bottom) <= hit) dragMode |= BOTTOM;
            if (dragMode == 0) dragMode = MOVE;
            activePointer = event.getPointerId(0);
            dragStart.set(frame);
            downX = x;
            downY = y;
            return true;
        }
        if (dragMode == 0) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                && event.getPointerId(event.getActionIndex()) == activePointer) {
            finishDrag();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            int index = event.findPointerIndex(activePointer);
            if (index < 0) { finishDrag(); return true; }
            drag(event.getX(index) - downX, event.getY(index) - downY);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            finishDrag();
            return true;
        }
        return true;
    }

    private void drag(float dx, float dy) {
        frame.set(dragStart);
        if (dragMode == MOVE) {
            frame.offsetTo(clamp(dragStart.left + dx, available.left, available.right - frame.width()),
                    clamp(dragStart.top + dy, available.top, available.bottom - frame.height()));
        } else {
            float minWidth = available.width() * .30f, maxWidth = available.width() * .95f;
            float minHeight = available.height() * .25f, maxHeight = available.height() * .95f;
            if ((dragMode & LEFT) != 0) frame.left = clamp(dragStart.left + dx,
                    Math.max(available.left, dragStart.right - maxWidth), dragStart.right - minWidth);
            if ((dragMode & RIGHT) != 0) frame.right = clamp(dragStart.right + dx,
                    dragStart.left + minWidth, Math.min(available.right, dragStart.left + maxWidth));
            if ((dragMode & TOP) != 0) frame.top = clamp(dragStart.top + dy,
                    Math.max(available.top, dragStart.bottom - maxHeight), dragStart.bottom - minHeight);
            if ((dragMode & BOTTOM) != 0) frame.bottom = clamp(dragStart.bottom + dy,
                    dragStart.top + minHeight, Math.min(available.bottom, dragStart.top + maxHeight));
        }
        draft = normalizedFrame();
        updateDescription();
        layoutCard();
        requestLayout();
        invalidate();
    }

    private SmallWindowLayout normalizedFrame() {
        int width = Math.max(30, Math.min(95, Math.round(frame.width() * 100 / available.width())));
        int height = Math.max(25, Math.min(95, Math.round(frame.height() * 100 / available.height())));
        float remainingX = available.width() - frame.width();
        float remainingY = available.height() - frame.height();
        int horizontal = Math.round((frame.left - available.left) * 100 / Math.max(1, remainingX));
        int vertical = Math.round((frame.top - available.top) * 100 / Math.max(1, remainingY));
        return new SmallWindowLayout(width, height,
                Math.max(0, Math.min(100, horizontal)), Math.max(0, Math.min(100, vertical)));
    }

    private void finishDrag() {
        dragMode = 0;
        activePointer = -1;
        // Snap to the same integer percentage contract that the system launcher will read.
        resolveDraft();
        performClick();
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private void updateDescription() {
        if (Build.VERSION.SDK_INT >= 30) setStateDescription("宽度" + draft.widthPercent
                + "%，高度" + draft.heightPercent + "%，水平" + draft.horizontalPercent
                + "%，垂直" + draft.verticalPercent + "%");
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        int[] actions = {WIDER, NARROWER, TALLER, SHORTER, MOVE_LEFT, MOVE_RIGHT, MOVE_UP, MOVE_DOWN};
        String[] labels = {"增加宽度", "减小宽度", "增加高度", "减小高度", "向左移动", "向右移动", "向上移动", "向下移动"};
        for (int index = 0; index < actions.length; index++) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(actions[index], labels[index]));
        }
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        int width = draft.widthPercent, height = draft.heightPercent;
        int horizontal = draft.horizontalPercent, vertical = draft.verticalPercent;
        if (action == WIDER) width += 1;
        else if (action == NARROWER) width -= 1;
        else if (action == TALLER) height += 1;
        else if (action == SHORTER) height -= 1;
        else if (action == MOVE_LEFT) horizontal -= 1;
        else if (action == MOVE_RIGHT) horizontal += 1;
        else if (action == MOVE_UP) vertical -= 1;
        else if (action == MOVE_DOWN) vertical += 1;
        else return super.performAccessibilityAction(action, arguments);
        draft = new SmallWindowLayout(Math.max(30, Math.min(95, width)), Math.max(25, Math.min(95, height)),
                Math.max(0, Math.min(100, horizontal)), Math.max(0, Math.min(100, vertical)));
        resolveDraft();
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int action = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? MOVE_LEFT
                : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? MOVE_RIGHT
                : keyCode == KeyEvent.KEYCODE_DPAD_UP ? MOVE_UP
                : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? MOVE_DOWN : 0;
        return action != 0 ? performAccessibilityAction(action, null) : super.onKeyDown(keyCode, event);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
