package ir.meelano.android;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Atiran database access of the Moadian app.
 *
 * <p>Reads follow Atiran's own tax views: {@code Vw_HeaderSaleTax} (current edit revision {@code MAX(rdf__)} of each
 * sales invoice, {@code Deleted=0}), {@code Vw_Tax_tis} (lines: qty = TEDVAH×mohvah + TEDJOZ, fee = JOZPRICE,
 * discount = litakhma) and {@code VW_AllBargashtiForTax} (returns/cancels: ka_act 21/17/27/128/129 joined to
 * back_sanad kinds 1/2/6/8/9).</p>
 *
 * <p>Writes: our own tables ({@code meelano_tax_*}: settings, serial counter, send log, goods and buyer ids) and,
 * after an accepted submission, Atiran's native tax columns on sailfact/back_sanad plus {@code TaxSystemLog}, so
 * Atiran itself shows the invoice as sent and never sends it twice.</p>
 */
final class MeelanoTaxDb {
    private MeelanoTaxDb() {}

    static final String SALE = "SALE", BACK = "BACK";

    // ------------------------------------------------------------------ schema

    static void ensureSchema(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("IF OBJECT_ID('dbo.meelano_tax_settings','U') IS NULL CREATE TABLE dbo.meelano_tax_settings("
                    + "k nvarchar(100) NOT NULL PRIMARY KEY, v nvarchar(max) NULL, updated_at datetime NOT NULL DEFAULT GETDATE(), updated_by nvarchar(100) NULL)");
            s.execute("IF OBJECT_ID('dbo.meelano_tax_serial','U') IS NULL CREATE TABLE dbo.meelano_tax_serial("
                    + "memory_id nvarchar(20) NOT NULL PRIMARY KEY, last_serial bigint NOT NULL)");
            s.execute("IF OBJECT_ID('dbo.meelano_tax_invoices','U') IS NULL CREATE TABLE dbo.meelano_tax_invoices("
                    + "id bigint IDENTITY(1,1) NOT NULL PRIMARY KEY, doc_kind nvarchar(10) NOT NULL, doc_no int NOT NULL, back_kind int NULL, "
                    + "rdf int NULL, chain_sale int NULL, ins int NOT NULL, inty int NULL, taxid nvarchar(40) NULL, irtaxid nvarchar(40) NULL, "
                    + "serial bigint NULL, memory_id nvarchar(20) NULL, env nvarchar(12) NULL, uid nvarchar(80) NULL, reference_number nvarchar(200) NULL, "
                    + "status nvarchar(30) NOT NULL, kartable_status nvarchar(60) NULL, errors nvarchar(max) NULL, warnings nvarchar(max) NULL, "
                    + "payload nvarchar(max) NULL, content_hash nvarchar(80) NULL, indatim bigint NULL, tbill bigint NULL, tvam bigint NULL, "
                    + "customer_shmo bigint NULL, customer_name nvarchar(300) NULL, doc_date nvarchar(12) NULL, "
                    + "created_at datetime NOT NULL DEFAULT GETDATE(), updated_at datetime NULL, user_name nvarchar(100) NULL, note nvarchar(500) NULL)");
            s.execute("IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name='IX_meelano_tax_invoices_doc' AND object_id=OBJECT_ID('dbo.meelano_tax_invoices')) "
                    + "CREATE INDEX IX_meelano_tax_invoices_doc ON dbo.meelano_tax_invoices(doc_kind, doc_no)");
            s.execute("IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name='IX_meelano_tax_invoices_chain' AND object_id=OBJECT_ID('dbo.meelano_tax_invoices')) "
                    + "CREATE INDEX IX_meelano_tax_invoices_chain ON dbo.meelano_tax_invoices(chain_sale)");
            s.execute("IF OBJECT_ID('dbo.meelano_tax_goods','U') IS NULL CREATE TABLE dbo.meelano_tax_goods("
                    + "shka int NOT NULL PRIMARY KEY, sstid nvarchar(20) NULL, sstt nvarchar(400) NULL, mu nvarchar(10) NULL, vra decimal(9,3) NULL, "
                    + "updated_at datetime NOT NULL DEFAULT GETDATE(), updated_by nvarchar(100) NULL)");
            s.execute("IF OBJECT_ID('dbo.meelano_tax_buyers','U') IS NULL CREATE TABLE dbo.meelano_tax_buyers("
                    + "shmo bigint NOT NULL PRIMARY KEY, tob int NULL, bid nvarchar(20) NULL, tinb nvarchar(20) NULL, bpc nvarchar(20) NULL, "
                    + "bbc nvarchar(20) NULL, inty int NULL, updated_at datetime NOT NULL DEFAULT GETDATE(), updated_by nvarchar(100) NULL)");
            s.execute("IF OBJECT_ID('dbo.meelano_tax_units','U') IS NULL CREATE TABLE dbo.meelano_tax_units("
                    + "unit_name nvarchar(60) NOT NULL PRIMARY KEY, mu nvarchar(10) NOT NULL)");
        }
    }

    // ------------------------------------------------------------------ metadata helpers

    private static final Map<String, Set<String>> COLS = new HashMap<>();

    static synchronized Set<String> columns(Connection c, String table) {
        String key = table.toLowerCase(Locale.US);
        Set<String> cached = COLS.get(key);
        if (cached != null) return cached;
        Set<String> out = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM sys.columns WHERE object_id=OBJECT_ID(?)")) {
            ps.setString(1, "dbo." + table);
            try (ResultSet r = ps.executeQuery()) { while (r.next()) out.add(r.getString(1).toLowerCase(Locale.US)); }
        } catch (Exception ignored) { }
        if (!out.isEmpty()) COLS.put(key, out);
        return out;
    }

    static synchronized void clearCache() { COLS.clear(); }

    static boolean has(Connection c, String table, String column) { return columns(c, table).contains(column.toLowerCase(Locale.US)); }

    static boolean tableExists(Connection c, String table) { return !columns(c, table).isEmpty(); }

    private static String col(Connection c, String table, String alias, String column, String fallback) {
        return has(c, table, column) ? alias + ".[" + column + "]" : fallback;
    }

    private static String nstr(Connection c, String table, String alias, String column, int len) {
        return has(c, table, column) ? "CAST(" + alias + ".[" + column + "] AS nvarchar(" + len + "))" : "CAST(NULL AS nvarchar(" + len + "))";
    }

    static String norm(String s) {
        if (s == null) return "";
        return s.replace('ي', 'ی').replace('ك', 'ک').trim();
    }

    private static String trim(String s) { return s == null ? null : (s.trim().isEmpty() ? null : s.trim()); }

    private static void setNullableString(PreparedStatement ps, int i, String v) throws Exception {
        if (v == null) ps.setNull(i, Types.VARCHAR); else ps.setString(i, v);
    }

    // ------------------------------------------------------------------ settings

    static Map<String, String> settings(Connection c) throws Exception {
        Map<String, String> m = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT k, v FROM dbo.meelano_tax_settings")) {
            while (r.next()) m.put(r.getString(1), r.getString(2));
        }
        return m;
    }

    static void putSetting(Connection c, String k, String v, String user) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_tax_settings SET v=?, updated_at=GETDATE(), updated_by=? WHERE k=?")) {
            setNullableString(ps, 1, v);
            setNullableString(ps, 2, user);
            ps.setString(3, k);
            if (ps.executeUpdate() > 0) return;
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dbo.meelano_tax_settings(k, v, updated_by) VALUES (?,?,?)")) {
            ps.setString(1, k);
            setNullableString(ps, 2, v);
            setNullableString(ps, 3, user);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ company (Atiran «Company» table)

    static final class Company {
        String name, economicCode, nationalId, postalCode, memoryId, branch;
        boolean hasPrivateKey;
        String privateKeyText;
    }

    static Company company(Connection c) {
        Company co = new Company();
        if (!tableExists(c, "Company")) return co;
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT TOP (1) * FROM dbo.Company")) {
            ResultSetMetaData md = r.getMetaData();
            if (!r.next()) return co;
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String n = md.getColumnName(i).toLowerCase(Locale.US);
                String v;
                try { v = r.getString(i); } catch (Exception e) { v = null; }
                v = trim(v);
                switch (n) {
                    case "name": co.name = norm(v); break;
                    case "c_egh": co.economicCode = v; break;
                    case "c_meli": co.nationalId = v; break;
                    case "c_pos": co.postalCode = v; break;
                    case "taxmemoryid": co.memoryId = v; break;
                    case "branch": co.branch = v; break;
                    case "taxprivatekey": co.hasPrivateKey = v != null; co.privateKeyText = v; break;
                    default: break;
                }
            }
        } catch (Exception ignored) { }
        return co;
    }

    // ------------------------------------------------------------------ login (Atiran sys_users)

    static final class User {
        int id;
        String name;
    }

    /** Atiran user login; null when the user/password does not match. Inactive users are refused. */
    static User login(Connection c, String username, String password) throws Exception {
        String u = username == null ? "" : username.trim();
        if (u.isEmpty()) return null;
        String sql = "SELECT user_id, CAST(user_name AS nvarchar(100)), user_password, " + col(c, "sys_users", "s", "active", "CAST(1 AS bit)")
                + " FROM dbo.sys_users s WHERE LOWER(LTRIM(RTRIM(CAST(user_name AS nvarchar(100)))))=LOWER(?) OR LOWER(LTRIM(RTRIM(CAST(user_name AS nvarchar(100)))))=LOWER(?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, u);
            ps.setString(2, u.replace('ی', 'ي').replace('ک', 'ك'));
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    String active = r.getString(4);
                    if (active != null && (active.equals("0") || active.equalsIgnoreCase("false"))) continue;
                    byte[] raw = null;
                    String txt = null;
                    try { raw = r.getBytes(3); } catch (Exception ignored) { }
                    try { txt = r.getString(3); } catch (Exception ignored) { }
                    if (passwordMatches(raw, txt, password)) {
                        User x = new User();
                        x.id = r.getInt(1);
                        x.name = norm(r.getString(2));
                        return x;
                    }
                }
            }
        }
        return null;
    }

    static boolean passwordMatches(byte[] raw, String text, String entered) {
        String e = entered == null ? "" : entered.replace("\u0000", "").trim();
        if (e.isEmpty()) return false;
        String ed = foldDigits(e);
        String t = text == null ? "" : text.replace("\u0000", "").trim();
        if (!t.isEmpty() && (t.equals(e) || t.equals(ed) || hashMatches(t, e) || hashMatches(t, ed))) return true;
        if (raw == null) return false;
        Charset[] sets = {StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.ISO_8859_1};
        for (Charset cs : sets) {
            if (java.util.Arrays.equals(raw, e.getBytes(cs)) || java.util.Arrays.equals(raw, ed.getBytes(cs))) return true;
            if (new String(raw, cs).replace("\u0000", "").trim().equals(e)) return true;
        }
        try {
            Charset w = Charset.forName("windows-1256");
            if (java.util.Arrays.equals(raw, e.getBytes(w))) return true;
        } catch (Exception ignored) { }
        for (String alg : new String[]{"MD5", "SHA-1", "SHA-256", "SHA-512"}) {
            try {
                MessageDigest md = MessageDigest.getInstance(alg);
                if (java.util.Arrays.equals(raw, md.digest(e.getBytes(StandardCharsets.UTF_8)))) return true;
                if (java.util.Arrays.equals(raw, MessageDigest.getInstance(alg).digest(ed.getBytes(StandardCharsets.UTF_8)))) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static boolean hashMatches(String stored, String entered) {
        String clean = stored.toLowerCase(Locale.US).replace("0x", "");
        for (String alg : new String[]{"MD5", "SHA-1", "SHA-256", "SHA-512"}) {
            try {
                byte[] d = MessageDigest.getInstance(alg).digest(entered.getBytes(StandardCharsets.UTF_8));
                StringBuilder b = new StringBuilder();
                for (byte x : d) b.append(String.format(Locale.US, "%02x", x & 0xff));
                if (clean.equals(b.toString())) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    static String foldDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch >= '۰' && ch <= '۹') b.append((char) ('0' + (ch - '۰')));
            else if (ch >= '٠' && ch <= '٩') b.append((char) ('0' + (ch - '٠')));
            else b.append(ch);
        }
        return b.toString();
    }

    static List<String[]> sysUsers(Connection c) throws Exception {
        List<String[]> out = new ArrayList<>();
        String sql = "SELECT user_id, CAST(user_name AS nvarchar(100)), " + col(c, "sys_users", "s", "active", "CAST(1 AS bit)") + " FROM dbo.sys_users s ORDER BY user_id";
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) out.add(new String[]{String.valueOf(r.getInt(1)), norm(r.getString(2)), r.getString(3)});
        }
        return out;
    }

    // ------------------------------------------------------------------ documents

    /** One Atiran document (sales invoice or return/cancel voucher) with its Moadian state. */
    static final class Doc {
        String kind;
        int no;
        int rdf;
        int backKind;
        int refSale;
        String date = "", time = "";
        long shmo;
        String name = "";
        long total, tax;
        boolean active = true;
        String atiranTaxId, atiranRefTaxId;
        int atiranSubmitted;
        Integer tob, custInty;
        String bid, tinb, bpc, bbc;
        long sumLines, tafif, lineDiscounts, barbari;
        Log last;
        final List<Log> chain = new ArrayList<>();

        String title() {
            if (SALE.equals(kind)) return "فاکتور فروش " + no;
            return backKindTitle(backKind) + " " + no;
        }
    }

    static String backKindTitle(int kind) {
        switch (kind) {
            case 1: return "برگشت از فروش";
            case 2: return "برگشت از فروش سنوات قبل";
            case 6: return "برگشت از مشتری";
            case 8: return "ابطال فروش سنوات قبل";
            case 9: return "ابطال فروش";
            default: return "سند برگشتی";
        }
    }

    /** ka_act act_id of a back_sanad kind (VW_AllBargashtiForTax). */
    static int actOfBackKind(int kind) {
        switch (kind) {
            case 1: return 21;
            case 2: return 17;
            case 6: return 27;
            case 8: return 128;
            case 9: return 129;
            default: return 0;
        }
    }

    static List<Doc> sales(Connection c, String from, String to, String search, int limit) throws Exception {
        String cust = "CUSTOMERS";
        StringBuilder sql = new StringBuilder("SELECT TOP (").append(Math.max(1, Math.min(limit, 3000))).append(") s.shfacfo, s.rdf__, CAST(s.[date] AS nvarchar(10)), ")
                .append(nstr(c, "sailfact", "s", "time_", 10)).append(", s.shmo, CAST(s.moname AS nvarchar(300)), ISNULL(s.[all],0), ISNULL(")
                .append(col(c, "sailfact", "s", "barbari", "0")).append(",0), ISNULL(s.tax,0)+ISNULL(").append(col(c, "sailfact", "s", "avarez", "0"))
                .append(",0), CAST(s.active AS nvarchar(2)), ").append(nstr(c, "sailfact", "s", "TaxUniqueID", 100)).append(", ISNULL(")
                .append(col(c, "sailfact", "s", "SubmittedTax", "0")).append(",0), ")
                .append(col(c, cust, "cu", "PersonalityType", "CAST(NULL AS int)")).append(", ")
                .append(nstr(c, cust, "cu", "c_mel", 30)).append(", ").append(nstr(c, cust, "cu", "c_egh", 30)).append(", ")
                .append(nstr(c, cust, "cu", "Shenaseh_Egh", 30)).append(", ").append(nstr(c, cust, "cu", "c_pos", 30)).append(", ")
                .append(col(c, cust, "cu", "TaxInvoiceType", "CAST(NULL AS int)")).append(", ").append(nstr(c, cust, "cu", "CustomerBranch", 20))
                .append(", ISNULL(s.sumlineall,0), ISNULL(s.tafif,0), ISNULL(").append(col(c, "sailfact", "s", "SumTafifAghlam", "0")).append(",0), ")
                .append("b.tob, b.bid, b.tinb, b.bpc, b.bbc, b.inty ")
                .append("FROM dbo.sailfact s INNER JOIN dbo.CUSTOMERS cu ON s.shmo=cu.SHMO LEFT JOIN dbo.meelano_tax_buyers b ON b.shmo=s.shmo ")
                .append("WHERE s.rdf__=(SELECT MAX(x.rdf__) FROM dbo.sailfact x WHERE x.shfacfo=s.shfacfo) AND ISNULL(s.Deleted,0)=0 AND s.[date]>=? AND s.[date]<=? ");
        String q = search == null ? "" : norm(foldDigits(search));
        boolean numeric = q.matches("\\d{1,9}");
        if (!q.isEmpty()) sql.append(numeric ? "AND s.shfacfo=? " : "AND (REPLACE(REPLACE(CAST(s.moname AS nvarchar(300)),N'ي',N'ی'),N'ك',N'ک') LIKE ?) ");
        sql.append("ORDER BY s.shfacfo DESC");
        List<Doc> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setString(1, from);
            ps.setString(2, to);
            if (!q.isEmpty()) {
                if (numeric) ps.setInt(3, Integer.parseInt(q)); else ps.setString(3, "%" + q + "%");
            }
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    Doc d = new Doc();
                    d.kind = SALE;
                    d.no = r.getInt(1);
                    d.rdf = r.getInt(2);
                    d.date = nz(r.getString(3)).trim();
                    d.time = nz(r.getString(4)).trim();
                    d.shmo = r.getLong(5);
                    d.name = norm(r.getString(6));
                    d.barbari = r.getBigDecimal(8) == null ? 0 : r.getBigDecimal(8).longValue();
                    d.total = (r.getBigDecimal(7) == null ? 0 : r.getBigDecimal(7).longValue()) - d.barbari;
                    d.tax = r.getBigDecimal(9) == null ? 0 : r.getBigDecimal(9).longValue();
                    d.active = !"f".equalsIgnoreCase(nz(r.getString(10)).trim());
                    d.atiranTaxId = trim(r.getString(11));
                    d.atiranSubmitted = r.getInt(12);
                    int pt = r.getInt(13);
                    d.tob = r.wasNull() ? null : pt;
                    d.bid = trim(MeelanoTaxInvoice.digits(r.getString(14)));
                    String egh = trim(MeelanoTaxInvoice.digits(r.getString(15)));
                    String sh = trim(MeelanoTaxInvoice.digits(r.getString(16)));
                    d.tinb = egh != null ? egh : sh;
                    d.bpc = trim(MeelanoTaxInvoice.digits(r.getString(17)));
                    int ti = r.getInt(18);
                    d.custInty = r.wasNull() || ti == 0 ? null : ti;
                    d.bbc = trim(MeelanoTaxInvoice.digits(r.getString(19)));
                    d.sumLines = r.getBigDecimal(20) == null ? 0 : r.getBigDecimal(20).longValue();
                    d.tafif = r.getBigDecimal(21) == null ? 0 : r.getBigDecimal(21).longValue();
                    d.lineDiscounts = r.getBigDecimal(22) == null ? 0 : r.getBigDecimal(22).longValue();
                    // Buyer overrides entered in this app win over the Atiran customer card.
                    int otob = r.getInt(23);
                    if (!r.wasNull() && otob > 0) d.tob = otob;
                    String obid = trim(r.getString(24)), otinb = trim(r.getString(25)), obpc = trim(r.getString(26)), obbc = trim(r.getString(27));
                    if (obid != null) d.bid = obid;
                    if (otinb != null) d.tinb = otinb;
                    if (obpc != null) d.bpc = obpc;
                    if (obbc != null) d.bbc = obbc;
                    int oin = r.getInt(28);
                    if (!r.wasNull() && oin > 0) d.custInty = oin;
                    out.add(d);
                }
            }
        }
        attachLogs(c, out);
        return out;
    }

    static Doc sale(Connection c, int shfacfo) throws Exception {
        List<Doc> l = salesByNo(c, shfacfo);
        return l.isEmpty() ? null : l.get(0);
    }

    private static List<Doc> salesByNo(Connection c, int no) throws Exception {
        return sales(c, "0000/00/00", "9999/99/99", String.valueOf(no), 1);
    }

    static List<Doc> backs(Connection c, String from, String to, String search, int limit) throws Exception {
        if (!tableExists(c, "back_sanad")) return new ArrayList<>();
        String sql = "SELECT TOP (" + Math.max(1, Math.min(limit, 3000)) + ") b.shomare, b.kind, ISNULL(b.shfac,0), CAST(b.date_ AS nvarchar(10)), "
                + nstr(c, "back_sanad", "b", "Hour", 10) + ", ISNULL(b.mab,0), CAST(b.moname AS nvarchar(300)), " + col(c, "back_sanad", "b", "Active", "CAST(1 AS bit)") + ", "
                + nstr(c, "back_sanad", "b", "TaxUniqueID", 100) + ", " + nstr(c, "back_sanad", "b", "TaxUniqueIDReference", 100) + ", ISNULL("
                + col(c, "back_sanad", "b", "SubmittedTax", "0") + ",0), ISNULL(" + col(c, "back_sanad", "b", "Shmo", "0") + ",0), ISNULL(b.Tax,0)+ISNULL("
                + col(c, "back_sanad", "b", "Avarez", "0") + ",0) FROM dbo.back_sanad b WHERE b.kind IN (1,2,6,8,9) AND ISNULL(" + col(c, "back_sanad", "b", "Active", "1")
                + ",1)=1 AND b.date_>=? AND b.date_<=? ";
        String q = search == null ? "" : norm(foldDigits(search));
        boolean numeric = q.matches("\\d{1,9}");
        if (!q.isEmpty()) sql += numeric ? "AND (b.shomare=? OR b.shfac=?) " : "AND REPLACE(REPLACE(CAST(b.moname AS nvarchar(300)),N'ي',N'ی'),N'ك',N'ک') LIKE ? ";
        sql += "ORDER BY b.date_ DESC, b.shomare DESC";
        List<Doc> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, from);
            ps.setString(2, to);
            if (!q.isEmpty()) {
                if (numeric) { ps.setInt(3, Integer.parseInt(q)); ps.setInt(4, Integer.parseInt(q)); }
                else ps.setString(3, "%" + q + "%");
            }
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    Doc d = new Doc();
                    d.kind = BACK;
                    d.no = r.getInt(1);
                    d.backKind = r.getInt(2);
                    d.refSale = r.getInt(3);
                    d.date = nz(r.getString(4)).trim();
                    d.time = nz(r.getString(5)).trim();
                    d.total = r.getBigDecimal(6) == null ? 0 : r.getBigDecimal(6).longValue();
                    d.name = norm(r.getString(7));
                    d.atiranTaxId = trim(r.getString(9));
                    d.atiranRefTaxId = trim(r.getString(10));
                    d.atiranSubmitted = r.getInt(11);
                    d.shmo = r.getLong(12);
                    d.tax = r.getBigDecimal(13) == null ? 0 : r.getBigDecimal(13).longValue();
                    out.add(d);
                }
            }
        }
        attachLogs(c, out);
        return out;
    }

    // ------------------------------------------------------------------ lines

    /** Lines of the current revision of a sales invoice (Vw_Tax_tis), with this app's goods overrides. */
    static List<MeelanoTaxInvoice.Line> saleLines(Connection c, int shfacfo, int rdf, Map<String, String> unitMap) throws Exception {
        String sql = "SELECT ss.SHKA, CAST(i.naka AS nvarchar(400)), ISNULL(ss.TEDVAH,0), ISNULL(ss.TEDJOZ,0), ISNULL(NULLIF(i.mohvah,0),1), ISNULL(ss.JOZPRICE,0), "
                + "ISNULL(ss.litakhma,0), ISNULL(" + col(c, "subsailfact", "ss", "ptax", "0") + ",0)+ISNULL(" + col(c, "subsailfact", "ss", "pavarez", "0") + ",0), "
                + "ISNULL(ss.tax,0)+ISNULL(" + col(c, "subsailfact", "ss", "avarez", "0") + ",0), CAST(i.vahsanj AS nvarchar(60)), " + nstr(c, "inventory", "i", "StuffCode", 40) + ", "
                + (has(c, "subsailfact", "Gift") ? "CAST(ISNULL(ss.Gift,0) AS int)" : "0") + ", g.sstid, g.sstt, g.mu, g.vra, ISNULL(" + col(c, "inventory", "i", "ptax", "0") + ",0) "
                + "FROM dbo.subsailfact ss INNER JOIN dbo.inventory i ON ss.SHKA=i.shka LEFT JOIN dbo.meelano_tax_goods g ON g.shka=ss.SHKA "
                + "WHERE ss.shfacfo=? AND ss.rdf__=?";
        List<MeelanoTaxInvoice.Line> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, shfacfo);
            ps.setInt(2, rdf);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    MeelanoTaxInvoice.Line l = new MeelanoTaxInvoice.Line();
                    l.shka = r.getInt(1);
                    l.name = norm(r.getString(2));
                    BigDecimal tv = dec(r, 3), tj = dec(r, 4), moh = dec(r, 5);
                    l.qty = tv.multiply(moh).add(tj);
                    l.fee = dec(r, 6);
                    l.discount = dec(r, 7);
                    l.vatRate = dec(r, 8);
                    l.atiranVat = dec(r, 9);
                    l.unitName = norm(r.getString(10));
                    l.sstid = trim(MeelanoTaxInvoice.digits(r.getString(11)));
                    l.gift = r.getInt(12) != 0;
                    applyGoodsOverride(l, r.getString(13), r.getString(14), r.getString(15), r.getBigDecimal(16));
                    l.mu = l.mu != null ? l.mu : MeelanoTaxInvoice.unitCode(l.unitName, unitMap);
                    out.add(l);
                }
            }
        }
        return out;
    }

    private static void applyGoodsOverride(MeelanoTaxInvoice.Line l, String sstid, String sstt, String mu, BigDecimal vra) {
        String s = trim(MeelanoTaxInvoice.digits(sstid));
        if (s != null) l.sstid = s;
        if (trim(sstt) != null) l.sstt = sstt.trim();
        if (trim(mu) != null) l.mu = mu.trim();
        if (vra != null) l.vatRate = vra;
    }

    private static BigDecimal dec(ResultSet r, int i) throws Exception {
        BigDecimal b = r.getBigDecimal(i);
        return b == null ? BigDecimal.ZERO : b;
    }

    /** Items of one return/cancel voucher (ka_act rows of the matching act, as VW_AllBargashtiForTax joins them). */
    static List<MeelanoTaxInvoice.Line> backLines(Connection c, int backKind, int shomare, Map<String, String> unitMap) throws Exception {
        int act = actOfBackKind(backKind);
        String link = backKind == 6 ? "k.ghno" : "k.sh_back_sanad";
        String sql = "SELECT k.shka, CAST(i.naka AS nvarchar(400)), ISNULL(k.tedvah,0), ISNULL(k.tedjoz,0), ISNULL(NULLIF(i.mohvah,0),1), ISNULL(k.price,0), ISNULL(k.litakhma,0), "
                + "ISNULL(" + col(c, "ka_act", "k", "ptax", "0") + ",0)+ISNULL(" + col(c, "ka_act", "k", "pavarez", "0") + ",0), ISNULL(k.tax,0)+ISNULL(" + col(c, "ka_act", "k", "avarez", "0")
                + ",0), CAST(i.vahsanj AS nvarchar(60)), " + nstr(c, "inventory", "i", "StuffCode", 40) + ", g.sstid, g.sstt, g.mu, g.vra, ISNULL(k.shfac,0) "
                + "FROM dbo.ka_act k INNER JOIN dbo.inventory i ON k.shka=i.shka LEFT JOIN dbo.meelano_tax_goods g ON g.shka=k.shka "
                + "WHERE k.act_id=? AND " + link + "=? AND k.active='t'";
        List<MeelanoTaxInvoice.Line> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, act);
            ps.setInt(2, shomare);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    MeelanoTaxInvoice.Line l = new MeelanoTaxInvoice.Line();
                    l.shka = r.getInt(1);
                    l.name = norm(r.getString(2));
                    l.qty = dec(r, 3).multiply(dec(r, 5)).add(dec(r, 4));
                    l.fee = dec(r, 6);
                    l.discount = dec(r, 7);
                    l.vatRate = dec(r, 8);
                    l.atiranVat = dec(r, 9);
                    l.unitName = norm(r.getString(10));
                    l.sstid = trim(MeelanoTaxInvoice.digits(r.getString(11)));
                    applyGoodsOverride(l, r.getString(12), r.getString(13), r.getString(14), r.getBigDecimal(15));
                    l.mu = l.mu != null ? l.mu : MeelanoTaxInvoice.unitCode(l.unitName, unitMap);
                    out.add(l);
                }
            }
        }
        return out;
    }

    /**
     * Quantity returned per product from a sales invoice by «برگشت از فروش» vouchers (act 21) up to and including
     * voucher {@code uptoShomare} (0 = all).
     */
    static Map<Integer, BigDecimal> returnedQuantities(Connection c, int shfac, int uptoShomare) throws Exception {
        String sql = "SELECT k.shka, SUM(ISNULL(k.tedvah,0)*ISNULL(NULLIF(i.mohvah,0),1)+ISNULL(k.tedjoz,0)) FROM dbo.ka_act k INNER JOIN dbo.inventory i ON k.shka=i.shka "
                + "WHERE k.act_id=21 AND k.shfac=? AND k.active='t' AND EXISTS (SELECT 1 FROM dbo.back_sanad b WHERE b.kind=1 AND b.shomare=k.sh_back_sanad AND ISNULL(b.Active,1)=1)"
                + (uptoShomare > 0 ? " AND k.sh_back_sanad<=?" : "") + " GROUP BY k.shka";
        Map<Integer, BigDecimal> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, shfac);
            if (uptoShomare > 0) ps.setInt(2, uptoShomare);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) out.put(r.getInt(1), dec(r, 2));
            }
        }
        return out;
    }

    /** Earlier «برگشت از فروش» vouchers of the same invoice that have not been accepted by Moadian yet. */
    static List<Integer> unsentEarlierReturns(Connection c, int shfac, int shomare) throws Exception {
        List<Integer> out = new ArrayList<>();
        String sql = "SELECT b.shomare FROM dbo.back_sanad b WHERE b.kind=1 AND b.shfac=? AND b.shomare<? AND ISNULL(b.Active,1)=1 "
                + "AND NOT EXISTS (SELECT 1 FROM dbo.meelano_tax_invoices m WHERE m.doc_kind='BACK' AND m.back_kind=1 AND m.doc_no=b.shomare AND m.status IN ('SUCCESS','EXTERNAL')) ORDER BY b.shomare";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, shfac);
            ps.setInt(2, shomare);
            try (ResultSet r = ps.executeQuery()) { while (r.next()) out.add(r.getInt(1)); }
        }
        return out;
    }

    static Map<String, String> unitMap(Connection c) {
        Map<String, String> m = new HashMap<>();
        if (has(c, "UNITS", "UnitCode")) {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT CAST(UNIT_NAME AS nvarchar(60)), UnitCode FROM dbo.UNITS WHERE UnitCode IS NOT NULL")) {
                while (r.next()) {
                    String code = trim(r.getString(2));
                    if (code != null && !code.equals("0")) m.put(MeelanoTaxInvoice.normName(r.getString(1)), code);
                }
            } catch (Exception ignored) { }
        }
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT unit_name, mu FROM dbo.meelano_tax_units")) {
            while (r.next()) m.put(MeelanoTaxInvoice.normName(r.getString(1)), r.getString(2).trim());
        } catch (Exception ignored) { }
        return m;
    }

    static void saveUnit(Connection c, String unitName, String mu) throws Exception {
        String n = MeelanoTaxInvoice.normName(unitName);
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM dbo.meelano_tax_units WHERE unit_name=?")) { ps.setString(1, n); ps.executeUpdate(); }
        if (mu == null || mu.trim().isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dbo.meelano_tax_units(unit_name, mu) VALUES (?,?)")) {
            ps.setString(1, n);
            ps.setString(2, mu.trim());
            ps.executeUpdate();
        }
    }

    /** Distinct unit names used by products (for the unit-mapping editor). */
    static List<String> productUnits(Connection c) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT DISTINCT CAST(vahsanj AS nvarchar(60)) FROM dbo.inventory WHERE vahsanj IS NOT NULL")) {
            while (r.next()) {
                String n = norm(r.getString(1));
                if (!n.isEmpty() && !out.contains(n)) out.add(n);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ goods & buyers

    static final class Good {
        int shka;
        String name, unit, sstid, sstt, mu, atiranCode;
        BigDecimal vra, atiranRate;
        int soldLines;
    }

    static List<Good> goods(Connection c, String search, boolean onlyMissing, int limit) throws Exception {
        String q = search == null ? "" : norm(foldDigits(search));
        String sql = "SELECT TOP (" + Math.max(1, Math.min(limit, 2500)) + ") i.shka, CAST(i.naka AS nvarchar(400)), CAST(i.vahsanj AS nvarchar(60)), " + nstr(c, "inventory", "i", "StuffCode", 40)
                + ", g.sstid, g.sstt, g.mu, g.vra, ISNULL(" + col(c, "inventory", "i", "ptax", "0") + ",0)+ISNULL(" + col(c, "inventory", "i", "PAvarez", "0") + ",0), "
                + "(SELECT COUNT(*) FROM dbo.subsailfact ss WHERE ss.SHKA=i.shka) "
                + "FROM dbo.inventory i LEFT JOIN dbo.meelano_tax_goods g ON g.shka=i.shka WHERE 1=1 "
                + (has(c, "inventory", "active") ? "AND ISNULL(CAST(i.active AS nvarchar(5)),'t') IN ('t','1','True') " : "")
                + (q.isEmpty() ? "" : (q.matches("\\d{1,9}") ? "AND i.shka=? " : "AND REPLACE(REPLACE(CAST(i.naka AS nvarchar(400)),N'ي',N'ی'),N'ك',N'ک') LIKE ? "))
                + (onlyMissing ? "AND ISNULL(NULLIF(LTRIM(g.sstid),''), NULLIF(LTRIM(" + (has(c, "inventory", "StuffCode") ? "CAST(i.StuffCode AS nvarchar(40))" : "''") + "),'')) IS NULL " : "")
                + "ORDER BY 10 DESC, i.shka";
        List<Good> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (!q.isEmpty()) {
                if (q.matches("\\d{1,9}")) ps.setInt(1, Integer.parseInt(q)); else ps.setString(1, "%" + q + "%");
            }
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    Good g = new Good();
                    g.shka = r.getInt(1);
                    g.name = norm(r.getString(2));
                    g.unit = norm(r.getString(3));
                    g.atiranCode = trim(MeelanoTaxInvoice.digits(r.getString(4)));
                    g.sstid = trim(r.getString(5));
                    g.sstt = trim(r.getString(6));
                    g.mu = trim(r.getString(7));
                    g.vra = r.getBigDecimal(8);
                    g.atiranRate = r.getBigDecimal(9);
                    g.soldLines = r.getInt(10);
                    out.add(g);
                }
            }
        }
        return out;
    }

    static void saveGood(Connection c, Good g, boolean writeAtiran, String user) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM dbo.meelano_tax_goods WHERE shka=?")) { ps.setInt(1, g.shka); ps.executeUpdate(); }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dbo.meelano_tax_goods(shka, sstid, sstt, mu, vra, updated_by) VALUES (?,?,?,?,?,?)")) {
            ps.setInt(1, g.shka);
            setNullableString(ps, 2, trim(g.sstid));
            setNullableString(ps, 3, trim(g.sstt));
            setNullableString(ps, 4, trim(g.mu));
            if (g.vra == null) ps.setNull(5, Types.DECIMAL); else ps.setBigDecimal(5, g.vra);
            setNullableString(ps, 6, user);
            ps.executeUpdate();
        }
        if (writeAtiran && has(c, "inventory", "StuffCode") && trim(g.sstid) != null) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.inventory SET StuffCode=? WHERE shka=?")) {
                ps.setString(1, g.sstid.trim());
                ps.setInt(2, g.shka);
                ps.executeUpdate();
            }
        }
    }

    static final class BuyerRow {
        long shmo;
        String name;
        Integer tob, inty;
        String bid, tinb, bpc, bbc;
        int invoices;
        boolean override;
    }

    static List<BuyerRow> buyers(Connection c, String search, boolean onlyWithSales, int limit) throws Exception {
        String q = search == null ? "" : norm(foldDigits(search));
        String cust = "CUSTOMERS";
        String sql = "SELECT TOP (" + Math.max(1, Math.min(limit, 3000)) + ") cu.SHMO, CAST(cu.MONAME AS nvarchar(300)), " + col(c, cust, "cu", "PersonalityType", "CAST(NULL AS int)") + ", "
                + nstr(c, cust, "cu", "c_mel", 30) + ", " + nstr(c, cust, "cu", "c_egh", 30) + ", " + nstr(c, cust, "cu", "Shenaseh_Egh", 30) + ", " + nstr(c, cust, "cu", "c_pos", 30) + ", "
                + col(c, cust, "cu", "TaxInvoiceType", "CAST(NULL AS int)") + ", " + nstr(c, cust, "cu", "CustomerBranch", 20) + ", b.tob, b.bid, b.tinb, b.bpc, b.bbc, b.inty, "
                + "(SELECT COUNT(*) FROM dbo.sailfact s WHERE s.shmo=cu.SHMO AND ISNULL(s.Deleted,0)=0) AS cnt "
                + "FROM dbo.CUSTOMERS cu LEFT JOIN dbo.meelano_tax_buyers b ON b.shmo=cu.SHMO WHERE 1=1 "
                + (q.isEmpty() ? "" : (q.matches("\\d{1,12}") ? "AND cu.SHMO=? " : "AND REPLACE(REPLACE(CAST(cu.MONAME AS nvarchar(300)),N'ي',N'ی'),N'ك',N'ک') LIKE ? "))
                + (onlyWithSales ? "AND EXISTS (SELECT 1 FROM dbo.sailfact s WHERE s.shmo=cu.SHMO AND ISNULL(s.Deleted,0)=0) " : "")
                + "ORDER BY cnt DESC, cu.SHMO";
        List<BuyerRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (!q.isEmpty()) {
                if (q.matches("\\d{1,12}")) ps.setLong(1, Long.parseLong(q)); else ps.setString(1, "%" + q + "%");
            }
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    BuyerRow b = new BuyerRow();
                    b.shmo = r.getLong(1);
                    b.name = norm(r.getString(2));
                    int pt = r.getInt(3);
                    b.tob = r.wasNull() ? null : pt;
                    b.bid = trim(MeelanoTaxInvoice.digits(r.getString(4)));
                    String egh = trim(MeelanoTaxInvoice.digits(r.getString(5))), sh = trim(MeelanoTaxInvoice.digits(r.getString(6)));
                    b.tinb = egh != null ? egh : sh;
                    b.bpc = trim(MeelanoTaxInvoice.digits(r.getString(7)));
                    int ti = r.getInt(8);
                    b.inty = r.wasNull() || ti == 0 ? null : ti;
                    b.bbc = trim(MeelanoTaxInvoice.digits(r.getString(9)));
                    int otob = r.getInt(10);
                    if (!r.wasNull() && otob > 0) { b.tob = otob; b.override = true; }
                    String obid = trim(r.getString(11)), otinb = trim(r.getString(12)), obpc = trim(r.getString(13)), obbc = trim(r.getString(14));
                    if (obid != null) { b.bid = obid; b.override = true; }
                    if (otinb != null) { b.tinb = otinb; b.override = true; }
                    if (obpc != null) { b.bpc = obpc; b.override = true; }
                    if (obbc != null) { b.bbc = obbc; b.override = true; }
                    int oin = r.getInt(15);
                    if (!r.wasNull() && oin > 0) { b.inty = oin; b.override = true; }
                    b.invoices = r.getInt(16);
                    out.add(b);
                }
            }
        }
        return out;
    }

    static void saveBuyer(Connection c, BuyerRow b, boolean writeAtiran, String user) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM dbo.meelano_tax_buyers WHERE shmo=?")) { ps.setLong(1, b.shmo); ps.executeUpdate(); }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dbo.meelano_tax_buyers(shmo, tob, bid, tinb, bpc, bbc, inty, updated_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, b.shmo);
            if (b.tob == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, b.tob);
            setNullableString(ps, 3, trim(MeelanoTaxInvoice.digits(b.bid)));
            setNullableString(ps, 4, trim(MeelanoTaxInvoice.digits(b.tinb)));
            setNullableString(ps, 5, trim(MeelanoTaxInvoice.digits(b.bpc)));
            setNullableString(ps, 6, trim(MeelanoTaxInvoice.digits(b.bbc)));
            if (b.inty == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, b.inty);
            setNullableString(ps, 8, user);
            ps.executeUpdate();
        }
        if (!writeAtiran) return;
        List<String> sets = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        if (b.tob != null && has(c, "CUSTOMERS", "PersonalityType")) { sets.add("PersonalityType=?"); vals.add(b.tob); }
        if (trim(b.bid) != null && has(c, "CUSTOMERS", "c_mel")) { sets.add("c_mel=?"); vals.add(MeelanoTaxInvoice.digits(b.bid)); }
        if (trim(b.tinb) != null && has(c, "CUSTOMERS", "c_egh")) { sets.add("c_egh=?"); vals.add(MeelanoTaxInvoice.digits(b.tinb)); }
        if (trim(b.bpc) != null && has(c, "CUSTOMERS", "c_pos")) { sets.add("c_pos=?"); vals.add(MeelanoTaxInvoice.digits(b.bpc)); }
        if (b.inty != null && has(c, "CUSTOMERS", "TaxInvoiceType")) { sets.add("TaxInvoiceType=?"); vals.add(b.inty); }
        if (sets.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.CUSTOMERS SET " + String.join(", ", sets) + " WHERE SHMO=?")) {
            int i = 1;
            for (Object v : vals) {
                if (v instanceof Integer) ps.setInt(i++, (Integer) v); else ps.setString(i++, String.valueOf(v));
            }
            ps.setLong(i, b.shmo);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ send log

    static final class Log {
        long id;
        String docKind;
        int docNo, backKind, rdf, chainSale, ins, inty;
        String taxid, irtaxid, uid, reference, status, kartable, errors, warnings, payload, contentHash, memoryId, env, user, createdAt, note, customerName, docDate;
        long serial, indatim, tbill, tvam, customerShmo;

        boolean live() { return "SUCCESS".equals(status) || "EXTERNAL".equals(status) || pending(); }

        boolean pending() { return "PENDING".equals(status) || "SENDING".equals(status) || "IN_PROGRESS".equals(status) || "UNKNOWN".equals(status); }

        boolean accepted() { return "SUCCESS".equals(status) || "EXTERNAL".equals(status); }
    }

    private static final String LOG_COLS = "id, doc_kind, doc_no, ISNULL(back_kind,0), ISNULL(rdf,0), ISNULL(chain_sale,0), ins, ISNULL(inty,0), taxid, irtaxid, uid, reference_number, status, "
            + "kartable_status, errors, warnings, payload, content_hash, memory_id, env, user_name, CONVERT(nvarchar(19), created_at, 120), note, ISNULL(serial,0), ISNULL(indatim,0), "
            + "ISNULL(tbill,0), ISNULL(tvam,0), customer_name, doc_date, ISNULL(customer_shmo,0)";

    private static Log readLog(ResultSet r) throws Exception {
        Log l = new Log();
        l.id = r.getLong(1);
        l.docKind = r.getString(2);
        l.docNo = r.getInt(3);
        l.backKind = r.getInt(4);
        l.rdf = r.getInt(5);
        l.chainSale = r.getInt(6);
        l.ins = r.getInt(7);
        l.inty = r.getInt(8);
        l.taxid = r.getString(9);
        l.irtaxid = r.getString(10);
        l.uid = r.getString(11);
        l.reference = r.getString(12);
        l.status = r.getString(13);
        l.kartable = r.getString(14);
        l.errors = r.getString(15);
        l.warnings = r.getString(16);
        l.payload = r.getString(17);
        l.contentHash = r.getString(18);
        l.memoryId = r.getString(19);
        l.env = r.getString(20);
        l.user = r.getString(21);
        l.createdAt = r.getString(22);
        l.note = r.getString(23);
        l.serial = r.getLong(24);
        l.indatim = r.getLong(25);
        l.tbill = r.getLong(26);
        l.tvam = r.getLong(27);
        l.customerName = r.getString(28);
        l.docDate = r.getString(29);
        l.customerShmo = r.getLong(30);
        return l;
    }

    /** Attaches the send history: a sale gets its whole chain (sale, corrections, returns, cancels); a voucher its own rows. */
    static void attachLogs(Connection c, List<Doc> docs) throws Exception {
        if (docs.isEmpty()) return;
        Map<String, Doc> byKey = new HashMap<>();
        Map<Integer, Doc> saleByNo = new HashMap<>();
        StringBuilder saleIds = new StringBuilder(), backIds = new StringBuilder();
        for (Doc d : docs) {
            if (SALE.equals(d.kind)) {
                saleByNo.put(d.no, d);
                saleIds.append(saleIds.length() == 0 ? "" : ",").append(d.no);
            } else {
                byKey.put(d.backKind + ":" + d.no, d);
                backIds.append(backIds.length() == 0 ? "" : ",").append(d.no);
            }
        }
        StringBuilder where = new StringBuilder();
        if (saleIds.length() > 0) where.append("chain_sale IN (").append(saleIds).append(")");
        if (backIds.length() > 0) where.append(where.length() == 0 ? "" : " OR ").append("(doc_kind='BACK' AND doc_no IN (").append(backIds).append("))");
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT " + LOG_COLS + " FROM dbo.meelano_tax_invoices WHERE " + where + " ORDER BY id")) {
            while (r.next()) {
                Log l = readLog(r);
                Doc sale = saleByNo.get(l.chainSale);
                if (sale != null) {
                    sale.chain.add(l);
                    if (SALE.equals(l.docKind) && l.docNo == sale.no) sale.last = l;
                }
                if (BACK.equals(l.docKind)) {
                    Doc b = byKey.get(l.backKind + ":" + l.docNo);
                    if (b != null) { b.chain.add(l); b.last = l; }
                }
            }
        }
    }

    static List<Log> chain(Connection c, int saleNo) throws Exception {
        List<Log> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + LOG_COLS + " FROM dbo.meelano_tax_invoices WHERE chain_sale=? ORDER BY id")) {
            ps.setInt(1, saleNo);
            try (ResultSet r = ps.executeQuery()) { while (r.next()) out.add(readLog(r)); }
        }
        return out;
    }

    static List<Log> logs(Connection c, String statusFilter, String search, int limit) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT TOP (").append(Math.max(1, Math.min(limit, 2000))).append(") ").append(LOG_COLS).append(" FROM dbo.meelano_tax_invoices WHERE 1=1 ");
        List<String> params = new ArrayList<>();
        if (statusFilter != null && !statusFilter.isEmpty()) {
            if (statusFilter.equals("OPEN")) sql.append("AND status IN ('PENDING','SENDING','IN_PROGRESS','UNKNOWN') ");
            else { sql.append("AND status=? "); params.add(statusFilter); }
        }
        String q = search == null ? "" : norm(foldDigits(search)).trim();
        if (!q.isEmpty()) {
            sql.append("AND (taxid LIKE ? OR reference_number LIKE ? OR customer_name LIKE ? OR CAST(doc_no AS nvarchar(20))=?) ");
            params.add("%" + q.toUpperCase(Locale.US) + "%");
            params.add("%" + q + "%");
            params.add("%" + q + "%");
            params.add(q);
        }
        sql.append("ORDER BY id DESC");
        List<Log> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
            try (ResultSet r = ps.executeQuery()) { while (r.next()) out.add(readLog(r)); }
        }
        return out;
    }

    static List<Log> openLogs(Connection c, int limit) throws Exception { return logs(c, "OPEN", null, limit); }

    static List<Log> acceptedWithoutFinalKartable(Connection c, int limit) throws Exception {
        List<Log> out = new ArrayList<>();
        String sql = "SELECT TOP (" + limit + ") " + LOG_COLS + " FROM dbo.meelano_tax_invoices WHERE status='SUCCESS' AND taxid IS NOT NULL "
                + "AND (kartable_status IS NULL OR kartable_status IN ('AWAITING_REACTION','') OR kartable_status LIKE 'AWAITING%') ORDER BY id DESC";
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { while (r.next()) out.add(readLog(r)); }
        return out;
    }

    /** Counts per status for the dashboard. */
    static Map<String, Integer> statusCounts(Connection c) throws Exception {
        Map<String, Integer> m = new LinkedHashMap<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT status, COUNT(*) FROM dbo.meelano_tax_invoices GROUP BY status")) {
            while (r.next()) m.put(r.getString(1), r.getInt(2));
        }
        return m;
    }

    static long[] acceptedTotals(Connection c, String fromIso) throws Exception {
        String sql = "SELECT COUNT(*), ISNULL(SUM(CASE WHEN ins=3 THEN -tbill WHEN ins=4 THEN 0 ELSE tbill END),0), ISNULL(SUM(CASE WHEN ins=3 THEN -tvam WHEN ins=4 THEN 0 ELSE tvam END),0) "
                + "FROM dbo.meelano_tax_invoices WHERE status IN ('SUCCESS','EXTERNAL') AND created_at>=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fromIso);
            try (ResultSet r = ps.executeQuery()) {
                if (r.next()) return new long[]{r.getLong(1), r.getLong(2), r.getLong(3)};
            }
        }
        return new long[]{0, 0, 0};
    }

    // ------------------------------------------------------------------ reservation & serial

    /**
     * Reserves a submission: under an application lock per document it refuses a second live submission of the same
     * subject, allocates the next serial and inserts the log row in status SENDING. Returns the row id.
     *
     * @throws IllegalStateException with a Persian message when the document already has a live submission.
     */
    static long reserve(Connection c, Log l, long serialSeed) throws Exception {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            try (PreparedStatement ps = c.prepareStatement("DECLARE @r int; EXEC @r = sp_getapplock @Resource=?, @LockMode='Exclusive', @LockOwner='Transaction', @LockTimeout=20000; SELECT @r")) {
                ps.setString(1, "meelano_tax_" + l.docKind + "_" + l.backKind + "_" + l.docNo);
                boolean rs = ps.execute();
                while (!rs && ps.getUpdateCount() != -1) rs = ps.getMoreResults();
                if (rs) try (ResultSet r = ps.getResultSet()) {
                    if (r.next() && r.getInt(1) < 0) throw new IllegalStateException("این سند همین حالا در دستگاه دیگری در حال ارسال است.");
                }
            }
            String dup = "SELECT TOP (1) status, taxid FROM dbo.meelano_tax_invoices WITH (UPDLOCK, HOLDLOCK) WHERE doc_kind=? AND doc_no=? AND ISNULL(back_kind,0)=? AND ins=? "
                    + "AND ISNULL(rdf,0)=? AND status IN ('SENDING','PENDING','IN_PROGRESS','UNKNOWN','SUCCESS','EXTERNAL')" + (l.ins == MeelanoTaxInvoice.INS_CORRECTION ? " AND content_hash=?" : "");
            try (PreparedStatement ps = c.prepareStatement(dup)) {
                ps.setString(1, l.docKind);
                ps.setInt(2, l.docNo);
                ps.setInt(3, l.backKind);
                ps.setInt(4, l.ins);
                ps.setInt(5, l.rdf);
                if (l.ins == MeelanoTaxInvoice.INS_CORRECTION) ps.setString(6, nz(l.contentHash));
                try (ResultSet r = ps.executeQuery()) {
                    if (r.next()) throw new IllegalStateException("این صورتحساب (" + MeelanoTaxInvoice.insTitle(l.ins) + ") قبلاً ارسال شده است"
                            + (r.getString(2) == null ? "" : " — شماره مالیاتی " + r.getString(2)) + " (وضعیت: " + MeelanoTaxApi.statusTitle(r.getString(1)) + ").");
                }
            }
            long serial = nextSerial(c, l.memoryId, serialSeed);
            l.serial = serial;
            l.taxid = MeelanoTaxCrypto.taxId(l.memoryId, serial, l.indatim);
            long id = insertLog(c, l, "SENDING");
            c.commit();
            return id;
        } catch (Exception e) {
            try { c.rollback(); } catch (Exception ignored) { }
            throw e;
        } finally {
            try { c.setAutoCommit(auto); } catch (Exception ignored) { }
        }
    }

    /** Next serial of the fiscal memory (inside the caller's transaction); starts above {@code seed}. */
    static long nextSerial(Connection c, String memoryId, long seed) throws Exception {
        Long last = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT last_serial FROM dbo.meelano_tax_serial WITH (UPDLOCK, HOLDLOCK) WHERE memory_id=?")) {
            ps.setString(1, memoryId);
            try (ResultSet r = ps.executeQuery()) { if (r.next()) last = r.getLong(1); }
        }
        long next;
        if (last == null) {
            next = Math.max(1, seed);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dbo.meelano_tax_serial(memory_id, last_serial) VALUES (?,?)")) {
                ps.setString(1, memoryId);
                ps.setLong(2, next);
                ps.executeUpdate();
            }
        } else {
            next = last + 1;
            try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_tax_serial SET last_serial=? WHERE memory_id=?")) {
                ps.setLong(1, next);
                ps.setString(2, memoryId);
                ps.executeUpdate();
            }
        }
        if (next > MeelanoTaxCrypto.MAX_SERIAL) throw new IllegalStateException("ظرفیت سریال صورتحساب این حافظه تمام شده است.");
        return next;
    }

    static long lastSerial(Connection c, String memoryId) {
        try (PreparedStatement ps = c.prepareStatement("SELECT last_serial FROM dbo.meelano_tax_serial WHERE memory_id=?")) {
            ps.setString(1, memoryId);
            try (ResultSet r = ps.executeQuery()) { if (r.next()) return r.getLong(1); }
        } catch (Exception ignored) { }
        return 0;
    }

    static long insertLog(Connection c, Log l, String status) throws Exception {
        String sql = "INSERT INTO dbo.meelano_tax_invoices(doc_kind, doc_no, back_kind, rdf, chain_sale, ins, inty, taxid, irtaxid, serial, memory_id, env, uid, status, "
                + "payload, content_hash, indatim, tbill, tvam, customer_shmo, customer_name, doc_date, user_name, note, warnings) OUTPUT inserted.id VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, l.docKind);
            ps.setInt(2, l.docNo);
            ps.setInt(3, l.backKind);
            ps.setInt(4, l.rdf);
            ps.setInt(5, l.chainSale);
            ps.setInt(6, l.ins);
            ps.setInt(7, l.inty);
            setNullableString(ps, 8, l.taxid);
            setNullableString(ps, 9, l.irtaxid);
            ps.setLong(10, l.serial);
            setNullableString(ps, 11, l.memoryId);
            setNullableString(ps, 12, l.env);
            setNullableString(ps, 13, l.uid);
            ps.setString(14, status);
            setNullableString(ps, 15, l.payload);
            setNullableString(ps, 16, l.contentHash);
            ps.setLong(17, l.indatim);
            ps.setLong(18, l.tbill);
            ps.setLong(19, l.tvam);
            ps.setLong(20, l.customerShmo);
            setNullableString(ps, 21, l.customerName);
            setNullableString(ps, 22, l.docDate);
            setNullableString(ps, 23, l.user);
            setNullableString(ps, 24, l.note);
            setNullableString(ps, 25, l.warnings);
            try (ResultSet r = ps.executeQuery()) {
                if (r.next()) { l.id = r.getLong(1); l.status = status; return l.id; }
            }
        }
        throw new IllegalStateException("ثبت سابقه ارسال انجام نشد.");
    }

    /** Replaces the payload once the tax id is known (the invoice JSON contains it). */
    static void updatePayload(Connection c, long id, String payload, long tbill, long tvam) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_tax_invoices SET payload=?, tbill=?, tvam=?, updated_at=GETDATE() WHERE id=?")) {
            ps.setString(1, payload);
            ps.setLong(2, tbill);
            ps.setLong(3, tvam);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    static void updateStatus(Connection c, long id, String status, String reference, String errors, String warnings) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_tax_invoices SET status=?, reference_number=ISNULL(?, reference_number), errors=?, "
                + "warnings=ISNULL(?, warnings), updated_at=GETDATE() WHERE id=?")) {
            ps.setString(1, status);
            setNullableString(ps, 2, reference);
            setNullableString(ps, 3, errors);
            setNullableString(ps, 4, warnings);
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    static void updateKartable(Connection c, long id, String kartable) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_tax_invoices SET kartable_status=?, updated_at=GETDATE() WHERE id=?")) {
            ps.setString(1, kartable);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    static void deleteDraft(Connection c, long id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_tax_invoices SET status='VOID', note=ISNULL(note,'')+N' | کنار گذاشته شد', updated_at=GETDATE() WHERE id=? AND status IN ('FAILED','LOCAL_ERROR','NOT_FOUND','TIMEOUT')")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** Latest log row with this tax id (any status), or null. */
    static Log logByTaxid(Connection c, String taxid) throws Exception {
        if (taxid == null || taxid.trim().isEmpty()) return null;
        try (PreparedStatement ps = c.prepareStatement("SELECT TOP (1) " + LOG_COLS + " FROM dbo.meelano_tax_invoices WHERE taxid=? ORDER BY id DESC")) {
            ps.setString(1, taxid.trim().toUpperCase(Locale.US));
            try (ResultSet r = ps.executeQuery()) { return r.next() ? readLog(r) : null; }
        }
    }

    /** Raises the serial counter to at least {@code serial} (after recording an externally sent tax id of our memory). */
    static void bumpSerial(Connection c, String memoryId, long serial) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_tax_serial SET last_serial=? WHERE memory_id=? AND last_serial<?")) {
            ps.setLong(1, serial);
            ps.setString(2, memoryId);
            ps.setLong(3, serial);
            if (ps.executeUpdate() > 0) return;
        }
        if (lastSerial(c, memoryId) > 0) return;
        try (PreparedStatement ps = c.prepareStatement("IF NOT EXISTS (SELECT 1 FROM dbo.meelano_tax_serial WHERE memory_id=?) INSERT INTO dbo.meelano_tax_serial(memory_id, last_serial) VALUES (?,?)")) {
            ps.setString(1, memoryId);
            ps.setString(2, memoryId);
            ps.setLong(3, serial);
            ps.executeUpdate();
        }
    }

    static Log logById(Connection c, long id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + LOG_COLS + " FROM dbo.meelano_tax_invoices WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet r = ps.executeQuery()) { return r.next() ? readLog(r) : null; }
        }
    }

    // ------------------------------------------------------------------ Atiran native columns

    /**
     * Mirrors the Moadian result into Atiran's own tax columns (sailfact / back_sanad) and TaxSystemLog, so Atiran's
     * «ارسال به سامانه مودیان» screens show the same state. Only columns that exist are written.
     */
    static void writeBack(Connection c, Log l, boolean accepted, String errorText, String userName, int atiranUserId) throws Exception {
        String now = MeelanoJalali.format(MeelanoJalali.today());
        String time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date());
        String text = accepted ? "ارسال موفق (" + MeelanoTaxInvoice.insTitle(l.ins) + ")" : "خطا در ارسال";
        Map<String, Object> set = new LinkedHashMap<>();
        if (SALE.equals(l.docKind)) {
            if (accepted) {
                set.put("TaxUniqueID", l.taxid);
                set.put("UidTax", l.uid);
                set.put("TaxRefrenceNumber", l.reference);
                set.put("SubmittedTax", 1);
                set.put("InvoiceSerialTax", MeelanoTaxCrypto.inno(l.serial));
                set.put("TypeInvoiceSentToMoadiyan", l.inty);
                set.put("FiscalId", l.memoryId);
                set.put("MsgErrorTax", "");
            } else {
                set.put("MsgErrorTax", clip(errorText, 3500));
            }
            set.put("SubmitTaxText", text);
            set.put("DateSendToTaxSystem", now);
            set.put("TimeSendToTaxSystem", time);
            updateExisting(c, "sailfact", set, "shfacfo=? AND rdf__=(SELECT MAX(x.rdf__) FROM dbo.sailfact x WHERE x.shfacfo=?)", l.docNo, l.docNo);
        } else {
            if (accepted) {
                set.put("TaxUniqueID", l.taxid);
                set.put("TaxUniqueIDReference", l.irtaxid);
                set.put("UIdTax", l.uid);
                set.put("TaxRefrenceNumber", l.reference);
                set.put("SubmittedTax", 1);
                set.put("InvoiceSerialTax", MeelanoTaxCrypto.inno(l.serial));
                set.put("FiscalId", l.memoryId);
                set.put("MsgErrorTax", "");
            } else {
                set.put("MsgErrorTax", clip(errorText, 3500));
            }
            set.put("SubmitTaxText", text);
            set.put("DateSendToSystemTax", now);
            set.put("TImeSendToSystemTax", time);
            updateExisting(c, "back_sanad", set, "shomare=? AND kind=?", l.docNo, l.backKind);
        }
        taxSystemLog(c, l, text + (accepted ? "" : ": " + clip(errorText, 800)) + " — پخش درخشان مودیان (" + nz(userName) + ")", now, time, atiranUserId);
    }

    private static void updateExisting(Connection c, String table, Map<String, Object> set, String where, Object... args) throws Exception {
        List<String> parts = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (Map.Entry<String, Object> e : set.entrySet()) {
            if (!has(c, table, e.getKey())) continue;
            parts.add("[" + e.getKey() + "]=?");
            vals.add(e.getValue());
        }
        if (parts.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo." + table + " SET " + String.join(", ", parts) + " WHERE " + where)) {
            int i = 1;
            for (Object v : vals) {
                if (v == null) ps.setNull(i++, Types.VARCHAR);
                else if (v instanceof Integer) ps.setInt(i++, (Integer) v);
                else ps.setString(i++, String.valueOf(v));
            }
            for (Object a : args) ps.setInt(i++, ((Number) a).intValue());
            ps.executeUpdate();
        }
    }

    private static void taxSystemLog(Connection c, Log l, String description, String date, String time, int userId) {
        if (!tableExists(c, "TaxSystemLog")) return;
        try {
            boolean identity = false;
            try (PreparedStatement ps = c.prepareStatement("SELECT COLUMNPROPERTY(OBJECT_ID('dbo.TaxSystemLog'),'Rdf','IsIdentity')")) {
                try (ResultSet r = ps.executeQuery()) { if (r.next()) identity = r.getInt(1) == 1; }
            }
            String cols = "DocumentNumber, DocumentType, UserId, ChangeDate, ChangeTime, Description, TaxUniqueID, Uid, RefrenceNumber, FiscalId";
            String sql = identity
                    ? "INSERT INTO dbo.TaxSystemLog(" + cols + ") VALUES (?,?,?,?,?,?,?,?,?,?)"
                    : "INSERT INTO dbo.TaxSystemLog(Rdf, " + cols + ") SELECT ISNULL(MAX(Rdf),0)+1,?,?,?,?,?,?,?,?,?,? FROM dbo.TaxSystemLog WITH (UPDLOCK, HOLDLOCK)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, l.docNo);
                ps.setInt(2, SALE.equals(l.docKind) ? 1 : (l.backKind > 0 ? 100 + l.backKind : 2));
                ps.setInt(3, userId);
                ps.setString(4, date);
                ps.setString(5, time);
                ps.setString(6, clip(description, 990));
                setNullableString(ps, 7, l.taxid);
                setNullableString(ps, 8, l.uid);
                setNullableString(ps, 9, l.reference);
                setNullableString(ps, 10, l.memoryId);
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
            // TaxSystemLog is informational; its absence or a different shape must not break a successful send.
        }
    }

    static String nz(String s) { return s == null ? "" : s; }

    static String clip(String s, int n) { return s == null ? "" : (s.length() <= n ? s : s.substring(0, n)); }

    /** Metadata sanity: the Atiran tables the app needs. Returns missing names. */
    static List<String> missingTables(Connection c) {
        List<String> out = new ArrayList<>();
        for (String t : new String[]{"sailfact", "subsailfact", "CUSTOMERS", "inventory", "ka_act", "back_sanad", "sys_users"}) {
            if (!tableExists(c, t)) out.add(t);
        }
        return out;
    }

    static String serverVersion(Connection c) {
        try {
            DatabaseMetaData md = c.getMetaData();
            return md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
        } catch (Exception e) {
            return "";
        }
    }
}
