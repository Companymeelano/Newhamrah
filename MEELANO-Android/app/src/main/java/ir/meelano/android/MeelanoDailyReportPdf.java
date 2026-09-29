package ir.meelano.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One-page-per-section A4 PDF for the visitor's day: summary boxes, today's pre-invoices and
 * today's visits. Pure drawing code — the activity passes in ready data, so this class has no
 * database or UI dependencies.
 */
final class MeelanoDailyReportPdf {
    private MeelanoDailyReportPdf() { }

    /** Data for the report. Amount strings are already formatted by the app. */
    static final class Data {
        String title = "گزارش روزانه ویزیتور";
        String visitor = "";
        String date = "";
        String developer = "";
        String appVersion = "";
        /** label → value pairs for the summary boxes (max 4). */
        final List<String[]> summary = new ArrayList<>();
        /** rows: time, customer, amount, status */
        final List<String[]> prefactors = new ArrayList<>();
        /** rows: time, customer, result */
        final List<String[]> visits = new ArrayList<>();
        String note = "";
    }

    private static final int W = 595, H = 842, M = 34;
    private static final int BROWN = Color.rgb(92, 52, 22), GOLD = Color.rgb(201, 145, 58), INK = Color.rgb(45, 34, 24),
            MUTED = Color.rgb(125, 104, 84), CARD = Color.rgb(255, 251, 244), LINE = Color.argb(70, 150, 92, 38);

    static File write(Context ctx, Data d, Typeface regular, Typeface bold, File outFile) throws Exception {
        PdfDocument doc = new PdfDocument();
        try {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            int pageNo = 1;
            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
            Canvas c = page.getCanvas();
            int y = header(ctx, c, p, d, regular, bold);

            // Summary boxes
            int n = Math.min(4, d.summary.size());
            if (n > 0) {
                float gap = 10, bw = (W - 2 * M - gap * (n - 1)) / n;
                for (int i = 0; i < n; i++) {
                    float right = W - M - i * (bw + gap), left = right - bw;
                    card(c, p, new RectF(left, y, right, y + 64));
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setTypeface(regular); p.setTextSize(10); p.setColor(MUTED);
                    c.drawText(d.summary.get(i)[0], (left + right) / 2, y + 22, p);
                    p.setTypeface(bold); p.setTextSize(fit(p, d.summary.get(i)[1], bw - 12, 15)); p.setColor(BROWN);
                    c.drawText(d.summary.get(i)[1], (left + right) / 2, y + 48, p);
                }
                y += 84;
            }

            String[] preHead = {"ساعت", "مشتری", "مبلغ", "وضعیت"};
            float[] preCols = {0.12f, 0.43f, 0.27f, 0.18f};
            String[] visHead = {"ساعت", "مشتری", "نتیجه"};
            float[] visCols = {0.12f, 0.50f, 0.38f};

            Object[][] sections = {
                    {"پیش‌فاکتورهای امروز", d.prefactors, preHead, preCols, "امروز پیش‌فاکتوری ثبت نشده است."},
                    {"ویزیت‌های امروز", d.visits, visHead, visCols, "امروز ویزیتی ثبت نشده است."}};
            for (Object[] s : sections) {
                @SuppressWarnings("unchecked") List<String[]> rows = (List<String[]>) s[1];
                String[] head = (String[]) s[2]; float[] cols = (float[]) s[3];
                if (y > H - 140) { footer(c, p, d, regular, pageNo); doc.finishPage(page); page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, ++pageNo).create()); c = page.getCanvas(); y = M + 10; }
                p.setTextAlign(Paint.Align.RIGHT); p.setTypeface(bold); p.setTextSize(14); p.setColor(BROWN);
                c.drawText((String) s[0] + "  (" + faDigits(String.valueOf(rows.size())) + ")", W - M, y + 14, p);
                y += 26;
                y = tableRow(c, p, head, cols, y, bold, true);
                if (rows.isEmpty()) {
                    p.setTypeface(regular); p.setTextSize(10.5f); p.setColor(MUTED); p.setTextAlign(Paint.Align.RIGHT);
                    c.drawText((String) s[4], W - M - 8, y + 16, p); y += 28;
                }
                for (String[] r : rows) {
                    if (y > H - 70) { footer(c, p, d, regular, pageNo); doc.finishPage(page); page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, ++pageNo).create()); c = page.getCanvas(); y = M + 10; y = tableRow(c, p, head, cols, y, bold, true); }
                    y = tableRow(c, p, r, cols, y, regular, false);
                }
                y += 18;
            }
            if (d.note != null && !d.note.isEmpty() && y < H - 80) {
                p.setTypeface(regular); p.setTextSize(9.5f); p.setColor(MUTED); p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(d.note, W - M, y + 6, p);
            }
            footer(c, p, d, regular, pageNo);
            doc.finishPage(page);
            try (FileOutputStream out = new FileOutputStream(outFile)) { doc.writeTo(out); }
            return outFile;
        } finally { doc.close(); }
    }

    private static int header(Context ctx, Canvas c, Paint p, Data d, Typeface regular, Typeface bold) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(250, 244, 234)); c.drawRect(0, 0, W, H, p);
        p.setColor(BROWN); c.drawRoundRect(new RectF(M - 6, 22, W - M + 6, 110), 22, 22, p);
        p.setColor(GOLD); c.drawRect(M + 10, 104, W - M - 10, 107, p);
        try {
            Bitmap logo = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.meelano_3d);
            if (logo != null) {
                Bitmap small = Bitmap.createScaledBitmap(logo, 64, 64, true);
                c.drawBitmap(small, W - M - 70, 34, p);
            }
        } catch (Exception ignored) { }
        p.setTextAlign(Paint.Align.RIGHT);
        p.setColor(Color.WHITE); p.setTypeface(bold); p.setTextSize(20);
        c.drawText(d.title, W - M - 84, 60, p);
        p.setTypeface(regular); p.setTextSize(11); p.setColor(Color.argb(230, 255, 240, 214));
        c.drawText(d.visitor + "  •  " + d.date, W - M - 84, 86, p);
        p.setTextAlign(Paint.Align.LEFT); p.setTypeface(bold); p.setTextSize(12); p.setColor(GOLD);
        c.drawText("پخش درخشان ویزیتور", M + 8, 60, p);
        return 128;
    }

    private static void footer(Canvas c, Paint p, Data d, Typeface regular, int pageNo) {
        p.setColor(LINE); c.drawRect(M, H - 42, W - M, H - 41, p);
        p.setTypeface(regular); p.setTextSize(9); p.setColor(MUTED);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("پخش درخشان ویزیتور " + d.appVersion + "  •  طراحی و برنامه‌نویسی: " + d.developer, W - M, H - 26, p);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText("صفحه " + faDigits(String.valueOf(pageNo)), M, H - 26, p);
    }

    private static void card(Canvas c, Paint p, RectF r) {
        p.setStyle(Paint.Style.FILL); p.setColor(CARD); c.drawRoundRect(r, 14, 14, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f); p.setColor(LINE); c.drawRoundRect(r, 14, 14, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Draws one right-to-left table row; returns the next y. */
    private static int tableRow(Canvas c, Paint p, String[] cells, float[] cols, int y, Typeface face, boolean head) {
        float x = W - M, width = W - 2 * M, h = head ? 24 : 22;
        p.setStyle(Paint.Style.FILL);
        p.setColor(head ? Color.rgb(241, 226, 202) : Color.WHITE);
        c.drawRect(M, y, W - M, y + h, p);
        p.setColor(LINE); c.drawRect(M, y + h - 0.8f, W - M, y + h, p);
        p.setTypeface(face); p.setTextSize(head ? 10.5f : 10f); p.setColor(head ? BROWN : INK); p.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i < cols.length && i < cells.length; i++) {
            float cw = width * cols[i];
            c.drawText(ellipsize(p, cells[i] == null ? "" : cells[i], cw - 10), x - 6, y + h - 7, p);
            x -= cw;
        }
        return (int) (y + h);
    }

    private static String ellipsize(Paint p, String s, float max) {
        if (p.measureText(s) <= max) return s;
        String t = s;
        while (t.length() > 1 && p.measureText(t + "…") > max) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    private static float fit(Paint p, String s, float max, float size) {
        float z = size; p.setTextSize(z);
        while (z > 8 && p.measureText(s) > max) { z -= 0.5f; p.setTextSize(z); }
        return z;
    }

    static String faDigits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) b.append(ch >= '0' && ch <= '9' ? (char) ('۰' + (ch - '0')) : ch);
        return b.toString();
    }

    static List<String[]> rows(JSONArray a, String... keys) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i); if (o == null) continue;
            String[] r = new String[keys.length];
            for (int k = 0; k < keys.length; k++) r[k] = o.optString(keys[k], "");
            out.add(r);
        }
        return out;
    }
}
