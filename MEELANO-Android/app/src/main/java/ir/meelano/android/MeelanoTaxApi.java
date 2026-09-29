package ir.meelano.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import javax.net.ssl.SSLException;

/**
 * Client of the Moadian v2 API ({@code /requestsmanager/api/v2/}) — RC_TICS.IS_v1.6.
 *
 * Every authenticated call fetches its own nonce and signs a fresh token (tokens are single use). Invoices are
 * signed (JWS) and then encrypted (JWE) to a server key from {@code server-information}.
 */
final class MeelanoTaxApi {
    static final String PRODUCTION = "https://tp.tax.gov.ir/requestsmanager";
    static final String SANDBOX = "https://sandboxrc.tax.gov.ir/requestsmanager";

    final String base;
    final String memoryId;
    private final PrivateKey key;
    private final byte[] certDer;
    int connectTimeoutMs = 20_000;
    int readTimeoutMs = 45_000;
    private ServerKey serverKey;

    static final class ServerKey {
        String id;
        PublicKey key;
        long fetchedAt;
    }

    MeelanoTaxApi(boolean sandbox, String memoryId, PrivateKey key, byte[] certDer) {
        this(sandbox ? SANDBOX : PRODUCTION, memoryId, key, certDer);
    }

    MeelanoTaxApi(String base, String memoryId, PrivateKey key, byte[] certDer) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.memoryId = memoryId == null ? "" : memoryId.trim().toUpperCase(Locale.US);
        this.key = key;
        this.certDer = certDer;
    }

    // ------------------------------------------------------------------ errors

    static final class ApiException extends Exception {
        final int http;
        final List<String[]> errors = new ArrayList<>();
        String requestTraceId;
        final boolean network;

        ApiException(int http, String message, boolean network) {
            super(message);
            this.http = http;
            this.network = network;
        }

        String firstCode() { return errors.isEmpty() ? "" : errors.get(0)[0]; }

        /** One Persian paragraph for the user. */
        String persian() {
            StringBuilder b = new StringBuilder();
            if (errors.isEmpty()) b.append(getMessage());
            for (String[] e : errors) {
                if (b.length() > 0) b.append('\n');
                b.append(persianError(e[0], e[1]));
            }
            return b.toString();
        }
    }

    /** Persian explanation of the authentication / request error codes of RC_TXPS.EC_V02. */
    static String persianError(String code, String message) {
        String m = message == null ? "" : message.trim();
        String hint;
        switch (code == null ? "" : code.trim()) {
            case "4101": hint = "ساختار توکن یا روش درخواست پذیرفته نشد."; break;
            case "4102": hint = "چالش امنیتی منقضی یا تکراری بود؛ دوباره تلاش کنید (ساعت گوشی را هم بررسی کنید)."; break;
            case "4103": hint = "کد/شناسه ملی داخل گواهی امضا با صاحب شناسه یکتای حافظه یکسان نیست. گواهی درست را وارد کنید."; break;
            case "4110": hint = "شناسه یکتای حافظه مالیاتی پیدا نشد یا غیرفعال است. شناسه را در تنظیمات بررسی کنید."; break;
            case "4120": hint = "شناسه شرکت معتمد پیدا نشد."; break;
            case "4130": hint = "ساختار توکن ارسالی صحیح نیست."; break;
            case "4131": hint = "امضا یا گواهی معتبر نیست: گواهی باید از مرکز صدور گواهی میانی معتبر گرفته شده و منقضی/باطل نشده باشد و با کلید خصوصی جفت باشد."; break;
            case "4132": hint = "کلید عمومی معتبری برای این شناسه یکتا ثبت نشده است؛ کلید عمومی را در کارپوشه بارگذاری کنید."; break;
            case "4133": hint = "امضای صورتحساب نامعتبر است."; break;
            case "4134": hint = "کدگذاری بسته UTF-8 نیست."; break;
            case "4135": hint = "گواهی امضا در بسته پیدا نشد."; break;
            case "4136": hint = "قالب گواهی امضا معتبر نیست."; break;
            case "4137": hint = "کلید عمومی ثبت‌شده برای این شناسه یکتا معتبر نیست؛ از کارپوشه کلید عمومی صحیح را دوباره بارگذاری کنید."; break;
            case "4140": hint = "ابتدای بازه استعلام باید قبل از انتهای آن باشد."; break;
            case "4141": hint = "در هر استعلام حداکثر ۱۰۰ مورد مجاز است."; break;
            case "4142": hint = "وضعیت استعلام نامعتبر است."; break;
            case "4143": hint = "در هر ارسال حداکثر ۱۰۰۰ صورتحساب مجاز است."; break;
            case "4144": hint = "بدنه درخواست خالی یا نامعتبر است."; break;
            case "4145": hint = "برخی فیلدهای ضروری بسته (payload / شناسه درخواست / شناسه حافظه) خالی است."; break;
            case "4146": hint = "زمان اعتبار چالش باید بین ۱۰ تا ۲۰۰ ثانیه باشد."; break;
            case "4148": hint = "شناسه یکتای حافظه باید ۶ حرف بزرگ انگلیسی یا عدد باشد."; break;
            case "4162": hint = "شناسه درخواست معتبر نیست."; break;
            case "4163": hint = "شناسه درخواست تکراری است."; break;
            case "4164": hint = "بازه زمانی استعلام حداکثر یک هفته است."; break;
            case "5119": case "5129": case "5139": hint = "خطای داخلی سامانه مودیان؛ کمی بعد دوباره تلاش کنید."; break;
            default: hint = "";
        }
        String c = code == null || code.isEmpty() ? "" : " (کد " + code + ")";
        if (hint.isEmpty()) return (m.isEmpty() ? "خطای سامانه مودیان" : m) + c;
        return hint + c + (m.isEmpty() || hint.contains(m) ? "" : "\n«" + m + "»");
    }

    static String statusTitle(String status) {
        if (status == null) return "نامشخص";
        switch (status) {
            case "SUCCESS": return "موفق — ثبت در کارپوشه";
            case "FAILED": return "رد شده";
            case "IN_PROGRESS": return "در صف بررسی";
            case "TIMEOUT": return "پایان مهلت پردازش";
            case "NOT_FOUND": return "پیدا نشد";
            case "PENDING": return "ارسال شده، منتظر نتیجه";
            case "APPROVED": return "تایید شده توسط خریدار";
            case "SYSTEMIC_APPROVED": return "تایید سیستمی";
            case "REJECTED": return "رد شده توسط خریدار";
            case "AWAITING_REACTION": return "در انتظار واکنش خریدار";
            case "NO_NEED_REACTION": return "بدون نیاز به واکنش";
            case "IMPOSSIBLE_REACTION": return "عدم امکان واکنش";
            case "CANCELED": return "باطل شده";
            default: return status;
        }
    }

    // ------------------------------------------------------------------ transport

    private Object request(String method, String endpoint, List<String[]> params, String body, boolean auth) throws ApiException {
        StringBuilder url = new StringBuilder(base).append("/api/v2/").append(endpoint);
        if (params != null && !params.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (String[] p : params) {
                if (!first) url.append('&');
                first = false;
                url.append(enc(p[0])).append('=').append(enc(p[1]));
            }
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url.toString()).openConnection();
            c.setConnectTimeout(connectTimeoutMs);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            if (auth) c.setRequestProperty("Authorization", bearer());
            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream o = c.getOutputStream()) { o.write(data); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : read(in);
            if (code >= 400) throw envelope(code, text);
            if (text.trim().isEmpty()) return null;
            try {
                return MeelanoTaxJson.parse(text);
            } catch (RuntimeException e) {
                throw new ApiException(code, "پاسخ سامانه قابل خواندن نبود: " + clip(text, 200), false);
            }
        } catch (ApiException e) {
            throw e;
        } catch (UnknownHostException e) {
            throw new ApiException(0, "نشانی سامانه مودیان پیدا نشد؛ اینترنت یا DNS را بررسی کنید.", true);
        } catch (SocketTimeoutException e) {
            throw new ApiException(0, "پاسخ سامانه مودیان به موقع نرسید؛ اگر VPN روشن است خاموش کنید و دوباره تلاش کنید.", true);
        } catch (SSLException e) {
            throw new ApiException(0, "اتصال امن با سامانه مودیان برقرار نشد (" + e.getClass().getSimpleName() + "). تاریخ و ساعت گوشی را بررسی کنید.", true);
        } catch (IOException e) {
            throw new ApiException(0, "اتصال به سامانه مودیان برقرار نشد: " + e.getMessage(), true);
        } catch (Exception e) {
            throw new ApiException(0, "خطا در آماده‌سازی درخواست: " + e.getMessage(), false);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static ApiException envelope(int code, String text) {
        ApiException ex = new ApiException(code, code == 401 ? "احراز هویت در سامانه مودیان پذیرفته نشد." : "سامانه مودیان درخواست را نپذیرفت (HTTP " + code + ").", false);
        try {
            Map<String, Object> m = MeelanoTaxJson.obj(MeelanoTaxJson.parse(text));
            if (m != null) {
                ex.requestTraceId = MeelanoTaxJson.str(m, "requestTraceId");
                List<Object> errs = MeelanoTaxJson.arr(m, "errors");
                if (errs != null) for (Object o : errs) {
                    Map<String, Object> e = MeelanoTaxJson.obj(o);
                    if (e != null) ex.errors.add(new String[]{nz(MeelanoTaxJson.str(e, "code")), nz(MeelanoTaxJson.str(e, "message"))});
                }
            }
        } catch (RuntimeException ignored) { }
        return ex;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return s; }
    }

    // ------------------------------------------------------------------ auth

    /** GET nonce (unauthenticated) → {"nonce","expDate"}. */
    Map<String, Object> nonce() throws ApiException {
        List<String[]> p = new ArrayList<>();
        p.add(new String[]{"timeToLive", "60"});
        Map<String, Object> m = MeelanoTaxJson.obj(request("GET", "nonce", p, null, false));
        if (m == null || MeelanoTaxJson.str(m, "nonce") == null) throw new ApiException(0, "چالش امنیتی از سامانه دریافت نشد.", false);
        return m;
    }

    String bearer() throws Exception {
        if (key == null || certDer == null) throw new ApiException(0, "کلید خصوصی یا گواهی امضا تنظیم نشده است.", false);
        String nonce = MeelanoTaxJson.str(nonce(), "nonce");
        String payload = MeelanoTaxCrypto.authPayload(nonce, memoryId);
        return "Bearer " + MeelanoTaxCrypto.signJws(key, certDer, payload.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ resources

    Map<String, Object> serverInformation() throws ApiException {
        return MeelanoTaxJson.obj(request("GET", "server-information", null, null, true));
    }

    ServerKey serverKey() throws Exception {
        if (serverKey != null && System.currentTimeMillis() - serverKey.fetchedAt < 3_600_000L) return serverKey;
        Map<String, Object> info = serverInformation();
        List<Object> keys = MeelanoTaxJson.arr(info, "publicKeys");
        if (keys == null || keys.isEmpty()) throw new ApiException(0, "کلید عمومی سامانه مودیان دریافت نشد.", false);
        Map<String, Object> k = MeelanoTaxJson.obj(keys.get(0));
        ServerKey s = new ServerKey();
        s.id = MeelanoTaxJson.str(k, "id");
        s.key = MeelanoTaxCrypto.publicKeyFromBase64(MeelanoTaxJson.str(k, "key"));
        s.fetchedAt = System.currentTimeMillis();
        serverKey = s;
        return s;
    }

    Map<String, Object> fiscalInformation() throws ApiException {
        List<String[]> p = new ArrayList<>();
        p.add(new String[]{"memoryId", memoryId});
        return MeelanoTaxJson.obj(request("GET", "fiscal-information", p, null, true));
    }

    Map<String, Object> taxpayer(String economicCode) throws ApiException {
        List<String[]> p = new ArrayList<>();
        p.add(new String[]{"economicCode", economicCode});
        return MeelanoTaxJson.obj(request("GET", "taxpayer", p, null, true));
    }

    /** One packet of POST invoice: the invoice JSON signed and encrypted. */
    static Map<String, Object> packet(String invoiceJson, PrivateKey key, byte[] certDer, ServerKey sk, String uid, String memoryId) throws Exception {
        String jws = MeelanoTaxCrypto.signJws(key, certDer, invoiceJson.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        String jwe = MeelanoTaxCrypto.encryptJwe(sk.key, sk.id, jws.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("requestTraceId", uid);
        header.put("fiscalId", memoryId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("payload", jwe);
        p.put("header", header);
        return p;
    }

    static String newUid() { return UUID.randomUUID().toString(); }

    /** Result of one submitted packet. */
    static final class Sent {
        String uid, referenceNumber, packetType, data;
    }

    /** POST invoice. {@code invoices} maps uid → invoice JSON (max 1000 per call). */
    List<Sent> send(Map<String, String> invoices) throws Exception {
        if (invoices.size() > 1000) throw new ApiException(0, "در هر ارسال حداکثر ۱۰۰۰ صورتحساب مجاز است.", false);
        ServerKey sk = serverKey();
        List<Object> packets = new ArrayList<>();
        for (Map.Entry<String, String> e : invoices.entrySet()) packets.add(packet(e.getValue(), key, certDer, sk, e.getKey(), memoryId));
        Map<String, Object> resp = MeelanoTaxJson.obj(request("POST", "invoice", null, MeelanoTaxJson.write(packets), true));
        List<Sent> out = new ArrayList<>();
        List<Object> result = MeelanoTaxJson.arr(resp, "result");
        if (result != null) for (Object o : result) {
            Map<String, Object> m = MeelanoTaxJson.obj(o);
            Sent s = new Sent();
            s.uid = MeelanoTaxJson.str(m, "uid");
            s.referenceNumber = MeelanoTaxJson.str(m, "referenceNumber");
            s.packetType = MeelanoTaxJson.str(m, "packetType");
            s.data = MeelanoTaxJson.str(m, "data");
            out.add(s);
        }
        return out;
    }

    /** Inquiry result of one packet. */
    static final class Inquiry {
        String uid, referenceNumber, status, taxId;
        final List<String[]> errors = new ArrayList<>();
        final List<String[]> warnings = new ArrayList<>();
        String raw;
    }

    static String queryTime(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT+03:30"));
        return f.format(new Date(millis)) + "000000+03:30";
    }

    List<Inquiry> inquiryByUid(List<String> uids, long startMillis, long endMillis) throws ApiException {
        List<String[]> p = new ArrayList<>();
        p.add(new String[]{"fiscalId", memoryId});
        for (String u : uids) p.add(new String[]{"uidList", u});
        if (startMillis > 0) p.add(new String[]{"start", queryTime(startMillis)});
        if (endMillis > 0) p.add(new String[]{"end", queryTime(endMillis)});
        return parseInquiry(request("GET", "inquiry-by-uid", p, null, true));
    }

    List<Inquiry> inquiryByReference(List<String> refs, long startMillis, long endMillis) throws ApiException {
        List<String[]> p = new ArrayList<>();
        for (String r : refs) p.add(new String[]{"referenceIds", r});
        if (startMillis > 0) p.add(new String[]{"start", queryTime(startMillis)});
        if (endMillis > 0) p.add(new String[]{"end", queryTime(endMillis)});
        return parseInquiry(request("GET", "inquiry-by-reference-id", p, null, true));
    }

    /** Inquiry by time range (max one week), optionally filtered by status. */
    List<Inquiry> inquiryByTime(long startMillis, long endMillis, String status, int page, int size) throws ApiException {
        List<String[]> p = new ArrayList<>();
        p.add(new String[]{"start", queryTime(startMillis)});
        if (endMillis > 0) p.add(new String[]{"end", queryTime(endMillis)});
        if (status != null && !status.isEmpty()) p.add(new String[]{"status", status});
        p.add(new String[]{"pageNumber", String.valueOf(page)});
        p.add(new String[]{"pageSize", String.valueOf(size)});
        return parseInquiry(request("GET", "inquiry", p, null, true));
    }

    /** Buyer-side status in the کارپوشه for accepted tax ids: taxId → invoiceStatus. */
    Map<String, String> invoiceStatus(List<String> taxIds) throws ApiException {
        List<String[]> p = new ArrayList<>();
        for (String t : taxIds) p.add(new String[]{"taxIds", t});
        Object o = request("GET", "inquiry-invoice-status", p, null, true);
        Map<String, String> out = new LinkedHashMap<>();
        List<Object> list = MeelanoTaxJson.arr(o);
        if (list == null) {
            Map<String, Object> m = MeelanoTaxJson.obj(o);
            list = MeelanoTaxJson.arr(m, "result");
            if (list == null) list = MeelanoTaxJson.arr(m, "data");
        }
        if (list != null) for (Object x : list) {
            Map<String, Object> m = MeelanoTaxJson.obj(x);
            String t = MeelanoTaxJson.str(m, "taxId");
            if (t != null) out.put(t.trim(), nz(MeelanoTaxJson.str(m, "invoiceStatus")) + (MeelanoTaxJson.str(m, "error") != null ? " | " + MeelanoTaxJson.str(m, "error") : ""));
        }
        return out;
    }

    /** POST invoice-payment for credit invoices; returns {"requestStatus","error"}. */
    Map<String, Object> registerPayment(String taxId, long paidAmount, long paymentDateMillis, String method, String terminal, String reference) throws ApiException {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("taxId", taxId);
        b.put("paidAmount", paidAmount);
        b.put("paymentDate", paymentDateMillis);
        b.put("paymentMethod", method);
        if (terminal != null && !terminal.isEmpty()) b.put("terminalNumber", terminal);
        if (reference != null && !reference.isEmpty()) b.put("referenceNumber", reference);
        Object o = request("POST", "invoice-payment", null, MeelanoTaxJson.write(b), true);
        Map<String, Object> m = MeelanoTaxJson.obj(o);
        if (m == null) {
            List<Object> l = MeelanoTaxJson.arr(o);
            if (l != null && !l.isEmpty()) m = MeelanoTaxJson.obj(l.get(0));
        }
        return m;
    }

    static List<Inquiry> parseInquiry(Object o) {
        List<Inquiry> out = new ArrayList<>();
        List<Object> list = MeelanoTaxJson.arr(o);
        if (list == null) {
            Map<String, Object> m = MeelanoTaxJson.obj(o);
            list = MeelanoTaxJson.arr(m, "result");
            if (list == null) list = MeelanoTaxJson.arr(m, "data");
        }
        if (list == null) return out;
        for (Object x : list) {
            Map<String, Object> m = MeelanoTaxJson.obj(x);
            if (m == null) continue;
            Inquiry q = new Inquiry();
            q.uid = MeelanoTaxJson.str(m, "uid");
            q.referenceNumber = MeelanoTaxJson.str(m, "referenceNumber");
            q.status = MeelanoTaxJson.str(m, "status");
            q.raw = MeelanoTaxJson.write(m);
            Object data = m.get("data");
            if (data instanceof String) {
                try { data = MeelanoTaxJson.parse((String) data); } catch (RuntimeException ignored) { }
            }
            Map<String, Object> d = MeelanoTaxJson.obj(data);
            if (d != null) {
                q.taxId = MeelanoTaxJson.str(d, "taxId");
                collect(MeelanoTaxJson.arr(d, "error"), q.errors);
                collect(MeelanoTaxJson.arr(d, "warning"), q.warnings);
            }
            out.add(q);
        }
        return out;
    }

    private static void collect(List<Object> src, List<String[]> dst) {
        if (src == null) return;
        for (Object o : src) {
            Map<String, Object> e = MeelanoTaxJson.obj(o);
            if (e == null) continue;
            dst.add(new String[]{nz(MeelanoTaxJson.str(e, "code")), nz(MeelanoTaxJson.str(e, "message")), nz(MeelanoTaxJson.str(e, "errorType"))});
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clip(String s, int n) { return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "…"); }
}
