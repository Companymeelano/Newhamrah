package ir.meelano.android;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds Moadian invoices (RC_IITP.IS v7.9.1, pattern 1 «فروش») from Atiran documents and validates them before a
 * tax id is spent. Pure Java: unit-tested on the JVM.
 *
 * <p>Money rules: every rial amount is truncated («روش قطع کردن», RC_IITP §8 general rule 1); {@code am} and
 * {@code fee} keep up to 8 decimals, {@code vra} 2. Line: prdis = am×fee, adis = prdis − dis,
 * vam = adis×vra/100, tsstam = adis + vam. Header totals are the sums of the lines.</p>
 */
final class MeelanoTaxInvoice {
    private MeelanoTaxInvoice() {}

    static final int INS_ORIGINAL = 1, INS_CORRECTION = 2, INS_CANCEL = 3, INS_RETURN = 4;

    static String insTitle(int ins) {
        switch (ins) {
            case INS_CORRECTION: return "اصلاحی";
            case INS_CANCEL: return "ابطالی";
            case INS_RETURN: return "برگشت از فروش";
            default: return "اصلی";
        }
    }

    static String tobTitle(Integer tob) {
        if (tob == null) return "نامشخص";
        switch (tob) {
            case 1: return "حقیقی";
            case 2: return "حقوقی";
            case 3: return "مشارکت مدنی";
            case 4: return "اتباع غیرایرانی";
            case 5: return "مصرف‌کننده نهایی";
            default: return "نامشخص";
        }
    }

    // ------------------------------------------------------------------ units (RC_UMGS.ST v1.18)

    private static final String[] UNIT_TABLE = {
            "1611", "لنگه", "1612", "عدل", "1613", "جعبه", "1618", "توپ", "1619", "ست", "1620", "دست", "1624", "کارتن", "1627", "عدد", "1628", "بسته", "1629", "پاکت", "1631", "دستگاه", "1640", "تخته", "1641", "رول", "1642", "طاقه", "1643", "جفت", "1645", "متر مربع", "1649", "پالت", "1661", "دوجین", "1668", "حلقه (رینگ)", "1673", "قراص", "1694", "قراصه (bundle)", "1637", "لیتر", "1650", "ساشه", "1683", "کپسول", "1656", "بندیل", "1630", "حلقه (رول)", "163", "قالب", "1660", "شانه", "1647", "متر مکعب", "1689", "ثوب", "1690", "نیم دوجین", "1635", "قرقره", "164", "کیلوگرم", "1638", "بطری", "161", "برگ", "1625", "سطل", "1654", "ورق", "1646", "شاخه", "1644", "قوطی", "1617", "جلد", "162", "تیوب", "165", "متر", "1610", "کلاف", "1615", "کیسه", "1680", "طغرا", "1639", "بشکه", "1614", "گالن", "1687", "فاقد بسته بندی", "1693", "کارتن (case master)", "166", "صفحه", "1666", "مخزن", "1626", "تانکر", "1648", "دبه", "1684", "سبد", "169", "تن", "1651", "بانکه", "1633", "سیلندر", "1679", "فوت مربع", "168", "حلب", "1665", "شیت", "1659", "چلیک", "1636", "جام", "1622", "گرم", "1616", "نخ", "1652", "شعله", "1678", "قیراط", "16100", "میلی لیتر", "16101", "میلی متر", "16102", "میلی گرم", "16103", "ساعت", "16104", "روز", "16105", "تن کیلومتر", "1669", "کیلووات ساعت", "1676", "نفر", "16110", "ثانیه", "16111", "دقیقه", "16112", "ماه", "16113", "سال", "16114", "قطعه", "16115", "سانتی متر", "16116", "سانتی متر مربع", "1632", "فروند", "1653", "واحد", "16108", "لیوان", "16117", "نوبت", "16118", "مگا وات ساعت", "16119", "گیگا بایت بر ثانیه", "1681", "ویال", "1667", "حلقه (دیسک)", "16120", "نسخه (جلد)", "16121", "نفر-ساعت", "16122", "کیلومتر", "16125", "آمپر", "16126", "میلی آمپر", "16127", "مثقال", "16128", "سیر", "16129", "دفعه (time)", "16130", "مگا یونیت", "16131", "کادر", "16132", "پرس", "16133", "بلوک", "16134", "نفر-ماه"
    };
    static final Map<String, String> UNITS = new LinkedHashMap<>();
    private static final Map<String, String> UNIT_BY_NAME = new HashMap<>();

    static {
        for (int i = 0; i + 1 < UNIT_TABLE.length; i += 2) {
            UNITS.put(UNIT_TABLE[i], UNIT_TABLE[i + 1]);
            UNIT_BY_NAME.put(normName(UNIT_TABLE[i + 1]), UNIT_TABLE[i]);
        }
        // Atiran spellings that are not in the official list.
        UNIT_BY_NAME.put(normName("کیلو"), "164");
        UNIT_BY_NAME.put(normName("کیلوگرم"), "164");
        UNIT_BY_NAME.put(normName("كيلو گرم"), "164");
        UNIT_BY_NAME.put(normName("کارتن"), "1624");
        UNIT_BY_NAME.put(normName("شرینگ"), "1624");
        UNIT_BY_NAME.put(normName("شیرینگ"), "1624");
        UNIT_BY_NAME.put(normName("بسته"), "1628");
        UNIT_BY_NAME.put(normName("عدد"), "1627");
        UNIT_BY_NAME.put(normName("گرم"), "1622");
        UNIT_BY_NAME.put(normName("لیتر"), "1637");
    }

    /** Folds Arabic yeh/kaf, removes spaces and ZWNJ so Atiran's CP1256 names match the official list. */
    static String normName(String s) {
        if (s == null) return "";
        return s.replace('ي', 'ی').replace('ك', 'ک').replace('ى', 'ی').replace("\u200c", "").replace(" ", "").trim();
    }

    /** Official unit code for a unit name (Atiran «vahsanj»), using an optional DB/user map first; null when unknown. */
    static String unitCode(String unitName, Map<String, String> customMap) {
        String n = normName(unitName);
        if (n.isEmpty()) return null;
        if (customMap != null) {
            String c = customMap.get(n);
            if (c != null && !c.trim().isEmpty()) return c.trim();
        }
        return UNIT_BY_NAME.get(n);
    }

    static String unitName(String code) {
        String n = code == null ? null : UNITS.get(code.trim());
        return n == null ? "" : n;
    }

    // ------------------------------------------------------------------ model

    static final class Line {
        int shka;
        String name = "";
        String sstid;
        String sstt;
        String unitName;
        String mu;
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal vatRate = BigDecimal.ZERO;
        /** VAT (+ avarez) that Atiran stored for the line, for a consistency warning; null when unknown. */
        BigDecimal atiranVat;
        boolean gift;
        // computed by build()
        BigDecimal am, feeOut, vra;
        long prdis, dis, adis, vam, tsstam;

        Line copy() {
            Line l = new Line();
            l.shka = shka; l.name = name; l.sstid = sstid; l.sstt = sstt; l.unitName = unitName; l.mu = mu;
            l.qty = qty; l.fee = fee; l.discount = discount; l.vatRate = vatRate; l.atiranVat = atiranVat; l.gift = gift;
            return l;
        }
    }

    static final class Buyer {
        Integer tob;
        String bid, tinb, bpc, bbc, name;
    }

    static final class Source {
        int ins = INS_ORIGINAL;
        int inty = 1;
        int inp = 1;
        /** 1 نقدی, 2 نسیه, 3 نقدی/نسیه; null → 2 for type 1 (as Atiran). */
        Integer setm;
        Long cap;
        String tins;
        String sbc;
        Buyer buyer = new Buyer();
        List<Line> lines = new ArrayList<>();
        /** Invoice-level discount on top of the line discounts, spread over the lines. */
        BigDecimal headerDiscount = BigDecimal.ZERO;
        long indatim;
        long now;
        int deadlineDays = 12;
        String irtaxid;
        long referenceIndatim;
        String nti1;
    }

    static final class Issue {
        final boolean error;
        final String field;
        final int line;
        final String message;

        Issue(boolean error, String field, int line, String message) {
            this.error = error; this.field = field; this.line = line; this.message = message;
        }

        @Override public String toString() {
            return (error ? "خطا: " : "هشدار: ") + (line > 0 ? "ردیف " + line + " — " : "") + message;
        }
    }

    static final class Result {
        Map<String, Object> invoice;
        String json;
        final List<Issue> issues = new ArrayList<>();
        final List<Line> lines = new ArrayList<>();
        long tprdis, tdis, tadis, tvam, todam, tbill;
        boolean late;

        boolean hasErrors() {
            for (Issue i : issues) if (i.error) return true;
            return false;
        }

        List<String> errorTexts() {
            List<String> out = new ArrayList<>();
            for (Issue i : issues) if (i.error) out.add(i.toString());
            return out;
        }

        List<String> warningTexts() {
            List<String> out = new ArrayList<>();
            for (Issue i : issues) if (!i.error) out.add(i.toString());
            return out;
        }
    }

    // ------------------------------------------------------------------ arithmetic

    static long truncate(BigDecimal v) { return v.setScale(0, RoundingMode.DOWN).longValueExact(); }

    static BigDecimal scale(BigDecimal v, int places) {
        BigDecimal s = v.setScale(places, RoundingMode.DOWN).stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    static boolean late(long indatim, long now, int deadlineDays) {
        return deadlineDays > 0 && now - indatim > deadlineDays * 86_400_000L;
    }

    static Result build(Source s, String taxid, String inno) {
        Result r = new Result();
        List<Line> work = new ArrayList<>();
        int index = 0;
        for (Line src : s.lines) {
            index++;
            if (src.qty == null || src.qty.signum() <= 0) continue;
            Line l = src.copy();
            if (l.fee == null || l.fee.signum() <= 0) {
                if (l.gift) {
                    r.issues.add(new Issue(false, "fee", index, "«" + l.name + "» اشانتیون بدون مبلغ است و در صورتحساب مودیان درج نمی‌شود."));
                    continue;
                }
                r.issues.add(new Issue(true, "fee", index, "مبلغ واحد «" + l.name + "» صفر است؛ مبلغ قبل از تخفیف باید بزرگ‌تر از صفر باشد."));
            }
            l.am = scale(l.qty, 8);
            l.feeOut = scale(l.fee == null ? BigDecimal.ZERO : l.fee, 8);
            l.prdis = truncate(l.am.multiply(l.feeOut));
            long d = truncate(l.discount == null ? BigDecimal.ZERO : l.discount.max(BigDecimal.ZERO));
            l.dis = Math.min(d, Math.max(0, l.prdis));
            work.add(l);
        }
        spreadHeaderDiscount(work, s.headerDiscount);
        int ln = 0;
        for (Line l : work) {
            ln++;
            l.adis = l.prdis - l.dis;
            l.vra = scale(l.vatRate == null ? BigDecimal.ZERO : l.vatRate, 2);
            l.vam = truncate(BigDecimal.valueOf(l.adis).multiply(l.vra).divide(BigDecimal.valueOf(100), 8, RoundingMode.DOWN));
            l.tsstam = l.adis + l.vam;
            r.tprdis += l.prdis; r.tdis += l.dis; r.tadis += l.adis; r.tvam += l.vam;
            if (l.atiranVat != null && Math.abs(l.atiranVat.longValue() - l.vam) > 1) {
                r.issues.add(new Issue(false, "vam", ln, "مالیات «" + l.name + "» در آتیران " + l.atiranVat.setScale(0, RoundingMode.DOWN).toPlainString()
                        + " ریال است ولی طبق نرخ " + MeelanoTaxJson.plain(l.vra) + "٪ برابر " + l.vam + " ریال محاسبه شد."));
            }
            r.lines.add(l);
        }
        r.todam = 0;
        r.tbill = r.tadis + r.tvam + r.todam;
        r.late = late(s.indatim, s.now, s.deadlineDays);

        Map<String, Object> h = new LinkedHashMap<>();
        h.put("taxid", taxid);
        h.put("indatim", s.indatim);
        if (r.late) h.put("indati2m", s.now);
        h.put("inty", s.inty);
        h.put("inno", inno);
        if (s.ins != INS_ORIGINAL) h.put("irtaxid", s.irtaxid);
        h.put("inp", s.inp);
        h.put("ins", s.ins);
        h.put("tins", digits(s.tins));
        if (s.ins == INS_ORIGINAL) {
            Buyer b = s.buyer == null ? new Buyer() : s.buyer;
            boolean any = notEmpty(b.bid) || notEmpty(b.tinb) || notEmpty(b.bpc);
            if (s.inty == 1 || (b.tob != null && any)) h.put("tob", b.tob);
            putIf(h, "bid", digits(b.bid));
            putIf(h, "tinb", digits(b.tinb));
            putIf(h, "bpc", digits(b.bpc));
            putIf(h, "bbc", digits(b.bbc));
        }
        putIf(h, "sbc", digits(s.sbc));
        h.put("tprdis", r.tprdis);
        h.put("tdis", r.tdis);
        h.put("tadis", r.tadis);
        h.put("tvam", r.tvam);
        h.put("todam", r.todam);
        h.put("tbill", r.tbill);
        if (s.inty == 1) {
            int setm = s.setm == null ? 2 : s.setm;
            h.put("setm", setm);
            if (setm == 3) {
                long cap = s.cap == null ? 0 : s.cap;
                h.put("cap", cap);
                h.put("insp", r.tbill - r.todam - r.tvam - cap);
            }
        }
        if (r.late) h.put("insr", 1);
        if (notEmpty(s.nti1)) h.put("nti1", s.nti1.trim());

        List<Object> body = new ArrayList<>();
        for (Line l : r.lines) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("sstid", digits(l.sstid));
            String sstt = notEmpty(l.sstt) ? l.sstt.trim() : (l.name == null ? "" : l.name.trim());
            if (!sstt.isEmpty()) b.put("sstt", clip(sstt, 400));
            b.put("am", l.am);
            putIf(b, "mu", l.mu);
            b.put("fee", l.feeOut);
            b.put("prdis", l.prdis);
            b.put("dis", l.dis);
            b.put("adis", l.adis);
            b.put("vra", l.vra);
            b.put("vam", l.vam);
            b.put("tsstam", l.tsstam);
            body.add(b);
        }
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("header", h);
        inv.put("body", body);
        inv.put("payments", new ArrayList<>());
        inv.put("extension", null);
        r.invoice = inv;
        r.json = MeelanoTaxJson.write(inv);
        validate(s, taxid, r);
        return r;
    }

    /** Spreads an invoice-level discount over the lines in proportion to their after-discount amounts. */
    static void spreadHeaderDiscount(List<Line> lines, BigDecimal headerDiscount) {
        if (headerDiscount == null || headerDiscount.signum() <= 0 || lines.isEmpty()) return;
        long total = truncate(headerDiscount);
        long base = 0;
        for (Line l : lines) base += Math.max(0, l.prdis - l.dis);
        if (base <= 0) return;
        total = Math.min(total, base);
        long given = 0;
        Line largest = null;
        for (Line l : lines) {
            long room = Math.max(0, l.prdis - l.dis);
            long share = BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(room)).divide(BigDecimal.valueOf(base), 0, RoundingMode.DOWN).longValue();
            share = Math.min(share, room);
            l.dis += share;
            given += share;
            if (largest == null || (l.prdis - l.dis) > (largest.prdis - largest.dis)) largest = l;
        }
        long rest = total - given;
        for (Line l : lines) {
            if (rest <= 0) break;
            long room = Math.max(0, l.prdis - l.dis);
            long add = Math.min(room, rest);
            l.dis += add;
            rest -= add;
        }
    }

    // ------------------------------------------------------------------ validation

    static void validate(Source s, String taxid, Result r) {
        String tins = digits(s.tins);
        if (tins == null || !(tins.length() == 11 || tins.length() == 14))
            r.issues.add(new Issue(true, "tins", 0, "شماره اقتصادی فروشنده باید ۱۱ یا ۱۴ رقم باشد (در تنظیمات اولیه وارد کنید)."));
        if (taxid == null || !MeelanoTaxCrypto.taxIdValid(taxid))
            r.issues.add(new Issue(true, "taxid", 0, "شماره منحصر به فرد مالیاتی ساخته‌شده معتبر نیست."));
        else if (MeelanoTaxCrypto.taxIdDay(taxid) != MeelanoTaxCrypto.dayRange(s.indatim))
            r.issues.add(new Issue(true, "taxid", 0, "تاریخ داخل شماره مالیاتی با تاریخ صدور یکسان نیست."));
        if (s.indatim <= 0)
            r.issues.add(new Issue(true, "indatim", 0, "تاریخ و ساعت صدور صورتحساب نامعتبر است."));
        else if (s.indatim > s.now + 120_000L)
            r.issues.add(new Issue(true, "indatim", 0, "تاریخ صدور صورتحساب نباید بعد از زمان حال باشد (ساعت و تاریخ گوشی را بررسی کنید)."));
        if (r.late)
            r.issues.add(new Issue(false, "insr", 0, "از مهلت " + s.deadlineDays + " روزه ارسال گذشته است؛ طبق ماده ۹ با «قاعده ارسال = ۱» و تاریخ ثبت امروز فرستاده می‌شود."));
        if (s.ins != INS_ORIGINAL) {
            if (s.irtaxid == null || !MeelanoTaxCrypto.taxIdValid(s.irtaxid))
                r.issues.add(new Issue(true, "irtaxid", 0, "شماره مالیاتی صورتحساب مرجع معتبر نیست."));
            if (s.referenceIndatim > 0 && s.indatim < s.referenceIndatim)
                r.issues.add(new Issue(true, "indatim", 0, "تاریخ صورتحساب " + insTitle(s.ins) + " باید بعد از صورتحساب مرجع باشد."));
        }
        if (s.inty != 1 && s.inty != 2)
            r.issues.add(new Issue(true, "inty", 0, "نوع صورتحساب باید «نوع اول» یا «نوع دوم» باشد."));
        if (r.lines.isEmpty())
            r.issues.add(new Issue(true, "body", 0, "صورتحساب هیچ ردیف قابل ارسالی ندارد."));
        if (r.tprdis <= 0 && !r.lines.isEmpty())
            r.issues.add(new Issue(true, "tprdis", 0, "مجموع مبلغ قبل از تخفیف باید بزرگ‌تر از صفر باشد."));
        if (r.tbill == 0 && r.tprdis > 0)
            r.issues.add(new Issue(false, "tbill", 0, "مبلغ نهایی صورتحساب صفر است (تخفیف کامل)."));
        int ln = 0;
        for (Line l : r.lines) {
            ln++;
            String sid = digits(l.sstid);
            if (sid == null || sid.length() != 13)
                r.issues.add(new Issue(true, "sstid", ln, "شناسه ۱۳ رقمی کالا/خدمت برای «" + l.name + "» ثبت نشده است (بخش شناسه کالاها)."));
            if (l.mu == null)
                r.issues.add(new Issue(false, "mu", ln, "واحد «" + nz(l.unitName) + "» برای «" + l.name + "» کد رسمی ندارد و بدون واحد ارسال می‌شود."));
            else if (!UNITS.containsKey(l.mu))
                r.issues.add(new Issue(false, "mu", ln, "کد واحد " + l.mu + " در فهرست رسمی واحدها نیست."));
            if (l.vra.signum() < 0 || l.vra.compareTo(BigDecimal.valueOf(100)) > 0)
                r.issues.add(new Issue(true, "vra", ln, "نرخ مالیات بر ارزش افزوده «" + l.name + "» نامعتبر است."));
            if (l.prdis <= 0 && l.feeOut.signum() > 0)
                r.issues.add(new Issue(true, "prdis", ln, "مبلغ قبل از تخفیف «" + l.name + "» صفر شد (مقدار خیلی کم است)."));
        }
        if (s.ins == INS_ORIGINAL && s.inty == 1) {
            Buyer b = s.buyer == null ? new Buyer() : s.buyer;
            String bid = digits(b.bid), tinb = digits(b.tinb), bpc = digits(b.bpc);
            if (b.tob == null || b.tob < 1 || b.tob > 5) {
                r.issues.add(new Issue(true, "tob", 0, "نوع شخص خریدار مشخص نیست (حقیقی/حقوقی/…)."));
            } else if (b.tob == 2 || b.tob == 3) {
                if (tinb == null)
                    r.issues.add(new Issue(true, "tinb", 0, "برای خریدار " + tobTitle(b.tob) + " در صورتحساب نوع اول، شماره اقتصادی (یا شناسه ملی) خریدار لازم است."));
            } else if (tinb == null && (bid == null || bpc == null)) {
                r.issues.add(new Issue(true, "tinb", 0, "برای خریدار حقیقی در صورتحساب نوع اول، شماره اقتصادی یا «کد ملی + کد پستی» خریدار لازم است."));
            }
            if (tinb != null && !(tinb.length() == 11 || tinb.length() == 14 || tinb.length() == 10))
                r.issues.add(new Issue(true, "tinb", 0, "شماره اقتصادی خریدار باید ۱۴ رقم (یا شناسه ملی ۱۱ رقمی / کد ملی ۱۰ رقمی) باشد."));
            if (bid != null && !(bid.length() == 10 || bid.length() == 11 || bid.length() == 12))
                r.issues.add(new Issue(true, "bid", 0, "شناسه/کد ملی خریدار باید ۱۰، ۱۱ یا ۱۲ رقم باشد."));
            if (bid != null && bid.length() == 10 && !nationalCodeValid(bid))
                r.issues.add(new Issue(false, "bid", 0, "کد ملی خریدار از نظر رقم کنترل معتبر به نظر نمی‌رسد."));
            if (bpc != null && bpc.length() != 10)
                r.issues.add(new Issue(true, "bpc", 0, "کد پستی خریدار باید ۱۰ رقم باشد."));
        }
        if (s.inty == 1 && s.setm != null && s.setm == 3) {
            long cap = s.cap == null ? 0 : s.cap;
            if (cap <= 0 || cap >= r.tbill - r.tvam)
                r.issues.add(new Issue(true, "cap", 0, "در تسویه نقدی/نسیه، مبلغ نقدی باید بزرگ‌تر از صفر و کمتر از مبلغ بدون مالیات باشد."));
        }
    }

    /** Iranian national code (کد ملی) check digit. */
    static boolean nationalCodeValid(String code) {
        if (code == null || !code.matches("\\d{10}")) return false;
        if (code.chars().distinct().count() == 1) return false;
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (code.charAt(i) - '0') * (10 - i);
        int rem = sum % 11;
        int check = code.charAt(9) - '0';
        return rem < 2 ? check == rem : check == 11 - rem;
    }

    // ------------------------------------------------------------------ returns, corrections, cancels

    /**
     * Lines left after returns: original quantities minus the returned quantities per product, in line order.
     * Price and VAT rate stay as in the original (RC_IITP §5-4); the line discount shrinks in proportion.
     */
    static List<Line> remainingAfterReturns(List<Line> original, Map<Integer, BigDecimal> returnedByShka) {
        Map<Integer, BigDecimal> left = new HashMap<>();
        if (returnedByShka != null) left.putAll(returnedByShka);
        List<Line> out = new ArrayList<>();
        for (Line o : original) {
            Line l = o.copy();
            BigDecimal ret = left.get(l.shka);
            if (ret != null && ret.signum() > 0 && l.qty.signum() > 0) {
                BigDecimal take = ret.min(l.qty);
                BigDecimal newQty = l.qty.subtract(take);
                left.put(l.shka, ret.subtract(take));
                if (l.discount != null && l.discount.signum() > 0)
                    l.discount = l.discount.multiply(newQty).divide(l.qty, 0, RoundingMode.DOWN);
                if (l.atiranVat != null && l.atiranVat.signum() > 0)
                    l.atiranVat = l.atiranVat.multiply(newQty).divide(l.qty, 0, RoundingMode.DOWN);
                l.qty = newQty;
            }
            if (l.qty.signum() > 0) out.add(l);
        }
        return out;
    }

    /** Returned quantity that could not be matched to the original lines (a warning for the user). */
    static Map<Integer, BigDecimal> unmatchedReturns(List<Line> original, Map<Integer, BigDecimal> returnedByShka) {
        Map<Integer, BigDecimal> sold = new HashMap<>();
        for (Line l : original) sold.put(l.shka, sold.getOrDefault(l.shka, BigDecimal.ZERO).add(l.qty));
        Map<Integer, BigDecimal> out = new LinkedHashMap<>();
        if (returnedByShka == null) return out;
        for (Map.Entry<Integer, BigDecimal> e : returnedByShka.entrySet()) {
            BigDecimal extra = e.getValue().subtract(sold.getOrDefault(e.getKey(), BigDecimal.ZERO));
            if (extra.signum() > 0) out.put(e.getKey(), extra);
        }
        return out;
    }

    /**
     * A cancel (ابطالی) built from the last accepted version of the chain: same body and totals, new tax id,
     * ins = 3 and irtaxid. Buyer fields are out of pattern for ins 2/3/4 and are removed.
     */
    static Result cancelFrom(String previousJson, String taxid, String inno, long indatim, long now, int deadlineDays,
                             String irtaxid, long referenceIndatim) {
        Map<String, Object> prev = MeelanoTaxJson.obj(MeelanoTaxJson.parse(previousJson));
        if (prev == null) throw new IllegalArgumentException("نسخه قبلی صورتحساب پیدا نشد.");
        Map<String, Object> ph = MeelanoTaxJson.obj(prev, "header");
        Map<String, Object> h = new LinkedHashMap<>();
        Result r = new Result();
        r.late = late(indatim, now, deadlineDays);
        h.put("taxid", taxid);
        h.put("indatim", indatim);
        if (r.late) h.put("indati2m", now);
        h.put("inty", MeelanoTaxJson.num(ph, "inty", 1));
        h.put("inno", inno);
        h.put("irtaxid", irtaxid);
        h.put("inp", MeelanoTaxJson.num(ph, "inp", 1));
        h.put("ins", INS_CANCEL);
        h.put("tins", MeelanoTaxJson.str(ph, "tins"));
        if (ph != null && ph.get("sbc") != null) h.put("sbc", MeelanoTaxJson.str(ph, "sbc"));
        for (String k : new String[]{"tprdis", "tdis", "tadis", "tvam", "todam", "tbill", "setm", "cap", "insp"}) {
            if (ph != null && ph.get(k) != null) h.put(k, MeelanoTaxJson.num(ph, k, 0));
        }
        if (r.late) h.put("insr", 1);
        r.tprdis = MeelanoTaxJson.num(ph, "tprdis", 0);
        r.tdis = MeelanoTaxJson.num(ph, "tdis", 0);
        r.tadis = MeelanoTaxJson.num(ph, "tadis", 0);
        r.tvam = MeelanoTaxJson.num(ph, "tvam", 0);
        r.tbill = MeelanoTaxJson.num(ph, "tbill", 0);
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("header", h);
        List<Object> body = MeelanoTaxJson.arr(prev, "body");
        inv.put("body", body == null ? new ArrayList<>() : body);
        inv.put("payments", new ArrayList<>());
        r.invoice = inv;
        r.json = MeelanoTaxJson.write(inv);
        if (!MeelanoTaxCrypto.taxIdValid(irtaxid)) r.issues.add(new Issue(true, "irtaxid", 0, "شماره مالیاتی صورتحساب مرجع معتبر نیست."));
        if (!MeelanoTaxCrypto.taxIdValid(taxid)) r.issues.add(new Issue(true, "taxid", 0, "شماره منحصر به فرد مالیاتی ساخته‌شده معتبر نیست."));
        if (referenceIndatim > 0 && indatim < referenceIndatim) r.issues.add(new Issue(true, "indatim", 0, "تاریخ ابطال باید بعد از صورتحساب مرجع باشد."));
        if (indatim > now + 120_000L) r.issues.add(new Issue(true, "indatim", 0, "تاریخ صدور صورتحساب نباید بعد از زمان حال باشد."));
        return r;
    }

    /**
     * Why a correction (ins 2) is not allowed and the chain needs «ابطال + صدور مجدد» instead, or null. RC_IITP §5-2:
     * a correction cannot change the invoice type, the buyer, the goods/services ids or their VAT rates.
     */
    static String correctionBlocker(String originalJson, Source now) {
        Map<String, Object> prev = MeelanoTaxJson.obj(MeelanoTaxJson.parse(originalJson));
        Map<String, Object> ph = MeelanoTaxJson.obj(prev, "header");
        if (ph == null) return "نسخه اصلی صورتحساب در دسترس نیست.";
        if (MeelanoTaxJson.num(ph, "inty", 1) != now.inty) return "نوع صورتحساب تغییر کرده است.";
        Buyer b = now.buyer == null ? new Buyer() : now.buyer;
        if (!same(MeelanoTaxJson.str(ph, "tinb"), digits(b.tinb)) || !same(MeelanoTaxJson.str(ph, "bid"), digits(b.bid)))
            return "مشخصات خریدار تغییر کرده است.";
        Map<String, BigDecimal> rates = new HashMap<>();
        List<Object> body = MeelanoTaxJson.arr(prev, "body");
        if (body != null) for (Object o : body) {
            Map<String, Object> m = MeelanoTaxJson.obj(o);
            rates.put(MeelanoTaxJson.str(m, "sstid"), MeelanoTaxJson.dec(m, "vra"));
        }
        for (Line l : now.lines) {
            if (l.qty == null || l.qty.signum() <= 0) continue;
            String sid = digits(l.sstid);
            if (!rates.containsKey(sid)) return "کالای جدید «" + l.name + "» اضافه شده است.";
            BigDecimal old = rates.get(sid);
            if (old != null && l.vatRate != null && old.compareTo(scale(l.vatRate, 2)) != 0) return "نرخ مالیات «" + l.name + "» تغییر کرده است.";
        }
        return null;
    }

    /**
     * Fingerprint of the sendable content (type, buyer, lines) — a changed hash after sending means the invoice
     * was edited in Atiran and needs a correction.
     */
    static String contentHash(Source s) {
        StringBuilder b = new StringBuilder();
        b.append(s.inty).append('|');
        Buyer by = s.buyer == null ? new Buyer() : s.buyer;
        b.append(by.tob).append('|').append(nz(digits(by.bid))).append('|').append(nz(digits(by.tinb))).append('|');
        List<String> rows = new ArrayList<>();
        for (Line l : s.lines) {
            if (l.qty == null || l.qty.signum() <= 0) continue;
            rows.add(l.shka + ":" + MeelanoTaxJson.plain(l.qty) + ":" + MeelanoTaxJson.plain(l.fee == null ? BigDecimal.ZERO : l.fee)
                    + ":" + MeelanoTaxJson.plain(l.discount == null ? BigDecimal.ZERO : l.discount) + ":" + MeelanoTaxJson.plain(l.vatRate == null ? BigDecimal.ZERO : l.vatRate));
        }
        Collections.sort(rows);
        for (String r : rows) b.append(r).append(';');
        b.append('|').append(MeelanoTaxJson.plain(s.headerDiscount == null ? BigDecimal.ZERO : s.headerDiscount));
        return MeelanoTaxCrypto.sha256Hex(b.toString()).substring(0, 32);
    }

    // ------------------------------------------------------------------ helpers

    /** Digits only (Persian/Arabic digits folded); null when empty. */
    static String digits(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') b.append(c);
            else if (c >= '۰' && c <= '۹') b.append((char) ('0' + (c - '۰')));
            else if (c >= '٠' && c <= '٩') b.append((char) ('0' + (c - '٠')));
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static boolean same(String a, String b) {
        String x = a == null ? "" : a.trim(), y = b == null ? "" : b.trim();
        return x.equals(y);
    }

    private static void putIf(Map<String, Object> m, String k, String v) {
        if (v != null && !v.trim().isEmpty()) m.put(k, v.trim());
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clip(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    /** Tehran wall-clock (fixed +03:30 since 1401) Jalali date "1405/07/06" + "HH:mm[:ss]" → epoch millis; -1 if invalid. */
    static long tehranMillis(String jalaliDate, String time) {
        int day = MeelanoJalali.parse(jalaliDate);
        if (day < 0) return -1;
        int hh = 12, mm = 0, ss = 0;
        if (time != null) {
            String t = time.trim();
            String[] p = t.split(":");
            try {
                if (p.length >= 1 && !p[0].trim().isEmpty()) hh = Integer.parseInt(p[0].trim());
                if (p.length >= 2) mm = Integer.parseInt(p[1].trim());
                if (p.length >= 3) ss = Integer.parseInt(p[2].trim().length() > 2 ? p[2].trim().substring(0, 2) : p[2].trim());
            } catch (Exception e) { hh = 12; mm = 0; ss = 0; }
        }
        if (hh < 0 || hh > 23) hh = 12;
        if (mm < 0 || mm > 59) mm = 0;
        if (ss < 0 || ss > 59) ss = 0;
        long epochDay = day - 2440588L;
        return (epochDay * 86_400L + hh * 3600L + mm * 60L + ss - 12_600L) * 1000L;
    }

    static String money(long v) { return String.format(Locale.US, "%,d", v); }
}
