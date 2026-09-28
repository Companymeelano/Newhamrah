package ir.meelano.android;

import java.util.Calendar;
import java.util.Locale;

/** Persian (Jalali) calendar arithmetic for Atiran's "1405/07/06" dates (day numbers, +/- days, month names). */
final class MeelanoJalali {
    private MeelanoJalali() { }

    private static final int[] BREAKS = {-61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178};
    static final String[] MONTHS = {"فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"};

    /** {leap offset, gregorian year, march day} of the Jalali year (jalaali-js algorithm). */
    private static int[] jalCal(int jy) {
        int bl = BREAKS.length, gy = jy + 621, leapJ = -14, jp = BREAKS[0], jm = 0, jump = 0, n;
        for (int i = 1; i < bl; i++) {
            jm = BREAKS[i]; jump = jm - jp;
            if (jy < jm) break;
            leapJ = leapJ + div(jump, 33) * 8 + div(mod(jump, 33), 4);
            jp = jm;
        }
        n = jy - jp;
        leapJ = leapJ + div(n, 33) * 8 + div(mod(n, 33) + 3, 4);
        if (mod(jump, 33) == 4 && jump - n == 4) leapJ += 1;
        int leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150;
        int march = 20 + leapJ - leapG;
        if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33;
        int leap = mod(mod(n + 1, 33) - 1, 4);
        if (leap == -1) leap = 4;
        return new int[]{leap, gy, march};
    }

    // Truncating division, exactly as in the reference algorithm (not floor).
    private static int div(int a, int b) { return a / b; }
    private static int mod(int a, int b) { return a - (a / b) * b; }

    private static int g2d(int gy, int gm, int gd) {
        int d = div((gy + div(gm - 8, 6) + 100100) * 1461, 4) + div(153 * mod(gm + 9, 12) + 2, 5) + gd - 34840408;
        d = d - div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) + 752;
        return d;
    }

    private static int[] d2g(int jdn) {
        int j = 4 * jdn + 139361631;
        j = j + div(div(4 * jdn + 183187720, 146097) * 3, 4) * 4 - 3908;
        int i = div(mod(j, 1461), 4) * 5 + 308;
        int gd = div(mod(i, 153), 5) + 1;
        int gm = mod(div(i, 153), 12) + 1;
        int gy = div(j, 1461) - 100100 + div(8 - gm, 6);
        return new int[]{gy, gm, gd};
    }

    /** Julian day number of a Gregorian date (same scale as toDay). */
    static int gregorianDay(int gy, int gm, int gd) { return g2d(gy, gm, gd); }

    /** Julian day number of a Jalali date. */
    static int toDay(int jy, int jm, int jd) {
        int[] r = jalCal(jy);
        return g2d(r[1], 3, r[2]) + (jm - 1) * 31 - div(jm, 7) * (jm - 7) + jd - 1;
    }

    static int[] fromDay(int jdn) {
        int[] g = d2g(jdn);
        int gy = g[0];
        int jy = gy - 621;
        int[] r = jalCal(jy);
        int jdn1f = g2d(gy, 3, r[2]);
        int k = jdn - jdn1f;
        if (k >= 0) {
            if (k <= 185) return new int[]{jy, 1 + div(k, 31), mod(k, 31) + 1};
            k -= 186;
        } else {
            jy -= 1; k += 179;
            if (r[0] == 1) k += 1;
        }
        return new int[]{jy, 7 + div(k, 30), mod(k, 30) + 1};
    }

    /** Day number of "1405/07/06" (digits may be Persian); -1 when the text is not a date. */
    static int parse(String text) {
        if (text == null) return -1;
        StringBuilder b = new StringBuilder();
        for (char ch : text.trim().toCharArray()) {
            if (ch >= '۰' && ch <= '۹') b.append((char) ('0' + (ch - '۰')));
            else if (ch >= '٠' && ch <= '٩') b.append((char) ('0' + (ch - '٠')));
            else b.append(ch);
        }
        String[] p = b.toString().split("[/\\-]");
        if (p.length < 3) return -1;
        try {
            int y = Integer.parseInt(p[0].trim()), m = Integer.parseInt(p[1].trim()), d = Integer.parseInt(p[2].trim().length() > 2 ? p[2].trim().substring(0, 2) : p[2].trim());
            if (y < 1300 || y > 1600 || m < 1 || m > 12 || d < 1 || d > 31) return -1;
            return toDay(y, m, d);
        } catch (Exception e) { return -1; }
    }

    static String format(int day) {
        int[] j = fromDay(day);
        return String.format(Locale.US, "%04d/%02d/%02d", j[0], j[1], j[2]);
    }

    static int today() {
        Calendar c = Calendar.getInstance();
        return g2d(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static String addDays(String date, int days) {
        int d = parse(date);
        return d < 0 ? date : format(d + days);
    }

    /** "1405/07/01" for the month of the given date. */
    static String monthStart(String date) {
        int d = parse(date);
        if (d < 0) return date;
        int[] j = fromDay(d);
        return String.format(Locale.US, "%04d/%02d/01", j[0], j[1]);
    }

    static String monthName(String date) {
        int d = parse(date);
        if (d < 0) return "";
        int[] j = fromDay(d);
        return MONTHS[j[1] - 1];
    }

    /** Short label for charts: "۶ مهر". */
    static String shortLabel(String date) {
        int d = parse(date);
        if (d < 0) return date == null ? "" : date;
        int[] j = fromDay(d);
        return j[2] + " " + MONTHS[j[1] - 1];
    }

    private static final String[] WEEKDAYS = {"شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه"};

    static String weekday(String date) {
        int d = parse(date);
        if (d < 0) return "";
        // Julian day 0 was a Monday; Saturday is the first day of the Persian week.
        return WEEKDAYS[((d + 2) % 7 + 7) % 7];
    }
}
