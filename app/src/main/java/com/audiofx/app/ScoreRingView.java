package com.audiofx.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Circular health-score ring for the diagnosis report (Option A UI). */
public class ScoreRingView extends View {
    private int score = 0;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ScoreRingView(Context context) {
        super(context);
        init();
    }

    public ScoreRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(dp(9));
        bgPaint.setColor(0xFF3A3A42);
        fgPaint.setStyle(Paint.Style.STROKE);
        fgPaint.setStrokeWidth(dp(9));
        fgPaint.setStrokeCap(Paint.Cap.ROUND);
        numPaint.setColor(0xFFEEF1F4);
        numPaint.setTextAlign(Paint.Align.CENTER);
        numPaint.setTextSize(dp(24));
        numPaint.setFakeBoldText(true);
        subPaint.setColor(0xFF9AA4AE);
        subPaint.setTextAlign(Paint.Align.CENTER);
        subPaint.setTextSize(dp(10));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setScore(int score) {
        this.score = Math.max(0, Math.min(100, score));
        int color = this.score >= 70 ? 0xFF4DB6AC
                : this.score >= 50 ? 0xFFFF8F00 : 0xFFE53935;
        fgPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - dp(6);
        canvas.drawCircle(cx, cy, r, bgPaint);
        float sweep = 360f * score / 100f;
        if (sweep > 0) {
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90, sweep, false,
                    fgPaint);
        }
        canvas.drawText(String.valueOf(score), cx, cy + dp(3), numPaint);
        canvas.drawText("/ 100", cx, cy + dp(17), subPaint);
    }
}
