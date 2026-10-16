package com.audiofx.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Draws the picked audio's waveform as vertical bars with a seconds timeline
 * underneath. Can overlay a translucent left-to-right gradient fill to
 * indicate background progress, and reports taps as seek requests.
 */
public class WaveformView extends View {

    /** Called when the user taps the waveform; fraction is 0..1 across the width. */
    public interface OnSeekListener {
        void onSeek(float fraction);
    }

    private static final int BAR_COLOR = 0xFF4DB6AC;
    private static final int PLACEHOLDER_COLOR = 0xFFB0BEC5;
    private static final int GRADIENT_START = 0x3326A69A; // translucent teal
    private static final int GRADIENT_END = 0xCC26A69A;   // more opaque teal
    private static final int EDGE_COLOR = 0xFF26A69A;
    private static final int TICK_COLOR = 0xFF90A4AE;
    private static final int LABEL_COLOR = 0xFF78909C;

    private float[] peaks = new float[0];
    private float progress; // 0..1; 0 means no fill
    private double durationSec;
    private OnSeekListener seekListener;
    private ValueAnimator progressAnimator;
    private final Runnable clearFillRunnable = () -> setProgress(0f);

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint();
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        barPaint.setColor(BAR_COLOR);
        barPaint.setStyle(Paint.Style.FILL);
        placeholderPaint.setColor(PLACEHOLDER_COLOR);
        placeholderPaint.setStrokeWidth(2f);
        edgePaint.setColor(EDGE_COLOR);
        edgePaint.setStrokeWidth(3f);
        tickPaint.setColor(TICK_COLOR);
        tickPaint.setStrokeWidth(2f);
        textPaint.setColor(LABEL_COLOR);
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

    /** Audio duration in seconds, drawn as the x-axis timeline. */
    public void setDuration(double seconds) {
        this.durationSec = Math.max(0, seconds);
        invalidate();
    }

    public void setOnSeekListener(OnSeekListener listener) {
        this.seekListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN
                && seekListener != null && getWidth() > 0) {
            float fraction = Math.max(0f, Math.min(1f, event.getX() / getWidth()));
            seekListener.onSeek(fraction);
            return true;
        }
        return super.onTouchEvent(event);
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
        float density = getResources().getDisplayMetrics().density;
        float labelH = 20 * density;
        float barH = h - labelH;

        if (peaks.length == 0) {
            // Empty placeholder: faint center line until audio is picked.
            float midY = barH / 2f;
            canvas.drawLine(0, midY, w, midY, placeholderPaint);
        } else {
            float barW = (float) w / peaks.length;
            float drawnW = Math.max(1f, barW * 0.7f);
            float midY = barH / 2f;
            float maxAmp = (barH / 2f) * 0.95f;
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
            canvas.drawRect(0, 0, fillW, barH, overlayPaint);
            overlayPaint.setShader(null);
            canvas.drawLine(fillW, 0, fillW, barH, edgePaint);
        }
        // Time axis in seconds.
        if (durationSec > 0) {
            textPaint.setTextSize(11 * density);
            textPaint.setTextAlign(Paint.Align.CENTER);
            double interval = niceInterval(durationSec);
            for (double t = 0; t <= durationSec + 1e-6; t += interval) {
                float x = (float) (w * t / durationSec);
                canvas.drawLine(x, barH, x, barH + 5 * density, tickPaint);
                String label = formatSeconds(t);
                float half = textPaint.measureText(label) / 2f;
                float tx = Math.max(half, Math.min(w - half, x));
                canvas.drawText(label, tx, barH + 17 * density, textPaint);
            }
        }
    }

    /** Tick spacing (~6 labels) snapped to 1/2/5-style steps. */
    private static double niceInterval(double durationSec) {
        double target = durationSec / 6.0;
        double[] steps = {1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800};
        for (double s : steps) {
            if (s >= target) {
                return s;
            }
        }
        return 3600;
    }

    private static String formatSeconds(double t) {
        return ((int) Math.round(t)) + "s";
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelProgressAnimation();
        super.onDetachedFromWindow();
    }
}
