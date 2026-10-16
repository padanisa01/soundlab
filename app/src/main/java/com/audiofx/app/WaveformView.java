package com.audiofx.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Draws the picked audio's waveform as vertical bars and can overlay a
 * translucent left-to-right gradient fill to indicate background progress
 * (e.g. while Apply effects is running).
 */
public class WaveformView extends View {

    private static final int BAR_COLOR = 0xFF4DB6AC;
    private static final int PLACEHOLDER_COLOR = 0xFFB0BEC5;
    private static final int GRADIENT_START = 0x3326A69A; // translucent teal
    private static final int GRADIENT_END = 0xCC26A69A;   // more opaque teal
    private static final int EDGE_COLOR = 0xFF26A69A;

    private float[] peaks = new float[0];
    private float progress; // 0..1; 0 means no fill
    private ValueAnimator progressAnimator;
    private final Runnable clearFillRunnable = () -> setProgress(0f);

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint();
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        barPaint.setColor(BAR_COLOR);
        barPaint.setStyle(Paint.Style.FILL);
        placeholderPaint.setColor(PLACEHOLDER_COLOR);
        placeholderPaint.setStrokeWidth(2f);
        edgePaint.setColor(EDGE_COLOR);
        edgePaint.setStrokeWidth(3f);
    }

    /** Replace the displayed waveform. Clears any progress fill. */
    public void setPeaks(float[] peaks) {
        this.peaks = peaks != null ? peaks : new float[0];
        cancelProgressAnimation();
        this.progress = 0f;
        invalidate();
    }

    /** Set the progress fill directly, 0..1. */
    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    /**
     * Animate the fill easing toward ~92% over ~20s while background work
     * runs. Call {@link #finishProgressAnimation()} when the work completes.
     */
    public void startProgressAnimation() {
        cancelProgressAnimation();
        progressAnimator = ValueAnimator.ofFloat(0f, 0.92f);
        progressAnimator.setDuration(20000);
        progressAnimator.setInterpolator(new DecelerateInterpolator());
        progressAnimator.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
        progressAnimator.start();
    }

    /** Snap the fill to full, then fade it away shortly after. */
    public void finishProgressAnimation() {
        cancelProgressAnimation();
        progressAnimator = ValueAnimator.ofFloat(progress, 1f);
        progressAnimator.setDuration(250);
        progressAnimator.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
        progressAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                postDelayed(clearFillRunnable, 600);
            }
        });
        progressAnimator.start();
    }

    /** Stop any progress animation and remove the fill. */
    public void clearProgress() {
        cancelProgressAnimation();
        setProgress(0f);
    }

    private void cancelProgressAnimation() {
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
        removeCallbacks(clearFillRunnable);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (peaks.length == 0) {
            // Empty placeholder: faint center line until audio is picked.
            float midY = h / 2f;
            canvas.drawLine(0, midY, w, midY, placeholderPaint);
        } else {
            float barW = (float) w / peaks.length;
            float drawnW = Math.max(1f, barW * 0.7f);
            float midY = h / 2f;
            float maxAmp = (h / 2f) * 0.95f;
            for (int i = 0; i < peaks.length; i++) {
                float amp = peaks[i] * maxAmp;
                float left = i * barW;
                canvas.drawRect(left, midY - amp, left + drawnW, midY + amp, barPaint);
            }
        }
        if (progress > 0f) {
            float fillW = w * progress;
            overlayPaint.setShader(new LinearGradient(
                    0, 0, fillW, 0, GRADIENT_START, GRADIENT_END,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, fillW, h, overlayPaint);
            overlayPaint.setShader(null);
            canvas.drawLine(fillW, 0, fillW, h, edgePaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelProgressAnimation();
        super.onDetachedFromWindow();
    }
}
