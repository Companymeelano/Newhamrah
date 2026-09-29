package ir.meelano.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Moadian app: official vectors, cross-implementation crypto checks and the invoice rules. */
public class MeelanoTaxTest {

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = MeelanoTaxTest.class.getResourceAsStream("/moadian/" + name)) {
            assertNotNull("missing test resource " + name, in);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static String text(String name) throws Exception { return new String(resource(name), StandardCharsets.UTF_8); }

    private static void writeCrossCheck(String name, String content) throws Exception {
        File dir = new File("build/moadian-crosscheck");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, name))) { o.write(content.getBytes(StandardCharsets.UTF_8)); }
    }

    // ------------------------------------------------------------------ JWS (RC_TICS.IS_v1.6 p.13)

    @Test
    public void officialJwsTokenIsReproducedByteForByte() throws Exception {
        PrivateKey key = MeelanoTaxCrypto.privateKeyFromPem(text("sample_private_key.pem"));
        X509Certificate cert = MeelanoTaxCrypto.certificateFromText(text("sample_cert.crt"));
        byte[] payload = resource("official_payload.txt");
        String expected = text("official_token.txt").trim();
        String token = MeelanoTaxCrypto.signJws(key, cert.getEncoded(), payload, 1709730350000L);
        assertEquals(expected, token);
        assertTrue(MeelanoTaxCrypto.verifyJws(cert.getPublicKey(), token));
    }

    @Test
    public void sigTimeIsUtc() {
        assertEquals("2024-03-06T13:05:50Z", MeelanoTaxCrypto.sigTime(1709730350000L));
    }

    @Test
    public void sampleCertificateSubjectAndKeyMatch() throws Exception {
        PrivateKey key = MeelanoTaxCrypto.privateKeyFromPem(text("sample_private_key.pem"));
        X509Certificate cert = MeelanoTaxCrypto.certificateFromText(text("sample_cert.crt"));
        Map<String, String> s = MeelanoTaxCrypto.subject(cert);
        assertEquals("14003778990", s.get("SERIALNUMBER"));
        assertEquals("Anzali", s.get("CN"));
        assertTrue(MeelanoTaxCrypto.keyMatchesCertificate(key, cert));
        KeyPair other = MeelanoTaxCrypto.generateKeyPair();
        assertFalse(MeelanoTaxCrypto.keyMatchesCertificate(other.getPrivate(), cert));
        assertEquals(((java.security.interfaces.RSAPublicKey) cert.getPublicKey()).getModulus(),
                ((java.security.interfaces.RSAPublicKey) MeelanoTaxCrypto.publicFromPrivate(key)).getModulus());
    }

    @Test
    public void pkcs1PrivateKeyIsAccepted() throws Exception {
        PrivateKey key = MeelanoTaxCrypto.privateKeyFromPem(text("sample_private_key.pem"));
        // Extract the PKCS#1 RSAPrivateKey from the PKCS#8 wrapper and read it back through the PKCS#1 path.
        byte[] p8 = key.getEncoded();
        int[] outer = MeelanoTaxCrypto.headerLen(p8, 0);
        int off = outer[0];
        int[] ver = MeelanoTaxCrypto.headerLen(p8, off);
        off = ver[0] + ver[1];
        int[] alg = MeelanoTaxCrypto.headerLen(p8, off);
        off = alg[0] + alg[1];
        int[] oct = MeelanoTaxCrypto.headerLen(p8, off);
        byte[] pkcs1 = java.util.Arrays.copyOfRange(p8, oct[0], oct[0] + oct[1]);
        PrivateKey again = MeelanoTaxCrypto.privateKeyFromPem(MeelanoTaxCrypto.pem("RSA PRIVATE KEY", pkcs1));
        java.security.interfaces.RSAPrivateKey a = (java.security.interfaces.RSAPrivateKey) key;
        java.security.interfaces.RSAPrivateKey b = (java.security.interfaces.RSAPrivateKey) again;
        assertEquals(a.getModulus(), b.getModulus());
        assertEquals(a.getPrivateExponent(), b.getPrivateExponent());
    }

    // ------------------------------------------------------------------ JWE

    @Test
    public void decryptsJweProducedByPythonCryptography() throws Exception {
        PrivateKey key = MeelanoTaxCrypto.privateKeyFromPem(text("jwe_test_private_key.pem"));
        byte[] plain = MeelanoTaxCrypto.decryptJwe(key, text("jwe_python_vector.txt").trim());
        assertTrue(java.util.Arrays.equals(resource("jwe_python_plaintext.txt"), plain));
    }

    @Test
    public void jweRoundTripAndCrossCheckFile() throws Exception {
        PrivateKey key = MeelanoTaxCrypto.privateKeyFromPem(text("jwe_test_private_key.pem"));
        PublicKey pub = MeelanoTaxCrypto.publicKeyFromBase64(text("jwe_test_public_spki.txt").trim());
        String plain = "{\"header\":{\"sstt\":\"مغز پسته\"}}";
        String jwe = MeelanoTaxCrypto.encryptJwe(pub, "kid-42", plain.getBytes(StandardCharsets.UTF_8));
        String[] parts = jwe.split("\\.");
        assertEquals(5, parts.length);
        assertEquals("{\"alg\":\"RSA-OAEP-256\",\"enc\":\"A256GCM\",\"kid\":\"kid-42\"}",
                new String(MeelanoTaxCrypto.b64decode(parts[0]), StandardCharsets.UTF_8));
        assertEquals(12, MeelanoTaxCrypto.b64decode(parts[2]).length);
        assertEquals(16, MeelanoTaxCrypto.b64decode(parts[4]).length);
        assertEquals(plain, new String(MeelanoTaxCrypto.decryptJwe(key, jwe), StandardCharsets.UTF_8));
        writeCrossCheck("java_jwe.txt", jwe);
        writeCrossCheck("java_jwe_plaintext.txt", plain);
    }

    @Test
    public void base64MatchesJdk() {
        java.util.Random r = new java.util.Random(7);
        for (int n = 0; n < 70; n++) {
            byte[] d = new byte[n];
            r.nextBytes(d);
            assertEquals(java.util.Base64.getEncoder().encodeToString(d), MeelanoTaxCrypto.b64(d));
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(d), MeelanoTaxCrypto.b64url(d));
            assertTrue(java.util.Arrays.equals(d, MeelanoTaxCrypto.b64decode(MeelanoTaxCrypto.b64url(d))));
            assertTrue(java.util.Arrays.equals(d, MeelanoTaxCrypto.b64decode(MeelanoTaxCrypto.b64(d))));
        }
    }

    // ------------------------------------------------------------------ tax id

    @Test
    public void verhoeffAndDecimaliseFollowTheSpec() {
        assertEquals('3', MeelanoTaxCrypto.verhoeff("236"));
        assertTrue(MeelanoTaxCrypto.verhoeffValid("2363"));
        assertFalse(MeelanoTaxCrypto.verhoeffValid("2364"));
        assertEquals("6511216", MeelanoTaxCrypto.decimalise("A11216"));
        assertEquals("651116887", MeelanoTaxCrypto.decimalise("A111DW"));
        assertEquals("65111721", MeelanoTaxCrypto.decimalise("A111H1"));
    }

    @Test
    public void specTaxIdsReproduceAndValidate() {
        String[] ids = {"A1121604C220002F095011", "A111DW04E8300004349008", "A111DW04E8300003CC4EA5", "A111H104EA6001D0B32AC6"};
        for (String id : ids) {
            assertTrue(id, MeelanoTaxCrypto.taxIdValid(id));
            long days = Long.parseLong(id.substring(6, 11), 16);
            long serial = Long.parseLong(id.substring(11, 21), 16);
            long millis = days * 86_400_000L + 45_000_000L;
            assertEquals(id, MeelanoTaxCrypto.taxId(id.substring(0, 6), serial, millis));
            assertEquals(days, MeelanoTaxCrypto.taxIdDay(id));
        }
        assertFalse(MeelanoTaxCrypto.taxIdValid("A1121604C220002F095012"));
        assertEquals("0000000ABC", MeelanoTaxCrypto.inno(0xABC));
        assertEquals("A111YO", MeelanoTaxCrypto.taxId("a111yo", 100, 1_700_000_000_000L).substring(0, 6));
    }

    // ------------------------------------------------------------------ CSR & key backup

    @Test
    public void csrIsSelfSignedAndWrittenForOpenssl() throws Exception {
        KeyPair kp = MeelanoTaxCrypto.generateKeyPair();
        MeelanoTaxCrypto.CsrSubject s = new MeelanoTaxCrypto.CsrSubject();
        s.company = false;
        s.commonName = MeelanoTaxCrypto.defaultCommonName(false, "Derakhshan Nuts");
        s.serialNumber = "2300510261";
        s.givenName = "Derakhshan";
        s.surname = "Nuts";
        s.organizationalUnit = "آجیل و خشکبار درخشان";
        byte[] csr = MeelanoTaxCrypto.buildCsr(kp, s);
        assertTrue(MeelanoTaxCrypto.verifyCsr(csr, kp.getPublic()));
        assertEquals("Derakhshan Nuts [Sign]", s.commonName);
        writeCrossCheck("test.csr", MeelanoTaxCrypto.pem("CERTIFICATE REQUEST", csr));
    }

    @Test
    public void passphraseSealRoundTrip() throws Exception {
        byte[] secret = "-----BEGIN PRIVATE KEY-----\nabc\n".getBytes(StandardCharsets.UTF_8);
        String sealed = MeelanoTaxCrypto.sealWithPassphrase(secret, "derakhshan-1405");
        assertTrue(sealed.startsWith("mtk1:"));
        assertTrue(java.util.Arrays.equals(secret, MeelanoTaxCrypto.openWithPassphrase(sealed, "derakhshan-1405")));
        try {
            MeelanoTaxCrypto.openWithPassphrase(sealed, "wrong-pass");
            fail("wrong passphrase must fail");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    // ------------------------------------------------------------------ JSON

    @Test
    public void jsonOmitsNullsKeepsPersianAndRoundTrips() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("a", 1);
        m.put("b", null);
        m.put("c", "پسته \"اکبری\"");
        m.put("d", new BigDecimal("2.50000000"));
        m.put("e", new BigDecimal("15150000.0000"));
        List<Object> l = new ArrayList<>();
        l.add(true);
        l.add(null);
        m.put("f", l);
        String j = MeelanoTaxJson.write(m);
        assertEquals("{\"a\":1,\"c\":\"پسته \\\"اکبری\\\"\",\"d\":2.5,\"e\":15150000,\"f\":[true,null]}", j);
        Map<String, Object> back = MeelanoTaxJson.obj(MeelanoTaxJson.parse(j));
        assertEquals("پسته \"اکبری\"", back.get("c"));
        assertEquals(0, new BigDecimal("2.5").compareTo((BigDecimal) back.get("d")));
        assertEquals(1L, MeelanoTaxJson.num(back, "a", 0));
    }

    // ------------------------------------------------------------------ invoice building

    private static MeelanoTaxInvoice.Line line(int shka, String name, String qty, String fee, String discount, String vat) {
        MeelanoTaxInvoice.Line l = new MeelanoTaxInvoice.Line();
        l.shka = shka;
        l.name = name;
        l.sstid = "2710000138624";
        l.mu = "164";
        l.qty = new BigDecimal(qty);
        l.fee = new BigDecimal(fee);
        l.discount = new BigDecimal(discount);
        l.vatRate = new BigDecimal(vat);
        return l;
    }

    private static MeelanoTaxInvoice.Source source(long indatim, long now) {
        MeelanoTaxInvoice.Source s = new MeelanoTaxInvoice.Source();
        s.tins = "23005102610002";
        s.indatim = indatim;
        s.now = now;
        s.inty = 1;
        s.buyer.tob = 1;
        s.buyer.bid = "0012345679";
        s.buyer.bpc = "6188653559";
        return s;
    }

    @Test
    public void lineArithmeticTruncatesRials() {
        long t = MeelanoTaxInvoice.tehranMillis("1405/07/06", "09:18");
        MeelanoTaxInvoice.Source s = source(t, t + 3_600_000L);
        s.lines.add(line(1775, "بادام استانه", "3.5", "15150000", "0", "0"));
        s.lines.add(line(621, "پسته", "1.333", "1001", "0", "10"));
        s.lines.add(line(9, "حذف‌شده", "0", "5000", "0", "0"));
        String taxid = MeelanoTaxCrypto.taxId("A11216", 77, t);
        MeelanoTaxInvoice.Result r = MeelanoTaxInvoice.build(s, taxid, MeelanoTaxCrypto.inno(77));
        assertFalse(r.errorTexts().toString(), r.hasErrors());
        assertEquals(2, r.lines.size());
        assertEquals(53_025_000L, r.lines.get(0).prdis);
        assertEquals(1334L, r.lines.get(1).prdis);          // 1.333 × 1001 = 1334.333 → truncated
        assertEquals(133L, r.lines.get(1).vam);             // 1334 × 10% = 133.4 → truncated
        assertEquals(53_025_000L + 1334L, r.tadis);
        assertEquals(r.tadis + r.tvam, r.tbill);
        Map<String, Object> h = MeelanoTaxJson.obj(r.invoice, "header");
        assertEquals(2, h.get("setm"));
        assertEquals(1, h.get("tob"));
        assertNull(h.get("insr"));
        assertTrue(r.json.contains("\"am\":3.5"));
        assertTrue(r.json.contains("\"sstt\":\"بادام استانه\""));
        assertFalse(r.json.contains("null"));
    }

    @Test
    public void headerDiscountIsSpreadExactly() {
        long t = MeelanoTaxInvoice.tehranMillis("1405/07/05", "10:00");
        MeelanoTaxInvoice.Source s = source(t, t + 1000);
        s.lines.add(line(1, "الف", "1", "1000", "0", "0"));
        s.lines.add(line(2, "ب", "1", "2000", "0", "0"));
        s.lines.add(line(3, "ج", "1", "3001", "0", "0"));
        s.headerDiscount = new BigDecimal("1001");
        MeelanoTaxInvoice.Result r = MeelanoTaxInvoice.build(s, MeelanoTaxCrypto.taxId("A11216", 5, t), MeelanoTaxCrypto.inno(5));
        assertEquals(1001L, r.tdis);
        assertEquals(6001L - 1001L, r.tadis);
        for (MeelanoTaxInvoice.Line l : r.lines) assertTrue(l.dis <= l.prdis);
    }

    @Test
    public void lateInvoiceGetsArticle9Fields() {
        long t = MeelanoTaxInvoice.tehranMillis("1405/06/01", "09:00");
        long now = t + 20L * 86_400_000L;
        MeelanoTaxInvoice.Source s = source(t, now);
        s.deadlineDays = 12;
        s.lines.add(line(1, "کشمش", "2", "500000", "0", "0"));
        MeelanoTaxInvoice.Result r = MeelanoTaxInvoice.build(s, MeelanoTaxCrypto.taxId("A11216", 6, t), MeelanoTaxCrypto.inno(6));
        Map<String, Object> h = MeelanoTaxJson.obj(r.invoice, "header");
        assertEquals(1, h.get("insr"));
        assertEquals(now, h.get("indati2m"));
        assertTrue(r.late);
    }

    @Test
    public void buyerRulesForTypeOneAndTwo() {
        long t = MeelanoTaxInvoice.tehranMillis("1405/07/01", "09:40");
        MeelanoTaxInvoice.Source s = source(t, t + 1000);
        s.buyer = new MeelanoTaxInvoice.Buyer();
        s.buyer.tob = 2;
        s.lines.add(line(1, "گردو", "1", "900000", "0", "0"));
        MeelanoTaxInvoice.Result r = MeelanoTaxInvoice.build(s, MeelanoTaxCrypto.taxId("A11216", 8, t), MeelanoTaxCrypto.inno(8));
        assertTrue(r.hasErrors());
        s.inty = 2;
        r = MeelanoTaxInvoice.build(s, MeelanoTaxCrypto.taxId("A11216", 8, t), MeelanoTaxCrypto.inno(8));
        assertFalse(r.errorTexts().toString(), r.hasErrors());
        assertNull(MeelanoTaxJson.obj(r.invoice, "header").get("setm"));
    }

    @Test
    public void missingGoodsIdAndFutureDateAreErrors() {
        long now = MeelanoTaxInvoice.tehranMillis("1405/07/06", "10:00");
        MeelanoTaxInvoice.Source s = source(now + 3_600_000L, now);
        MeelanoTaxInvoice.Line l = line(1, "تخمه", "1", "100", "0", "0");
        l.sstid = null;
        s.lines.add(l);
        MeelanoTaxInvoice.Result r = MeelanoTaxInvoice.build(s, MeelanoTaxCrypto.taxId("A11216", 9, s.indatim), MeelanoTaxCrypto.inno(9));
        int errors = 0;
        for (MeelanoTaxInvoice.Issue i : r.issues) if (i.error && (i.field.equals("sstid") || i.field.equals("indatim"))) errors++;
        assertEquals(2, errors);
    }

    @Test
    public void returnKeepsRemainingItemsAndOmitsBuyer() {
        List<MeelanoTaxInvoice.Line> original = new ArrayList<>();
        original.add(line(10, "بادام", "5", "1000", "500", "0"));
        original.add(line(11, "فندق", "2", "3000", "0", "0"));
        Map<Integer, BigDecimal> returned = new HashMap<>();
        returned.put(10, new BigDecimal("2"));
        returned.put(11, new BigDecimal("2"));
        returned.put(99, new BigDecimal("1"));
        List<MeelanoTaxInvoice.Line> rest = MeelanoTaxInvoice.remainingAfterReturns(original, returned);
        assertEquals(1, rest.size());
        assertEquals(0, new BigDecimal("3").compareTo(rest.get(0).qty));
        assertEquals(0, new BigDecimal("300").compareTo(rest.get(0).discount));
        assertEquals(1, MeelanoTaxInvoice.unmatchedReturns(original, returned).size());

        long t0 = MeelanoTaxInvoice.tehranMillis("1405/07/01", "09:00");
        long t1 = MeelanoTaxInvoice.tehranMillis("1405/07/03", "09:00");
        MeelanoTaxInvoice.Source s = source(t1, t1 + 1000);
        s.ins = MeelanoTaxInvoice.INS_RETURN;
        s.irtaxid = MeelanoTaxCrypto.taxId("A11216", 20, t0);
        s.referenceIndatim = t0;
        s.lines = rest;
        MeelanoTaxInvoice.Result r = MeelanoTaxInvoice.build(s, MeelanoTaxCrypto.taxId("A11216", 21, t1), MeelanoTaxCrypto.inno(21));
        assertFalse(r.errorTexts().toString(), r.hasErrors());
        Map<String, Object> h = MeelanoTaxJson.obj(r.invoice, "header");
        assertEquals(4, h.get("ins"));
        assertEquals(s.irtaxid, h.get("irtaxid"));
        assertNull(h.get("tob"));
        assertNull(h.get("bid"));
        assertEquals(2700L, r.tadis);
    }

    @Test
    public void cancelCopiesTheLastAcceptedVersion() {
        long t0 = MeelanoTaxInvoice.tehranMillis("1405/07/01", "09:00");
        MeelanoTaxInvoice.Source s = source(t0, t0 + 1000);
        s.lines.add(line(1, "انجیر", "1", "250000", "0", "10"));
        String taxid = MeelanoTaxCrypto.taxId("A11216", 30, t0);
        MeelanoTaxInvoice.Result orig = MeelanoTaxInvoice.build(s, taxid, MeelanoTaxCrypto.inno(30));
        long t1 = t0 + 86_400_000L;
        String cancelId = MeelanoTaxCrypto.taxId("A11216", 31, t1);
        MeelanoTaxInvoice.Result c = MeelanoTaxInvoice.cancelFrom(orig.json, cancelId, MeelanoTaxCrypto.inno(31), t1, t1 + 1000, 12, taxid, t0);
        assertFalse(c.errorTexts().toString(), c.hasErrors());
        Map<String, Object> h = MeelanoTaxJson.obj(c.invoice, "header");
        assertEquals(3, h.get("ins"));
        assertEquals(taxid, h.get("irtaxid"));
        assertEquals(cancelId, h.get("taxid"));
        assertNull(h.get("tob"));
        assertEquals(orig.tbill, c.tbill);
        assertEquals(1, MeelanoTaxJson.arr(c.invoice, "body").size());
    }

    @Test
    public void correctionBlockerDetectsBuyerAndRateChanges() {
        long t0 = MeelanoTaxInvoice.tehranMillis("1405/07/01", "09:00");
        MeelanoTaxInvoice.Source s = source(t0, t0 + 1000);
        s.buyer.tinb = "23005102610002";
        s.lines.add(line(1, "انجیر", "1", "250000", "0", "10"));
        MeelanoTaxInvoice.Result orig = MeelanoTaxInvoice.build(s, MeelanoTaxCrypto.taxId("A11216", 40, t0), MeelanoTaxCrypto.inno(40));
        assertNull(MeelanoTaxInvoice.correctionBlocker(orig.json, s));
        s.lines.get(0).qty = new BigDecimal("2");
        assertNull(MeelanoTaxInvoice.correctionBlocker(orig.json, s));
        s.lines.get(0).vatRate = new BigDecimal("0");
        assertNotNull(MeelanoTaxInvoice.correctionBlocker(orig.json, s));
        s.lines.get(0).vatRate = new BigDecimal("10");
        s.buyer.tinb = "14000000000000";
        assertNotNull(MeelanoTaxInvoice.correctionBlocker(orig.json, s));
    }

    @Test
    public void contentHashIgnoresLineOrder() {
        MeelanoTaxInvoice.Source a = source(1, 2);
        a.lines.add(line(1, "x", "1", "10", "0", "0"));
        a.lines.add(line(2, "y", "2", "20", "0", "0"));
        MeelanoTaxInvoice.Source b = source(1, 2);
        b.lines.add(line(2, "y", "2", "20", "0", "0"));
        b.lines.add(line(1, "x", "1", "10", "0", "0"));
        assertEquals(MeelanoTaxInvoice.contentHash(a), MeelanoTaxInvoice.contentHash(b));
        b.lines.get(0).qty = new BigDecimal("3");
        assertFalse(MeelanoTaxInvoice.contentHash(a).equals(MeelanoTaxInvoice.contentHash(b)));
    }

    @Test
    public void tehranTimeAndUnitsAndNationalCode() {
        assertEquals(0L, MeelanoTaxInvoice.tehranMillis("1348/10/11", "03:30"));
        assertEquals(-1L, MeelanoTaxInvoice.tehranMillis("bad", "10:00"));
        assertEquals("164", MeelanoTaxInvoice.unitCode("كيلو", null));
        assertEquals("164", MeelanoTaxInvoice.unitCode("كيلوگرم", null));
        assertEquals("1624", MeelanoTaxInvoice.unitCode("شرينگ", null));
        assertEquals("1627", MeelanoTaxInvoice.unitCode("عدد", null));
        assertNull(MeelanoTaxInvoice.unitCode("شل", null));
        Map<String, String> custom = new HashMap<>();
        custom.put(MeelanoTaxInvoice.normName("شل"), "1628");
        assertEquals("1628", MeelanoTaxInvoice.unitCode("شل", custom));
        assertEquals(102, MeelanoTaxInvoice.UNITS.size());
        assertTrue(MeelanoTaxInvoice.nationalCodeValid("0012345679"));
        assertFalse(MeelanoTaxInvoice.nationalCodeValid("0012345678"));
        assertFalse(MeelanoTaxInvoice.nationalCodeValid("1111111111"));
        assertEquals("1234", MeelanoTaxInvoice.digits("۱۲-۳۴"));
    }

    // ------------------------------------------------------------------ API helpers

    @Test
    public void inquiryParsingAndErrors() {
        String json = "[{\"referenceNumber\":\"r1\",\"uid\":\"u1\",\"status\":\"FAILED\",\"data\":{\"error\":[{\"code\":\"0100513\",\"message\":\"شناسه کالا نامعتبر\",\"errorType\":\"ERROR\"}],"
                + "\"warning\":[{\"code\":\"W1\",\"message\":\"هشدار\"}],\"success\":false},\"packetType\":\"INVOICE.V01\",\"fiscalId\":\"A11216\"},"
                + "{\"referenceNumber\":\"r2\",\"uid\":\"u2\",\"status\":\"SUCCESS\",\"data\":\"{\\\"error\\\":[],\\\"warning\\\":[],\\\"success\\\":true}\"}]";
        List<MeelanoTaxApi.Inquiry> l = MeelanoTaxApi.parseInquiry(MeelanoTaxJson.parse(json));
        assertEquals(2, l.size());
        assertEquals("FAILED", l.get(0).status);
        assertEquals("0100513", l.get(0).errors.get(0)[0]);
        assertEquals(1, l.get(0).warnings.size());
        assertEquals("SUCCESS", l.get(1).status);
        assertTrue(l.get(1).errors.isEmpty());
        assertTrue(MeelanoTaxApi.persianError("4103", "x").contains("گواهی"));
        assertEquals("1970-01-01T03:30:00.000000000+03:30", MeelanoTaxApi.queryTime(0));
        assertEquals("{\"nonce\":\"n-1\",\"clientId\":\"A11216\"}", MeelanoTaxCrypto.authPayload("n-1", "A11216"));
    }
}
