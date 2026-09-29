package ir.meelano.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.io.ByteArrayOutputStream;

/** Finger signature pad for the delivery receipt: smooth strokes, «clear», and a compact PNG with a white background. */
final class MeelanoSignatureView extends View {
    private final Path path = new Path();
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private float lastX, lastY, travelled;
    private int strokes;
    private final int background;
    private Runnable onChange;

    MeelanoSignatureView(Context ctx, int inkColor, int guideColor, int background) {
        super(ctx);
        this.background = background;
        float d = ctx.getResources().getDisplayMetrics().density;
        ink.setColor(inkColor); ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(2.6f * d);
        ink.setStrokeCap(Paint.Cap.ROUND); ink.setStrokeJoin(Paint.Join.ROUND);
        guide.setColor(guideColor); guide.setStrokeWidth(1f * d); guide.setStyle(Paint.Style.STROKE);
        setContentDescription("محل امضای مشتری");
    }

    void setOnChange(Runnable r) { onChange = r; }

    /** A real signature: at least one stroke with enough length and size (not a single tap). */
    boolean hasSignature() {
        float d = getResources().getDisplayMetrics().density;
        return strokes > 0 && travelled > 60 * d && (bounds.width() > 24 * d || bounds.height() > 24 * d);
    }

    void clear() {
        path.reset(); strokes = 0; travelled = 0; bounds.setEmpty();
        invalidate();
        if (onChange != null) onChange.run();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(background);
        float y = getHeight() * 0.74f, m = getWidth() * 0.08f;
        canvas.drawLine(m, y, getWidth() - m, y, guide);
        canvas.drawPath(path, ink);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                path.moveTo(x, y); lastX = x; lastY = y; strokes++;
                if (bounds.isEmpty()) bounds.set(x, y, x, y); else bounds.union(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getHistorySize(); i++) addPoint(e.getHistoricalX(i), e.getHistoricalY(i));
                addPoint(x, y);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                addPoint(x, y);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                if (onChange != null) onChange.run();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void addPoint(float x, float y) {
        float mx = (x + lastX) / 2f, my = (y + lastY) / 2f;
        path.quadTo(lastX, lastY, mx, my);
        travelled += (float) Math.hypot(x - lastX, y - lastY);
        lastX = x; lastY = y;
        bounds.union(x, y);
    }

    /** PNG of the signature (dark ink on white, max 900 px wide) for the receipt row. */
    byte[] toPng() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        float scale = w > 900 ? 900f / w : 1f;
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        c.scale(scale, scale);
        Paint p = new Paint(ink); p.setColor(Color.rgb(20, 16, 40));
        c.drawPath(path, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return out.toByteArray();
    }
}
