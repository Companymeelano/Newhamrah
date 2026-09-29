package ir.meelano.android;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.PrivateKey;
import java.sql.Connection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Moadian send logic without any UI: which submission a document needs (original, correction, return, cancel),
 * reservation of the tax id, signing/encryption/POST, result inquiry, buyer-side status and the Atiran write-back.
 *
 * <p>Rules (RC_IITP 7.9.1): a correction (ins 2) and a return (ins 4) always reference the latest accepted tax id of
 * the chain; a return carries the items that remain after all returns; returning everything is a cancel (ins 3); a
 * correction cannot change the buyer, the invoice type, the goods ids or VAT rates — those need cancel + re-issue.</p>
 */
final class MeelanoTaxEngine {
    private MeelanoTaxEngine() {}

    static final String ACT_NONE = "NONE", ACT_ORIGINAL = "ORIGINAL", ACT_CORRECTION = "CORRECTION", ACT_RETURN = "RETURN", ACT_CANCEL = "CANCEL";

    /** Everything a submission needs besides the document. */
    static final class Config {
        String memoryId = "";
        String tins = "";
        String sbc;
        boolean sandbox = true;
        int deadlineDays = 12;
        long serialSeed = 1;
        boolean writeBack = true;
        Integer defaultSetm;
        boolean typeTwoWhenNoIds = true;
        String userName = "";
        int atiranUserId;
        PrivateKey key;
        byte[] certDer;

        static Config from(Map<String, String> s) {
            Config c = new Config();
            c.memoryId = MeelanoTaxDb.nz(s.get("memory_id")).trim().toUpperCase(Locale.US);
            c.tins = MeelanoTaxDb.nz(MeelanoTaxInvoice.digits(s.get("economic_code")));
            c.sbc = MeelanoTaxInvoice.digits(s.get("branch_code"));
            c.sandbox = !"PRODUCTION".equals(s.get("env"));
            c.deadlineDays = parseInt(s.get("deadline_days"), 12);
            c.serialSeed = Math.max(1, parseLong(s.get("serial_seed"), 1));
            c.writeBack = !"0".equals(s.get("write_back"));
            int setm = parseInt(s.get("default_setm"), 0);
            c.defaultSetm = setm >= 1 && setm <= 3 ? setm : null;
            c.typeTwoWhenNoIds = !"0".equals(s.get("type2_when_no_ids"));
            return c;
        }

        boolean ready() { return memoryId.matches("[A-Z0-9]{6}") && (tins.length() == 11 || tins.length() == 14) && key != null && certDer != null; }
    }

    static int parseInt(String s, int def) {
        try { return Integer.parseInt(MeelanoTaxDb.foldDigits(s == null ? "" : s).trim()); } catch (Exception e) { return def; }
    }

    static long parseLong(String s, long def) {
        try { return Long.parseLong(MeelanoTaxDb.foldDigits(s == null ? "" : s).trim()); } catch (Exception e) { return def; }
    }

    /** A document prepared for sending: the chosen action, the built invoice (with a preview tax id) and the log skeleton. */
    static final class Prepared {
        MeelanoTaxDb.Doc doc;
        String action = ACT_NONE;
        /** Why nothing can be sent (Persian), for ACT_NONE; or an extra note for the other actions. */
        String message = "";
        MeelanoTaxInvoice.Source source;
        MeelanoTaxInvoice.Result result;
        /** For a cancel built from the previous version's payload. */
        String cancelFromJson;
        String irtaxid;
        long referenceIndatim;
        int chainSale;
        int rdf;
        String contentHash;
        final List<String> notes = new ArrayList<>();

        boolean sendable() { return !ACT_NONE.equals(action) && result != null && !result.hasErrors(); }

        int ins() {
            switch (action) {
                case ACT_CORRECTION: return MeelanoTaxInvoice.INS_CORRECTION;
                case ACT_RETURN: return MeelanoTaxInvoice.INS_RETURN;
                case ACT_CANCEL: return MeelanoTaxInvoice.INS_CANCEL;
                default: return MeelanoTaxInvoice.INS_ORIGINAL;
            }
        }

        String actionTitle() { return actionTitle(action); }

        static String actionTitle(String action) {
            switch (action) {
                case ACT_ORIGINAL: return "ارسال صورتحساب اصلی";
                case ACT_CORRECTION: return "ارسال صورتحساب اصلاحی";
                case ACT_RETURN: return "ارسال برگشت از فروش";
                case ACT_CANCEL: return "ارسال ابطالی";
                default: return "بدون ارسال";
            }
        }
    }

    // ------------------------------------------------------------------ time

    /** Issue time of an Atiran document (Tehran wall clock); never in the future. */
    static long docMillis(String date, String time, long now) {
        long t = MeelanoTaxInvoice.tehranMillis(date, time == null || time.trim().isEmpty() ? "12:00" : time);
        if (t <= 0) return -1;
        if (t > now - 60_000L) t = now - 60_000L;
        return t;
    }

    /** created_at (server local time, Tehran) "yyyy-MM-dd HH:mm:ss" → millis; 0 if unknown. */
    static long serverMillis(String createdAt) {
        if (createdAt == null) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Tehran"));
            return f.parse(createdAt.trim()).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ chain helpers

    static MeelanoTaxDb.Log latestAccepted(List<MeelanoTaxDb.Log> chain) {
        MeelanoTaxDb.Log out = null;
        for (MeelanoTaxDb.Log l : chain) if (l.accepted() && l.taxid != null) out = l;
        return out;
    }

    static MeelanoTaxDb.Log lastAcceptedSaleVersion(List<MeelanoTaxDb.Log> chain) {
        MeelanoTaxDb.Log out = null;
        for (MeelanoTaxDb.Log l : chain) {
            if (!l.accepted() || l.taxid == null || !MeelanoTaxDb.SALE.equals(l.docKind)) continue;
            if (l.ins == MeelanoTaxInvoice.INS_ORIGINAL || l.ins == MeelanoTaxInvoice.INS_CORRECTION) out = l;
        }
        return out;
    }

    static MeelanoTaxDb.Log pendingIn(List<MeelanoTaxDb.Log> chain) {
        for (MeelanoTaxDb.Log l : chain) if (l.pending()) return l;
        return null;
    }

    /** Short Persian state of a document for the lists, and a colour key: ok / wait / err / warn / idle / off. */
    static String[] state(MeelanoTaxDb.Doc d, int deadlineDays, long now) {
        List<MeelanoTaxDb.Log> chain = d.chain;
        MeelanoTaxDb.Log pending = pendingIn(MeelanoTaxDb.SALE.equals(d.kind) ? chain : ownRows(d));
        if (pending != null) return new String[]{"در انتظار نتیجه", "wait"};
        if (MeelanoTaxDb.BACK.equals(d.kind)) {
            MeelanoTaxDb.Log a = latestAccepted(chain);
            if (a != null) return new String[]{a.status.equals("EXTERNAL") ? "ثبت‌شده (خارج از برنامه)" : "ثبت شد — " + MeelanoTaxInvoice.insTitle(a.ins), "ok"};
            if (d.atiranTaxId != null) return new String[]{"ارسال‌شده از آتیران", "ok"};
            if (d.last != null && "FAILED".equals(d.last.status)) return new String[]{"رد شده — نیاز به بررسی", "err"};
            if (d.last != null && ("LOCAL_ERROR".equals(d.last.status) || "TIMEOUT".equals(d.last.status) || "NOT_FOUND".equals(d.last.status))) return new String[]{"ناموفق — دوباره بفرستید", "err"};
            return new String[]{"ارسال نشده", "idle"};
        }
        MeelanoTaxDb.Log acc = latestAccepted(chain);
        if (acc == null) {
            if (d.atiranTaxId != null) return new String[]{"ارسال‌شده از آتیران", "ok"};
            if (!d.active) return new String[]{"باطل در آتیران", "off"};
            if (d.last != null && "FAILED".equals(d.last.status)) return new String[]{"رد شده — نیاز به بررسی", "err"};
            if (d.last != null && ("LOCAL_ERROR".equals(d.last.status) || "TIMEOUT".equals(d.last.status) || "NOT_FOUND".equals(d.last.status))) return new String[]{"ناموفق — دوباره بفرستید", "err"};
            long t = docMillis(d.date, d.time, now);
            if (t > 0 && MeelanoTaxInvoice.late(t, now, deadlineDays)) return new String[]{"ارسال نشده — خارج از مهلت", "warn"};
            if (t > 0 && deadlineDays > 0 && now - t > (deadlineDays - 3) * 86_400_000L) return new String[]{"ارسال نشده — نزدیک مهلت", "warn"};
            return new String[]{"ارسال نشده", "idle"};
        }
        if (acc.ins == MeelanoTaxInvoice.INS_CANCEL) return new String[]{"ابطال شده", "off"};
        if (!d.active) return new String[]{"باطل در آتیران — نیاز به ابطال", "warn"};
        MeelanoTaxDb.Log v = lastAcceptedSaleVersion(chain);
        if (v != null && v.rdf != d.rdf && !"EXTERNAL".equals(v.status)) return new String[]{"ویرایش شده — نیاز به اصلاحی", "warn"};
        if (acc.ins == MeelanoTaxInvoice.INS_RETURN) return new String[]{"ثبت شد — دارای برگشتی", "ok"};
        if (acc.ins == MeelanoTaxInvoice.INS_CORRECTION) return new String[]{"ثبت شد — اصلاح شده", "ok"};
        return new String[]{acc.status.equals("EXTERNAL") ? "ثبت‌شده (خارج از برنامه)" : "ثبت شد در سامانه", "ok"};
    }

    private static List<MeelanoTaxDb.Log> ownRows(MeelanoTaxDb.Doc d) {
        List<MeelanoTaxDb.Log> out = new ArrayList<>();
        for (MeelanoTaxDb.Log l : d.chain) if (l.docKind != null && l.docKind.equals(d.kind) && l.docNo == d.no && l.backKind == d.backKind) out.add(l);
        return out;
    }

    // ------------------------------------------------------------------ preparing sales

    static String previewTaxId(Config cfg, long indatim, long serial) {
        String mem = cfg.memoryId.matches("[A-Z0-9]{6}") ? cfg.memoryId : "AAAAAA";
        return MeelanoTaxCrypto.taxId(mem, Math.max(1, serial), indatim);
    }

    private static MeelanoTaxInvoice.Source saleSource(Config cfg, MeelanoTaxDb.Doc d, List<MeelanoTaxInvoice.Line> lines, long now) {
        MeelanoTaxInvoice.Source s = new MeelanoTaxInvoice.Source();
        s.tins = cfg.tins;
        s.sbc = cfg.sbc;
        s.now = now;
        s.deadlineDays = cfg.deadlineDays;
        s.indatim = docMillis(d.date, d.time, now);
        MeelanoTaxInvoice.Buyer b = new MeelanoTaxInvoice.Buyer();
        b.tob = d.tob;
        b.bid = d.bid;
        b.tinb = d.tinb;
        b.bpc = d.bpc;
        b.bbc = d.bbc;
        b.name = d.name;
        s.buyer = b;
        boolean ids = MeelanoTaxInvoice.digits(d.tinb) != null || (MeelanoTaxInvoice.digits(d.bid) != null && MeelanoTaxInvoice.digits(d.bpc) != null);
        if (d.custInty != null && (d.custInty == 1 || d.custInty == 2)) s.inty = d.custInty;
        else s.inty = ids || !cfg.typeTwoWhenNoIds ? 1 : 2;
        if (s.inty == 1 && b.tob == null && MeelanoTaxInvoice.digits(d.tinb) != null) b.tob = MeelanoTaxInvoice.digits(d.tinb).length() == 11 ? 2 : 1;
        s.setm = cfg.defaultSetm;
        s.lines = lines;
        long extra = d.tafif - d.lineDiscounts;
        s.headerDiscount = extra > 0 ? BigDecimal.valueOf(extra) : BigDecimal.ZERO;
        return s;
    }

    private static void totalsCheck(Prepared p, long atiranTotal) {
        if (p.result == null || atiranTotal <= 0) return;
        long diff = Math.abs(atiranTotal - p.result.tbill);
        if (diff > Math.max(2, p.result.lines.size())) {
            p.result.issues.add(new MeelanoTaxInvoice.Issue(false, "tbill", 0, "جمع نهایی آتیران " + MeelanoTaxInvoice.money(atiranTotal) + " ریال است ولی صورتحساب مودیان "
                    + MeelanoTaxInvoice.money(p.result.tbill) + " ریال محاسبه شد (اختلاف " + MeelanoTaxInvoice.money(diff) + ")."));
        }
    }

    /** Decides and builds the submission a sales invoice needs now. {@code forceCancel} = the user asked for «ابطال». */
    static Prepared prepareSale(Connection c, Config cfg, MeelanoTaxDb.Doc d, Map<String, String> unitMap, boolean forceCancel) throws Exception {
        long now = System.currentTimeMillis();
        Prepared p = new Prepared();
        p.doc = d;
        p.chainSale = d.no;
        p.rdf = d.rdf;
        List<MeelanoTaxDb.Log> chain = d.chain;
        MeelanoTaxDb.Log pending = pendingIn(chain);
        if (pending != null) {
            p.message = "ارسال قبلی این فاکتور (" + MeelanoTaxInvoice.insTitle(pending.ins) + ") هنوز نتیجه نگرفته است؛ ابتدا «استعلام نتیجه» را بزنید.";
            return p;
        }
        long serialPreview = MeelanoTaxDb.lastSerial(c, cfg.memoryId) + 1;
        MeelanoTaxDb.Log acc = latestAccepted(chain);
        if (acc == null) {
            if (forceCancel) { p.message = "این فاکتور هنوز در سامانه ثبت نشده است و ابطال لازم ندارد."; return p; }
            if (d.atiranTaxId != null) {
                p.message = "این فاکتور قبلاً از خود آتیران به سامانه ارسال شده است (شماره مالیاتی " + d.atiranTaxId + "). با «ثبت در سوابق» آن را به برنامه اضافه کنید تا برگشتی و ابطال آن از همین‌جا انجام شود.";
                return p;
            }
            if (!d.active) { p.message = "این فاکتور در آتیران باطل شده و نیازی به ارسال ندارد."; return p; }
            List<MeelanoTaxInvoice.Line> lines = MeelanoTaxDb.saleLines(c, d.no, d.rdf, unitMap);
            p.action = ACT_ORIGINAL;
            p.source = saleSource(cfg, d, lines, now);
            p.contentHash = MeelanoTaxInvoice.contentHash(p.source);
            p.result = MeelanoTaxInvoice.build(p.source, previewTaxId(cfg, p.source.indatim, serialPreview), MeelanoTaxCrypto.inno(serialPreview));
            totalsCheck(p, d.total);
            return p;
        }
        if (acc.ins == MeelanoTaxInvoice.INS_CANCEL) {
            MeelanoTaxDb.Log v = lastAcceptedSaleVersion(chain);
            if (!forceCancel && d.active && v != null && v.rdf != d.rdf) {
                // Cancelled and edited afterwards: the new revision is issued as a fresh original.
                List<MeelanoTaxInvoice.Line> lines = MeelanoTaxDb.saleLines(c, d.no, d.rdf, unitMap);
                p.action = ACT_ORIGINAL;
                p.source = saleSource(cfg, d, lines, now);
                p.source.indatim = now - 60_000L;
                p.contentHash = MeelanoTaxInvoice.contentHash(p.source);
                p.result = MeelanoTaxInvoice.build(p.source, previewTaxId(cfg, p.source.indatim, serialPreview), MeelanoTaxCrypto.inno(serialPreview));
                p.notes.add("صورتحساب قبلی ابطال شده بود؛ نسخه ویرایش‌شده به‌عنوان صورتحساب اصلی جدید فرستاده می‌شود.");
                totalsCheck(p, d.total);
                return p;
            }
            p.message = "این فاکتور در سامانه ابطال شده است.";
            return p;
        }
        if (forceCancel || !d.active) {
            return cancelOf(c, cfg, p, acc, now, serialPreview, forceCancel ? "به درخواست کاربر" : "فاکتور در آتیران باطل شده است");
        }
        MeelanoTaxDb.Log v = lastAcceptedSaleVersion(chain);
        if (v == null || v.rdf == d.rdf || "EXTERNAL".equals(v.status) && v.rdf == 0) {
            p.message = "این فاکتور در سامانه ثبت شده است" + (acc.taxid != null ? " (شماره مالیاتی " + acc.taxid + ")" : "") + ".";
            return p;
        }
        // Edited in Atiran after it was accepted → correction of the latest accepted version.
        List<MeelanoTaxInvoice.Line> lines = MeelanoTaxDb.saleLines(c, d.no, d.rdf, unitMap);
        Map<Integer, BigDecimal> returned = acceptedReturnQuantities(c, chain, unitMap);
        List<MeelanoTaxInvoice.Line> remaining = MeelanoTaxInvoice.remainingAfterReturns(lines, returned);
        p.source = saleSource(cfg, d, remaining, now);
        p.source.indatim = Math.max(now - 60_000L, acc.indatim);
        p.source.ins = MeelanoTaxInvoice.INS_CORRECTION;
        p.source.irtaxid = acc.taxid;
        p.source.referenceIndatim = acc.indatim;
        p.source.inty = acc.inty > 0 ? acc.inty : p.source.inty;
        p.irtaxid = acc.taxid;
        p.referenceIndatim = acc.indatim;
        p.contentHash = MeelanoTaxInvoice.contentHash(p.source);
        String blocker = v.payload == null ? null : MeelanoTaxInvoice.correctionBlocker(v.payload, p.source);
        if (blocker != null) {
            p.message = "اصلاح این فاکتور مجاز نیست: " + blocker + " در این حالت باید صورتحساب قبلی را «ابطال» کنید؛ سپس نسخه جدید به‌عنوان صورتحساب اصلی فرستاده می‌شود.";
            return p;
        }
        p.action = ACT_CORRECTION;
        p.result = MeelanoTaxInvoice.build(p.source, previewTaxId(cfg, p.source.indatim, serialPreview), MeelanoTaxCrypto.inno(serialPreview));
        p.notes.add("فاکتور بعد از ثبت در سامانه در آتیران ویرایش شده است؛ صورتحساب اصلاحی به مرجع " + acc.taxid + " فرستاده می‌شود.");
        return p;
    }

    /** Sum of the items of all accepted returns (ins 4) in a chain, read from their Atiran vouchers. */
    static Map<Integer, BigDecimal> acceptedReturnQuantities(Connection c, List<MeelanoTaxDb.Log> chain, Map<String, String> unitMap) throws Exception {
        Map<Integer, BigDecimal> out = new HashMap<>();
        for (MeelanoTaxDb.Log l : chain) {
            if (!l.accepted() || !MeelanoTaxDb.BACK.equals(l.docKind) || l.ins != MeelanoTaxInvoice.INS_RETURN) continue;
            addQuantities(out, MeelanoTaxDb.backLines(c, l.backKind, l.docNo, unitMap));
        }
        return out;
    }

    static void addQuantities(Map<Integer, BigDecimal> into, List<MeelanoTaxInvoice.Line> lines) {
        for (MeelanoTaxInvoice.Line l : lines) {
            if (l.qty == null || l.qty.signum() <= 0) continue;
            into.put(l.shka, into.getOrDefault(l.shka, BigDecimal.ZERO).add(l.qty));
        }
    }

    private static Prepared cancelOf(Connection c, Config cfg, Prepared p, MeelanoTaxDb.Log acc, long now, long serialPreview, String reason) throws Exception {
        long indatim = Math.max(now - 60_000L, acc.indatim);
        if (p.doc != null && MeelanoTaxDb.BACK.equals(p.doc.kind)) {
            long t = docMillis(p.doc.date, p.doc.time, now);
            if (t >= acc.indatim) indatim = t;
        }
        p.action = ACT_CANCEL;
        p.irtaxid = acc.taxid;
        p.referenceIndatim = acc.indatim;
        String prev = acc.payload;
        if (prev == null) {
            // The accepted version was sent outside this app: rebuild its content from Atiran.
            MeelanoTaxDb.Doc sale = MeelanoTaxDb.sale(c, p.chainSale);
            if (sale == null) { p.action = ACT_NONE; p.message = "فاکتور فروش مرجع در آتیران پیدا نشد."; return p; }
            List<MeelanoTaxInvoice.Line> lines = MeelanoTaxDb.saleLines(c, sale.no, sale.rdf, MeelanoTaxDb.unitMap(c));
            MeelanoTaxInvoice.Source s = saleSource(cfg, sale, lines, now);
            s.ins = MeelanoTaxInvoice.INS_CANCEL;
            s.irtaxid = acc.taxid;
            s.referenceIndatim = acc.indatim;
            s.indatim = indatim;
            if (acc.inty > 0) s.inty = acc.inty;
            p.source = s;
            p.result = MeelanoTaxInvoice.build(s, previewTaxId(cfg, indatim, serialPreview), MeelanoTaxCrypto.inno(serialPreview));
        } else {
            p.cancelFromJson = prev;
            p.result = MeelanoTaxInvoice.cancelFrom(prev, previewTaxId(cfg, indatim, serialPreview), MeelanoTaxCrypto.inno(serialPreview), indatim, now, cfg.deadlineDays, acc.taxid, acc.indatim);
            MeelanoTaxInvoice.Source s = new MeelanoTaxInvoice.Source();
            s.ins = MeelanoTaxInvoice.INS_CANCEL;
            s.indatim = indatim;
            s.now = now;
            s.irtaxid = acc.taxid;
            s.referenceIndatim = acc.indatim;
            s.inty = acc.inty;
            p.source = s;
        }
        p.notes.add("ابطال صورتحساب " + acc.taxid + " (" + reason + ").");
        return p;
    }

    // ------------------------------------------------------------------ preparing returns / cancel vouchers

    /**
     * Decides and builds the submission of an Atiran return/cancel voucher.
     *
     * @param manualIrtaxid tax id of the original invoice, needed for the previous-year kinds (2, 8) and when the
     *                      original is not known to this app
     * @param manualSale    original sales invoice number when the voucher does not carry it (kind 6)
     * @param fullReturn    the user confirmed that a previous-year return covers the whole original invoice
     */
    static Prepared prepareBack(Connection c, Config cfg, MeelanoTaxDb.Doc d, Map<String, String> unitMap, String manualIrtaxid, int manualSale, boolean fullReturn) throws Exception {
        long now = System.currentTimeMillis();
        Prepared p = new Prepared();
        p.doc = d;
        p.rdf = 0;
        List<MeelanoTaxDb.Log> own = ownRows(d);
        MeelanoTaxDb.Log ownPending = pendingIn(own);
        if (ownPending != null) { p.message = "ارسال قبلی این سند هنوز نتیجه نگرفته است؛ ابتدا «استعلام نتیجه» را بزنید."; return p; }
        MeelanoTaxDb.Log ownAcc = latestAccepted(own);
        if (ownAcc != null) { p.message = "این سند در سامانه ثبت شده است (شماره مالیاتی " + ownAcc.taxid + ")."; return p; }
        if (d.atiranTaxId != null) {
            p.message = "این سند قبلاً از خود آتیران ارسال شده است (شماره مالیاتی " + d.atiranTaxId + "). با «ثبت در سوابق» آن را به برنامه اضافه کنید.";
            return p;
        }
        long serialPreview = MeelanoTaxDb.lastSerial(c, cfg.memoryId) + 1;

        // Which sales chain does the voucher belong to?
        int sale = d.refSale > 0 ? d.refSale : manualSale;
        List<MeelanoTaxDb.Log> chain = null;
        String irManual = manualIrtaxid == null ? "" : manualIrtaxid.trim().toUpperCase(Locale.US);
        if (!irManual.isEmpty()) {
            if (!MeelanoTaxCrypto.taxIdValid(irManual)) { p.message = "شماره مالیاتی مرجع واردشده معتبر نیست (۲۲ کاراکتر با رقم کنترل)."; return p; }
            MeelanoTaxDb.Log ref = MeelanoTaxDb.logByTaxid(c, irManual);
            if (ref != null && ref.chainSale > 0) sale = ref.chainSale;
        }
        if (d.backKind == 2 || d.backKind == 8) {
            // Previous fiscal year: the original is usually not in this database.
            if (irManual.isEmpty() && sale <= 0) { p.message = "برای سند سنوات قبل، شماره مالیاتی (۲۲ کاراکتری) صورتحساب اصلی را وارد کنید."; return p; }
        }
        if (sale > 0) {
            chain = MeelanoTaxDb.chain(c, sale);
            p.chainSale = sale;
        }
        MeelanoTaxDb.Log acc = chain == null ? null : latestAccepted(chain);
        if (chain != null && pendingIn(chain) != null) { p.message = "ارسال دیگری از فاکتور فروش " + sale + " هنوز نتیجه نگرفته است؛ ابتدا «استعلام نتیجه» را بزنید."; return p; }

        if (acc == null && !irManual.isEmpty()) {
            // Original unknown to this app: only a whole cancel can be built (from the voucher's own items).
            if (d.backKind == 8 || d.backKind == 9 || fullReturn) {
                List<MeelanoTaxInvoice.Line> lines = MeelanoTaxDb.backLines(c, d.backKind, d.no, unitMap);
                MeelanoTaxInvoice.Source s = new MeelanoTaxInvoice.Source();
                s.tins = cfg.tins;
                s.sbc = cfg.sbc;
                s.now = now;
                s.deadlineDays = cfg.deadlineDays;
                s.indatim = docMillis(d.date, d.time, now);
                s.ins = MeelanoTaxInvoice.INS_CANCEL;
                s.irtaxid = irManual;
                s.inty = 1;
                s.lines = lines;
                p.source = s;
                p.action = ACT_CANCEL;
                p.irtaxid = irManual;
                p.result = MeelanoTaxInvoice.build(s, previewTaxId(cfg, s.indatim, serialPreview), MeelanoTaxCrypto.inno(serialPreview));
                p.notes.add("صورتحساب اصلی در این برنامه ثبت نشده است؛ ابطالی با اقلام همین سند ساخته شد. نوع صورتحساب باید با اصل یکی باشد (پیش‌فرض نوع اول).");
                return p;
            }
            p.message = "صورتحساب اصلی در این برنامه ثبت نشده و اقلام آن در دسترس نیست. اگر همه اقلام برگشت خورده، گزینه «برگشت کامل است» را بزنید تا ابطالی ارسال شود؛ در غیر این صورت برگشتی جزئی را در کارپوشه ثبت و اینجا «ثبت دستی» کنید.";
            return p;
        }
        if (acc == null) {
            if (sale <= 0) { p.message = "فاکتور فروش مرجع این سند مشخص نیست؛ شماره فاکتور فروش را انتخاب کنید."; return p; }
            p.message = d.backKind == 9 || d.backKind == 8
                    ? "فاکتور فروش " + sale + " در سامانه ثبت نشده است؛ ابطال آن لازم نیست (کافی است اصل فاکتور ارسال نشود)."
                    : "فاکتور فروش مرجع (شماره " + sale + ") هنوز در سامانه ثبت نشده است؛ ابتدا آن را ارسال کنید.";
            return p;
        }
        if (acc.ins == MeelanoTaxInvoice.INS_CANCEL) { p.message = "فاکتور فروش مرجع (شماره " + sale + ") در سامانه ابطال شده است؛ ارسال این سند لازم نیست."; return p; }

        if (d.backKind == 9 || d.backKind == 8) return cancelOf(c, cfg, p, acc, now, serialPreview, "سند «" + MeelanoTaxDb.backKindTitle(d.backKind) + "» شماره " + d.no);

        if (d.backKind == 1 && sale > 0) {
            List<Integer> earlier = MeelanoTaxDb.unsentEarlierReturns(c, sale, d.no);
            if (!earlier.isEmpty()) { p.message = "ابتدا برگشتی‌های قبلی همین فاکتور را ارسال کنید (سند شماره " + joinInts(earlier) + ")."; return p; }
        }
        MeelanoTaxDb.Log version = lastAcceptedSaleVersion(chain);
        List<MeelanoTaxInvoice.Line> base;
        MeelanoTaxDb.Doc saleDoc = MeelanoTaxDb.sale(c, sale);
        if (saleDoc == null) { p.message = "فاکتور فروش مرجع (شماره " + sale + ") در آتیران پیدا نشد."; return p; }
        int baseRdf = version != null && version.rdf > 0 ? version.rdf : saleDoc.rdf;
        base = MeelanoTaxDb.saleLines(c, sale, baseRdf, unitMap);
        Map<Integer, BigDecimal> returned = acceptedReturnQuantities(c, chain, unitMap);
        List<MeelanoTaxInvoice.Line> mine = MeelanoTaxDb.backLines(c, d.backKind, d.no, unitMap);
        if (mine.isEmpty()) { p.message = "اقلام این سند برگشتی در آتیران پیدا نشد."; return p; }
        addQuantities(returned, mine);
        Map<Integer, BigDecimal> unmatched = MeelanoTaxInvoice.unmatchedReturns(base, returned);
        List<MeelanoTaxInvoice.Line> remaining = MeelanoTaxInvoice.remainingAfterReturns(base, returned);
        long docTime = docMillis(d.date, d.time, now);
        long indatim = docTime >= acc.indatim ? docTime : Math.max(now - 60_000L, acc.indatim);
        if (remaining.isEmpty()) {
            Prepared cp = cancelOf(c, cfg, p, acc, now, serialPreview, "همه اقلام فاکتور " + sale + " برگشت خورده است");
            cp.notes.add("طبق دستورالعمل، برگشت همه اقلام به‌صورت «ابطالی» ارسال می‌شود.");
            return cp;
        }
        MeelanoTaxInvoice.Source s = saleSource(cfg, saleDoc, remaining, now);
        s.ins = MeelanoTaxInvoice.INS_RETURN;
        s.irtaxid = acc.taxid;
        s.referenceIndatim = acc.indatim;
        s.indatim = indatim;
        if (acc.inty > 0) s.inty = acc.inty;
        // The invoice-level discount shrinks with the remaining amount.
        BigDecimal baseAmount = BigDecimal.ZERO, remAmount = BigDecimal.ZERO;
        for (MeelanoTaxInvoice.Line l : base) baseAmount = baseAmount.add(l.qty.multiply(l.fee));
        for (MeelanoTaxInvoice.Line l : remaining) remAmount = remAmount.add(l.qty.multiply(l.fee));
        if (s.headerDiscount.signum() > 0 && baseAmount.signum() > 0)
            s.headerDiscount = s.headerDiscount.multiply(remAmount).divide(baseAmount, 0, RoundingMode.DOWN);
        p.source = s;
        p.action = ACT_RETURN;
        p.irtaxid = acc.taxid;
        p.referenceIndatim = acc.indatim;
        p.contentHash = MeelanoTaxInvoice.contentHash(s);
        p.result = MeelanoTaxInvoice.build(s, previewTaxId(cfg, indatim, serialPreview), MeelanoTaxCrypto.inno(serialPreview));
        p.notes.add("طبق دستورالعمل، صورتحساب برگشتی شامل اقلام باقی‌مانده فاکتور " + sale + " پس از کسر برگشتی‌هاست (مرجع " + acc.taxid + ").");
        if (!unmatched.isEmpty()) {
            p.result.issues.add(new MeelanoTaxInvoice.Issue(false, "body", 0, "برخی اقلام برگشتی در فاکتور مرجع نبودند یا بیشتر از مقدار فروش برگشت خورده‌اند (کالای " + joinInts(new ArrayList<>(unmatched.keySet())) + ")."));
        }
        return p;
    }

    static String joinInts(List<Integer> l) {
        StringBuilder b = new StringBuilder();
        for (Integer i : l) { if (b.length() > 0) b.append("، "); b.append(i); }
        return b.toString();
    }

    // ------------------------------------------------------------------ sending

    static final class Outcome {
        int sent, failed, skipped;
        final List<String> lines = new ArrayList<>();
        final List<Long> ids = new ArrayList<>();
    }

    private static MeelanoTaxDb.Log logFor(Config cfg, Prepared p) {
        MeelanoTaxDb.Log l = new MeelanoTaxDb.Log();
        MeelanoTaxDb.Doc d = p.doc;
        l.docKind = d.kind;
        l.docNo = d.no;
        l.backKind = d.backKind;
        l.rdf = p.rdf;
        l.chainSale = p.chainSale;
        l.ins = p.ins();
        l.inty = p.source != null ? p.source.inty : 1;
        l.irtaxid = p.irtaxid;
        l.memoryId = cfg.memoryId;
        l.env = cfg.sandbox ? "SANDBOX" : "PRODUCTION";
        l.uid = MeelanoTaxApi.newUid();
        l.contentHash = p.contentHash;
        l.indatim = p.source != null ? p.source.indatim : System.currentTimeMillis() - 60_000L;
        l.tbill = p.result.tbill;
        l.tvam = p.result.tvam;
        l.customerShmo = d.shmo;
        l.customerName = d.name;
        l.docDate = d.date;
        l.user = cfg.userName;
        List<String> w = p.result.warningTexts();
        l.warnings = w.isEmpty() ? null : MeelanoTaxDb.clip(String.join("\n", w), 3900);
        return l;
    }

    /** Final invoice JSON for a reserved tax id. */
    private static MeelanoTaxInvoice.Result finalBuild(Config cfg, Prepared p, MeelanoTaxDb.Log l) {
        String inno = MeelanoTaxCrypto.inno(l.serial);
        if (p.cancelFromJson != null) {
            long now = System.currentTimeMillis();
            return MeelanoTaxInvoice.cancelFrom(p.cancelFromJson, l.taxid, inno, l.indatim, now, cfg.deadlineDays, p.irtaxid, p.referenceIndatim);
        }
        p.source.now = System.currentTimeMillis();
        return MeelanoTaxInvoice.build(p.source, l.taxid, inno);
    }

    /**
     * Reserves, builds and POSTs the prepared documents (skipping those with errors). Each document gets its own
     * tax id and uid before the network call, so a crash or timeout can always be resolved by inquiry-by-uid.
     */
    static Outcome send(Connection c, Config cfg, MeelanoTaxApi api, List<Prepared> list) throws Exception {
        Outcome o = new Outcome();
        Map<String, String> batch = new LinkedHashMap<>();
        Map<String, MeelanoTaxDb.Log> byUid = new HashMap<>();
        Map<String, Prepared> prepByUid = new HashMap<>();
        for (Prepared p : list) {
            if (!p.sendable()) {
                o.skipped++;
                o.lines.add(p.doc.title() + ": " + (ACT_NONE.equals(p.action) ? p.message : "دارای خطا — ارسال نشد"));
                continue;
            }
            MeelanoTaxDb.Log l = logFor(cfg, p);
            try {
                MeelanoTaxDb.reserve(c, l, cfg.serialSeed);
            } catch (IllegalStateException e) {
                o.skipped++;
                o.lines.add(p.doc.title() + ": " + e.getMessage());
                continue;
            }
            MeelanoTaxInvoice.Result r = finalBuild(cfg, p, l);
            if (r.hasErrors()) {
                MeelanoTaxDb.updateStatus(c, l.id, "LOCAL_ERROR", null, String.join("\n", r.errorTexts()), null);
                o.failed++;
                o.lines.add(p.doc.title() + ": " + r.errorTexts().get(0));
                continue;
            }
            MeelanoTaxDb.updatePayload(c, l.id, r.json, r.tbill, r.tvam);
            batch.put(l.uid, r.json);
            byUid.put(l.uid, l);
            prepByUid.put(l.uid, p);
            o.ids.add(l.id);
            if (batch.size() >= 50) { post(c, cfg, api, batch, byUid, prepByUid, o); batch.clear(); }
        }
        if (!batch.isEmpty()) post(c, cfg, api, batch, byUid, prepByUid, o);
        return o;
    }

    private static void post(Connection c, Config cfg, MeelanoTaxApi api, Map<String, String> batch, Map<String, MeelanoTaxDb.Log> byUid,
                             Map<String, Prepared> prep, Outcome o) throws Exception {
        List<MeelanoTaxApi.Sent> sent;
        try {
            sent = api.send(batch);
        } catch (MeelanoTaxApi.ApiException e) {
            boolean unknown = e.network || e.http >= 500;
            boolean local = !unknown && e.http == 0;
            for (String uid : batch.keySet()) {
                MeelanoTaxDb.Log l = byUid.get(uid);
                if (unknown) {
                    MeelanoTaxDb.updateStatus(c, l.id, "UNKNOWN", null, "وضعیت ارسال نامشخص است: " + e.persian(), null);
                } else if (local) {
                    MeelanoTaxDb.updateStatus(c, l.id, "LOCAL_ERROR", null, e.persian(), null);
                } else {
                    MeelanoTaxDb.updateStatus(c, l.id, "FAILED", null, e.persian(), null);
                    if (cfg.writeBack) safeWriteBack(c, l, false, e.persian(), cfg);
                }
                o.failed++;
            }
            o.lines.add((unknown ? "ارتباط با سامانه قطع شد؛ نتیجه با «استعلام» مشخص می‌شود: " : local ? "ارسال انجام نشد: " : "سامانه ارسال را نپذیرفت: ") + e.persian());
            return;
        } catch (Exception e) {
            // Signing/encryption failed before anything left the phone.
            String msg = "ساخت بسته امضاشده انجام نشد: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            for (String uid : batch.keySet()) {
                MeelanoTaxDb.updateStatus(c, byUid.get(uid).id, "LOCAL_ERROR", null, msg, null);
                o.failed++;
            }
            o.lines.add(msg);
            return;
        }
        Map<String, MeelanoTaxApi.Sent> got = new HashMap<>();
        for (MeelanoTaxApi.Sent s : sent) if (s.uid != null) got.put(s.uid, s);
        for (String uid : batch.keySet()) {
            MeelanoTaxDb.Log l = byUid.get(uid);
            MeelanoTaxApi.Sent s = got.get(uid);
            if (s != null && s.referenceNumber != null) {
                MeelanoTaxDb.updateStatus(c, l.id, "PENDING", s.referenceNumber, null, null);
                o.sent++;
                o.lines.add(prep.get(uid).doc.title() + ": فرستاده شد — شماره مالیاتی " + l.taxid);
            } else {
                MeelanoTaxDb.updateStatus(c, l.id, "UNKNOWN", null, "پاسخ سامانه برای این صورتحساب شماره پیگیری نداشت؛ با استعلام بررسی کنید.", null);
                o.failed++;
            }
        }
    }

    static void safeWriteBack(Connection c, MeelanoTaxDb.Log l, boolean accepted, String error, Config cfg) {
        try { MeelanoTaxDb.writeBack(c, l, accepted, error, cfg.userName, cfg.atiranUserId); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ inquiry

    static final class InquiryOutcome {
        int success, failed, waiting, notFound;
        final List<String> lines = new ArrayList<>();
    }

    /** Resolves open submissions by uid (windows of at most a week around the send time). */
    static InquiryOutcome inquire(Connection c, Config cfg, MeelanoTaxApi api, List<MeelanoTaxDb.Log> logs) throws Exception {
        InquiryOutcome out = new InquiryOutcome();
        long now = System.currentTimeMillis();
        Map<Long, List<MeelanoTaxDb.Log>> byDay = new LinkedHashMap<>();
        for (MeelanoTaxDb.Log l : logs) {
            if (l.uid == null || l.uid.isEmpty()) continue;
            long created = serverMillis(l.createdAt);
            if (created <= 0) created = now - 3_600_000L;
            long day = Math.floorDiv(created, 86_400_000L);
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(l);
        }
        for (Map.Entry<Long, List<MeelanoTaxDb.Log>> e : byDay.entrySet()) {
            long start = e.getKey() * 86_400_000L - 12 * 3_600_000L;
            long end = Math.min(now, start + 6 * 86_400_000L);
            List<MeelanoTaxDb.Log> group = e.getValue();
            for (int i = 0; i < group.size(); i += 100) {
                List<MeelanoTaxDb.Log> part = group.subList(i, Math.min(group.size(), i + 100));
                List<String> uids = new ArrayList<>();
                Map<String, MeelanoTaxDb.Log> byUid = new HashMap<>();
                for (MeelanoTaxDb.Log l : part) { uids.add(l.uid); byUid.put(l.uid, l); }
                List<MeelanoTaxApi.Inquiry> res = api.inquiryByUid(uids, start, end);
                Map<String, MeelanoTaxApi.Inquiry> got = new HashMap<>();
                for (MeelanoTaxApi.Inquiry q : res) if (q.uid != null) got.put(q.uid, q);
                for (MeelanoTaxDb.Log l : part) apply(c, cfg, l, got.get(l.uid), now, out);
            }
        }
        return out;
    }

    private static String issueText(List<String[]> list) {
        if (list.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (String[] e : list) {
            if (b.length() > 0) b.append('\n');
            b.append(e[1] == null || e[1].isEmpty() ? "خطا" : e[1]);
            if (e[0] != null && !e[0].isEmpty()) b.append(" (کد ").append(e[0]).append(')');
        }
        return MeelanoTaxDb.clip(b.toString(), 3900);
    }

    private static void apply(Connection c, Config cfg, MeelanoTaxDb.Log l, MeelanoTaxApi.Inquiry q, long now, InquiryOutcome out) throws Exception {
        String title = (MeelanoTaxDb.SALE.equals(l.docKind) ? "فاکتور " : "سند برگشتی ") + l.docNo;
        if (q == null || q.status == null || "NOT_FOUND".equals(q.status)) {
            long created = serverMillis(l.createdAt);
            boolean old = created > 0 && now - created > 86_400_000L;
            boolean unsure = "UNKNOWN".equals(l.status) || "SENDING".equals(l.status);
            if (old || unsure && created > 0 && now - created > 30 * 60_000L) {
                MeelanoTaxDb.updateStatus(c, l.id, "NOT_FOUND", null, "این صورتحساب در سامانه پیدا نشد (به سامانه نرسیده است)؛ می‌توانید دوباره بفرستید.", null);
                out.notFound++;
                out.lines.add(title + ": در سامانه پیدا نشد");
            } else {
                out.waiting++;
            }
            return;
        }
        String warnings = issueText(q.warnings);
        switch (q.status) {
            case "SUCCESS": {
                MeelanoTaxDb.updateStatus(c, l.id, "SUCCESS", q.referenceNumber, null, warnings);
                l.status = "SUCCESS";
                if (q.referenceNumber != null) l.reference = q.referenceNumber;
                if (cfg.writeBack) safeWriteBack(c, l, true, null, cfg);
                out.success++;
                out.lines.add(title + ": ثبت شد (" + l.taxid + ")");
                break;
            }
            case "FAILED": {
                String err = issueText(q.errors);
                MeelanoTaxDb.updateStatus(c, l.id, "FAILED", q.referenceNumber, err == null ? "رد شد" : err, warnings);
                if (cfg.writeBack) safeWriteBack(c, l, false, err, cfg);
                out.failed++;
                out.lines.add(title + ": رد شد — " + (err == null ? "" : err.split("\n")[0]));
                break;
            }
            case "TIMEOUT": {
                MeelanoTaxDb.updateStatus(c, l.id, "TIMEOUT", q.referenceNumber, "مهلت پردازش در سامانه تمام شد؛ دوباره بفرستید.", warnings);
                out.failed++;
                out.lines.add(title + ": پایان مهلت پردازش");
                break;
            }
            default: {
                if (!"IN_PROGRESS".equals(l.status)) MeelanoTaxDb.updateStatus(c, l.id, "IN_PROGRESS", q.referenceNumber, null, warnings);
                out.waiting++;
                break;
            }
        }
    }

    /** Applies results of a time-range inquiry to the matching open submissions (by uid). */
    static InquiryOutcome applyResults(Connection c, Config cfg, List<MeelanoTaxApi.Inquiry> res) throws Exception {
        InquiryOutcome out = new InquiryOutcome();
        Map<String, MeelanoTaxDb.Log> byUid = new HashMap<>();
        for (MeelanoTaxDb.Log l : MeelanoTaxDb.logs(c, "OPEN", null, 2000)) if (l.uid != null) byUid.put(l.uid, l);
        long now = System.currentTimeMillis();
        for (MeelanoTaxApi.Inquiry q : res) {
            MeelanoTaxDb.Log l = q.uid == null ? null : byUid.get(q.uid);
            if (l != null && q.status != null && !"NOT_FOUND".equals(q.status)) apply(c, cfg, l, q, now, out);
        }
        return out;
    }

    /** Buyer-side (کارپوشه) status of accepted invoices. Returns how many rows were updated. */
    static int refreshKartable(Connection c, MeelanoTaxApi api, List<MeelanoTaxDb.Log> logs) throws Exception {
        int n = 0;
        for (int i = 0; i < logs.size(); i += 100) {
            List<MeelanoTaxDb.Log> part = logs.subList(i, Math.min(logs.size(), i + 100));
            List<String> ids = new ArrayList<>();
            for (MeelanoTaxDb.Log l : part) if (l.taxid != null) ids.add(l.taxid);
            if (ids.isEmpty()) continue;
            Map<String, String> st = api.invoiceStatus(ids);
            for (MeelanoTaxDb.Log l : part) {
                String s = st.get(l.taxid);
                if (s == null || s.trim().isEmpty()) continue;
                MeelanoTaxDb.updateKartable(c, l.id, MeelanoTaxDb.clip(s.trim(), 60));
                n++;
            }
        }
        return n;
    }

    /** Records a document as already registered (sent from Atiran, the portal or another program). */
    static long markExternal(Connection c, Config cfg, MeelanoTaxDb.Doc d, String taxid, int ins, String irtaxid, String reference, String note) throws Exception {
        String t = taxid == null ? "" : taxid.trim().toUpperCase(Locale.US);
        if (!MeelanoTaxCrypto.taxIdValid(t)) throw new IllegalArgumentException("شماره مالیاتی واردشده معتبر نیست (۲۲ کاراکتر با رقم کنترل).");
        MeelanoTaxDb.Log existing = MeelanoTaxDb.logByTaxid(c, t);
        if (existing != null && existing.accepted()) throw new IllegalArgumentException("این شماره مالیاتی قبلاً برای " + (MeelanoTaxDb.SALE.equals(existing.docKind) ? "فاکتور " : "سند ") + existing.docNo + " ثبت شده است.");
        MeelanoTaxDb.Log l = new MeelanoTaxDb.Log();
        l.docKind = d.kind;
        l.docNo = d.no;
        l.backKind = d.backKind;
        l.rdf = MeelanoTaxDb.SALE.equals(d.kind) ? d.rdf : 0;
        l.chainSale = MeelanoTaxDb.SALE.equals(d.kind) ? d.no : d.refSale;
        l.ins = ins;
        l.inty = d.custInty != null ? d.custInty : 1;
        l.taxid = t;
        l.irtaxid = irtaxid == null || irtaxid.trim().isEmpty() ? null : irtaxid.trim().toUpperCase(Locale.US);
        l.memoryId = t.substring(0, 6);
        l.env = cfg.sandbox ? "SANDBOX" : "PRODUCTION";
        l.indatim = MeelanoTaxCrypto.taxIdDay(t) * 86_400_000L + 43_200_000L;
        l.tbill = d.total;
        l.tvam = d.tax;
        l.customerShmo = d.shmo;
        l.customerName = d.name;
        l.docDate = d.date;
        l.user = cfg.userName;
        l.note = MeelanoTaxDb.clip(note, 480);
        try { l.serial = Long.parseLong(t.substring(11, 21), 16); } catch (Exception ignored) { }
        long id = MeelanoTaxDb.insertLog(c, l, "EXTERNAL");
        if (reference != null && !reference.trim().isEmpty()) MeelanoTaxDb.updateStatus(c, id, "EXTERNAL", reference.trim(), null, null);
        if (t.substring(0, 6).equals(cfg.memoryId) && l.serial > 0) MeelanoTaxDb.bumpSerial(c, cfg.memoryId, l.serial);
        return id;
    }
}
