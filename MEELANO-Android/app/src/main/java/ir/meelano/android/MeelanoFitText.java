package ir.meelano.android;

import android.content.Context;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Single-line label that shrinks its font (never below a minimum) until the whole text fits the width it gets, so
 * amounts and figures in report tables, KPI tiles and chart legends are never cut off on small phones and never look
 * tiny on tablets. When even the minimum size is too wide, the end is ellipsized.
 */
final class MeelanoFitText extends TextView {
    private final float maxPx, minPx, stepPx;
    private final TextPaint probe = new TextPaint();

    MeelanoFitText(Context c, float maxSp, float minSp) {
        super(c);
        float sd = c.getResources().getDisplayMetrics().scaledDensity;
        maxPx = maxSp * sd; minPx = Math.min(maxSp, minSp) * sd; stepPx = 0.5f * c.getResources().getDisplayMetrics().density;
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        super.setTextSize(TypedValue.COMPLEX_UNIT_PX, maxPx);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int mode = MeasureSpec.getMode(widthSpec);
        if (mode != MeasureSpec.UNSPECIFIED) {
            int avail = MeasureSpec.getSize(widthSpec);
            int maxW = getMaxWidth();
            if (maxW > 0 && maxW < avail) avail = maxW;
            avail -= getCompoundPaddingLeft() + getCompoundPaddingRight();
            if (avail > 0) {
                String t = String.valueOf(getText());
                probe.set(getPaint());
                float size = maxPx;
                probe.setTextSize(size);
                while (size > minPx && probe.measureText(t) > avail) { size -= stepPx; probe.setTextSize(size); }
                size = Math.max(minPx, size);
                if (Math.abs(getTextSize() - size) > 0.2f) super.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        if (maxPx > 0 && Math.abs(getTextSize() - maxPx) > 0.2f) super.setTextSize(TypedValue.COMPLEX_UNIT_PX, maxPx);
        requestLayout();
    }
}
