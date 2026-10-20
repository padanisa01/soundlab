package com.audiofx.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.View;

/**
 * Dim layer for the first-use tutorial: paints a translucent veil
 * over the whole screen with a rounded-rectangle hole punched over
 * the control being introduced, ringed in teal. The hole is drawn
 * with a CLEAR xfermode on a software layer, so the real UI shows
 * through untouched.
 */
public class TutorialOverlayView extends View {

    private final Paint dimPaint = new Paint();
    private final Paint clearPaint = new Paint();
    private final Paint ringPaint = new Paint();
    private final RectF hole = new RectF();
    private final float corner;
    private boolean hasHole;

    public TutorialOverlayView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        float density = context.getResources().getDisplayMetrics().density;
        corner = 14 * density;
        dimPaint.setColor(0xB0000000);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        clearPaint.setAntiAlias(true);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2.5f * density);
        ringPaint.setColor(0xFF4DB6AC);
        ringPaint.setAntiAlias(true);
    }

    /** The hole to punch, in this view's coordinates; null = none. */
    public void setHole(RectF rect) {
        hasHole = rect != null;
        if (rect != null) {
            hole.set(rect);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);
        if (hasHole) {
            canvas.drawRoundRect(hole, corner, corner, clearPaint);
            canvas.drawRoundRect(hole, corner, corner, ringPaint);
        }
    }
}
