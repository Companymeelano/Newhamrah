package ir.meelano.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, dependency-free animated charts for the store edition (area, horizontal bars, donut).
 * Colours and fonts are passed in from the active theme; numbers are drawn with Persian digits.
 */
final class MeelanoCharts {
    private MeelanoCharts() { }

    interface Formatter { String format(double v); }

    static final class Point {
        final String label; final double value; final int color;
        Point(String label, double value) { this(label, value, 0); }
        Point(String label, double value, int color) { this.label = label == null ? "" : label; this.value = value; this.color = color; }
    }

    static String fa(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : (s == null ? "" : s).toCharArray()) b.append(ch >= '0' && ch <= '9' ? (char) ('۰' + (ch - '0')) : ch);
        return b.toString();
    }

    /** Short amount: 1.2 میلیارد / 350 میلیون / 12 هزار (in the unit given by the caller). */
    static String compact(double v) {
        double a = Math.abs(v);
        String s;
        if (a >= 1e9) s = trim(v / 1e9) + " میلیارد";
        else if (a >= 1e6) s = trim(v / 1e6) + " میلیون";
        else if (a >= 1e3) s = trim(v / 1e3) + " هزار";
        else s = trim(v);
        return fa(s);
    }

    private static String trim(double v) {
        String s = String.format(java.util.Locale.US, Math.abs(v) >= 100 ? "%.0f" : "%.1f", v);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s.replace('.', '٫');
    }

    /** Base: theme colours, fonts and a 0→1 entrance animation. */
    abstract static class Base extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int accent, textColor, muted, grid;
        final float density;
        float progress = 0f;
        Formatter formatter = MeelanoCharts::compact;
        List<Point> points = new ArrayList<>();

        Base(Context c, int accent, int textColor, int muted, Typeface font) {
            super(c);
            this.accent = accent; this.textColor = textColor; this.muted = muted;
            this.grid = Color.argb(28, Color.red(muted), Color.green(muted), Color.blue(muted));
            density = c.getResources().getDisplayMetrics().density;
            if (font != null) text.setTypeface(font);
            text.setColor(muted);
        }

        Base setPoints(List<Point> p, Formatter f) {
            points = p == null ? new ArrayList<>() : p;
            if (f != null) formatter = f;
            StringBuilder cd = new StringBuilder();
            for (Point q : points) cd.append(q.label).append(' ').append(formatter.format(q.value)).append("، ");
            setContentDescription(cd.toString());
            animateIn();
            return this;
        }

        void animateIn() {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(900);
            a.setInterpolator(new DecelerateInterpolator(1.6f));
            a.addUpdateListener(v -> { progress = (float) v.getAnimatedValue(); invalidate(); });
            a.start();
        }

        float dp(float v) { return v * density; }

        int withAlpha(int color, int a) { return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color)); }
    }

    /** Line with a soft gradient fill; tap a point to see its value. Points go right→left (RTL). */
    static final class Area extends Base {
        private int selected = -1;

        Area(Context c, int accent, int textColor, int muted, Typeface font) { super(c, accent, textColor, muted, font); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (points.size() < 2) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                float left = dp(8), right = getWidth() - dp(8);
                float step = (right - left) / (points.size() - 1);
                int i = Math.round((right - e.getX()) / step);
                selected = Math.max(0, Math.min(points.size() - 1, i));
                invalidate();
                return true;
            }
            return super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas canvas) {
            int n = points.size();
            float w = getWidth(), h = getHeight();
            float top = dp(26), bottom = h - dp(24), left = dp(8), right = w - dp(8);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(grid); paint.setShader(null);
            for (int g = 0; g <= 3; g++) { float y = top + (bottom - top) * g / 3f; canvas.drawLine(left, y, right, y, paint); }
            if (n == 0) return;
            double max = 0; for (Point p : points) max = Math.max(max, p.value);
            if (max <= 0) max = 1;
            float step = n > 1 ? (right - left) / (n - 1) : 0;
            float[] xs = new float[n], ys = new float[n];
            for (int i = 0; i < n; i++) {
                xs[i] = right - step * i;
                ys[i] = bottom - (float) (points.get(i).value / max) * (bottom - top) * progress;
            }
            Path line = new Path(), fill = new Path();
            line.moveTo(xs[0], ys[0]); fill.moveTo(xs[0], bottom); fill.lineTo(xs[0], ys[0]);
            for (int i = 1; i < n; i++) {
                float cx = (xs[i - 1] + xs[i]) / 2f;
                line.cubicTo(cx, ys[i - 1], cx, ys[i], xs[i], ys[i]);
                fill.cubicTo(cx, ys[i - 1], cx, ys[i], xs[i], ys[i]);
            }
            fill.lineTo(xs[n - 1], bottom); fill.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, top, 0, bottom, withAlpha(accent, 110), withAlpha(accent, 6), Shader.TileMode.CLAMP));
            canvas.drawPath(fill, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.6f)); paint.setColor(accent); paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawPath(line, paint);
            // x labels: first, middle, last
            text.setTextSize(dp(10)); text.setColor(muted); text.setTextAlign(Paint.Align.CENTER);
            int[] marks = n > 2 ? new int[]{0, n / 2, n - 1} : (n == 2 ? new int[]{0, 1} : new int[]{0});
            for (int i : marks) canvas.drawText(fa(points.get(i).label), Math.max(dp(24), Math.min(w - dp(24), xs[i])), h - dp(6), text);
            int s = selected >= 0 ? selected : indexOfMax();
            paint.setStyle(Paint.Style.FILL); paint.setColor(accent);
            canvas.drawCircle(xs[s], ys[s], dp(5), paint);
            paint.setColor(Color.WHITE); canvas.drawCircle(xs[s], ys[s], dp(2.2f), paint);
            String label = fa(points.get(s).label) + " • " + formatter.format(points.get(s).value);
            text.setTextSize(dp(11)); text.setColor(textColor); text.setFakeBoldText(true);
            float tw = text.measureText(label);
            float bx = Math.max(left + tw / 2 + dp(6), Math.min(right - tw / 2 - dp(6), xs[s]));
            paint.setColor(withAlpha(accent, 34));
            canvas.drawRoundRect(new RectF(bx - tw / 2 - dp(8), dp(2), bx + tw / 2 + dp(8), dp(22)), dp(10), dp(10), paint);
            canvas.drawText(label, bx, dp(16), text);
            text.setFakeBoldText(false);
        }

        private int indexOfMax() { int m = 0; for (int i = 1; i < points.size(); i++) if (points.get(i).value > points.get(m).value) m = i; return m; }
    }

    /** Horizontal bars: label on the right, value on the left, bars grow right→left. */
    static final class Bars extends Base {
        Bars(Context c, int accent, int textColor, int muted, Typeface font) { super(c, accent, textColor, muted, font); }

        static int heightFor(int rows, float density) { return (int) ((Math.max(1, rows) * 38 + 6) * density); }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            double max = 0; for (Point p : points) max = Math.max(max, Math.abs(p.value));
            if (max <= 0) max = 1;
            float row = dp(38);
            for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                float y = i * row + dp(4);
                int color = p.color != 0 ? p.color : accent;
                text.setTextSize(dp(11)); text.setColor(textColor); text.setTextAlign(Paint.Align.RIGHT); text.setFakeBoldText(true);
                String label = p.label.length() > 34 ? p.label.substring(0, 33) + "…" : p.label;
                canvas.drawText(fa(label), w - dp(2), y + dp(12), text);
                text.setFakeBoldText(false); text.setTextAlign(Paint.Align.LEFT); text.setColor(muted); text.setTextSize(dp(10.5f));
                canvas.drawText(formatter.format(p.value), dp(2), y + dp(12), text);
                float barTop = y + dp(18), barBottom = y + dp(28);
                paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(withAlpha(color, 30));
                canvas.drawRoundRect(new RectF(dp(2), barTop, w - dp(2), barBottom), dp(6), dp(6), paint);
                float len = (float) (Math.abs(p.value) / max) * (w - dp(4)) * progress;
                paint.setShader(new LinearGradient(w - len, 0, w, 0, withAlpha(color, 170), color, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(new RectF(w - dp(2) - len, barTop, w - dp(2), barBottom), dp(6), dp(6), paint);
                paint.setShader(null);
            }
        }
    }

    /** Donut with the total in the middle; segment colours come with the points. */
    static final class Donut extends Base {
        private String centerTitle = "";

        Donut(Context c, int accent, int textColor, int muted, Typeface font) { super(c, accent, textColor, muted, font); }

        Donut setCenterTitle(String t) { centerTitle = t == null ? "" : t; invalidate(); return this; }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float size = Math.min(w, h);
            float stroke = size * 0.13f;
            float cx = w / 2f, cy = h / 2f, r = size / 2f - stroke / 2f - dp(2);
            RectF box = new RectF(cx - r, cy - r, cx + r, cy + r);
            double total = 0; for (Point p : points) total += Math.max(0, p.value);
            paint.setShader(null); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setColor(grid);
            canvas.drawArc(box, 0, 360, false, paint);
            float start = -90;
            if (total > 0) for (Point p : points) {
                float sweep = (float) (Math.max(0, p.value) / total * 360f) * progress;
                paint.setColor(p.color != 0 ? p.color : accent);
                canvas.drawArc(box, start, Math.max(0, sweep - 1.2f), false, paint);
                start += sweep;
            }
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(textColor); text.setFakeBoldText(true); text.setTextSize(size * 0.12f);
            canvas.drawText(formatter.format(total * progress), cx, cy + size * 0.03f, text);
            text.setFakeBoldText(false); text.setColor(muted); text.setTextSize(size * 0.075f);
            canvas.drawText(centerTitle, cx, cy + size * 0.14f, text);
        }
    }
}
