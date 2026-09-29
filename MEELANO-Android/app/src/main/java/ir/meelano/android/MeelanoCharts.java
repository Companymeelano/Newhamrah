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

    /** Full amount in rials, grouped three by three: «۱۲٬۴۵۰٬۰۰۰ ریال». */
    static String rial(double v) {
        return fa(new java.text.DecimalFormat("#,##0", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US)).format(Math.round(v)).replace(',', '٬')) + " ریال";
    }

    /** Amounts are always shown in full rials; only the y-axis scale of a chart stays short. */
    static final Formatter RIAL = MeelanoCharts::rial;

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

        /** Font scale by the chart's own width: readable on small phones, not tiny on tablets. */
        float scale() { float w = getWidth(); return w <= 0 ? 1f : Math.max(0.9f, Math.min(1.2f, w / dp(340))); }

        int withAlpha(int color, int a) { return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color)); }

        /** The text cut with «…» so it never runs into the value next to it. */
        String fitEllipsis(String s, float avail) {
            if (s == null) return "";
            if (avail <= 0) return "";
            if (text.measureText(s) <= avail) return s;
            float dots = text.measureText("…");
            int k = text.breakText(s, true, Math.max(0, avail - dots), null);
            return k <= 0 ? "…" : s.substring(0, k).trim() + "…";
        }
    }

    /** Rounds the top of the value axis up to a value whose thirds are round too. */
    static double niceMax(double v) {
        if (v <= 0) return 1;
        double e = Math.pow(10, Math.floor(Math.log10(v)));
        double f = v / e;
        double[] steps = {1.2, 1.5, 3, 4.5, 6, 9, 12};
        for (double st : steps) if (f <= st + 1e-9) return st * e;
        return 12 * e;
    }

    /** Monotone cubic tangents (Fritsch–Butland): the curve never overshoots its points, so it never dips below zero. */
    static float[] monotoneTangents(float[] y) {
        int n = y.length;
        float[] m = new float[n];
        if (n < 2) return m;
        float[] d = new float[n - 1];
        for (int i = 0; i < n - 1; i++) d[i] = y[i + 1] - y[i];
        m[0] = d[0]; m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0f : 2f / (1f / d[i - 1] + 1f / d[i]);
        return m;
    }

    /** Line with a soft gradient fill and a value axis; tap a point to see its value. Points go right→left (RTL). */
    static final class Area extends Base {
        private int selected = -1;
        private float plotLeft = 0, plotRight = 0;

        Area(Context c, int accent, int textColor, int muted, Typeface font) { super(c, accent, textColor, muted, font); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (points.size() < 2 || plotRight <= plotLeft) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                float step = (plotRight - plotLeft) / (points.size() - 1);
                int i = Math.round((plotRight - e.getX()) / step);
                selected = Math.max(0, Math.min(points.size() - 1, i));
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            return super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas canvas) {
            int n = points.size();
            float w = getWidth(), h = getHeight(), sc = scale();
            double max = 0; for (Point p : points) max = Math.max(max, p.value);
            max = niceMax(max);
            float axisSize = dp(9.5f) * sc;
            text.setFakeBoldText(false); text.setTextSize(axisSize); text.setColor(muted);
            String[] yl = new String[4]; float yw = 0;
            for (int g = 0; g <= 3; g++) { yl[g] = g == 3 ? fa("0") : (formatter == RIAL ? compact(max * (3 - g) / 3.0) : formatter.format(max * (3 - g) / 3.0)); yw = Math.max(yw, text.measureText(yl[g])); }
            float left = Math.min(w * 0.32f, yw + dp(10)), right = w - dp(10);
            float tipH = dp(11) * sc + dp(12);
            float top = tipH + dp(8), bottom = h - (axisSize + dp(12));
            plotLeft = left; plotRight = right;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(grid); paint.setShader(null);
            text.setTextAlign(Paint.Align.RIGHT);
            for (int g = 0; g <= 3; g++) {
                float y = top + (bottom - top) * g / 3f;
                canvas.drawLine(left, y, right, y, paint);
                canvas.drawText(yl[g], left - dp(5), y + axisSize * 0.35f, text);
            }
            if (n == 0) return;
            float step = n > 1 ? (right - left) / (n - 1) : 0;
            float[] xs = new float[n], ys = new float[n];
            for (int i = 0; i < n; i++) {
                xs[i] = n > 1 ? right - step * i : (left + right) / 2f;
                ys[i] = bottom - (float) (Math.max(0, points.get(i).value) / max) * (bottom - top) * progress;
            }
            float[] m = monotoneTangents(ys);
            Path line = new Path(), fill = new Path();
            line.moveTo(xs[0], ys[0]); fill.moveTo(xs[0], bottom); fill.lineTo(xs[0], ys[0]);
            for (int i = 0; i < n - 1; i++) {
                float dx = (xs[i + 1] - xs[i]) / 3f;
                float c1x = xs[i] + dx, c1y = Math.min(bottom, ys[i] + m[i] / 3f);
                float c2x = xs[i + 1] - dx, c2y = Math.min(bottom, ys[i + 1] - m[i + 1] / 3f);
                line.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1]);
                fill.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1]);
            }
            fill.lineTo(xs[n - 1], bottom); fill.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, top, 0, bottom, withAlpha(accent, 110), withAlpha(accent, 6), Shader.TileMode.CLAMP));
            canvas.drawPath(fill, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.4f) * sc); paint.setColor(accent); paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawPath(line, paint);
            // x labels: as many as fit without touching each other
            text.setTextSize(axisSize); text.setColor(muted); text.setTextAlign(Paint.Align.CENTER);
            float lw = 0; for (Point p : points) lw = Math.max(lw, text.measureText(fa(p.label)));
            int fit = Math.max(2, (int) ((right - left) / (lw + dp(12))) + 1);
            int every = Math.max(1, (int) Math.ceil((n - 1) / (double) Math.max(1, fit - 1)));
            float lastX = Float.NaN;
            for (int i = 0; i < n; i += every) { drawX(canvas, i, xs[i], lw, w, h); lastX = xs[i]; }
            if ((n - 1) % every != 0 && (Float.isNaN(lastX) || Math.abs(lastX - xs[n - 1]) >= lw + dp(8))) drawX(canvas, n - 1, xs[n - 1], lw, w, h);
            // selected point + value tip
            int s = selected >= 0 ? selected : indexOfMax();
            paint.setStyle(Paint.Style.FILL); paint.setColor(accent);
            canvas.drawCircle(xs[s], ys[s], dp(5) * sc, paint);
            paint.setColor(Color.WHITE); canvas.drawCircle(xs[s], ys[s], dp(2.2f) * sc, paint);
            text.setTextSize(dp(11) * sc); text.setColor(textColor); text.setFakeBoldText(true);
            String label = fitEllipsis(fa(points.get(s).label) + " • " + formatter.format(points.get(s).value), w - dp(24));
            float tw = text.measureText(label);
            float bx = Math.max(tw / 2 + dp(10), Math.min(w - tw / 2 - dp(10), xs[s]));
            paint.setColor(withAlpha(accent, 34));
            canvas.drawRoundRect(new RectF(bx - tw / 2 - dp(8), dp(2), bx + tw / 2 + dp(8), dp(2) + tipH), dp(10), dp(10), paint);
            canvas.drawText(label, bx, dp(2) + tipH / 2f + dp(11) * sc * 0.36f, text);
            text.setFakeBoldText(false);
        }

        private void drawX(Canvas canvas, int i, float x, float lw, float w, float h) {
            String l = fa(points.get(i).label);
            float half = text.measureText(l) / 2f;
            canvas.drawText(l, Math.max(half + dp(2), Math.min(w - half - dp(2), x)), h - dp(5), text);
        }

        private int indexOfMax() { int m = 0; for (int i = 1; i < points.size(); i++) if (points.get(i).value > points.get(m).value) m = i; return m; }
    }

    /** Horizontal bars: label on the right, value on the left, bars grow right→left. Long labels are cut with «…». */
    static final class Bars extends Base {
        Bars(Context c, int accent, int textColor, int muted, Typeface font) { super(c, accent, textColor, muted, font); }

        static int heightFor(int rows, float density) { return (int) ((Math.max(1, rows) * 42 + 6) * density); }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), sc = Math.min(1.12f, scale());
            double max = 0; for (Point p : points) max = Math.max(max, Math.abs(p.value));
            if (max <= 0) max = 1;
            float row = dp(42);
            for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                float y = i * row + dp(4);
                int color = p.color != 0 ? p.color : accent;
                String value = formatter.format(p.value);
                text.setFakeBoldText(false); text.setTextSize(dp(10.5f) * sc);
                float vw = text.measureText(value);
                text.setTextAlign(Paint.Align.LEFT); text.setColor(muted);
                canvas.drawText(value, dp(2), y + dp(14), text);
                text.setTextSize(dp(11) * sc); text.setColor(textColor); text.setTextAlign(Paint.Align.RIGHT); text.setFakeBoldText(true);
                canvas.drawText(fitEllipsis(fa(p.label), w - dp(4) - vw - dp(14)), w - dp(2), y + dp(14), text);
                text.setFakeBoldText(false);
                float barTop = y + dp(21), barBottom = y + dp(31);
                paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(withAlpha(color, 30));
                canvas.drawRoundRect(new RectF(dp(2), barTop, w - dp(2), barBottom), dp(6), dp(6), paint);
                float len = (float) (Math.abs(p.value) / max) * (w - dp(4)) * progress;
                if (len > 0.5f) {
                    paint.setShader(new LinearGradient(w - len, 0, w, 0, withAlpha(color, 170), color, Shader.TileMode.CLAMP));
                    canvas.drawRoundRect(new RectF(w - dp(2) - len, barTop, w - dp(2), barBottom), dp(6), dp(6), paint);
                }
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
            float inner = (2 * r - stroke) * 0.82f;
            String value = formatter.format(total * progress);
            text.setColor(textColor); text.setFakeBoldText(true);
            float ts = size * 0.12f; text.setTextSize(ts);
            while (ts > dp(9) && text.measureText(value) > inner) { ts -= dp(0.5f); text.setTextSize(ts); }
            canvas.drawText(value, cx, cy + ts * 0.25f, text);
            text.setFakeBoldText(false); text.setColor(muted);
            float ss = Math.max(dp(9), size * 0.075f); text.setTextSize(ss);
            canvas.drawText(fitEllipsis(centerTitle, inner), cx, cy + ts * 0.25f + ss * 1.45f, text);
        }
    }
}
