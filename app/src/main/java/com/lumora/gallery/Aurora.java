package com.lumora.gallery;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Slowly drifting colour blobs: the living backdrop behind the welcome glass. */
public class Aurora extends View {
    private static final int[] COLORS = {0x803B82F6, 0x808B5CF6, 0x7014B8A6};
    private final Paint[] paints = new Paint[3];
    private float phase;
    private ValueAnimator animator;
    private Runnable onFrame;

    public Aurora(Context c) {
        super(c);
        for (int i = 0; i < 3; i++) paints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
    }

    public void setOnFrame(Runnable r) {
        onFrame = r;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float r = Math.max(w, h) * 0.55f;
        for (int i = 0; i < 3; i++) {
            paints[i].setShader(new RadialGradient(0, 0, r, COLORS[i], COLORS[i] & 0x00FFFFFF, Shader.TileMode.CLAMP));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        animator.setDuration(20000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
            if (onFrame != null) onFrame.run();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF05060A);
        int w = getWidth(), h = getHeight();
        float r = Math.max(w, h) * 0.55f;
        for (int i = 0; i < 3; i++) {
            float a = phase + i * 2.1f;
            float x = w * (0.5f + 0.38f * (float) Math.sin(a + i));
            float y = h * (0.5f + 0.34f * (float) Math.cos(a * 0.8f + i * 1.7f));
            canvas.save();
            canvas.translate(x, y);
            canvas.drawCircle(0, 0, r, paints[i]);
            canvas.restore();
        }
    }
}
