package com.audiofx.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

/**
 * Waveform display with a seconds timeline, pinch-to-zoom, pan, and a
 * tap-driven selection:
 *
 * <ul>
 *   <li>Tap: place the start marker. A second tap after the start places
 *       the end marker (a tap at/before the start moves the start instead).
 *   <li>Double-tap: clear the selection.
 *   <li>Pinch: zoom 1x-8x around the focal point.
 *   <li>One-finger drag while zoomed: pan.
 * </ul>
 *
 * A translucent left-to-right gradient fill can overlay the bars to
 * indicate background progress (e.g. while Apply effects is running).
 */
public class WaveformView extends View {

    /** Fractions are 0..1 across the whole audio; null clears. */
    public interface OnSelectionListener {
        void onSelectionChanged(Float startFraction, Float endFraction);
    }

    private static final int BAR_COLOR = 0xFF4DB6AC;
    private static final int PLACEHOLDER_COLOR = 0xFFB0BEC5;
    private static final int GRADIENT_START = 0x3326A69A; // translucent teal
    private static final int GRADIENT_END = 0xCC26A69A;   // more opaque teal
    private static final int EDGE_COLOR = 0xFF26A69A;
    private static final int TICK_COLOR = 0xFF90A4AE;
    private static final int LABEL_COLOR = 0xFF78909C;
    private static final int SELECTION_FILL = 0x4026A69A;
    private static final int SELECTION_EDGE = 0xFF00897B;
    private static final int PB_TRACK = 0xFFB0BEC5;
    private static final int PB_FILL = 0xFFFF8F00;
    private static final int PB_HEAD = 0xFFE65100;
    private static final float MAX_ZOOM = 8f;

    private float[] peaks = new float[0];
    private float progress; // 0..1; 0 means no fill
    private double durationSec;

    private float zoom = 1f;
    private float viewStart; // audio fraction at the left edge

    private Float selStartFrac;
    private Float selEndFrac;
    private OnSelectionListener selectionListener;

    // Playback progress: a track spanning [pbStartFrac, pbEndFrac] that
    // fills up to pbPositionFrac. Null start hides the indicator.
    private Float pbStartFrac;
    private Float pbEndFrac;
    private float pbPositionFrac;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final int touchSlop;
    private float downX;
    private float lastX;
    private boolean panning;

    private ValueAnimator progressAnimator;
    private final Runnable clearFillRunnable = () -> setProgress(0f);

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint();
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionFillPaint = new Paint();
    private final Paint selectionEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pbTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pbFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pbHeadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pbRect = new RectF();

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
        selectionFillPaint.setColor(SELECTION_FILL);
        selectionEdgePaint.setColor(SELECTION_EDGE);
        selectionEdgePaint.setStrokeWidth(3f);
        pbTrackPaint.setColor(PB_TRACK);
        pbFillPaint.setColor(PB_FILL);
        pbHeadPaint.setColor(PB_HEAD);
        pbHeadPaint.setStrokeWidth(3f);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    /** Replace the displayed waveform. Resets zoom, pan, progress, selection. */
    public void setPeaks(float[] peaks) {
        this.peaks = peaks != null ? peaks : new float[0];
        this.zoom = 1f;
        this.viewStart = 0f;
        cancelProgressAnimation();
        this.progress = 0f;
        clearPlayback();
        clearSelection();
        invalidate();
    }

    /**
     * Show the playback range as a horizontal bar. Fractions are 0..1
     * across the whole audio; pass a null start to hide the indicator.
     */
    public void setPlaybackRange(Float startFraction, Float endFraction) {
        this.pbStartFrac = startFraction;
        this.pbEndFrac = endFraction;
        invalidate();
    }

    /** Move the playback fill/playhead to an absolute audio fraction. */
    public void setPlaybackPosition(float fraction) {
        this.pbPositionFrac = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    /** Hide the playback indicator. */
    public void clearPlayback() {
        this.pbStartFrac = null;
        this.pbEndFrac = null;
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

    public void setOnSelectionListener(OnSelectionListener listener) {
        this.selectionListener = listener;
    }

    /** Current selection as audio fractions, or nulls when unset. */
    public Float getSelectionStart() {
        return selStartFrac;
    }

    public Float getSelectionEnd() {
        return selEndFrac;
    }

    public void clearSelection() {
        if (selStartFrac != null || selEndFrac != null) {
            selStartFrac = null;
            selEndFrac = null;
            invalidate();
            notifySelection();
        }
    }

    private void placeMarkerTap(float frac) {
        if (selStartFrac == null || selEndFrac != null) {
            // Fresh start (or replace a completed window).
            selStartFrac = frac;
            selEndFrac = null;
        } else if (frac > selStartFrac) {
            selEndFrac = frac;
        } else {
            // Tapped at/before the start: move the start earlier.
            selStartFrac = frac;
        }
        invalidate();
        notifySelection();
    }

    private void notifySelection() {
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selStartFrac, selEndFrac);
        }
    }

    /** Audio fraction (0..1) under view x-coordinate. */
    private float xToFraction(float x) {
        float frac = viewStart + (x / getWidth()) / zoom;
        return Math.max(0f, Math.min(1f, frac));
    }

    /** View x-coordinate for an audio fraction. */
    private float fractionToX(float frac) {
        return (frac - viewStart) * zoom * getWidth();
    }

    private void clampViewStart() {
        float maxStart = 1f - 1f / zoom;
        viewStart = Math.max(0f, Math.min(maxStart, viewStart));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                lastX = downX;
                panning = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && zoom > 1f
                        && event.getPointerCount() == 1) {
                    if (!panning && Math.abs(event.getX() - downX) < touchSlop) {
                        break;
                    }
                    panning = true;
                    float dx = event.getX() - lastX;
                    lastX = event.getX();
                    if (getWidth() > 0) {
                        viewStart -= dx / getWidth() / zoom;
                        clampViewStart();
                        invalidate();
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                // Avoid a pan jump when the second finger lands/lifts.
                lastX = event.getX();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                panning = false;
                break;
        }
        return true;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float oldZoom = zoom;
            float newZoom = Math.max(1f, Math.min(MAX_ZOOM, oldZoom * detector.getScaleFactor()));
            if (newZoom != oldZoom && getWidth() > 0) {
                float focusX = detector.getFocusX();
                float focusFrac = viewStart + (focusX / getWidth()) / oldZoom;
                zoom = newZoom;
                if (zoom == 1f) {
                    viewStart = 0f;
                } else {
                    viewStart = focusFrac - (focusX / getWidth()) / zoom;
                    clampViewStart();
                }
                invalidate();
            }
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (getWidth() > 0) {
                placeMarkerTap(xToFraction(e.getX()));
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            clearSelection();
            return true;
        }
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
        float midY = barH / 2f;

        if (peaks.length == 0) {
            // Empty placeholder: faint center line until audio is picked.
            canvas.drawLine(0, midY, w, midY, placeholderPaint);
        } else {
            float maxAmp = midY * 0.95f;
            float startF = viewStart;
            int i0 = (int) (startF * peaks.length);
            int i1 = Math.min(peaks.length,
                    (int) Math.ceil((viewStart + 1f / zoom) * peaks.length) + 1);
            float pxPerBucket = zoom * w / peaks.length;
            float drawnW = Math.max(1f, pxPerBucket * 0.7f);
            for (int i = Math.max(0, i0); i < i1; i++) {
                float left = (i / (float) peaks.length - startF) * zoom * w;
                float amp = peaks[i] * maxAmp;
                canvas.drawRect(left, midY - amp, left + drawnW, midY + amp, barPaint);
            }
        }

        // Selection window shading + marker lines.
        if (selStartFrac != null) {
            float x0 = fractionToX(selStartFrac);
            if (selEndFrac != null) {
                float x1 = fractionToX(selEndFrac);
                canvas.drawRect(x0, 0, x1, barH, selectionFillPaint);
                canvas.drawLine(x1, 0, x1, barH, selectionEdgePaint);
            }
            canvas.drawLine(x0, 0, x0, barH, selectionEdgePaint);
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

        // Playback progress: track across the played range, filled up to
        // the current position, plus a playhead line through the bars.
        if (pbStartFrac != null && pbEndFrac != null) {
            float density = getResources().getDisplayMetrics().density;
            float stripH = 6 * density;
            float stripY = barH - stripH - 4 * density;
            float rx0 = Math.max(0f, fractionToX(pbStartFrac));
            float rx1 = Math.min(w, fractionToX(pbEndFrac));
            if (rx1 > rx0) {
                pbRect.set(rx0, stripY, rx1, stripY + stripH);
                canvas.drawRoundRect(pbRect, stripH / 2f, stripH / 2f, pbTrackPaint);
                float px = Math.max(rx0, Math.min(rx1, fractionToX(pbPositionFrac)));
                if (px > rx0) {
                    pbRect.set(rx0, stripY, px, stripY + stripH);
                    canvas.drawRoundRect(pbRect, stripH / 2f, stripH / 2f, pbFillPaint);
                }
                canvas.drawLine(px, 0, px, barH, pbHeadPaint);
            }
        }

        // Time axis in seconds, over the visible range.
        if (durationSec > 0) {
            double visStartSec = viewStart * durationSec;
            double visEndSec = Math.min(durationSec, (viewStart + 1f / zoom) * durationSec);
            textPaint.setTextSize(11 * density);
            textPaint.setTextAlign(Paint.Align.CENTER);
            double interval = niceInterval(visEndSec - visStartSec);
            double t0 = Math.ceil(visStartSec / interval) * interval;
            for (double t = t0; t <= visEndSec + 1e-6; t += interval) {
                float x = (float) ((t / durationSec - viewStart) * zoom * w);
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
