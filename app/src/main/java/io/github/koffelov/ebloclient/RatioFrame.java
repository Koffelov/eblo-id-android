package io.github.koffelov.ebloclient;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/** A frame whose height is width * ratio (media previews). */
final class RatioFrame extends FrameLayout {
    private float ratio;

    RatioFrame(Context c, float ratio) {
        super(c);
        this.ratio = ratio;
    }

    void setRatio(float ratio) {
        if (this.ratio != ratio) {
            this.ratio = ratio;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * ratio);
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    }
}
