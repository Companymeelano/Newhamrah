package ir.meelano.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * «تحویل بار» (staff app, v5.8). Every final sales invoice that the store staff (محمودی / نظری) register in Atiran becomes
 * a delivery job that everybody signed in to the staff app sees. One person takes it (an atomic
 * {@code UPDATE … WHERE status='open'}, so a second person can never take the same invoice), ticks the items as they are
 * handed over, may pass the job to a colleague (who has to accept it), and closes it with the customer's receipt:
 * receiver's name, signature and location. A closed delivery is read-only.
 *
 * <p>Atiran is ONLY read here (sailfact, subsailfact, CUSTOMERS, inventory, sys_users); everything that is written lives in
 * the app's own tables dbo.meelano_delivery, dbo.meelano_delivery_item, dbo.meelano_delivery_log and dbo.meelano_staff_user.
 * An invoice that is deleted or voided in Atiran marks its delivery «cancelled».
 */
final class MeelanoDelivery {
    private MeelanoDelivery() { }

    static final String OPEN = "open", CLAIMED = "claimed", DELIVERED = "delivered", PARTIAL = "partial", CANCELLED = "cancelled", SKIPPED = "skipped";
    static final String CHANNEL = "meelano_delivery";
    static final String PREF_USER = "staff_job_user", PREF_USER_NAME = "staff_job_user_name", PREF_OPEN_MAX = "delivery_seen_open_id", PREF_LOG_MAX = "delivery_seen_log_id";
    static final String EXTRA_PAGE = "meelano_open_page";
    private static volatile boolean ready = false;

    static String norm(String s) {
        return s == null ? "" : s.replace('ي', 'ی').replace('ك', 'ک').replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static String statusFa(String st) {
        switch (nz(st)) {
            case OPEN: return "آماده تحویل";
            case CLAIMED: return "در حال تحویل";
            case DELIVERED: return "تحویل شد";
            case PARTIAL: return "تحویل ناقص";
            case CANCELLED: return "فاکتور باطل شد";
            case SKIPPED: return "بدون نیاز به ارسال";
            default: return nz(st);
        }
    }

    static boolean isFinal(String st) { return DELIVERED.equals(st) || PARTIAL.equals(st) || CANCELLED.equals(st) || SKIPPED.equals(st); }

    // ------------------------------------------------------------------------------------------- tables
    static void ensureTables(Connection c) throws SQLException {
        if (ready) return;
        try (Statement st = c.createStatement()) {
            st.execute("IF OBJECT_ID(N'dbo.meelano_chat_settings',N'U') IS NULL CREATE TABLE dbo.meelano_chat_settings (setting_key nvarchar(80) NOT NULL PRIMARY KEY, setting_value nvarchar(max) NULL, updated_at datetime2 NOT NULL DEFAULT SYSDATETIME())");
            st.execute("IF OBJECT_ID(N'dbo.meelano_delivery',N'U') IS NULL CREATE TABLE dbo.meelano_delivery ("
                    + "id bigint IDENTITY(1,1) NOT NULL PRIMARY KEY, shfacfo bigint NOT NULL, rdf__ int NOT NULL, inv_date nvarchar(10) NULL, inv_time nvarchar(8) NULL, "
                    + "shmo bigint NULL, customer nvarchar(300) NULL, address nvarchar(600) NULL, phone nvarchar(80) NULL, total decimal(19,2) NULL, items_count int NULL, "
                    + "registered_by nvarchar(120) NULL, registered_uid int NULL, vis_rdf int NULL, "
                    + "status nvarchar(20) NOT NULL DEFAULT N'open', assignee nvarchar(120) NULL, assignee_name nvarchar(200) NULL, claimed_at datetime2 NULL, "
                    + "pending_to nvarchar(120) NULL, pending_to_name nvarchar(200) NULL, pending_at datetime2 NULL, pending_note nvarchar(300) NULL, "
                    + "delivered_at datetime2 NULL, receiver_name nvarchar(200) NULL, receiver_phone nvarchar(60) NULL, signature varbinary(max) NULL, "
                    + "sign_lat float NULL, sign_lng float NULL, sign_acc float NULL, note nvarchar(600) NULL, close_reason nvarchar(300) NULL, closed_by nvarchar(120) NULL, "
                    + "inv_changed bit NOT NULL DEFAULT 0, created_at datetime2 NOT NULL DEFAULT SYSDATETIME(), updated_at datetime2 NOT NULL DEFAULT SYSDATETIME(), "
                    + "CONSTRAINT UQ_meelano_delivery_inv UNIQUE (shfacfo, rdf__))");
            st.execute("IF OBJECT_ID(N'dbo.meelano_delivery_item',N'U') IS NULL CREATE TABLE dbo.meelano_delivery_item ("
                    + "id bigint IDENTITY(1,1) NOT NULL PRIMARY KEY, delivery_id bigint NOT NULL, line_no int NOT NULL DEFAULT 0, shka bigint NULL, name nvarchar(400) NULL, "
                    + "qty decimal(19,3) NULL, cartons decimal(19,3) NULL, pieces decimal(19,3) NULL, per_carton int NULL, unit nvarchar(60) NULL, amount decimal(19,2) NULL, "
                    + "state nvarchar(12) NOT NULL DEFAULT N'pending', reason nvarchar(300) NULL, changed_by nvarchar(120) NULL, changed_at datetime2 NULL)");
            st.execute("IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name=N'IX_meelano_delivery_item_d') CREATE INDEX IX_meelano_delivery_item_d ON dbo.meelano_delivery_item(delivery_id)");
            st.execute("IF OBJECT_ID(N'dbo.meelano_delivery_log',N'U') IS NULL CREATE TABLE dbo.meelano_delivery_log ("
                    + "id bigint IDENTITY(1,1) NOT NULL PRIMARY KEY, delivery_id bigint NOT NULL, action nvarchar(30) NOT NULL, actor nvarchar(120) NULL, actor_name nvarchar(200) NULL, "
                    + "target nvarchar(120) NULL, note nvarchar(500) NULL, created_at datetime2 NOT NULL DEFAULT SYSDATETIME())");
            st.execute("IF OBJECT_ID(N'dbo.meelano_staff_user',N'U') IS NULL CREATE TABLE dbo.meelano_staff_user (username nvarchar(120) NOT NULL PRIMARY KEY, "
                    + "display_name nvarchar(200) NULL, last_seen datetime2 NOT NULL DEFAULT SYSDATETIME(), active bit NOT NULL DEFAULT 1)");
            st.execute("IF OBJECT_ID(N'dbo.meelano_hr_inbox',N'U') IS NULL CREATE TABLE dbo.meelano_hr_inbox (id bigint IDENTITY(1,1) NOT NULL PRIMARY KEY, kind nvarchar(30) NOT NULL, username nvarchar(120) NOT NULL, display_name nvarchar(220) NULL, ref_id bigint NULL, title nvarchar(300) NOT NULL, body nvarchar(1000) NULL, created_at datetime2 NOT NULL DEFAULT SYSDATETIME(), seen_at datetime2 NULL, seen_by nvarchar(120) NULL)");
        }
        ready = true;
    }

    static String setting(Connection c, String key, String fallback) {
        try (PreparedStatement ps = c.prepareStatement("SELECT setting_value FROM dbo.meelano_chat_settings WHERE setting_key=?")) {
            ps.setString(1, key);
            try (ResultSet r = ps.executeQuery()) { if (r.next()) { String v = r.getString(1); return v == null || v.trim().isEmpty() ? fallback : v.trim(); } }
        } catch (Exception ignored) { }
        return fallback;
    }

    static void setSetting(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("IF EXISTS (SELECT 1 FROM dbo.meelano_chat_settings WHERE setting_key=?) UPDATE dbo.meelano_chat_settings SET setting_value=?, updated_at=SYSDATETIME() WHERE setting_key=? ELSE INSERT INTO dbo.meelano_chat_settings(setting_key,setting_value) VALUES(?,?)")) {
            ps.setString(1, key); ps.setString(2, value); ps.setString(3, key); ps.setString(4, key); ps.setString(5, value);
            ps.executeUpdate();
        }
    }

    /** Atiran's own Jalali «today» (the server's date), e.g. 1405/07/07. */
    static String today(Connection c) {
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT TRY_CONVERT(nvarchar(30),dbo.UDF_Gregorian_To_Persian(GETDATE()))")) {
            if (r.next()) {
                String v = digits(nz(r.getString(1))).trim();
                if (v.matches("^1[34]\\d{2}/\\d{2}/\\d{2}$")) return v;
            }
        } catch (Exception ignored) { }
        return MeelanoJalali.format(MeelanoJalali.today());
    }

    private static String digits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch >= '۰' && ch <= '۹') b.append((char) ('0' + (ch - '۰')));
            else if (ch >= '٠' && ch <= '٩') b.append((char) ('0' + (ch - '٠')));
            else b.append(ch);
        }
        return b.toString();
    }

    /** First invoice date that becomes a delivery. Set once (two days before the first run); the manager may change it. */
    static String startDate(Connection c, String today) throws SQLException {
        String v = setting(c, "delivery_start_date", "");
        if (v.matches("^1[34]\\d{2}/\\d{2}/\\d{2}$")) return v;
        String start = MeelanoJalali.addDays(today, -2);
        setSetting(c, "delivery_start_date", start);
        return start;
    }

    /** Atiran system users whose invoices need delivery: the store staff (sys_users «محمودي» / «نظري»), or the setting delivery_source_uids. */
    static String sourceUids(Connection c) {
        String v = setting(c, "delivery_source_uids", "");
        StringBuilder b = new StringBuilder();
        for (String p : v.split("[,،\\s]+")) {
            try { int id = Integer.parseInt(p.trim()); if (id > 0) { if (b.length() > 0) b.append(','); b.append(id); } } catch (Exception ignored) { }
        }
        if (b.length() > 0) return b.toString();
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT user_id FROM dbo.sys_users WHERE "
                + "REPLACE(REPLACE(CAST(ISNULL(user_name,N'') AS nvarchar(100))+N' '+CAST(ISNULL(user_lname,N'') AS nvarchar(100)),N'ي',N'ی'),N'ك',N'ک') LIKE N'%محمودی%' OR "
                + "REPLACE(REPLACE(CAST(ISNULL(user_name,N'') AS nvarchar(100))+N' '+CAST(ISNULL(user_lname,N'') AS nvarchar(100)),N'ي',N'ی'),N'ك',N'ک') LIKE N'%نظری%'")) {
            while (r.next()) { int id = r.getInt(1); if (id > 0) { if (b.length() > 0) b.append(','); b.append(id); } }
        } catch (Exception ignored) { }
        return b.length() > 0 ? b.toString() : "5,6";
    }

    // ------------------------------------------------------------------------------------------- sync (reads Atiran)
    private static final String NAME_N = "REPLACE(REPLACE(LTRIM(RTRIM(CAST(ISNULL(cu.MONAME,N'') AS nvarchar(300)))),N'ي',N'ی'),N'ك',N'ک')";

    /**
     * Brings new final invoices of the store staff into the delivery list, refreshes jobs nobody has taken yet when the
     * invoice was edited in Atiran, flags edited invoices of jobs already taken and cancels jobs whose invoice is gone.
     * Serialised with an application lock so two phones never add the same invoice twice. Returns the number of new jobs.
     */
    static int sync(Connection c, String today) throws SQLException {
        ensureTables(c);
        String start = startDate(c, today);
        String uids = sourceUids(c);
        int added = 0;
        boolean locked = false;
        try {
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SET NOCOUNT ON; DECLARE @r int; EXEC @r = sp_getapplock @Resource=N'meelano_delivery_sync', @LockMode=N'Exclusive', @LockOwner=N'Session', @LockTimeout=8000; SELECT @r")) {
                locked = r.next() && r.getInt(1) >= 0;
            } catch (Exception ignored) { }
            // The lock query turned NOCOUNT on for this connection; without row counts the INSERT below would report 0.
            try (Statement st = c.createStatement()) { st.execute("SET NOCOUNT OFF"); } catch (Exception ignored) { }
            String ins = "INSERT INTO dbo.meelano_delivery(shfacfo,rdf__,inv_date,inv_time,shmo,customer,address,phone,total,items_count,registered_by,registered_uid,vis_rdf,status) "
                    + "SELECT s.shfacfo, ISNULL(s.rdf__,1), LEFT(CAST(s.[date] AS nvarchar(20)),10), LEFT(LTRIM(RTRIM(CAST(ISNULL(s.time_,N'') AS nvarchar(20)))),5), s.shmo, "
                    + "LEFT(" + NAME_N + ",300), "
                    + "LEFT(REPLACE(REPLACE(LTRIM(RTRIM(CAST(ISNULL(cu.addre,N'') AS nvarchar(600)))),N'ي',N'ی'),N'ك',N'ک'),600), "
                    + "LEFT(COALESCE(NULLIF(LTRIM(RTRIM(CAST(cu.cell AS nvarchar(40)))),N''),NULLIF(LTRIM(RTRIM(CAST(cu.tell1 AS nvarchar(40)))),N''),NULLIF(LTRIM(RTRIM(CAST(cu.tell2 AS nvarchar(40)))),N''),N''),80), "
                    + "ISNULL(TRY_CONVERT(decimal(19,2),s.[all]),0), "
                    + "(SELECT COUNT(1) FROM dbo.subsailfact x WHERE x.shfacfo=s.shfacfo AND x.rdf__=s.rdf__ AND x.active='t'), "
                    + "LEFT(REPLACE(REPLACE(LTRIM(RTRIM(CAST(ISNULL(s.TaeedUser,N'') AS nvarchar(120)))),N'ي',N'ی'),N'ك',N'ک'),120), s.userid, s.vis_rdf, N'open' "
                    + "FROM dbo.sailfact s LEFT JOIN dbo.CUSTOMERS cu ON cu.SHMO=s.shmo "
                    + "WHERE s.active='t' AND ISNULL(s.Deleted,0)=0 AND ISNULL(s.[Status],0)=1 AND s.userid IN (" + uids + ") AND s.[date] >= ? "
                    + "AND " + NAME_N + " NOT LIKE N'%مشتری محترم%' "
                    + "AND NOT EXISTS (SELECT 1 FROM dbo.meelano_delivery d WITH (UPDLOCK, HOLDLOCK) WHERE d.shfacfo=s.shfacfo AND d.rdf__=ISNULL(s.rdf__,1))";
            try (PreparedStatement ps = c.prepareStatement(ins)) { ps.setString(1, start); added = ps.executeUpdate(); }
            catch (SQLException ex) { if (!duplicate(ex)) throw ex; }

            // Invoice deleted / voided in Atiran → the delivery is cancelled (the person who had taken it is notified).
            List<long[]> gone = new ArrayList<>();
            List<String> goneTo = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT d.id, ISNULL(d.assignee,N'') FROM dbo.meelano_delivery d WHERE d.status IN (N'open',N'claimed') "
                    + "AND NOT EXISTS (SELECT 1 FROM dbo.sailfact s WHERE s.shfacfo=d.shfacfo AND ISNULL(s.rdf__,1)=d.rdf__ AND s.active='t' AND ISNULL(s.Deleted,0)=0)")) {
                while (r.next()) { gone.add(new long[]{r.getLong(1)}); goneTo.add(nz(r.getString(2))); }
            }
            for (int i = 0; i < gone.size(); i++) {
                long id = gone.get(i)[0];
                try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_delivery SET status=N'cancelled', close_reason=N'فاکتور در آتیران حذف یا باطل شد', pending_to=NULL, pending_to_name=NULL, updated_at=SYSDATETIME() WHERE id=? AND status IN (N'open',N'claimed')")) {
                    ps.setLong(1, id);
                    if (ps.executeUpdate() > 0) log(c, id, "cancelled", "atiran", "آتیران", goneTo.get(i), "فاکتور در آتیران حذف یا باطل شد");
                }
            }

            // Invoice edited in Atiran (total changed): an untaken job is rebuilt; a taken one is flagged for its deliverer.
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT d.id, d.status, ISNULL(d.assignee,N''), ISNULL(TRY_CONVERT(decimal(19,2),s.[all]),0), d.inv_changed FROM dbo.meelano_delivery d "
                    + "JOIN dbo.sailfact s ON s.shfacfo=d.shfacfo AND ISNULL(s.rdf__,1)=d.rdf__ AND s.active='t' AND ISNULL(s.Deleted,0)=0 "
                    + "WHERE d.status IN (N'open',N'claimed') AND ABS(ISNULL(TRY_CONVERT(decimal(19,2),s.[all]),0)-ISNULL(d.total,0)) >= 1")) {
                List<Object[]> changed = new ArrayList<>();
                while (r.next()) changed.add(new Object[]{r.getLong(1), nz(r.getString(2)), nz(r.getString(3)), r.getDouble(4), r.getBoolean(5)});
                r.close();
                for (Object[] ch : changed) {
                    long id = (Long) ch[0];
                    if (OPEN.equals(ch[1])) rebuildItems(c, id, (Double) ch[3]);
                    else if (!(Boolean) ch[4]) {
                        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_delivery SET inv_changed=1, updated_at=SYSDATETIME() WHERE id=? AND inv_changed=0")) {
                            ps.setLong(1, id);
                            if (ps.executeUpdate() > 0) log(c, id, "invoice_changed", "atiran", "آتیران", (String) ch[2], "مبلغ فاکتور در آتیران تغییر کرد");
                        }
                    }
                }
            }
            fillItems(c);
        } finally {
            if (locked) {
                try (Statement st = c.createStatement()) { st.execute("EXEC sp_releaseapplock @Resource=N'meelano_delivery_sync', @LockOwner=N'Session'"); } catch (Exception ignored) { }
            }
        }
        return Math.max(0, added);
    }

    private static boolean duplicate(SQLException ex) {
        int code = ex.getErrorCode();
        return code == 2627 || code == 2601 || String.valueOf(ex.getMessage()).contains("UQ_meelano_delivery_inv");
    }

    private static final String ITEM_SELECT = "SELECT d.id, ISNULL(x.RDF,0), x.SHKA, "
            + "LEFT(REPLACE(REPLACE(ISNULL(NULLIF(LTRIM(RTRIM(CAST(x.naka AS nvarchar(400)))),N''),N'کالای '+CAST(x.SHKA AS nvarchar(20))),N'ي',N'ی'),N'ك',N'ک'),400), "
            + "ISNULL(TRY_CONVERT(decimal(19,3),x.TEDVAH),0)*ISNULL(NULLIF(i.mohvah,0),1)+ISNULL(TRY_CONVERT(decimal(19,3),x.TEDJOZ),0), "
            + "ISNULL(TRY_CONVERT(decimal(19,3),x.TEDVAH),0), ISNULL(TRY_CONVERT(decimal(19,3),x.TEDJOZ),0), ISNULL(NULLIF(i.mohvah,0),1), "
            + "LEFT(LTRIM(RTRIM(CAST(ISNULL(x.BASTEBANDI,N'') AS nvarchar(60)))),60), ISNULL(TRY_CONVERT(decimal(19,2),x.LINESUM),0) "
            + "FROM dbo.meelano_delivery d JOIN dbo.subsailfact x ON x.shfacfo=d.shfacfo AND x.rdf__=d.rdf__ AND x.active='t' "
            + "OUTER APPLY (SELECT TOP (1) TRY_CONVERT(int,mohvah) AS mohvah FROM dbo.inventory WHERE shka=x.SHKA) i ";

    private static void fillItems(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO dbo.meelano_delivery_item(delivery_id,line_no,shka,name,qty,cartons,pieces,per_carton,unit,amount) " + ITEM_SELECT
                    + "WHERE d.status IN (N'open',N'claimed') AND NOT EXISTS (SELECT 1 FROM dbo.meelano_delivery_item t WHERE t.delivery_id=d.id)");
        }
    }

    /** Re-reads the invoice lines from Atiran (only while no item has been ticked). */
    private static void rebuildItems(Connection c, long id, double total) throws SQLException {
        try (PreparedStatement del = c.prepareStatement("DELETE FROM dbo.meelano_delivery_item WHERE delivery_id=?")) { del.setLong(1, id); del.executeUpdate(); }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dbo.meelano_delivery_item(delivery_id,line_no,shka,name,qty,cartons,pieces,per_carton,unit,amount) " + ITEM_SELECT + "WHERE d.id=?")) {
            ps.setLong(1, id); ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_delivery SET total=?, inv_changed=0, items_count=(SELECT COUNT(1) FROM dbo.meelano_delivery_item WHERE delivery_id=?), updated_at=SYSDATETIME() WHERE id=?")) {
            ps.setDouble(1, total); ps.setLong(2, id); ps.setLong(3, id); ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------------------------------- people
    static void registerUser(Connection c, String login, String name) {
        try (PreparedStatement ps = c.prepareStatement("IF EXISTS (SELECT 1 FROM dbo.meelano_staff_user WHERE username=?) UPDATE dbo.meelano_staff_user SET display_name=?, last_seen=SYSDATETIME() WHERE username=? "
                + "ELSE INSERT INTO dbo.meelano_staff_user(username,display_name) VALUES(?,?)")) {
            ps.setString(1, login); ps.setString(2, name); ps.setString(3, login); ps.setString(4, login); ps.setString(5, name);
            ps.executeUpdate();
        } catch (Exception ignored) { }
    }

    /** Colleagues a job can be handed to: everyone who has signed in to the staff app (and is not switched off by the manager). */
    static JSONArray colleagues(Connection c, String me) throws Exception {
        JSONArray a = new JSONArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT username, ISNULL(display_name,username), DATEDIFF(day,last_seen,SYSDATETIME()) FROM dbo.meelano_staff_user WHERE active=1 AND username<>? ORDER BY last_seen DESC")) {
            ps.setString(1, me);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) a.put(new JSONObject().put("login", r.getString(1)).put("name", norm(r.getString(2))).put("days", r.getInt(3)));
            }
        }
        return a;
    }

    private static String nameOf(Connection c, String login) {
        try (PreparedStatement ps = c.prepareStatement("SELECT ISNULL(display_name,username) FROM dbo.meelano_staff_user WHERE username=?")) {
            ps.setString(1, login);
            try (ResultSet r = ps.executeQuery()) { if (r.next()) return norm(r.getString(1)); }
        } catch (Exception ignored) { }
        return login;
    }

    // ------------------------------------------------------------------------------------------- reading
    private static final String ROW_COLS = "d.id, d.shfacfo, d.inv_date, ISNULL(d.inv_time,N''), ISNULL(d.shmo,0), ISNULL(d.customer,N''), ISNULL(d.address,N''), ISNULL(d.phone,N''), "
            + "ISNULL(d.total,0), ISNULL(d.items_count,0), ISNULL(d.registered_by,N''), d.status, ISNULL(d.assignee,N''), ISNULL(d.assignee_name,N''), "
            + "CONVERT(nvarchar(19),d.claimed_at,120), ISNULL(d.pending_to,N''), ISNULL(d.pending_to_name,N''), ISNULL(d.pending_note,N''), "
            + "CONVERT(nvarchar(19),d.delivered_at,120), ISNULL(d.receiver_name,N''), ISNULL(d.receiver_phone,N''), ISNULL(d.note,N''), ISNULL(d.close_reason,N''), d.inv_changed, "
            + "(SELECT COUNT(1) FROM dbo.meelano_delivery_item t WHERE t.delivery_id=d.id AND t.state=N'delivered'), "
            + "(SELECT COUNT(1) FROM dbo.meelano_delivery_item t WHERE t.delivery_id=d.id AND t.state=N'missing'), "
            + "(SELECT COUNT(1) FROM dbo.meelano_delivery_item t WHERE t.delivery_id=d.id), "
            + "CASE WHEN d.signature IS NULL THEN 0 ELSE 1 END, d.sign_lat, d.sign_lng, ISNULL(d.vis_rdf,0), CONVERT(nvarchar(19),d.updated_at,120), ISNULL(d.closed_by,N'') ";

    private static JSONObject row(ResultSet r) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", r.getLong(1)); o.put("no", r.getLong(2)); o.put("date", nz(r.getString(3)).trim()); o.put("time", nz(r.getString(4)).trim());
        o.put("shmo", r.getLong(5)); o.put("customer", norm(r.getString(6))); o.put("address", norm(r.getString(7))); o.put("phone", nz(r.getString(8)).trim());
        o.put("total", r.getDouble(9)); o.put("items", r.getInt(10)); o.put("registeredBy", norm(r.getString(11))); o.put("status", nz(r.getString(12)).trim());
        o.put("assignee", nz(r.getString(13)).trim()); o.put("assigneeName", norm(r.getString(14))); o.put("claimedAt", jalali(r.getString(15)));
        o.put("pendingTo", nz(r.getString(16)).trim()); o.put("pendingToName", norm(r.getString(17))); o.put("pendingNote", norm(r.getString(18)));
        o.put("deliveredAt", jalali(r.getString(19))); o.put("receiver", norm(r.getString(20))); o.put("receiverPhone", nz(r.getString(21)).trim());
        o.put("note", norm(r.getString(22))); o.put("closeReason", norm(r.getString(23))); o.put("changed", r.getBoolean(24));
        int delivered = r.getInt(25), missing = r.getInt(26), all = r.getInt(27);
        o.put("delivered", delivered); o.put("missing", missing); o.put("lines", all); o.put("pending", Math.max(0, all - delivered - missing));
        o.put("signed", r.getInt(28) == 1);
        double lat = r.getDouble(29); boolean hasLat = !r.wasNull(); double lng = r.getDouble(30); boolean hasLng = !r.wasNull();
        if (hasLat && hasLng) { o.put("lat", lat); o.put("lng", lng); }
        o.put("vis", r.getInt(31)); o.put("updatedAt", jalali(r.getString(32))); o.put("closedBy", nz(r.getString(33)));
        o.put("statusFa", statusFa(o.optString("status")));
        return o;
    }

    /** "2026-09-29 10:12:30" → "1405/07/07 10:12". */
    static String jalali(String g) {
        if (g == null || g.length() < 10) return "";
        try {
            int d = MeelanoJalali.gregorianDay(Integer.parseInt(g.substring(0, 4)), Integer.parseInt(g.substring(5, 7)), Integer.parseInt(g.substring(8, 10)));
            return MeelanoJalali.format(d) + (g.length() >= 16 ? " " + g.substring(11, 16) : "");
        } catch (Exception e) { return g; }
    }

    private static List<JSONObject> rows(Connection c, String where, Object... params) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + ROW_COLS + "FROM dbo.meelano_delivery d " + where)) {
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p instanceof Long) ps.setLong(i + 1, (Long) p); else if (p instanceof Integer) ps.setInt(i + 1, (Integer) p); else ps.setString(i + 1, String.valueOf(p));
            }
            try (ResultSet r = ps.executeQuery()) { while (r.next()) list.add(row(r)); }
        }
        return list;
    }

    private static JSONArray arr(List<JSONObject> l) { JSONArray a = new JSONArray(); for (JSONObject o : l) a.put(o); return a; }

    /** Everything the staff app's «تحویل بار» page shows for one person. */
    static JSONObject staffLists(Connection c, String me) throws Exception {
        JSONObject out = new JSONObject();
        List<JSONObject> open = rows(c, "WHERE d.status=N'open' ORDER BY d.id DESC");
        List<JSONObject> mine = rows(c, "WHERE d.status=N'claimed' AND LOWER(d.assignee)=? ORDER BY d.claimed_at", me);
        List<JSONObject> incoming = rows(c, "WHERE d.status=N'claimed' AND LOWER(d.pending_to)=? ORDER BY d.pending_at", me);
        List<JSONObject> others = rows(c, "WHERE d.status=N'claimed' AND LOWER(d.assignee)<>? ORDER BY d.claimed_at DESC", me);
        List<JSONObject> done = rows(c, "WHERE d.status IN (N'delivered',N'partial') AND LOWER(d.assignee)=? AND d.delivered_at >= DATEADD(day,-45,SYSDATETIME()) ORDER BY d.delivered_at DESC", me);
        List<JSONObject> closed = rows(c, "WHERE d.status IN (N'cancelled') AND LOWER(ISNULL(d.assignee,N''))=? AND d.updated_at >= DATEADD(day,-7,SYSDATETIME()) ORDER BY d.updated_at DESC", me);
        out.put("open", arr(open)); out.put("mine", arr(mine)); out.put("incoming", arr(incoming)); out.put("others", arr(others));
        out.put("done", arr(done)); out.put("cancelled", arr(closed));
        int today = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(1) FROM dbo.meelano_delivery WHERE status IN (N'delivered',N'partial') AND LOWER(assignee)=? AND delivered_at >= CAST(SYSDATETIME() AS date)")) {
            ps.setString(1, me);
            try (ResultSet r = ps.executeQuery()) { if (r.next()) today = r.getInt(1); }
        }
        out.put("doneToday", today);
        out.put("colleagues", colleagues(c, me));
        return out;
    }

    /** Small numbers for the home screen. */
    static JSONObject summary(Connection c, String me) throws Exception {
        JSONObject o = new JSONObject();
        try (PreparedStatement ps = c.prepareStatement("SELECT SUM(CASE WHEN status=N'open' THEN 1 ELSE 0 END), SUM(CASE WHEN status=N'claimed' AND LOWER(assignee)=? THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN status=N'claimed' AND LOWER(pending_to)=? THEN 1 ELSE 0 END), SUM(CASE WHEN status IN (N'delivered',N'partial') AND LOWER(assignee)=? AND delivered_at >= CAST(SYSDATETIME() AS date) THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN status IN (N'delivered',N'partial') AND LOWER(assignee)=? AND delivered_at >= DATEADD(day,-30,SYSDATETIME()) THEN 1 ELSE 0 END) FROM dbo.meelano_delivery")) {
            ps.setString(1, me); ps.setString(2, me); ps.setString(3, me); ps.setString(4, me);
            try (ResultSet r = ps.executeQuery()) { if (r.next()) { o.put("open", r.getInt(1)); o.put("mine", r.getInt(2)); o.put("incoming", r.getInt(3)); o.put("doneToday", r.getInt(4)); o.put("done30", r.getInt(5)); } }
        }
        return o;
    }

    /** One job with its items and history (no signature bytes — see {@link #signature}). */
    static JSONObject detail(Connection c, long id) throws Exception {
        List<JSONObject> l = rows(c, "WHERE d.id=?", id);
        if (l.isEmpty()) throw new MainActivity.DbException("این بار دیگر در فهرست نیست.");
        JSONObject o = l.get(0);
        JSONArray items = new JSONArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, line_no, ISNULL(shka,0), ISNULL(name,N''), ISNULL(qty,0), ISNULL(cartons,0), ISNULL(pieces,0), ISNULL(per_carton,1), ISNULL(unit,N''), ISNULL(amount,0), state, ISNULL(reason,N''), ISNULL(changed_by,N''), CONVERT(nvarchar(19),changed_at,120) FROM dbo.meelano_delivery_item WHERE delivery_id=? ORDER BY line_no, id")) {
            ps.setLong(1, id);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    JSONObject it = new JSONObject();
                    it.put("id", r.getLong(1)); it.put("line", r.getInt(2)); it.put("code", r.getLong(3)); it.put("name", norm(r.getString(4)));
                    it.put("qty", r.getDouble(5)); it.put("cartons", r.getDouble(6)); it.put("pieces", r.getDouble(7)); it.put("per", r.getInt(8)); it.put("unit", norm(r.getString(9)));
                    it.put("amount", r.getDouble(10)); it.put("state", nz(r.getString(11)).trim()); it.put("reason", norm(r.getString(12))); it.put("by", nz(r.getString(13)));
                    it.put("at", jalali(r.getString(14)));
                    items.put(it);
                }
            }
        }
        o.put("itemsList", items);
        JSONArray log = new JSONArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT TOP (60) action, ISNULL(actor,N''), ISNULL(actor_name,N''), ISNULL(target,N''), ISNULL(note,N''), CONVERT(nvarchar(19),created_at,120) FROM dbo.meelano_delivery_log WHERE delivery_id=? ORDER BY id")) {
            ps.setLong(1, id);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) log.put(new JSONObject().put("action", nz(r.getString(1))).put("actor", nz(r.getString(2))).put("actorName", norm(r.getString(3)))
                        .put("target", nz(r.getString(4))).put("note", norm(r.getString(5))).put("at", jalali(r.getString(6))));
            }
        }
        o.put("log", log);
        return o;
    }

    static byte[] signature(Connection c, long id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT signature FROM dbo.meelano_delivery WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet r = ps.executeQuery()) { return r.next() ? r.getBytes(1) : null; }
        }
    }

    /** Store app / manager: recent deliveries with their state, deliverer and receipt (read-only). */
    static JSONObject panel(Connection c, int days) throws Exception {
        JSONObject out = new JSONObject();
        List<JSONObject> l = rows(c, "WHERE d.created_at >= DATEADD(day,?,SYSDATETIME()) OR d.status IN (N'open',N'claimed') ORDER BY CASE d.status WHEN N'claimed' THEN 0 WHEN N'open' THEN 1 ELSE 2 END, d.id DESC", -Math.max(1, days));
        int open = 0, claimed = 0, delivered = 0, partial = 0, other = 0;
        for (JSONObject o : l) {
            switch (o.optString("status")) {
                case OPEN: open++; break;
                case CLAIMED: claimed++; break;
                case DELIVERED: delivered++; break;
                case PARTIAL: partial++; break;
                default: other++;
            }
        }
        out.put("rows", arr(l)); out.put("open", open); out.put("claimed", claimed); out.put("delivered", delivered); out.put("partial", partial); out.put("other", other);
        JSONArray by = new JSONArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT ISNULL(assignee_name,assignee), COUNT(1), SUM(CASE WHEN status=N'partial' THEN 1 ELSE 0 END) FROM dbo.meelano_delivery WHERE status IN (N'delivered',N'partial') AND delivered_at >= DATEADD(day,?,SYSDATETIME()) GROUP BY ISNULL(assignee_name,assignee) ORDER BY COUNT(1) DESC")) {
            ps.setInt(1, -Math.max(1, days));
            try (ResultSet r = ps.executeQuery()) { while (r.next()) by.put(new JSONObject().put("name", norm(r.getString(1))).put("count", r.getInt(2)).put("partial", r.getInt(3))); }
        }
        out.put("byPerson", by);
        return out;
    }

    // ------------------------------------------------------------------------------------------- actions
    static void log(Connection c, long id, String action, String actor, String actorName, String target, String note) {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dbo.meelano_delivery_log(delivery_id,action,actor,actor_name,target,note) VALUES(?,?,?,?,?,?)")) {
            ps.setLong(1, id); ps.setString(2, action); ps.setString(3, actor); ps.setString(4, actorName); ps.setString(5, target == null ? "" : target); ps.setString(6, note == null ? "" : note);
            ps.executeUpdate();
        } catch (Exception ignored) { }
    }

    private static void inbox(Connection c, String kind, String user, String name, long ref, String title, String body) {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dbo.meelano_hr_inbox(kind,username,display_name,ref_id,title,body) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, kind); ps.setString(2, user); ps.setString(3, name); ps.setLong(4, ref); ps.setString(5, title); ps.setString(6, body == null ? "" : body);
            ps.executeUpdate();
        } catch (Exception ignored) { }
    }

    private static JSONObject current(Connection c, long id) throws Exception {
        List<JSONObject> l = rows(c, "WHERE d.id=?", id);
        if (l.isEmpty()) throw new MainActivity.DbException("این بار دیگر در فهرست نیست.");
        return l.get(0);
    }

    private static String whyNot(JSONObject d, String me) {
        String st = d.optString("status");
        if (CANCELLED.equals(st)) return "فاکتور این بار در آتیران باطل شده است.";
        if (SKIPPED.equals(st)) return "فروشگاه اعلام کرده این فاکتور نیاز به ارسال ندارد.";
        if (DELIVERED.equals(st) || PARTIAL.equals(st)) return "این بار قبلاً تحویل و رسید آن ثبت شده است؛ دیگر قابل تغییر نیست.";
        if (CLAIMED.equals(st) && !me.equalsIgnoreCase(d.optString("assignee"))) return "این بار را «" + d.optString("assigneeName", d.optString("assignee")) + "» برعهده گرفته است.";
        if (!d.optString("pendingTo").isEmpty()) return "این بار در انتظار پاسخ «" + d.optString("pendingToName") + "» برای واگذاری است.";
        return "این کار الان ممکن نیست؛ فهرست را تازه کنید.";
    }

    /** «برعهده می‌گیرم»: only one person can win — the row changes only while it is still open. */
    static String claim(Connection c, long id, String me, String myName) throws Exception {
        int n;
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_delivery SET status=N'claimed', assignee=?, assignee_name=?, claimed_at=SYSDATETIME(), updated_at=SYSDATETIME() WHERE id=? AND status=N'open'")) {
            ps.setString(1, me); ps.setString(2, myName); ps.setLong(3, id); n = ps.executeUpdate();
        }
        if (n == 0) {
            JSONObject d = current(c, id);
            if (CLAIMED.equals(d.optString("status")) && me.equalsIgnoreCase(d.optString("assignee"))) return "این بار از قبل با شماست.";
            throw new MainActivity.DbException(CLAIMED.equals(d.optString("status")) ? "دیر شد! این بار را «" + d.optString("assigneeName", d.optString("assignee")) + "» زودتر برعهده گرفت." : whyNot(d, me));
        }
        log(c, id, "claimed", me, myName, "", "");
        return "تحویل این بار با شما ثبت شد. دیگران دیگر نمی‌توانند آن را بردارند.";
    }

    /** «برگرداندن به فهرست» — allowed while nothing has been handed over yet. */
    static String release(Connection c, long id, String me, String myName, String reason) throws Exception {
        if (reason == null || reason.trim().isEmpty()) throw new MainActivity.DbException("علت برگرداندن را بنویسید (مثلاً مشتری نبود یا خودرو خراب شد).");
        int n;
        try (PreparedStatement ps = c.prepareStatement("UPDATE d SET status=N'open', assignee=NULL, assignee_name=NULL, claimed_at=NULL, pending_to=NULL, pending_to_name=NULL, pending_at=NULL, pending_note=NULL, updated_at=SYSDATETIME() "
                + "FROM dbo.meelano_delivery d WHERE d.id=? AND d.status=N'claimed' AND LOWER(d.assignee)=? AND NOT EXISTS (SELECT 1 FROM dbo.meelano_delivery_item t WHERE t.delivery_id=d.id AND t.state=N'delivered')")) {
            ps.setLong(1, id); ps.setString(2, me); n = ps.executeUpdate();
        }
        if (n == 0) {
            JSONObject d = current(c, id);
            if (CLAIMED.equals(d.optString("status")) && me.equalsIgnoreCase(d.optString("assignee")) && d.optInt("delivered") > 0)
                throw new MainActivity.DbException("بخشی از اقلام تحویل شده است. یا تحویل را با رسید مشتری ببندید (اقلام تحویل‌نشده با علت ثبت می‌شوند)، یا بار را به همکار واگذار کنید.");
            throw new MainActivity.DbException(whyNot(d, me));
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_delivery_item SET state=N'pending', reason=NULL, changed_by=NULL, changed_at=NULL WHERE delivery_id=?")) { ps.setLong(1, id); ps.executeUpdate(); }
        log(c, id, "released", me, myName, "", reason.trim());
        return "بار به فهرست برگشت تا همکاران بتوانند آن را بردارند.";
    }

    /** Asks a colleague to take over the job; the job stays with the sender until the colleague accepts. */
    static String handover(Connection c, long id, String me, String myName, String to, String note) throws Exception {
        String target = to == null ? "" : to.trim().toLowerCase(Locale.US);
        if (target.isEmpty() || target.equals(me)) throw new MainActivity.DbException("همکار دیگری را انتخاب کنید.");
        boolean known;
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(1) FROM dbo.meelano_staff_user WHERE username=? AND active=1")) {
            ps.setString(1, target);
            try (ResultSet r = ps.executeQuery()) { known = r.next() && r.getInt(1) > 0; }
        }
        if (!known) throw new MainActivity.DbException("این همکار هنوز وارد برنامه پرسنل نشده است.");
        String tn = nameOf(c, target);
        int n;
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_delivery SET pending_to=?, pending_to_name=?, pending_at=SYSDATETIME(), pending_note=?, updated_at=SYSDATETIME() WHERE id=? AND status=N'claimed' AND LOWER(assignee)=? AND pending_to IS NULL")) {
            ps.setString(1, target); ps.setString(2, tn); ps.setString(3, note == null ? "" : note.trim()); ps.setLong(4, id); ps.setString(5, me); n = ps.executeUpdate();
        }
        if (n == 0) throw new MainActivity.DbException(whyNot(current(c, id), me));
        log(c, id, "handover_request", me, myName, target, note);
        return "درخواست واگذاری برای «" + tn + "» فرستاده شد. تا وقتی نپذیرد، بار با شماست.";
    }

    static String cancelHandover(Connection c, long id, String me, String myName) throws Exception {
        String target = current(c, id).optString("pendingTo");
        int n;
        try (PreparedStatement ps = c.prepareStatement("UPDATE dbo.meelano_delivery SET pending_to=NULL, pending_to_name=NULL, pending_at=NULL, pending_note=NULL, updated_at=SYSDATETIME() WHERE id=? AND status=N'claimed' AND LOWER(assignee)=? AND pending_to IS NOT NULL")) {
            ps.setLong(1, id); ps.setString(2, me); n = ps.executeUpdate();
        }
        if (n == 0) throw new MainActivity.DbException("درخواست واگذاری‌ای برای لغو نیست.");
        log(c, id, "handover_cancel", me, myName, target, "");
        return "درخواست واگذاری لغو شد.";
    }

    static String answerHandover(Connection c, long id, String me, String myName, boolean accept, String note) throws Exception {
        JSONObject d = current(c, id);
        String from = d.optString("assignee");
        int n;
        String sql = accept
                ? "UPDATE dbo.meelano_delivery SET assignee=pending_to, assignee_name=pending_to_name, claimed_at=SYSDATETIME(), pending_to=NULL, pending_to_name=NULL, pending_at=NULL, pending_note=NULL, updated_at=SYSDATETIME() WHERE id=? AND status=N'claimed' AND LOWER(pending_to)=?"
                : "UPDATE dbo.meelano_delivery SET pending_to=NULL, pending_to_name=NULL, pending_at=NULL, pending_note=NULL, updated_at=SYSDATETIME() WHERE id=? AND status=N'claimed' AND LOWER(pending_to)=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setLong(1, id); ps.setString(2, me); n = ps.executeUpdate(); }
        if (n == 0) throw new MainActivity.DbException("این درخواست واگذاری دیگر معتبر نیست (لغو شده یا بار بسته شده است).");
        log(c, id, accept ? "handover_accept" : "handover_reject", me, myName, from, note);
        return accept ? "بار به شما واگذار شد؛ اقلامی که قبلاً تحویل شده‌اند همان‌طور ثبت مانده‌اند." : "درخواست واگذاری رد شد و بار نزد همکارتان ماند.";
    }

    /** Tick one line: delivered / missing (with a reason) / back to pending. Only the deliverer, only before the receipt. */
    static String setItem(Connection c, long itemId, String me, String state, String reason) throws Exception {
        if (!"delivered".equals(state) && !"missing".equals(state) && !"pending".equals(state)) throw new MainActivity.DbException("وضعیت نامعتبر است.");
        if ("missing".equals(state) && (reason == null || reason.trim().isEmpty())) throw new MainActivity.DbException("علت تحویل‌نشدن را انتخاب کنید.");
        int n;
        try (PreparedStatement ps = c.prepareStatement("UPDATE t SET state=?, reason=?, changed_by=?, changed_at=SYSDATETIME() FROM dbo.meelano_delivery_item t JOIN dbo.meelano_delivery d ON d.id=t.delivery_id "
                + "WHERE t.id=? AND d.status=N'claimed' AND LOWER(d.assignee)=? AND d.pending_to IS NULL")) {
            ps.setString(1, state); if ("missing".equals(state)) ps.setString(2, reason.trim()); else ps.setNull(2, Types.VARCHAR);
            ps.setString(3, me); ps.setLong(4, itemId); ps.setString(5, me); n = ps.executeUpdate();
        }
        if (n == 0) {
            long did = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT delivery_id FROM dbo.meelano_delivery_item WHERE id=?")) { ps.setLong(1, itemId); try (ResultSet r = ps.executeQuery()) { if (r.next()) did = r.getLong(1); } }
            if (did == 0) throw new MainActivity.DbException("این قلم پیدا نشد؛ صفحه را تازه کنید.");
            throw new MainActivity.DbException(whyNot(current(c, did), me));
        }
        return "delivered".equals(state) ? "تحویل شد" : "missing".equals(state) ? "تحویل‌نشده ثبت شد" : "به فهرست تحویل برگشت";
    }

    /** «همه اقلام تحویل شد» for the lines still pending. */
    static int deliverAll(Connection c, long id, String me) throws Exception {
        int n;
        try (PreparedStatement ps = c.prepareStatement("UPDATE t SET state=N'delivered', reason=NULL, changed_by=?, changed_at=SYSDATETIME() FROM dbo.meelano_delivery_item t JOIN dbo.meelano_delivery d ON d.id=t.delivery_id "
                + "WHERE t.delivery_id=? AND t.state=N'pending' AND d.status=N'claimed' AND LOWER(d.assignee)=? AND d.pending_to IS NULL")) {
            ps.setString(1, me); ps.setLong(2, id); ps.setString(3, me); n = ps.executeUpdate();
        }
        if (n == 0) {
            JSONObject d = current(c, id);
            if (!(CLAIMED.equals(d.optString("status")) && me.equalsIgnoreCase(d.optString("assignee")) && d.optString("pendingTo").isEmpty())) throw new MainActivity.DbException(whyNot(d, me));
        }
        return n;
    }

    /** The deliverer re-reads the lines of an invoice that was edited in Atiran (ticks start again). */
    static String reloadItems(Connection c, long id, String me, String myName) throws Exception {
        JSONObject d = current(c, id);
        if (!(CLAIMED.equals(d.optString("status")) && me.equalsIgnoreCase(d.optString("assignee")))) throw new MainActivity.DbException(whyNot(d, me));
        double total = d.optDouble("total");
        try (PreparedStatement ps = c.prepareStatement("SELECT TOP (1) ISNULL(TRY_CONVERT(decimal(19,2),[all]),0) FROM dbo.sailfact WHERE shfacfo=? AND ISNULL(rdf__,1)=(SELECT rdf__ FROM dbo.meelano_delivery WHERE id=?) AND active='t' AND ISNULL(Deleted,0)=0")) {
            ps.setLong(1, d.optLong("no")); ps.setLong(2, id);
            try (ResultSet r = ps.executeQuery()) { if (r.next()) total = r.getDouble(1); }
        }
        rebuildItems(c, id, total);
        log(c, id, "items_reloaded", me, myName, "", "");
        return "اقلام دوباره از فاکتور آتیران خوانده شد.";
    }

    /** Receipt: every line settled, at least one delivered, receiver's name and signature. The job becomes read-only. */
    static String finish(Connection c, long id, String me, String myName, String receiver, String phone, byte[] sign, double lat, double lng, double acc, String note) throws Exception {
        if (receiver == null || receiver.trim().length() < 2) throw new MainActivity.DbException("نام تحویل‌گیرنده را بنویسید.");
        if (sign == null || sign.length < 200) throw new MainActivity.DbException("امضای مشتری لازم است.");
        JSONObject d = current(c, id);
        if (!(CLAIMED.equals(d.optString("status")) && me.equalsIgnoreCase(d.optString("assignee")))) throw new MainActivity.DbException(whyNot(d, me));
        if (!d.optString("pendingTo").isEmpty()) throw new MainActivity.DbException("ابتدا درخواست واگذاری را لغو کنید.");
        if (d.optInt("pending") > 0) throw new MainActivity.DbException("هنوز " + d.optInt("pending") + " قلم تعیین‌تکلیف نشده است: هر قلم را «تحویل شد» یا «تحویل نشد» بزنید.");
        if (d.optInt("delivered") == 0) throw new MainActivity.DbException("هیچ کالایی تحویل نشده است. اگر مشتری بار را نگرفت، «برگرداندن به فهرست» را بزنید.");
        String status = d.optInt("missing") > 0 ? PARTIAL : DELIVERED;
        int n;
        try (PreparedStatement ps = c.prepareStatement("UPDATE d SET status=?, delivered_at=SYSDATETIME(), receiver_name=?, receiver_phone=?, signature=?, sign_lat=?, sign_lng=?, sign_acc=?, note=?, updated_at=SYSDATETIME() "
                + "FROM dbo.meelano_delivery d WHERE d.id=? AND d.status=N'claimed' AND LOWER(d.assignee)=? AND d.pending_to IS NULL AND NOT EXISTS (SELECT 1 FROM dbo.meelano_delivery_item t WHERE t.delivery_id=d.id AND t.state=N'pending')")) {
            ps.setString(1, status); ps.setString(2, receiver.trim()); ps.setString(3, phone == null ? "" : phone.trim()); ps.setBytes(4, sign);
            if (Double.isNaN(lat)) ps.setNull(5, Types.FLOAT); else ps.setDouble(5, lat);
            if (Double.isNaN(lng)) ps.setNull(6, Types.FLOAT); else ps.setDouble(6, lng);
            if (Double.isNaN(acc) || Double.isNaN(lat)) ps.setNull(7, Types.FLOAT); else ps.setDouble(7, acc);
            ps.setString(8, note == null ? "" : note.trim()); ps.setLong(9, id); ps.setString(10, me);
            n = ps.executeUpdate();
        }
        if (n == 0) throw new MainActivity.DbException(whyNot(current(c, id), me));
        String summary = "فاکتور " + d.optLong("no") + " • " + d.optString("customer") + " • تحویل‌گیرنده: " + receiver.trim()
                + (d.optInt("missing") > 0 ? " • " + d.optInt("missing") + " قلم تحویل نشد" : "");
        log(c, id, status, me, myName, "", summary);
        inbox(c, "delivery", me, myName, id, (PARTIAL.equals(status) ? "تحویل ناقص بار • " : "تحویل بار • ") + d.optString("customer"), summary);
        return PARTIAL.equals(status) ? "رسید ثبت شد (تحویل ناقص). فروشگاه و مدیر اقلام تحویل‌نشده را می‌بینند." : "بار تحویل شد و رسید مشتری ثبت شد. فروشگاه و مدیر آن را می‌بینند.";
    }

    /** Store app: an invoice the customer takes away himself needs no delivery (and can be put back). */
    static String skip(Connection c, long id, String me, String myName, boolean skip, String reason) throws Exception {
        int n;
        String sql = skip
                ? "UPDATE dbo.meelano_delivery SET status=N'skipped', close_reason=?, closed_by=?, updated_at=SYSDATETIME() WHERE id=? AND status=N'open'"
                : "UPDATE dbo.meelano_delivery SET status=N'open', close_reason=?, closed_by=?, updated_at=SYSDATETIME() WHERE id=? AND status=N'skipped'";
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setString(1, skip ? (reason == null || reason.trim().isEmpty() ? "مشتری خودش برد" : reason.trim()) : ""); ps.setString(2, skip ? myName : ""); ps.setLong(3, id); n = ps.executeUpdate(); }
        if (n == 0) throw new MainActivity.DbException(skip ? "فقط باری را که هنوز کسی برنداشته می‌توان «بدون نیاز به ارسال» کرد." : "این فاکتور در حالت «بدون نیاز به ارسال» نیست.");
        log(c, id, skip ? "skipped" : "unskipped", me, myName, "", reason);
        return skip ? "این فاکتور از فهرست تحویل پرسنل خارج شد." : "فاکتور دوباره به فهرست تحویل برگشت.";
    }

    // ------------------------------------------------------------------------------------------- alerts
    /**
     * New work for one person since the last check: new jobs in the list, a colleague handing a job to them, the answer to
     * their own handover, a job of theirs whose invoice was cancelled or edited in Atiran. {@code lastOpen}/{@code lastLog}
     * are the ids already announced; the first check (0/0) only sets the baseline plus one summary line.
     */
    static JSONObject alerts(Connection c, String me, long lastOpen, long lastLog) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray msgs = new JSONArray();
        long maxOpen = lastOpen, maxLog = lastLog;
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT ISNULL(MAX(id),0) FROM dbo.meelano_delivery")) { if (r.next()) maxOpen = Math.max(maxOpen, r.getLong(1)); }
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT ISNULL(MAX(id),0) FROM dbo.meelano_delivery_log")) { if (r.next()) maxLog = Math.max(maxLog, r.getLong(1)); }
        if (lastOpen <= 0 && lastLog <= 0) {
            int open = 0;
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT COUNT(1) FROM dbo.meelano_delivery WHERE status=N'open'")) { if (r.next()) open = r.getInt(1); }
            if (open > 0) msgs.put(new JSONObject().put("kind", "open").put("title", open + " بار آماده تحویل").put("body", "در «تحویل بار» یکی را برعهده بگیرید.").put("id", 0));
        } else {
            List<JSONObject> fresh = rows(c, "WHERE d.id>? AND d.status=N'open' ORDER BY d.id", lastOpen);
            if (fresh.size() == 1) {
                JSONObject o = fresh.get(0);
                msgs.put(new JSONObject().put("kind", "open").put("title", "بار تازه برای تحویل • " + o.optString("customer")).put("body", "فاکتور " + o.optLong("no") + " • " + o.optInt("items") + " قلم" + (o.optString("address").isEmpty() ? "" : " • " + o.optString("address"))).put("id", o.optLong("id")));
            } else if (fresh.size() > 1) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < fresh.size() && i < 4; i++) { if (b.length() > 0) b.append("، "); b.append(fresh.get(i).optString("customer")); }
                msgs.put(new JSONObject().put("kind", "open").put("title", fresh.size() + " بار تازه برای تحویل").put("body", b.toString()).put("id", 0));
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT l.id, l.delivery_id, l.action, ISNULL(l.actor_name,l.actor), ISNULL(l.note,N''), ISNULL(d.customer,N''), d.shfacfo FROM dbo.meelano_delivery_log l JOIN dbo.meelano_delivery d ON d.id=l.delivery_id "
                    + "WHERE l.id>? AND LOWER(ISNULL(l.target,N''))=? AND l.action IN (N'handover_request',N'handover_accept',N'handover_reject',N'handover_cancel',N'cancelled',N'invoice_changed') ORDER BY l.id")) {
                ps.setLong(1, lastLog); ps.setString(2, me);
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        String a = nz(r.getString(3)), who = norm(r.getString(4)), cust = norm(r.getString(6));
                        String title, body;
                        switch (a) {
                            case "handover_request": title = "«" + who + "» می‌خواهد باری را به شما بسپارد"; body = cust + " • فاکتور " + r.getLong(7) + " • بپذیرید یا رد کنید"; break;
                            case "handover_accept": title = "«" + who + "» بار را پذیرفت"; body = cust + " • دیگر با شما نیست"; break;
                            case "handover_reject": title = "«" + who + "» واگذاری را نپذیرفت"; body = cust + " • بار هنوز با شماست" + (norm(r.getString(5)).isEmpty() ? "" : " • " + norm(r.getString(5))); break;
                            case "handover_cancel": title = "درخواست واگذاری لغو شد"; body = cust; break;
                            case "cancelled": title = "فاکتور باطل شد • ارسال نکنید"; body = cust + " • فاکتور " + r.getLong(7) + " در آتیران حذف یا باطل شد"; break;
                            default: title = "فاکتور در آتیران تغییر کرد"; body = cust + " • اقلام را دوباره بخوانید"; break;
                        }
                        msgs.put(new JSONObject().put("kind", a).put("title", title).put("body", body).put("id", r.getLong(2)));
                    }
                }
            }
        }
        out.put("messages", msgs); out.put("openMax", maxOpen); out.put("logMax", maxLog);
        return out;
    }

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, "تحویل بار", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("بار تازه برای تحویل، واگذاری از همکار و ابطال فاکتور");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) { }
    }

    /** Posts the alerts as phone notifications; tapping one opens «تحویل بار». */
    static void post(Context ctx, JSONArray msgs) {
        if (msgs == null || msgs.length() == 0) return;
        try {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            ensureChannel(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i); if (m == null) continue;
                Intent intent = new Intent(ctx, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.putExtra(EXTRA_PAGE, "staff_delivery");
                if (m.optLong("id") > 0) intent.putExtra("meelano_delivery_id", m.optLong("id"));
                int code = 5100 + (int) (Math.abs(m.optLong("id") * 7 + i) % 800);
                PendingIntent pi = PendingIntent.getActivity(ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
                Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx);
                b.setSmallIcon(R.drawable.mi_local_shipping)
                        .setContentTitle(m.optString("title"))
                        .setContentText(m.optString("body"))
                        .setStyle(new Notification.BigTextStyle().bigText(m.optString("body")))
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .setWhen(System.currentTimeMillis());
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
                nm.notify(code, b.build());
            }
        } catch (Exception ignored) { }
    }
}
