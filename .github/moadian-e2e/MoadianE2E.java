package ir.meelano.android;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * End-to-end check of the Moadian app's data layer and send engine against a restored copy of the real Atiran
 * database (CI only, never the live server). The tax portal is replaced by {@link FakeApi}, which decrypts every
 * packet with its own server key, verifies the JWS signature and re-checks the invoice arithmetic.
 * Only aggregate numbers and generic messages are printed (no customer names).
 */
public class MoadianE2E {
    static final Map<String, Object> out = new LinkedHashMap<>();
    static final List<String> failures = new ArrayList<>();
    static final String MEM = "A1B2C3";

    interface Step { void run() throws Exception; }

    static void step(String name, Step s) {
        long t0 = System.currentTimeMillis();
        try {
            s.run();
            System.out.println("[ok] " + name + " (" + (System.currentTimeMillis() - t0) + " ms)");
        } catch (Throwable e) {
            StringBuilder b = new StringBuilder(name + ": " + e);
            for (StackTraceElement el : e.getStackTrace()) {
                if (el.getClassName().startsWith("ir.meelano")) {
                    b.append(" @").append(el.getClassName().replace("ir.meelano.android.", "")).append('.').append(el.getMethodName()).append(':').append(el.getLineNumber());
                    break;
                }
            }
            failures.add(b.toString());
            System.out.println("[FAIL] " + b);
            e.printStackTrace(System.out);
        }
    }

    static void check(boolean ok, String what) {
        if (!ok) { failures.add(what); System.out.println("[FAIL] " + what); }
    }

    static String generic(String s) {
        if (s == null) return "";
        return s.replaceAll("[0-9۰-۹]+", "#").replaceAll("«[^»]*»", "«…»").replaceAll("\\s+", " ").trim();
    }

    static List<String> generic(List<String> l) { return l.stream().map(x -> generic(x)).collect(Collectors.toList()); }

    static void inc(Map<String, Integer> m, String k) { m.merge(k, 1, Integer::sum); }

    static Map<String, Integer> top(Map<String, Integer> m, int n) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        Map<String, Integer> r = new LinkedHashMap<>();
        for (int i = 0; i < l.size() && i < n; i++) r.put(l.get(i).getKey(), l.get(i).getValue());
        return r;
    }

    // ------------------------------------------------------------------ fake portal

    static final class FakeApi extends MeelanoTaxApi {
        final KeyPair server;
        final PrivateKey myKey;
        final byte[] myDer;
        final PublicKey certPub;
        final Map<String, String> refs = new HashMap<>();
        final Set<String> fail = new HashSet<>();
        final Map<String, Integer> problems = new TreeMap<>();
        int packets;

        FakeApi(PrivateKey k, byte[] der, PublicKey certPub) throws Exception {
            super(true, MEM, k, der);
            this.server = MeelanoTaxCrypto.generateKeyPair();
            this.myKey = k;
            this.myDer = der;
            this.certPub = certPub;
        }

        @Override
        ServerKey serverKey() {
            ServerKey s = new ServerKey();
            s.id = "e2e-kid";
            s.key = server.getPublic();
            s.fetchedAt = System.currentTimeMillis();
            return s;
        }

        @Override
        List<Sent> send(Map<String, String> invoices) throws Exception {
            if (invoices.size() > 1000) inc(problems, "more than 1000 per send");
            List<Sent> res = new ArrayList<>();
            for (Map.Entry<String, String> e : invoices.entrySet()) {
                Map<String, Object> p = MeelanoTaxApi.packet(e.getValue(), myKey, myDer, serverKey(), e.getKey(), MEM);
                packets++;
                Map<String, Object> hdr = MeelanoTaxJson.obj(p, "header");
                if (!e.getKey().equals(MeelanoTaxJson.str(hdr, "requestTraceId")) || !MEM.equals(MeelanoTaxJson.str(hdr, "fiscalId"))) inc(problems, "packet header");
                String jwe = (String) p.get("payload");
                String[] jp = jwe.split("\\.");
                if (jp.length != 5) { inc(problems, "JWE parts"); continue; }
                String jwh = new String(MeelanoTaxCrypto.b64decode(jp[0]), StandardCharsets.UTF_8);
                if (!jwh.equals("{\"alg\":\"RSA-OAEP-256\",\"enc\":\"A256GCM\",\"kid\":\"e2e-kid\"}")) inc(problems, "JWE header " + jwh);
                String jws = new String(MeelanoTaxCrypto.decryptJwe(server.getPrivate(), jwe), StandardCharsets.UTF_8);
                if (!MeelanoTaxCrypto.verifyJws(certPub, jws)) inc(problems, "JWS signature");
                String[] sp = jws.split("\\.");
                String jsh = new String(MeelanoTaxCrypto.b64decode(sp[0]), StandardCharsets.UTF_8);
                for (String need : new String[]{"\"alg\":\"RS256\"", "\"x5c\"", "\"sigT\"", "\"crit\":[\"sigT\"]"})
                    if (!jsh.contains(need)) inc(problems, "JWS header lacks " + need);
                String payload = new String(MeelanoTaxCrypto.b64decode(sp[1]), StandardCharsets.UTF_8);
                if (!payload.equals(e.getValue())) inc(problems, "payload differs from invoice");
                validateInvoice(MeelanoTaxJson.obj(MeelanoTaxJson.parse(payload)), problems);
                Sent s = new Sent();
                s.uid = e.getKey();
                s.referenceNumber = UUID.randomUUID().toString();
                s.packetType = "INVOICE.V01";
                refs.put(s.uid, s.referenceNumber);
                res.add(s);
            }
            return res;
        }

        @Override
        List<Inquiry> inquiryByUid(List<String> uids, long startMillis, long endMillis) {
            if (uids.size() > 100) inc(problems, "inquiry > 100 uids");
            if (endMillis - startMillis > 7L * 86_400_000L) inc(problems, "inquiry range > 7 days");
            if (endMillis < startMillis) inc(problems, "inquiry start after end");
            List<Inquiry> r = new ArrayList<>();
            for (String u : uids) {
                if (!refs.containsKey(u)) continue;
                Inquiry q = new Inquiry();
                q.uid = u;
                q.referenceNumber = refs.get(u);
                if (fail.contains(u)) {
                    q.status = "FAILED";
                    q.errors.add(new String[]{"0100", "خطای آزمایشی سامانه"});
                } else q.status = "SUCCESS";
                r.add(q);
            }
            return r;
        }

        @Override
        Map<String, String> invoiceStatus(List<String> taxIds) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String t : taxIds) m.put(t, "APPROVED");
            return m;
        }
    }

    static long num(Map<String, Object> m, String k) { return MeelanoTaxJson.num(m, k, 0); }

    /** Re-checks the invoice as the portal would: tax id, date, totals = sum of lines, reference rules. */
    static void validateInvoice(Map<String, Object> inv, Map<String, Integer> problems) {
        Map<String, Object> h = MeelanoTaxJson.obj(inv, "header");
        List<Object> body = MeelanoTaxJson.arr(inv, "body");
        if (h == null || body == null || body.isEmpty()) { inc(problems, "no header/body"); return; }
        String taxid = MeelanoTaxJson.str(h, "taxid");
        long indatim = num(h, "indatim");
        if (taxid == null || !MeelanoTaxCrypto.taxIdValid(taxid)) inc(problems, "invalid taxid");
        else {
            if (!taxid.startsWith(MEM)) inc(problems, "taxid memory");
            if (MeelanoTaxCrypto.taxIdDay(taxid) != Math.floorDiv(indatim, 86_400_000L)) inc(problems, "taxid day != indatim day");
            String inno = MeelanoTaxJson.str(h, "inno");
            if (inno == null || !taxid.substring(11, 21).equals(inno)) inc(problems, "inno != taxid serial");
        }
        if (indatim <= 0 || indatim > System.currentTimeMillis() + 60_000L) inc(problems, "indatim");
        int ins = (int) num(h, "ins");
        int inty = (int) num(h, "inty");
        String irtaxid = MeelanoTaxJson.str(h, "irtaxid");
        if (ins == 1 && irtaxid != null) inc(problems, "original with irtaxid");
        if (ins >= 2 && (irtaxid == null || !MeelanoTaxCrypto.taxIdValid(irtaxid))) inc(problems, "missing/invalid irtaxid for ins " + ins);
        if (ins >= 2 && (h.containsKey("tob") || h.containsKey("bid") || h.containsKey("tinb"))) inc(problems, "buyer fields on ins " + ins);
        if (inty == 2 && (h.containsKey("setm") || h.containsKey("cap"))) inc(problems, "type 2 with setm/cap");
        String tins = MeelanoTaxJson.str(h, "tins");
        if (tins == null || !(tins.length() == 11 || tins.length() == 14)) inc(problems, "tins");
        long sPrdis = 0, sDis = 0, sAdis = 0, sVam = 0, sOdam = 0, sTsstam = 0;
        for (Object o : body) {
            Map<String, Object> l = MeelanoTaxJson.obj(o);
            String sstid = MeelanoTaxJson.str(l, "sstid");
            if (sstid == null || !sstid.matches("\\d{13}")) inc(problems, "line sstid");
            java.math.BigDecimal am = MeelanoTaxJson.dec(l, "am"), fee = MeelanoTaxJson.dec(l, "fee");
            if (am == null || am.signum() <= 0) inc(problems, "line am");
            if (fee == null || fee.signum() < 0) inc(problems, "line fee");
            long prdis = num(l, "prdis"), dis = num(l, "dis"), adis = num(l, "adis"), vam = num(l, "vam"), tsstam = num(l, "tsstam"), odam = num(l, "odam");
            if (am != null && fee != null && prdis != am.multiply(fee).setScale(0, java.math.RoundingMode.DOWN).longValueExact()) inc(problems, "prdis != trunc(am*fee)");
            if (adis != prdis - dis) inc(problems, "adis != prdis-dis");
            java.math.BigDecimal vra = MeelanoTaxJson.dec(l, "vra");
            if (vra != null && vam != java.math.BigDecimal.valueOf(adis).multiply(vra).movePointLeft(2).setScale(0, java.math.RoundingMode.DOWN).longValueExact()) inc(problems, "vam != trunc(adis*vra)");
            if (tsstam != adis + vam + odam) inc(problems, "tsstam != adis+vam+odam");
            if (dis < 0 || dis > prdis) inc(problems, "discount out of range");
            sPrdis += prdis; sDis += dis; sAdis += adis; sVam += vam; sOdam += odam; sTsstam += tsstam;
        }
        if (num(h, "tprdis") != sPrdis) inc(problems, "tprdis != sum");
        if (num(h, "tdis") != sDis) inc(problems, "tdis != sum");
        if (num(h, "tadis") != sAdis) inc(problems, "tadis != sum");
        if (num(h, "tvam") != sVam) inc(problems, "tvam != sum");
        if (num(h, "todam") != sOdam) inc(problems, "todam != sum");
        if (num(h, "tbill") != sTsstam) inc(problems, "tbill != sum(tsstam)");
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Class.forName("net.sourceforge.jtds.jdbc.Driver");
        Properties p = new Properties();
        p.setProperty("user", "sa");
        p.setProperty("password", System.getenv("SA_PASS"));
        p.setProperty("charset", "UTF-8");
        p.setProperty("sendStringParametersAsUnicode", "true");
        try (Connection c = DriverManager.getConnection("jdbc:jtds:sqlserver://127.0.0.1:1433/Atiran2;loginTimeout=30;socketTimeout=900;appName=MEELANOTaxE2E;", p)) {
            run(c, args[1], args[2]);
        } catch (Throwable t) {
            failures.add("fatal: " + t);
            t.printStackTrace(System.out);
        }
        out.put("failures", failures);
        try (FileOutputStream f = new FileOutputStream(args[0])) { f.write(MeelanoTaxJson.write(out).getBytes(StandardCharsets.UTF_8)); }
        for (Map.Entry<String, Object> e : out.entrySet()) System.out.println("== " + e.getKey() + ": " + MeelanoTaxJson.write(e.getValue()));
        System.out.println(failures.isEmpty() ? "ALL CHECKS PASSED" : failures.size() + " CHECK(S) FAILED");
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    static MeelanoTaxEngine.Config cfg;
    static Map<String, String> units = new HashMap<>();

    static void run(Connection c, String pfxPath, String pfxPass) throws Exception {
        long now = System.currentTimeMillis();
        MeelanoTaxCrypto.Pfx pfx = MeelanoTaxCrypto.importPfx(Files.readAllBytes(Paths.get(pfxPath)), pfxPass);
        X509Certificate cert = pfx.cert;
        check(MeelanoTaxCrypto.keyMatchesCertificate(pfx.key, cert), "PFX key/cert pair");
        out.put("certificate", MeelanoTaxCrypto.describeCertificate(cert));

        step("schema", () -> {
            MeelanoTaxDb.ensureSchema(c);
            MeelanoTaxDb.clearCache();
            MeelanoTaxDb.ensureSchema(c);
            out.put("missing_tables", MeelanoTaxDb.missingTables(c));
            out.put("server", MeelanoTaxDb.serverVersion(c));
            check(MeelanoTaxDb.missingTables(c).isEmpty(), "missing Atiran tables: " + MeelanoTaxDb.missingTables(c));
        });
        step("settings", () -> {
            MeelanoTaxDb.putSetting(c, "e2e_probe", "آزمون ۱۲۳ يك", "e2e");
            MeelanoTaxDb.putSetting(c, "e2e_probe", "آزمون ۱۲۳", "e2e");
            String back = MeelanoTaxDb.settings(c).get("e2e_probe");
            check("آزمون ۱۲۳".equals(back), "settings unicode round trip: " + back);
        });
        final MeelanoTaxDb.Company[] co = new MeelanoTaxDb.Company[1];
        step("company", () -> {
            co[0] = MeelanoTaxDb.company(c);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name_len", co[0].name == null ? 0 : co[0].name.length());
            m.put("economic_code_digits", MeelanoTaxDb.nz(MeelanoTaxInvoice.digits(co[0].economicCode)).length());
            m.put("national_id_digits", MeelanoTaxDb.nz(MeelanoTaxInvoice.digits(co[0].nationalId)).length());
            m.put("memory_id", co[0].memoryId);
            m.put("has_private_key", co[0].hasPrivateKey);
            out.put("company", m);
        });
        step("users", () -> {
            out.put("sys_users", MeelanoTaxDb.sysUsers(c).size());
            check(MeelanoTaxDb.login(c, "no-such-user-e2e", "x") == null, "login with unknown user must fail");
        });
        step("reference data", () -> {
            units = MeelanoTaxDb.unitMap(c);
            List<String> pu = MeelanoTaxDb.productUnits(c);
            int noCode = 0;
            for (String u : pu) if (MeelanoTaxInvoice.unitCode(u, units) == null) noCode++;
            List<MeelanoTaxDb.Good> goods = MeelanoTaxDb.goods(c, "", false, 5000);
            List<MeelanoTaxDb.Good> missing = MeelanoTaxDb.goods(c, "", true, 5000);
            List<MeelanoTaxDb.BuyerRow> buyers = MeelanoTaxDb.buyers(c, "", true, 5000);
            int withIds = 0;
            for (MeelanoTaxDb.BuyerRow b : buyers) if (MeelanoTaxInvoice.digits(b.tinb) != null || (MeelanoTaxInvoice.digits(b.bid) != null && MeelanoTaxInvoice.digits(b.bpc) != null)) withIds++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("product_units", pu.size());
            m.put("units_without_code", noCode);
            m.put("goods", goods.size());
            m.put("goods_without_sstid", missing.size());
            m.put("buyers_with_sales", buyers.size());
            m.put("buyers_type1_ready", withIds);
            m.put("goods_search_pesteh", MeelanoTaxDb.goods(c, "پسته", false, 500).size());
            out.put("reference", m);
            check(!goods.isEmpty(), "goods list empty");
        });

        Map<String, String> s = new HashMap<>();
        s.put("memory_id", MEM);
        String eco = co[0] == null ? null : MeelanoTaxInvoice.digits(co[0].economicCode);
        s.put("economic_code", eco != null && (eco.length() == 14 || eco.length() == 11) ? eco : "14000000000000");
        s.put("deadline_days", "12");
        s.put("write_back", "1");
        cfg = MeelanoTaxEngine.Config.from(s);
        cfg.key = pfx.key;
        cfg.certDer = cert.getEncoded();
        cfg.userName = "e2e";
        cfg.atiranUserId = 1;
        check(cfg.ready(), "config ready");
        FakeApi api = new FakeApi(pfx.key, cfg.certDer, cert.getPublicKey());

        step("sales + prepare (as imported)", () -> out.put("sales_before", prepareAll(c, MeelanoTaxDb.sales(c, "0000/00/00", "9999/99/99", "", 10000), now, false)));
        step("returns + prepare", () -> out.put("backs_before", prepareAll(c, MeelanoTaxDb.backs(c, "0000/00/00", "9999/99/99", "", 10000), now, true)));

        // Fill the base data on the throw-away copy so the invoices become sendable.
        step("fill sstid + buyer ids", () -> {
            int n = 0;
            boolean first = true;
            for (MeelanoTaxDb.Good g : MeelanoTaxDb.goods(c, "", true, 5000)) {
                g.sstid = "2720000000000";
                MeelanoTaxDb.saveGood(c, g, first, "e2e");
                first = false;
                n++;
            }
            out.put("goods_filled", n);
            int still = 0;
            for (MeelanoTaxDb.Good g : MeelanoTaxDb.goods(c, "", true, 5000)) if (g.soldLines > 0) still++;
            check(still == 0, still + " sold goods still without sstid after save");
            List<MeelanoTaxDb.BuyerRow> buyers = MeelanoTaxDb.buyers(c, "", true, 50);
            if (!buyers.isEmpty()) {
                MeelanoTaxDb.BuyerRow b = buyers.get(0);
                b.tob = 1; b.inty = 1; b.bid = "0012345679"; b.bpc = "1234567890"; b.tinb = null;
                MeelanoTaxDb.saveBuyer(c, b, true, "e2e");
                MeelanoTaxDb.BuyerRow again = null;
                for (MeelanoTaxDb.BuyerRow x : MeelanoTaxDb.buyers(c, String.valueOf(b.shmo), false, 5)) if (x.shmo == b.shmo) again = x;
                check(again != null && "0012345679".equals(again.bid) && Integer.valueOf(1).equals(again.tob), "buyer override not read back");
            }
        });

        final List<MeelanoTaxEngine.Prepared> toSend = new ArrayList<>();
        step("prepare after fill", () -> {
            List<MeelanoTaxDb.Doc> sales = MeelanoTaxDb.sales(c, "0000/00/00", "9999/99/99", "", 10000);
            out.put("sales_after_fill", prepareAll(c, sales, now, false));
            int type1 = 0;
            for (MeelanoTaxDb.Doc d : sales) {
                if (toSend.size() >= 60) break;
                MeelanoTaxEngine.Prepared p = MeelanoTaxEngine.prepareSale(c, cfg, d, units, false);
                if (p.sendable() && MeelanoTaxEngine.ACT_ORIGINAL.equals(p.action)) {
                    if (MeelanoTaxJson.num(MeelanoTaxJson.obj(p.result.invoice, "header"), "inty", 0) == 1) type1++;
                    toSend.add(p);
                }
            }
            out.put("picked_to_send", toSend.size());
            out.put("picked_type1", type1);
            check(toSend.size() >= 5, "fewer than 5 sendable invoices after filling base data");
        });

        step("send (fake portal, real signing/encryption)", () -> {
            MeelanoTaxEngine.Outcome o = MeelanoTaxEngine.send(c, cfg, api, toSend);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sent", o.sent); m.put("failed", o.failed); m.put("skipped", o.skipped); m.put("packets", api.packets);
            m.put("problems", api.problems);
            out.put("send", m);
            check(o.sent == toSend.size(), "sent " + o.sent + " of " + toSend.size() + ": " + generic(String.join(" | ", o.lines.subList(0, Math.min(3, o.lines.size())))));
            check(api.problems.isEmpty(), "packet problems: " + api.problems);
            int resend = 0;
            for (MeelanoTaxEngine.Prepared p : toSend) if (MeelanoTaxEngine.prepareSale(c, cfg, MeelanoTaxDb.sale(c, p.doc.no), units, false).sendable()) resend++;
            check(resend == 0, resend + " pending invoices were sendable again (double send)");
        });

        step("inquire (one rejected)", () -> {
            List<MeelanoTaxDb.Log> open = MeelanoTaxDb.openLogs(c, 1000);
            check(open.size() == toSend.size(), "open logs " + open.size() + " != sent " + toSend.size());
            if (!open.isEmpty()) api.fail.add(open.get(0).uid);
            MeelanoTaxEngine.InquiryOutcome r = MeelanoTaxEngine.inquire(c, cfg, api, open);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", r.success); m.put("failed", r.failed); m.put("waiting", r.waiting); m.put("not_found", r.notFound);
            m.put("status_counts", MeelanoTaxDb.statusCounts(c));
            out.put("inquire", m);
            check(r.failed == 1 && r.success == open.size() - 1, "inquiry outcome " + m);
        });

        step("write-back into Atiran tax columns", () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String col : new String[]{"TaxUniqueID", "UidTax", "TaxRefrenceNumber"}) {
                if (!MeelanoTaxDb.has(c, "sailfact", col)) { m.put("sailfact." + col, "absent"); continue; }
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dbo.sailfact WHERE " + col + " IS NOT NULL AND LEN(CAST(" + col + " AS nvarchar(100)))>0")) {
                    rs.next();
                    m.put("sailfact." + col, rs.getInt(1));
                }
            }
            if (MeelanoTaxDb.tableExists(c, "TaxSystemLog")) {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dbo.TaxSystemLog")) { rs.next(); m.put("TaxSystemLog rows", rs.getInt(1)); }
            }
            List<MeelanoTaxDb.Log> ok = MeelanoTaxDb.logs(c, "SUCCESS", null, 5);
            if (!ok.isEmpty()) MeelanoTaxDb.writeBack(c, ok.get(0), true, null, "e2e", 1); // direct call surfaces SQL errors
            out.put("write_back", m);
        });

        step("states after acceptance", () -> {
            Map<String, Integer> st = new TreeMap<>();
            for (MeelanoTaxEngine.Prepared p : toSend) inc(st, MeelanoTaxEngine.state(MeelanoTaxDb.sale(c, p.doc.no), 12, System.currentTimeMillis())[0]);
            out.put("states_after", st);
        });

        step("cancel an accepted invoice", () -> {
            List<MeelanoTaxDb.Log> succ = MeelanoTaxDb.logs(c, "SUCCESS", null, 50);
            check(succ.size() >= 2, "need 2 accepted invoices");
            if (succ.isEmpty()) return;
            MeelanoTaxDb.Log target = succ.get(0);
            MeelanoTaxEngine.Prepared p = MeelanoTaxEngine.prepareSale(c, cfg, MeelanoTaxDb.sale(c, target.docNo), units, true);
            check(MeelanoTaxEngine.ACT_CANCEL.equals(p.action) && p.sendable(), "forced cancel not sendable: " + p.action + " " + generic(p.message)
                    + (p.result == null ? "" : " " + generic(p.result.errorTexts())));
            check(target.taxid.equals(p.irtaxid), "cancel irtaxid != accepted taxid");
            MeelanoTaxEngine.Outcome o = MeelanoTaxEngine.send(c, cfg, api, Collections.singletonList(p));
            check(o.sent == 1, "cancel not sent: " + generic(String.join(" | ", o.lines)));
            MeelanoTaxEngine.inquire(c, cfg, api, MeelanoTaxDb.openLogs(c, 100));
            String[] st = MeelanoTaxEngine.state(MeelanoTaxDb.sale(c, target.docNo), 12, System.currentTimeMillis());
            out.put("cancel_state", st[0]);
            check("off".equals(st[1]), "state after cancel: " + st[0]);
            MeelanoTaxEngine.Prepared after = MeelanoTaxEngine.prepareSale(c, cfg, MeelanoTaxDb.sale(c, target.docNo), units, false);
            out.put("after_cancel_action", after.action + " / " + generic(after.message));
            check(api.problems.isEmpty(), "cancel packet problems: " + api.problems);
        });

        step("return against an accepted invoice", () -> {
            List<MeelanoTaxDb.Doc> backs = MeelanoTaxDb.backs(c, "0000/00/00", "9999/99/99", "", 10000);
            Set<Integer> acceptedSales = new HashSet<>();
            for (MeelanoTaxDb.Log l : MeelanoTaxDb.logs(c, "SUCCESS", null, 2000)) if (MeelanoTaxDb.SALE.equals(l.docKind) && l.ins == 1) acceptedSales.add(l.docNo);
            MeelanoTaxDb.Doc back = null;
            for (MeelanoTaxDb.Doc d : backs) if (d.refSale > 0 && acceptedSales.contains(d.refSale)) { back = d; break; }
            if (back == null) {
                for (MeelanoTaxDb.Doc d : backs) {
                    if (d.refSale <= 0) continue;
                    MeelanoTaxDb.Doc sale = MeelanoTaxDb.sale(c, d.refSale);
                    if (sale == null) continue;
                    MeelanoTaxEngine.Prepared sp = MeelanoTaxEngine.prepareSale(c, cfg, sale, units, false);
                    if (!sp.sendable()) continue;
                    MeelanoTaxEngine.send(c, cfg, api, Collections.singletonList(sp));
                    MeelanoTaxEngine.inquire(c, cfg, api, MeelanoTaxDb.openLogs(c, 100));
                    back = d;
                    break;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("returns_total", backs.size());
            int linked = 0;
            for (MeelanoTaxDb.Doc d : backs) if (d.refSale > 0) linked++;
            m.put("returns_linked_to_sale", linked);
            if (back == null) { m.put("result", "no linked return with a sendable sale in this backup"); out.put("return", m); return; }
            back = findBack(c, back.backKind, back.no);
            MeelanoTaxEngine.Prepared p = MeelanoTaxEngine.prepareBack(c, cfg, back, units, null, 0, false);
            m.put("kind", back.backKind);
            m.put("action", p.action);
            m.put("message", generic(p.message));
            m.put("notes", generic(p.notes));
            if (p.result != null) m.put("errors", generic(p.result.errorTexts()));
            if (p.sendable()) {
                MeelanoTaxEngine.Outcome o = MeelanoTaxEngine.send(c, cfg, api, Collections.singletonList(p));
                MeelanoTaxEngine.InquiryOutcome r = MeelanoTaxEngine.inquire(c, cfg, api, MeelanoTaxDb.openLogs(c, 100));
                m.put("sent", o.sent);
                m.put("accepted", r.success);
                check(o.sent == 1 && r.success == 1, "return not accepted: " + generic(String.join(" | ", o.lines)));
                check(api.problems.isEmpty(), "return packet problems: " + api.problems);
                String[] st = MeelanoTaxEngine.state(findBack(c, back.backKind, back.no), 12, System.currentTimeMillis());
                m.put("state_after", st[0]);
            } else check(false, "linked return not sendable: " + m);
            out.put("return", m);
        });

        step("mark external", () -> {
            MeelanoTaxDb.Doc d = null;
            for (MeelanoTaxDb.Doc x : MeelanoTaxDb.sales(c, "0000/00/00", "9999/99/99", "", 10000)) if (x.active && x.chain.isEmpty() && x.atiranTaxId == null) { d = x; break; }
            if (d == null) { out.put("external", "no unsent sale"); return; }
            long t = MeelanoTaxEngine.docMillis(d.date, d.time, System.currentTimeMillis());
            String taxid = MeelanoTaxCrypto.taxId(MEM, 9_000_000L, t);
            long id = MeelanoTaxEngine.markExternal(c, cfg, d, taxid, 1, null, "E2E-REF", "e2e");
            String[] st = MeelanoTaxEngine.state(MeelanoTaxDb.sale(c, d.no), 12, System.currentTimeMillis());
            out.put("external", st[0]);
            check(id > 0 && "ok".equals(st[1]), "external state: " + st[0]);
            MeelanoTaxEngine.Prepared again = MeelanoTaxEngine.prepareSale(c, cfg, MeelanoTaxDb.sale(c, d.no), units, false);
            check(!(again.sendable() && MeelanoTaxEngine.ACT_ORIGINAL.equals(again.action)), "externally registered sale offered as original again");
            check(MeelanoTaxDb.lastSerial(c, MEM) >= 9_000_000L, "serial counter not bumped past the external serial");
        });

        step("kartable + range results", () -> {
            int n = MeelanoTaxEngine.refreshKartable(c, api, MeelanoTaxDb.acceptedWithoutFinalKartable(c, 500));
            out.put("kartable_updated", n);
            check(n > 0, "kartable not updated");
            MeelanoTaxEngine.InquiryOutcome r = MeelanoTaxEngine.applyResults(c, cfg, new ArrayList<>());
            check(r.success == 0, "empty applyResults");
        });

        step("logs, serials, void", () -> {
            List<MeelanoTaxDb.Log> all = MeelanoTaxDb.logs(c, "", null, 2000);
            Set<String> ids = new HashSet<>();
            long prev = Long.MAX_VALUE;
            int bad = 0, dup = 0, order = 0;
            for (MeelanoTaxDb.Log l : all) {
                if (l.taxid == null) continue;
                if (!MeelanoTaxCrypto.taxIdValid(l.taxid)) bad++;
                if (!ids.add(l.taxid)) dup++;
                if (!"EXTERNAL".equals(l.status)) { if (l.serial >= prev) order++; prev = l.serial; }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("logs", all.size()); m.put("invalid_taxids", bad); m.put("duplicate_taxids", dup); m.put("serial_order_breaks", order);
            m.put("last_serial", MeelanoTaxDb.lastSerial(c, MEM));
            m.put("search_by_memory", MeelanoTaxDb.logs(c, "", MEM, 50).size());
            List<MeelanoTaxDb.Log> failed = MeelanoTaxDb.logs(c, "FAILED", null, 5);
            if (!failed.isEmpty()) {
                MeelanoTaxDb.deleteDraft(c, failed.get(0).id);
                check("VOID".equals(MeelanoTaxDb.logById(c, failed.get(0).id).status), "deleteDraft did not void");
            }
            m.put("status_counts", MeelanoTaxDb.statusCounts(c));
            long[] tot = MeelanoTaxDb.acceptedTotals(c, "2000-01-01");
            m.put("accepted_totals", java.util.Arrays.asList(tot[0], tot[1], tot[2]));
            out.put("logs", m);
            check(bad == 0 && dup == 0, "tax id problems: " + m);
        });
    }

    static MeelanoTaxDb.Doc findBack(Connection c, int kind, int no) throws Exception {
        for (MeelanoTaxDb.Doc d : MeelanoTaxDb.backs(c, "0000/00/00", "9999/99/99", String.valueOf(no), 200)) if (d.no == no && d.backKind == kind) return d;
        return null;
    }

    /** Prepares every document; tallies actions, states and (generic) validation messages; exceptions fail the run. */
    static Map<String, Object> prepareAll(Connection c, List<MeelanoTaxDb.Doc> docs, long now, boolean backs) {
        Map<String, Integer> actions = new TreeMap<>(), states = new TreeMap<>(), errors = new HashMap<>(), warnings = new HashMap<>();
        int sendable = 0, exceptions = 0, type1 = 0, late = 0;
        long sumAtiran = 0, sumBill = 0, maxDiff = 0;
        List<String> ex = new ArrayList<>();
        for (MeelanoTaxDb.Doc d : docs) {
            try {
                inc(states, MeelanoTaxEngine.state(d, 12, now)[0]);
                MeelanoTaxEngine.Prepared p = backs ? MeelanoTaxEngine.prepareBack(c, cfg, d, units, null, 0, false) : MeelanoTaxEngine.prepareSale(c, cfg, d, units, false);
                inc(actions, p.action);
                if (MeelanoTaxEngine.ACT_NONE.equals(p.action)) inc(errors, "[none] " + generic(p.message));
                if (p.result != null) {
                    for (String e : p.result.errorTexts()) inc(errors, generic(e));
                    for (String w : p.result.warningTexts()) inc(warnings, generic(w));
                    if (p.result.late) late++;
                    if (MeelanoTaxJson.num(MeelanoTaxJson.obj(p.result.invoice, "header"), "inty", 0) == 1) type1++;
                    if (!backs && MeelanoTaxEngine.ACT_ORIGINAL.equals(p.action)) {
                        sumAtiran += d.total;
                        sumBill += p.result.tbill;
                        maxDiff = Math.max(maxDiff, Math.abs(d.total - p.result.tbill));
                    }
                }
                if (p.sendable()) sendable++;
            } catch (Throwable t) {
                exceptions++;
                if (ex.size() < 5) {
                    String where = "";
                    for (StackTraceElement el : t.getStackTrace()) if (el.getClassName().startsWith("ir.meelano")) { where = " @" + el.getMethodName() + ":" + el.getLineNumber(); break; }
                    ex.add((backs ? "back " + d.backKind + ":" : "sale ") + d.no + " " + t + where);
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docs", docs.size());
        m.put("sendable", sendable);
        m.put("type1", type1);
        m.put("late", late);
        m.put("actions", actions);
        m.put("states", states);
        m.put("top_errors", top(errors, 12));
        m.put("top_warnings", top(warnings, 8));
        if (!backs) { m.put("sum_atiran_total", sumAtiran); m.put("sum_tbill", sumBill); m.put("max_invoice_diff", maxDiff); }
        m.put("exceptions", exceptions);
        if (!ex.isEmpty()) m.put("exception_samples", ex);
        check(exceptions == 0, (backs ? "returns" : "sales") + ": " + exceptions + " exceptions, e.g. " + ex);
        return m;
    }
}
