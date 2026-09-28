package ir.meelano.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Ring with the loading percentage in the middle, the app icon above the number and a slowly
 * turning highlight. Colours come from the current theme (set by MainActivity).
 */
final class MeelanoLoadingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    private final ValueAnimator spin;
    private float shown = 0f;       // percentage drawn now (animated)
    private float target = 0f;      // percentage reached by the real work
    private float angle = 0f;
    private int accent, accent2, trackColor;
    private Bitmap icon;
    private String captionText = "";

    MeelanoLoadingView(Context c, int accent, int accent2, int trackColor, int textColor, int mutedColor, Typeface bold, Typeface regular) {
        super(c);
        this.accent = accent; this.accent2 = accent2; this.trackColor = trackColor;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(trackColor);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        number.setColor(textColor);
        number.setTextAlign(Paint.Align.CENTER);
        if (bold != null) number.setTypeface(bold);
        caption.setColor(mutedColor);
        caption.setTextAlign(Paint.Align.CENTER);
        if (regular != null) caption.setTypeface(regular);
        spin = ValueAnimator.ofFloat(0f, 360f);
        spin.setDuration(2400);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> {
            angle = (float) a.getAnimatedValue();
            // Ease the drawn number toward the real one (never backwards).
            if (shown < target) shown = Math.min(target, shown + Math.max(0.25f, (target - shown) * 0.08f));
            invalidate();
        });
        setContentDescription("در حال دریافت اطلاعات");
    }

    void setIcon(Bitmap b) { icon = b; invalidate(); }

    void setCaption(String s) { captionText = s == null ? "" : s; invalidate(); }

    /** 0..100. Only moves forward. */
    void setProgress(float percent) {
        target = Math.max(target, Math.max(0f, Math.min(100f, percent)));
        setContentDescription("در حال دریافت اطلاعات، " + Math.round(target) + " درصد");
    }

    float progress() { return target; }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); spin.start(); }

    @Override protected void onDetachedFromWindow() { spin.cancel(); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float size = Math.min(w, h);
        float stroke = size * 0.055f;
        float cx = w / 2f, cy = h / 2f;
        float r = size / 2f - stroke * 1.4f;
        box.set(cx - r, cy - r, cx + r, cy + r);
        track.setStrokeWidth(stroke);
        canvas.drawArc(box, 0, 360, false, track);

        arc.setStrokeWidth(stroke);
        arc.setShader(new SweepGradient(cx, cy, new int[]{accent2, accent, accent2}, new float[]{0f, 0.5f, 1f}));
        canvas.save();
        canvas.rotate(-90, cx, cy);
        canvas.drawArc(box, 0, 360f * shown / 100f, false, arc);
        canvas.restore();

        // A short bright comet turning around the ring while work is running.
        if (shown < 100f) {
            glow.setStrokeWidth(stroke * 0.55f);
            glow.setColor(accent);
            glow.setAlpha(150);
            canvas.drawArc(box, angle, 28, false, glow);
        }

        float iconSize = r * 0.62f;
        if (icon != null && !icon.isRecycled()) {
            RectF ib = new RectF(cx - iconSize / 2f, cy - r * 0.68f, cx + iconSize / 2f, cy - r * 0.68f + iconSize);
            canvas.drawBitmap(icon, null, ib, iconPaint);
        }
        number.setTextSize(r * 0.44f);
        String pct = faDigits(String.valueOf(Math.round(shown))) + "٪";
        canvas.drawText(pct, cx, cy + r * 0.36f, number);
        if (!captionText.isEmpty()) {
            caption.setTextSize(r * 0.15f);
            canvas.drawText(captionText, cx, cy + r * 0.62f, caption);
        }
    }

    static String faDigits(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(ch >= '0' && ch <= '9' ? (char) ('۰' + (ch - '0')) : ch);
        return b.toString();
    }
}
