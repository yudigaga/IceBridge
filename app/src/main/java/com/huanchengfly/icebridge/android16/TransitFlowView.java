package com.huanchengfly.icebridge.android16;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** A single native drawing surface for the source-to-destination stream. */
final class TransitFlowView extends View {
    private static final int STREAM_PARTS = 32;
    private final Paint track = stroke(1);
    private final Paint glow = stroke(8);
    private final Paint stream = stroke(2.8f);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path route = new Path();
    private final Path fragment = new Path();
    private final Path sourceClip = new Path();
    private final Path targetClip = new Path();
    private final RectF sourceBounds = new RectF();
    private final RectF targetBounds = new RectF();
    private final PathMeasure measure = new PathMeasure();
    private final float[] point = new float[2];
    private final float[] tangent = new float[2];
    private final ValueAnimator animator;
    private Drawable sourceIcon;
    private Drawable targetIcon;
    private String sourceName = "发起应用";
    private String targetName = "目标应用";
    private float routeLength;
    private float sourceCenter;
    private float targetCenter;
    private float phase = 0.2f;
    private int frameCount;

    TransitFlowView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Butt joins avoid round-cap overlaps turning the moving line into a string of beads.
        stream.setStrokeCap(Paint.Cap.BUTT);
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(2_800);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(value -> {
            phase = (Float) value.getAnimatedValue();
            invalidate();
        });
    }

    void bind(Drawable source, String sourceLabel, Drawable target, String targetLabel,
              TransitSessionPolicy.Palette palette) {
        boolean differentRoute = !sourceName.equals(sourceLabel) || !targetName.equals(targetLabel);
        sourceIcon = source;
        targetIcon = target;
        sourceName = sourceLabel;
        targetName = targetLabel;
        track.setColor(palette.outline);
        track.setAlpha(105);
        glow.setColor(palette.accent);
        stream.setColor(palette.accent);
        head.setColor(palette.accent);
        setContentDescription(sourceName + " → " + targetName);
        if (differentRoute && animator.isStarted()) {
            animator.cancel();
            phase = 0.2f;
        }
        updateAnimation();
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(Math.round(dp(184)), widthMeasureSpec),
                resolveSize(Math.round(dp(80)), heightMeasureSpec));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float radius = Math.min(dp(24), Math.max(dp(14), width / 6f));
        sourceCenter = Math.max(radius + dp(2), width * 0.16f);
        targetCenter = width - sourceCenter;
        float y = height * 0.5f;
        float k = 0.55228475f;
        route.rewind();
        route.moveTo(sourceCenter + radius, y);
        // Orbit the source, leave from its right edge, then follow the directed S-curve.
        route.cubicTo(sourceCenter + radius, y + k * radius,
                sourceCenter + k * radius, y + radius, sourceCenter, y + radius);
        route.cubicTo(sourceCenter - k * radius, y + radius,
                sourceCenter - radius, y + k * radius, sourceCenter - radius, y);
        route.cubicTo(sourceCenter - radius, y - k * radius,
                sourceCenter - k * radius, y - radius, sourceCenter, y - radius);
        route.cubicTo(sourceCenter + k * radius, y - radius,
                sourceCenter + radius, y - k * radius, sourceCenter + radius, y);
        route.cubicTo(sourceCenter + radius, y + dp(17),
                targetCenter - radius, y - dp(17), targetCenter - radius, y);
        // The same contour continues around the destination, without jumping across the card.
        route.cubicTo(targetCenter - radius, y + k * radius,
                targetCenter - k * radius, y + radius, targetCenter, y + radius);
        route.cubicTo(targetCenter + k * radius, y + radius,
                targetCenter + radius, y + k * radius, targetCenter + radius, y);
        route.cubicTo(targetCenter + radius, y - k * radius,
                targetCenter + k * radius, y - radius, targetCenter, y - radius);
        route.cubicTo(targetCenter - k * radius, y - radius,
                targetCenter - radius, y - k * radius, targetCenter - radius, y);
        measure.setPath(route, false);
        routeLength = measure.getLength();
        float iconRadius = Math.max(dp(10), radius - dp(6));
        sourceBounds.set(sourceCenter - iconRadius, y - iconRadius,
                sourceCenter + iconRadius, y + iconRadius);
        targetBounds.set(targetCenter - iconRadius, y - iconRadius,
                targetCenter + iconRadius, y + iconRadius);
        sourceClip.rewind();
        targetClip.rewind();
        sourceClip.addRoundRect(sourceBounds, dp(10), dp(10), Path.Direction.CW);
        targetClip.addRoundRect(targetBounds, dp(10), dp(10), Path.Direction.CW);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        frameCount++;
        canvas.drawPath(route, track);
        if (routeLength > 0) drawStream(canvas);
        drawIcon(canvas, sourceIcon, sourceBounds, sourceClip);
        drawIcon(canvas, targetIcon, targetBounds, targetClip);
    }

    private void drawStream(Canvas canvas) {
        float trail = routeLength * 0.24f;
        float end = phase * (routeLength + trail);
        float start = end - trail;
        fragment.rewind();
        if (measure.getSegment(Math.max(0, start), Math.min(routeLength, end), fragment, true)) {
            glow.setAlpha(25);
            canvas.drawPath(fragment, glow);
        }
        for (int part = 0; part < STREAM_PARTS; part++) {
            float from = start + trail * part / STREAM_PARTS;
            float to = start + trail * (part + 1) / STREAM_PARTS;
            if (to <= 0 || from >= routeLength) continue;
            float strength = (part + 1f) / STREAM_PARTS;
            fragment.rewind();
            measure.getSegment(Math.max(0, from), Math.min(routeLength, to), fragment, true);
            stream.setAlpha((int) (245 * strength * strength));
            stream.setStrokeWidth(dp(0.8f + 2 * strength));
            canvas.drawPath(fragment, stream);
        }
        if (end > 0 && end < routeLength && measure.getPosTan(end, point, tangent)) {
            canvas.drawCircle(point[0], point[1], dp(2.4f), head);
        }
    }

    private void drawIcon(Canvas canvas, Drawable icon, RectF bounds, Path clip) {
        if (icon == null) return;
        int save = canvas.save();
        canvas.clipPath(clip);
        icon.setBounds(Math.round(bounds.left), Math.round(bounds.top),
                Math.round(bounds.right), Math.round(bounds.bottom));
        icon.draw(canvas);
        canvas.restoreToCount(save);
    }

    private Paint stroke(float width) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(width));
        return paint;
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private void updateAnimation() {
        boolean visible = isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE
                && sourceIcon != null && targetIcon != null && ValueAnimator.areAnimatorsEnabled();
        if (visible && !animator.isStarted()) animator.start();
        else if (!visible && animator.isStarted()) animator.cancel();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // View constructors may dispatch visibility before the animator field is initialized.
        if (animator != null) updateAnimation();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (animator != null) updateAnimation();
    }

    String sourceLabel() { return sourceName; }
    String targetLabel() { return targetName; }
    boolean hasSourceIcon() { return sourceIcon != null; }
    boolean hasTargetIcon() { return targetIcon != null; }
    int renderedFrames() { return frameCount; }
    boolean animationRunning() { return animator.isStarted(); }
}
