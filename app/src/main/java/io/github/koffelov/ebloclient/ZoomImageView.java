package io.github.koffelov.ebloclient;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Pinch / double-tap zoom and pan for the full-screen viewer. */
final class ZoomImageView extends ImageView {
    interface SwipeListener {
        void onSwipe(int direction);
    }

    private final Matrix matrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float baseScale = 1f;
    private SwipeListener swipeListener;
    private Runnable tapListener;

    ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float current = currentScale();
                float factor = d.getScaleFactor();
                float target = Math.max(baseScale, Math.min(baseScale * 5f, current * factor));
                factor = target / current;
                matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                clamp();
                return true;
            }
        });
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float current = currentScale();
                float target = current > baseScale * 1.1f ? baseScale : baseScale * 2.5f;
                matrix.postScale(target / current, target / current, e.getX(), e.getY());
                clamp();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapListener != null) tapListener.run();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (!zoomed()) return false;
                matrix.postTranslate(-dx, -dy);
                clamp();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (zoomed() || swipeListener == null || Math.abs(vx) < Math.abs(vy) * 1.5f) return false;
                swipeListener.onSwipe(vx < 0 ? 1 : -1);
                return true;
            }
        });
    }

    void setSwipeListener(SwipeListener l) {
        swipeListener = l;
    }

    void setTapListener(Runnable r) {
        tapListener = r;
    }

    boolean zoomed() {
        return currentScale() > baseScale * 1.02f;
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        fit();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
    }

    private void fit() {
        Drawable d = getDrawable();
        // matrix is null if the ImageView constructor sets a drawable before this class's fields.
        if (matrix == null || d == null || getWidth() == 0 || getHeight() == 0) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        baseScale = Math.min(getWidth() / dw, getHeight() / dh);
        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        matrix.postTranslate((getWidth() - dw * baseScale) / 2f, (getHeight() - dh * baseScale) / 2f);
        setImageMatrix(matrix);
    }

    private float currentScale() {
        float[] v = new float[9];
        matrix.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    private void clamp() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(r);
        float dx = 0, dy = 0;
        if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        if (zoomed() && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }
}
