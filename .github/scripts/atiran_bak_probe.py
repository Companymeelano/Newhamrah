#!/usr/bin/env python3
"""
READ-ONLY look at a RESTORED COPY of the Atiran2 backup (never the live server).

Collects what the app needs for: per-visitor customer scope (latifi / khodayar), adding a new
customer the way Atiran itself does, and the real product names (for product photos).
The output JSON is encrypted by the workflow because the repository is public.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from atiran_e2e import connect, safe_rows  # noqa: E402

OUT = sys.argv[1]


SQL_FILE = "MEELANO-Android/app/src/main/res/raw/atiran_new_customer.sql"


def stage2(c, cur, q, out):
    """Follow-up rows of a real customer, then run the app's exact new-customer batch on this copy."""
    q("last_real", "SELECT TOP (1) SHMO FROM dbo.CUSTOMERS ORDER BY SHMO DESC")
    last = out["last_real"]["rows"][0][0]
    for t in ["cus_image", "cust_act", "sys_cus"]:
        q("cols2_" + t, "SELECT c.name, t.name, c.is_nullable, c.is_identity FROM sys.columns c JOIN sys.types t ON c.user_type_id=t.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.%s') ORDER BY c.column_id" % t)
    q("real_cus_image", "SELECT shmo, DATALENGTH([image]) FROM dbo.cus_image WHERE shmo IN (%d,%d,%d)" % (last, last - 1, last - 2))
    q("real_cust_act", "SELECT TOP (10) * FROM dbo.cust_act WHERE shmo IN (%d,%d,%d) ORDER BY shmo DESC" % (last, last - 1, last - 2))
    q("real_sys_cus", "SELECT * FROM dbo.sys_cus WHERE Shmo IN (%d,%d,%d)" % (last, last - 1, last - 2))
    q("counts_follow", "SELECT (SELECT COUNT(*) FROM dbo.CUSTOMERS), (SELECT COUNT(DISTINCT shmo) FROM dbo.cus_image), (SELECT COUNT(DISTINCT shmo) FROM dbo.cust_act), (SELECT COUNT(DISTINCT Shmo) FROM dbo.sys_cus)")
    q("custgroup", "SELECT * FROM dbo.custgroup")
    q("acc_started", "SELECT dbo.IsAccountingSystemStarted()")
    q("settings", "SELECT * FROM dbo.overal_setting WHERE id IN (74,77,78,95,117)")
    q("shim_by_masir", "SELECT RDF_masir, MAX(sh_i_m), COUNT(*) FROM dbo.CUSTOMERS GROUP BY RDF_masir")
    q("ttms", "SELECT * FROM dbo.CustomerTypeTTMS")
    q("inv_cols", "SELECT c.name, t.name FROM sys.columns c JOIN sys.types t ON c.user_type_id=t.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.inventory') ORDER BY c.column_id")
    q("server_date", "SELECT dbo.ReturnDateServer()")
    q("inv_rows", "SELECT TOP (900) * FROM dbo.inventory")

    sql = open(SQL_FILE, encoding="utf-8").read()
    assert "%" not in sql
    pyformat = sql.replace("?", "%s")
    date = out["server_date"]["rows"][0][0] if out["server_date"]["rows"] else "1405/07/06"

    def add(name, vis):
        cur.execute(pyformat, (name, "09160000000", "", "آزمايش ميلانو", "", "ثبت از برنامه ميلانو", vis, 1, 1, "latifi", date, 31.3, 48.6, 1))
        res = None
        while True:
            if cur.description:
                res = cur.fetchall()
            if not cur.nextset():
                break
        return res

    tests = {}
    try:
        tests["insert1"] = [list(r) for r in add("08آزمايش ميلانو-اهواز", 6)]
        tests["insert2"] = [list(r) for r in add("08آزمايش دوم ميلانو", 6)]
    except Exception as ex:
        tests["insert_error"] = str(ex)[:400]
    try:
        add("08آزمايش ميلانو-اهواز", 6)
        tests["duplicate"] = "NOT BLOCKED"
    except Exception as ex:
        tests["duplicate"] = "blocked: " + str(ex)[:160]
    out["tests"] = tests
    if "insert1" in tests:
        a = tests["insert1"][0][0]
        q("new_row", "SELECT * FROM dbo.CUSTOMERS WHERE SHMO IN (%d,%d)" % (a, a + 1))
        q("new_cus_image", "SELECT shmo, DATALENGTH([image]) FROM dbo.cus_image WHERE shmo=%d" % a)
        q("new_cust_act", "SELECT * FROM dbo.cust_act WHERE shmo=%d" % a)
        q("new_sys_cus", "SELECT * FROM dbo.sys_cus WHERE Shmo=%d" % a)
        q("new_chain", "SELECT COUNT(*) FROM dbo.CUSTOMERS cu JOIN dbo.masir m ON cu.RDF_masir=m.rdf_masir JOIN dbo.[Quarter] qq ON m.QuarterID=qq.ID JOIN dbo.regions r ON qq.RegionId=r.rdf_region JOIN dbo.CITYS ct ON r.rdf_city=ct.RDF WHERE cu.SHMO=%d" % a)
        for v in ["VW_ListCustomer", "vw_customer", "VW_CustomerInformation", "moshtari", "CustomersTablet"]:
            cur.execute("SELECT name FROM sys.columns WHERE object_id=OBJECT_ID(N'dbo.%s')" % v)
            cols = [r[0] for r in cur.fetchall()]
            key = next((x for x in cols if x.lower() in ("shmo", "shmo_", "customerid", "customer_id")), None)
            if key:
                q("view_" + v, "SELECT COUNT(*) FROM dbo.[%s] WHERE [%s]=%d" % (v, key, a))
            else:
                out["view_" + v] = {"cols": cols[:40], "rows": []}


def stage3(c, cur, q, out):
    """Sales invoices (store edition): tables, triggers, Atiran's own procedures, real samples, reports."""
    q("s3_tables", "SELECT name FROM sys.tables WHERE name LIKE N'%sail%' OR name LIKE N'%fact%' OR name LIKE N'%sale%' OR name LIKE N'%kardex%' "
                   "OR name LIKE N'%mojod%' OR name LIKE N'%anbar%' OR name LIKE N'%tasvie%' OR name LIKE N'%daryaft%' OR name LIKE N'%chek%' OR name LIKE N'%check%' "
                   "OR name LIKE N'%sanad%' OR name LIKE N'%sys%' OR name LIKE N'%hozor%' OR name LIKE N'%attend%' ORDER BY name")
    for t in ["sailfact", "subsailfact", "sailfact_pish", "subsailfact_pish", "cust_act", "anbars", "kardex"]:
        q("s3_cols_" + t, "SELECT c.name, t.name, c.max_length, c.is_nullable, c.is_identity, OBJECT_DEFINITION(c.default_object_id) "
                          "FROM sys.columns c JOIN sys.types t ON c.user_type_id=t.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.%s') ORDER BY c.column_id" % t)
    q("s3_triggers", "SELECT OBJECT_NAME(parent_id), name, is_disabled, LEFT(OBJECT_DEFINITION(object_id), 12000) FROM sys.triggers "
                     "WHERE OBJECT_NAME(parent_id) IN (N'sailfact', N'subsailfact', N'inventory', N'cust_act', N'sailfact_pish', N'subsailfact_pish', N'CUSTOMERS')")
    q("s3_proc_names", "SELECT o.name, o.type FROM sys.objects o WHERE o.type IN ('P','FN','IF','TF','V') ORDER BY o.name")
    # Every module that writes a sales invoice header, or turns a pre-invoice into an invoice.
    q("s3_procs_sail", "SELECT o.name, o.type, LEN(m.definition), LEFT(m.definition, 30000) FROM sys.sql_modules m JOIN sys.objects o ON o.object_id=m.object_id "
                       "WHERE (m.definition LIKE N'%INSERT%INTO%sailfact%' OR m.definition LIKE N'%insert%sailfact%' OR o.name LIKE N'%sail%' OR o.name LIKE N'%pish%' "
                       "OR o.name LIKE N'%mojod%' OR o.name LIKE N'%kardex%' OR o.name LIKE N'%FixMan%' OR o.name LIKE N'%tasvie%' OR o.name LIKE N'%sarresid%' "
                       "OR o.name LIKE N'%bedeh%' OR o.name LIKE N'%mande%') AND o.type IN ('P','FN','IF','TF','V','TR')")
    q("s3_last_sail", "SELECT TOP (6) * FROM dbo.sailfact ORDER BY shfacfo DESC")
    q("s3_sail_counts", "SELECT rdf__, active, COUNT(*), MAX(shfacfo), MIN([date]), MAX([date]) FROM dbo.sailfact GROUP BY rdf__, active")
    try:
        cur.execute("SELECT TOP (3) shfacfo FROM dbo.sailfact WHERE active='t' ORDER BY shfacfo DESC")
        nums = [r[0] for r in cur.fetchall()]
    except Exception as ex:
        nums = []
        out["errors"].append("last nums: %s" % str(ex)[:200])
    out["s3_nums"] = nums
    if nums:
        lst = ",".join(str(int(n)) for n in nums)
        q("s3_last_sub", "SELECT * FROM dbo.subsailfact WHERE shfacfo IN (%s) ORDER BY shfacfo, RDF" % lst)
        q("s3_last_cust_act", "SELECT TOP (40) * FROM dbo.cust_act WHERE ghno IN (%s) OR act_id IN (%s) ORDER BY rdf_ DESC" % (lst, lst))
    q("s3_pish_converted", "SELECT TOP (10) * FROM dbo.sailfact_pish WHERE sh_f<>0 ORDER BY shfacfo DESC")
    q("s3_anbars", "SELECT * FROM dbo.anbars")
    q("s3_visitors", "SELECT vis_rdf, vis_name, Username, UserID FROM dbo.visitors")
    cur.execute("SELECT name FROM sys.columns WHERE object_id=OBJECT_ID(N'dbo.sys_users')")
    names = [r[0] for r in cur.fetchall() if not any(k in r[0].lower() for k in ("pass", "pwd"))]
    q("s3_sys_users", "SELECT " + ",".join("[%s]" % n for n in names) + " FROM dbo.sys_users")
    q("s3_settings", "SELECT * FROM dbo.overal_setting")
    q("s3_debtors_by_vis", "SELECT vis_rdf, COUNT(*), SUM(man) FROM dbo.CUSTOMERS WHERE man>0 GROUP BY vis_rdf")
    q("s3_man_sign", "SELECT SUM(CASE WHEN man>0 THEN 1 ELSE 0 END), SUM(CASE WHEN man<0 THEN 1 ELSE 0 END), SUM(CASE WHEN man=0 THEN 1 ELSE 0 END) FROM dbo.CUSTOMERS")
    q("s3_open_invoices", "SELECT TOP (40) shfacfo, [date], done_date, ted_rooz, shmo, vis_rdf, [all], man_gh FROM dbo.sailfact WHERE active='t' ORDER BY shfacfo DESC")
    q("s3_server_date", "SELECT dbo.ReturnDateServer(), CAST(dbo.UDF_Gregorian_To_Persian(GETDATE()) AS nvarchar(30))")


RECEIPT_WORDS = ["daryaft", "chk", "chek", "check", "cheq", "bank", "hesab", "pos", "kart", "card", "havale", "hvl",
                 "sandog", "sandoq", "naghd", "nagd", "tasv", "sayad", "fish", "recei", "pay", "get", "sanad", "tafsil",
                 "moin", "kol", "vosol", "vasl", "pardakht", "trans", "enteghal", "cash", "box", "account", "acc"]


def stage4(c, cur, q, out):
    """Receipts (store edition): how Atiran records cash, cheques, card (POS), bank transfer and havaleh
    against a customer, how they are linked to invoices (settlement) and which lists (banks, boxes, POS) exist."""
    like = " OR ".join("t.name LIKE N'%%%s%%'" % w for w in RECEIPT_WORDS)
    cur.execute("SELECT t.name FROM sys.tables t WHERE " + like + " ORDER BY t.name")
    tables = [r[0] for r in cur.fetchall()]
    out["s4_tables"] = tables
    q("s4_counts", "SELECT t.name, SUM(p.rows) FROM sys.tables t JOIN sys.partitions p ON p.object_id=t.object_id AND p.index_id IN (0,1) "
                   "WHERE " + like + " GROUP BY t.name ORDER BY t.name")
    q("s4_all_counts", "SELECT t.name, SUM(p.rows) FROM sys.tables t JOIN sys.partitions p ON p.object_id=t.object_id AND p.index_id IN (0,1) GROUP BY t.name HAVING SUM(p.rows) > 0 ORDER BY t.name")
    q("s4_cols", "SELECT OBJECT_NAME(c.object_id), c.name, ty.name, c.max_length, c.is_nullable, c.is_identity, OBJECT_DEFINITION(c.default_object_id) "
                 "FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id JOIN sys.tables t ON t.object_id=c.object_id "
                 "WHERE (" + like + ") OR t.name IN (N'cust_act', N'sailfact') ORDER BY OBJECT_NAME(c.object_id), c.column_id")
    q("s4_triggers", "SELECT OBJECT_NAME(tr.parent_id), tr.name, tr.is_disabled, tr.is_instead_of_trigger, LEFT(OBJECT_DEFINITION(tr.object_id), 15000) "
                     "FROM sys.triggers tr JOIN sys.tables t ON t.object_id=tr.parent_id WHERE (" + like + ") OR t.name IN (N'cust_act', N'sailfact')")
    q("s4_fks", "SELECT OBJECT_NAME(fk.parent_object_id), COL_NAME(fc.parent_object_id, fc.parent_column_id), OBJECT_NAME(fc.referenced_object_id), "
                "COL_NAME(fc.referenced_object_id, fc.referenced_column_id) FROM sys.foreign_keys fk JOIN sys.foreign_key_columns fc ON fk.object_id=fc.constraint_object_id")
    # Procedures / functions that write receipts or settle invoices.
    q("s4_procs", "SELECT o.name, o.type, LEN(m.definition), LEFT(m.definition, 40000) FROM sys.sql_modules m JOIN sys.objects o ON o.object_id=m.object_id "
                  "WHERE o.type IN ('P','FN','IF','TF','V') AND (o.name LIKE N'%dar%' OR o.name LIKE N'%chk%' OR o.name LIKE N'%chek%' OR o.name LIKE N'%check%' "
                  "OR o.name LIKE N'%tasv%' OR o.name LIKE N'%pos%' OR o.name LIKE N'%havale%' OR o.name LIKE N'%bank%' OR o.name LIKE N'%sanad%' "
                  "OR o.name LIKE N'%sayad%' OR o.name LIKE N'%naghd%' OR o.name LIKE N'%recei%' OR o.name LIKE N'%vosol%' OR o.name LIKE N'%hesab%' "
                  "OR m.definition LIKE N'%INSERT%INTO%getchk%' OR m.definition LIKE N'%INSERT%cust_act%act_bes%' OR m.definition LIKE N'%tasvieh%')")
    q("s4_proc_params", "SELECT OBJECT_NAME(p.object_id), p.name, TYPE_NAME(p.user_type_id), p.max_length, p.is_output FROM sys.parameters p "
                        "JOIN sys.objects o ON o.object_id=p.object_id WHERE o.type='P' AND (o.name LIKE N'%dar%' OR o.name LIKE N'%chk%' OR o.name LIKE N'%tasv%' "
                        "OR o.name LIKE N'%pos%' OR o.name LIKE N'%havale%' OR o.name LIKE N'%bank%' OR o.name LIKE N'%sanad%') ORDER BY OBJECT_NAME(p.object_id), p.parameter_id")
    # What kinds of customer movements exist (act_id), with examples.
    q("s4_act_ids", "SELECT act_id, COUNT(*), SUM(act_bed), SUM(act_bes), MIN([date]), MAX([date]) FROM dbo.cust_act GROUP BY act_id ORDER BY act_id")
    q("s4_act_samples", "SELECT * FROM (SELECT ROW_NUMBER() OVER (PARTITION BY act_id ORDER BY rdf_ DESC) rn, * FROM dbo.cust_act) x WHERE rn <= 4 ORDER BY act_id, rn")
    q("s4_recent_bes", "SELECT TOP (40) * FROM dbo.cust_act WHERE act_bes > 0 ORDER BY rdf_ DESC")
    for t in tables:
        q("s4_rows_" + t, "SELECT TOP (8) * FROM dbo.[%s] ORDER BY 1 DESC" % t.replace("]", "]]"))
    q("s4_tasvieh", "SELECT tasvieh, COUNT(*), SUM([all]) FROM dbo.sailfact WHERE active='t' GROUP BY tasvieh")
    q("s4_settings", "SELECT * FROM dbo.overal_setting")
    q("s4_visitors", "SELECT vis_rdf, CAST(vis_name AS nvarchar(200)), CAST(Username AS nvarchar(100)), UserID FROM dbo.visitors")
    q("s4_server_date", "SELECT dbo.ReturnDateServer(), CAST(dbo.UDF_Gregorian_To_Persian(GETDATE()) AS nvarchar(30))")


STAFF_WORDS = ["mosa", "mosae", "masa", "msd", "vam", "loan", "hogh", "hoq", "hoghoogh", "salar", "pers", "karmand", "emp", "staff",
               "pardakht", "pay", "sanad", "asnad", "doc", "tafsil", "moin", "kol", "hesab", "act", "gardesh", "sarfasl", "kasr", "ezafe",
               "cow", "sandog", "box", "dar", "par", "hazine", "cost", "mand"]


def _sel(cur, table, where="", top=300, order="1 DESC"):
    """SELECT with every char/varchar/text column cast to nvarchar so Persian (CP1256) text survives."""
    cur.execute("SELECT c.name, t.name FROM sys.columns c JOIN sys.types t ON c.user_type_id=t.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.[%s]') ORDER BY c.column_id" % table.replace("]", "]]"))
    cols = []
    for n, ty in cur.fetchall():
        if any(k in n.lower() for k in ("pass", "pwd")):
            continue
        qn = "[%s]" % n.replace("]", "]]")
        cols.append("CAST(%s AS nvarchar(max)) AS %s" % (qn, qn) if ty in ("char", "varchar", "text") else ("CAST(%s AS nvarchar(40)) AS %s" % (qn, qn) if ty in ("image", "varbinary", "binary", "timestamp") else qn))
    if not cols:
        return None
    return "SELECT TOP (%d) %s FROM dbo.[%s] %s ORDER BY %s" % (top, ",".join(cols), table.replace("]", "]]"), where, order)


def stage5(c, cur, q, out):
    """Store staff panel (v5.7.0): advances (مساعده), the staff member's own account statement (گردش حساب:
    invoices, payments, receipts, accounting headings in their name). Read-only."""
    q("s5_all_counts", "SELECT t.name, SUM(p.rows) FROM sys.tables t JOIN sys.partitions p ON p.object_id=t.object_id AND p.index_id IN (0,1) GROUP BY t.name ORDER BY t.name")
    like = " OR ".join("t.name LIKE N'%%%s%%'" % w for w in STAFF_WORDS)
    cur.execute("SELECT t.name FROM sys.tables t JOIN sys.partitions p ON p.object_id=t.object_id AND p.index_id IN (0,1) WHERE (" + like + ") GROUP BY t.name HAVING SUM(p.rows) > 0 ORDER BY t.name")
    tables = [r[0] for r in cur.fetchall()]
    out["s5_tables"] = tables
    q("s5_cols", "SELECT OBJECT_NAME(c.object_id), c.name, ty.name, c.max_length, c.is_nullable, c.is_identity FROM sys.columns c "
                 "JOIN sys.types ty ON c.user_type_id=ty.user_type_id JOIN sys.tables t ON t.object_id=c.object_id WHERE (" + like + ") "
                 "OR t.name IN (N'CUSTOMERS', N'visitors', N'sys_users', N'custgroup') ORDER BY OBJECT_NAME(c.object_id), c.column_id")
    for t in tables[:140]:
        sql = _sel(cur, t, top=6)
        if sql:
            q("s5_rows_" + t, sql)
    # Objects (procedures, views, functions) mentioning advances / salary / statement.
    q("s5_modules", "SELECT o.name, o.type, LEN(m.definition) FROM sys.sql_modules m JOIN sys.objects o ON o.object_id=m.object_id "
                    "WHERE m.definition LIKE N'%مساعد%' OR m.definition LIKE N'%مساعده%' OR o.name LIKE N'%mosa%' OR o.name LIKE N'%masa%' OR o.name LIKE N'%vam%' "
                    "OR o.name LIKE N'%hogh%' OR o.name LIKE N'%gardesh%' OR o.name LIKE N'%kardex%' OR o.name LIKE N'%cust_act%' OR o.name LIKE N'%daftar%' "
                    "OR o.name LIKE N'%tafsil%' OR o.name LIKE N'%pardakht%' OR o.name LIKE N'%sanad%' ORDER BY o.name")
    q("s5_module_defs", "SELECT o.name, LEFT(m.definition, 12000) FROM sys.sql_modules m JOIN sys.objects o ON o.object_id=m.object_id "
                        "WHERE (o.name LIKE N'%gardesh%' OR o.name LIKE N'%cust_act%' OR o.name LIKE N'%mosa%' OR o.name LIKE N'%masa%' OR o.name LIKE N'%FixManCustomer%' "
                        "OR o.name LIKE N'%daftar%' OR m.definition LIKE N'%مساعد%') AND o.type IN ('P','V','FN','IF','TF')")
    # Which tables hold text «مساعده» (any nvarchar/varchar column), with counts.
    cur.execute("SELECT t.name, c.name FROM sys.columns c JOIN sys.tables t ON t.object_id=c.object_id JOIN sys.types ty ON ty.user_type_id=c.user_type_id "
                "JOIN sys.partitions p ON p.object_id=t.object_id AND p.index_id IN (0,1) WHERE ty.name IN ('varchar','nvarchar','char','nchar','text','ntext') "
                "AND (c.max_length >= 20 OR c.max_length = -1) GROUP BY t.name, c.name HAVING SUM(p.rows) BETWEEN 1 AND 3000000")
    hits = []
    for t, col in cur.fetchall():
        try:
            cur.execute("SELECT COUNT(*) FROM dbo.[%s] WHERE CAST([%s] AS nvarchar(max)) LIKE N'%%مساعد%%' OR CAST([%s] AS nvarchar(max)) LIKE N'%%مساعده%%'"
                        % (t.replace("]", "]]"), col.replace("]", "]]"), col.replace("]", "]]")))
            n = cur.fetchone()[0]
            if n:
                hits.append([t, col, n])
        except Exception as ex:
            pass
    out["s5_mosaede_hits"] = hits
    for t, col, n in hits[:12]:
        sql = _sel(cur, t, "WHERE CAST([%s] AS nvarchar(max)) LIKE N'%%مساعد%%'" % col.replace("]", "]]"), top=30)
        if sql:
            q("s5_mosaede_rows_%s_%s" % (t, col), sql)
    # The staff members as customers / accounts (طرف حساب).
    words = ["محمودي", "محمودی", "نظري", "نظری", "لطيفي", "خدايار"]
    wl = " OR ".join("CAST(MONAME AS nvarchar(500)) LIKE N'%%%s%%'" % w for w in words)
    sql = _sel(cur, "CUSTOMERS", "WHERE " + wl, top=60)
    if sql:
        q("s5_staff_customers", sql)
    cur.execute("SELECT SHMO FROM dbo.CUSTOMERS WHERE " + wl)
    shmos = [int(r[0]) for r in cur.fetchall()][:20]
    out["s5_staff_shmos"] = shmos
    if shmos:
        sql = _sel(cur, "cust_act", "WHERE shmo IN (%s)" % ",".join(map(str, shmos)), top=600, order="shmo, [date], rdf_")
        if sql:
            q("s5_staff_cust_act", sql)
        q("s5_staff_sail", "SELECT shmo, COUNT(*), SUM([all]) FROM dbo.sailfact WHERE active='t' AND shmo IN (%s) GROUP BY shmo" % ",".join(map(str, shmos)))
    q("s5_sys_users", _sel(cur, "sys_users", top=60) or "SELECT 1")
    q("s5_visitors", _sel(cur, "visitors", top=60) or "SELECT 1")
    q("s5_act_ids", "SELECT act_id, COUNT(*), SUM(act_bed), SUM(act_bes), MIN([date]), MAX([date]), MAX(CAST(act_dis AS nvarchar(300))) FROM dbo.cust_act GROUP BY act_id ORDER BY act_id")
    q("s5_act_samples", "SELECT * FROM (SELECT ROW_NUMBER() OVER (PARTITION BY act_id ORDER BY rdf_ DESC) rn, rdf_, shmo, [date], act_id, act_bed, act_bes, CAST(act_dis AS nvarchar(400)) dis, ghno FROM dbo.cust_act) x WHERE rn <= 5 ORDER BY act_id, rn")
    # Customer groups (staff may sit in a «پرسنل» group).
    q("s5_custgroup", _sel(cur, "custgroup", top=80, order="1") or "SELECT 1")
    q("s5_cust_by_group", "SELECT ISNULL(group_rdf,0), COUNT(*) FROM dbo.CUSTOMERS GROUP BY ISNULL(group_rdf,0)")
    q("s5_server_date", "SELECT dbo.ReturnDateServer()")


def _sel6(cur, table, where="", top=300, order="1 DESC"):
    """Like _sel, but binary/image columns become their length (never their content) and passwords are skipped."""
    cur.execute("SELECT c.name, t.name FROM sys.columns c JOIN sys.types t ON c.user_type_id=t.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.[%s]') ORDER BY c.column_id" % table.replace("]", "]]"))
    cols = []
    for n, ty in cur.fetchall():
        qn = "[%s]" % n.replace("]", "]]")
        if any(k in n.lower() for k in ("pass", "pwd")):
            cols.append("CASE WHEN %s IS NULL THEN 0 ELSE 1 END AS %s" % (qn, "[has_" + n.replace("]", "]]") + "]"))
            continue
        if ty in ("image", "varbinary", "binary", "timestamp"):
            cols.append("DATALENGTH(%s) AS %s" % (qn, qn))
        elif ty in ("char", "varchar", "text"):
            cols.append("CAST(%s AS nvarchar(max)) AS %s" % (qn, qn))
        elif ty in ("ntext",):
            cols.append("CAST(%s AS nvarchar(max)) AS %s" % (qn, qn))
        else:
            cols.append(qn)
    if not cols:
        return None
    return "SELECT TOP (%d) %s FROM dbo.[%s] %s ORDER BY %s" % (top, ",".join(cols), table.replace("]", "]]"), where, order)


def stage6(c, cur, q, out):
    """Staff app (v5.8.0): who can sign in (sys_users / visitors), staff accounts (drivers, workers, office),
    the invoices written by the store users (UserID of mahmodi / nazari) with their lines and customer
    address / phone for delivery. Read-only."""
    q("s6_cols", "SELECT OBJECT_NAME(c.object_id), c.name, ty.name, c.max_length FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id "
                 "WHERE OBJECT_NAME(c.object_id) IN (N'sys_users', N'visitors', N'sailfact', N'subsailfact', N'CUSTOMERS', N'drivers', N'Driver', N'mamorp', N'Masir', N'masir', N'FactorConfirmation') "
                 "ORDER BY OBJECT_NAME(c.object_id), c.column_id")
    q("s6_tables_like", "SELECT t.name, SUM(p.rows) FROM sys.tables t JOIN sys.partitions p ON p.object_id=t.object_id AND p.index_id IN (0,1) "
                        "WHERE t.name LIKE N'%driver%' OR t.name LIKE N'%ranand%' OR t.name LIKE N'%mamor%' OR t.name LIKE N'%masir%' OR t.name LIKE N'%tahvil%' "
                        "OR t.name LIKE N'%deliver%' OR t.name LIKE N'%haml%' OR t.name LIKE N'%bar%' OR t.name LIKE N'%user%' OR t.name LIKE N'%role%' OR t.name LIKE N'%access%' GROUP BY t.name ORDER BY t.name")
    for t in ("sys_users", "visitors"):
        sql = _sel6(cur, t, top=80, order="1")
        if sql:
            q("s6_" + t, sql)
    for t in ("drivers", "Driver", "mamorp", "masir", "Masir"):
        try:
            sql = _sel6(cur, t, top=40, order="1")
            if sql:
                q("s6_rows_" + t, sql)
        except Exception as ex:
            out["errors"].append(["s6_rows_" + t, str(ex)])
    sql = _sel6(cur, "CUSTOMERS", "WHERE ISNULL(group_rdf,0) IN (3,4,5,6,7,8) OR ISNULL(IsEmp,0)=1", top=120, order="group_rdf, SHMO")
    if sql:
        q("s6_staff_customers", sql)
    sql = _sel6(cur, "sailfact", "WHERE active='t'", top=12, order="shfacfo DESC")
    if sql:
        q("s6_sailfact_last", sql)
    q("s6_sail_by_user", "SELECT UserID, COUNT(*), MIN([date]), MAX([date]), SUM(CASE WHEN ISNULL(Deleted,0)=0 THEN 1 ELSE 0 END) FROM dbo.sailfact WHERE active='t' GROUP BY UserID ORDER BY UserID")
    q("s6_sail_by_user_90", "SELECT UserID, vis_rdf, COUNT(*), SUM([all]) FROM dbo.sailfact WHERE active='t' AND ISNULL(Deleted,0)=0 AND [date] >= '1405/04/01' GROUP BY UserID, vis_rdf ORDER BY UserID, vis_rdf")
    q("s6_sail_driver", "SELECT rdf_driver, CAST(driver_name AS nvarchar(200)), rdf_mamorp, CAST(mamorp_name AS nvarchar(200)), COUNT(*) FROM dbo.sailfact WHERE active='t' GROUP BY rdf_driver, CAST(driver_name AS nvarchar(200)), rdf_mamorp, CAST(mamorp_name AS nvarchar(200)) ORDER BY COUNT(*) DESC")
    q("s6_sail_store_sample", "SELECT TOP (30) s.shfacfo, s.rdf__, s.[date], s.shmo, CAST(c.MONAME AS nvarchar(300)), s.[all], s.UserID, s.vis_rdf, s.[Status], "
                              "CAST(c.addre AS nvarchar(500)), CAST(c.tell1 AS nvarchar(60)), CAST(c.cell AS nvarchar(60)), c.Lat, c.Lng, CAST(s.[description] AS nvarchar(500)), s.t_time "
                              "FROM dbo.sailfact s LEFT JOIN dbo.CUSTOMERS c ON c.SHMO=s.shmo WHERE s.active='t' AND ISNULL(s.Deleted,0)=0 AND s.UserID IN (5,6) ORDER BY s.shfacfo DESC")
    q("s6_sail_store_contact", "SELECT COUNT(*), SUM(CASE WHEN LEN(LTRIM(CAST(c.addre AS nvarchar(500))))>3 THEN 1 ELSE 0 END), SUM(CASE WHEN LEN(LTRIM(CAST(c.cell AS nvarchar(60))))>6 OR LEN(LTRIM(CAST(c.tell1 AS nvarchar(60))))>6 THEN 1 ELSE 0 END), "
                               "SUM(CASE WHEN ISNULL(c.Lat,0)<>0 THEN 1 ELSE 0 END) FROM dbo.sailfact s LEFT JOIN dbo.CUSTOMERS c ON c.SHMO=s.shmo WHERE s.active='t' AND ISNULL(s.Deleted,0)=0 AND s.UserID IN (5,6)")
    cur.execute("SELECT TOP (3) shfacfo FROM dbo.sailfact WHERE active='t' AND ISNULL(Deleted,0)=0 AND UserID IN (5,6) ORDER BY shfacfo DESC")
    ids = [int(r[0]) for r in cur.fetchall()]
    if ids:
        sql = _sel6(cur, "subsailfact", "WHERE shfacfo IN (%s)" % ",".join(map(str, ids)), top=60, order="shfacfo, RDF")
        if sql:
            q("s6_lines", sql)
        q("s6_lines_units", "SELECT d.shfacfo, d.SHKA, CAST(d.naka AS nvarchar(300)), d.TEDVAH, d.TEDJOZ, d.LINESUM, i.mohvah, CAST(i.vahed AS nvarchar(60)), CAST(i.vahjoz AS nvarchar(60)) "
                             "FROM dbo.subsailfact d LEFT JOIN dbo.inventory i ON i.shka=d.SHKA WHERE d.shfacfo IN (%s) AND d.active='t'" % ",".join(map(str, ids)))
    q("s6_inventory_cols", "SELECT c.name, ty.name FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.inventory') ORDER BY c.column_id")
    q("s6_confirm", "SELECT TOP (10) * FROM dbo.FactorConfirmation ORDER BY 1 DESC")
    q("s6_server_date", "SELECT dbo.ReturnDateServer()")


def stage7(c, cur, q, out):
    """Store app (v5.9.0): customer groups (who is a supplier / staff), how suppliers appear in purchase
    invoices, product flags (active / hidden) and credit-limit columns. Read-only."""
    q("s7_custgroup", _sel6(cur, "custgroup", top=100, order="1"))
    q("s7_group_counts", "SELECT ISNULL(c.group_rdf,-1), COUNT(*), SUM(CASE WHEN ISNULL(c.man,0)>0 THEN 1 ELSE 0 END), "
                         "SUM(CASE WHEN EXISTS (SELECT 1 FROM dbo.sailfact s WHERE s.shmo=c.SHMO AND s.active='t') THEN 1 ELSE 0 END), "
                         "SUM(CASE WHEN EXISTS (SELECT 1 FROM dbo.buyfact b WHERE b.shmo=c.SHMO) THEN 1 ELSE 0 END) FROM dbo.CUSTOMERS c GROUP BY ISNULL(c.group_rdf,-1) ORDER BY 1")
    q("s7_customer_cols", "SELECT c.name, ty.name FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.CUSTOMERS') ORDER BY c.column_id")
    q("s7_buyfact_cols", "SELECT c.name, ty.name FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.buyfact') ORDER BY c.column_id")
    q("s7_suppliers", "SELECT TOP (60) c.SHMO, CAST(c.MONAME AS nvarchar(300)), c.group_rdf, c.man, (SELECT COUNT(*) FROM dbo.buyfact b WHERE b.shmo=c.SHMO), "
                      "(SELECT COUNT(*) FROM dbo.sailfact s WHERE s.shmo=c.SHMO AND s.active='t') FROM dbo.CUSTOMERS c WHERE EXISTS (SELECT 1 FROM dbo.buyfact b WHERE b.shmo=c.SHMO) ORDER BY 5 DESC")
    for g in (2, 5, 12, 13, 14, 15):
        q("s7_group_sample_%d" % g, "SELECT TOP (15) SHMO, CAST(MONAME AS nvarchar(300)), man FROM dbo.CUSTOMERS WHERE group_rdf=%d ORDER BY SHMO" % g)
    q("s7_flag_cols", "SELECT OBJECT_NAME(c.object_id), c.name, ty.name FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id "
                      "WHERE OBJECT_NAME(c.object_id) IN (N'CUSTOMERS', N'inventory') AND (c.name LIKE N'%active%' OR c.name LIKE N'%delet%' OR c.name LIKE N'%hide%' OR c.name LIKE N'%show%' "
                      "OR c.name LIKE N'%etebar%' OR c.name LIKE N'%credit%' OR c.name LIKE N'%type%' OR c.name LIKE N'%kind%' OR c.name LIKE N'%Is%' OR c.name LIKE N'%status%' OR c.name LIKE N'%sagf%' OR c.name LIKE N'%saqf%' OR c.name LIKE N'%max%')")
    q("s7_inventory_counts", "SELECT COUNT(*), SUM(CASE WHEN ISNULL(TRY_CONVERT(decimal(19,2),mojkavah),0)>0 OR ISNULL(TRY_CONVERT(decimal(19,2),mojkajoz),0)>0 THEN 1 ELSE 0 END) FROM dbo.inventory")
    q("s7b_groups", ";WITH x AS (SELECT c.SHMO, ISNULL(c.group_rdf,-1) g, ISNULL(c.man,0) man, CAST(c.MONAME AS nvarchar(300)) nm, ISNULL(c.active,'t') act, ISNULL(c.kind,-1) kind, ISNULL(c.IsEmp,-1) emp, "
                    "CASE WHEN EXISTS (SELECT 1 FROM dbo.sailfact s WHERE s.shmo=c.SHMO AND s.active='t') THEN 1 ELSE 0 END sold, "
                    "CASE WHEN EXISTS (SELECT 1 FROM dbo.buyfact b WHERE b.shmo=c.SHMO AND b.active='t') THEN 1 ELSE 0 END bought FROM dbo.CUSTOMERS c) "
                    "SELECT g, COUNT(*), SUM(sold), SUM(bought), SUM(CASE WHEN sold=0 AND bought=0 THEN 1 ELSE 0 END), SUM(CASE WHEN nm LIKE N'%0[0-9]%' THEN 1 ELSE 0 END), "
                    "SUM(CASE WHEN man>0 THEN 1 ELSE 0 END), SUM(CASE WHEN act<>'t' THEN 1 ELSE 0 END), MIN(SHMO), MAX(SHMO) FROM x GROUP BY g ORDER BY g")
    q("s7b_kind", "SELECT ISNULL(kind,-1), ISNULL(group_rdf,-1), COUNT(*) FROM dbo.CUSTOMERS GROUP BY ISNULL(kind,-1), ISNULL(group_rdf,-1) ORDER BY 1,2")
    q("s7b_active", "SELECT ISNULL(active,'?'), COUNT(*) FROM dbo.CUSTOMERS GROUP BY ISNULL(active,'?')")
    q("s7b_g2_sold", "SELECT TOP (40) c.SHMO, CAST(c.MONAME AS nvarchar(300)), c.man, (SELECT COUNT(*) FROM dbo.sailfact s WHERE s.shmo=c.SHMO AND s.active='t'), (SELECT COUNT(*) FROM dbo.buyfact b WHERE b.shmo=c.SHMO AND b.active='t'), c.vis_rdf "
                     "FROM dbo.CUSTOMERS c WHERE c.group_rdf=2 ORDER BY 4 DESC")
    q("s7b_g2_tagged_nosale", "SELECT COUNT(*) FROM dbo.CUSTOMERS c WHERE c.group_rdf=2 AND CAST(c.MONAME AS nvarchar(300)) LIKE N'%0[0-9]%' AND NOT EXISTS (SELECT 1 FROM dbo.buyfact b WHERE b.shmo=c.SHMO AND b.active='t')")
    q("s7b_staffgroups_sample", "SELECT TOP (80) c.SHMO, CAST(c.MONAME AS nvarchar(300)), c.group_rdf, c.man, (SELECT COUNT(*) FROM dbo.sailfact s WHERE s.shmo=c.SHMO AND s.active='t') FROM dbo.CUSTOMERS c WHERE c.group_rdf IN (3,4,5,6,7,8) ORDER BY c.group_rdf, c.SHMO")
    q("s7b_inv_active", "SELECT ISNULL(active,'?'), ISNULL(black_list,-1), COUNT(*) FROM dbo.inventory GROUP BY ISNULL(active,'?'), ISNULL(black_list,-1)")
    q("s7_inventory_groups", "SELECT i.group_rdf, CAST(g.group_name AS nvarchar(200)), COUNT(*) FROM dbo.inventory i LEFT JOIN dbo.kagroup g ON g.group_rdf=i.group_rdf GROUP BY i.group_rdf, CAST(g.group_name AS nvarchar(200)) ORDER BY 1")


def stage8(c, cur, q, out):
    """Stock accuracy (produced goods), product search text and product images. Read-only."""
    q("s8_objects", "SELECT o.name, o.type_desc FROM sys.objects o WHERE o.is_ms_shipped=0 AND o.type IN ('P','FN','TF','IF','V','U','TR') AND ("
                    "o.name LIKE '%mojodi%' OR o.name LIKE '%mojoodi%' OR o.name LIKE '%stock%' OR o.name LIKE '%kardex%' OR o.name LIKE '%tolid%' OR o.name LIKE '%product%' "
                    "OR o.name LIKE '%formul%' OR o.name LIKE '%montage%' OR o.name LIKE '%anbar%' OR o.name LIKE '%ka_act%' OR o.name LIKE '%kaact%' OR o.name LIKE '%gardesh%' "
                    "OR o.name LIKE '%mande%' OR o.name LIKE '%remain%' OR o.name LIKE '%balance%' OR o.name LIKE '%warehouse%' OR o.name LIKE '%movement%' OR o.name LIKE '%pic%' "
                    "OR o.name LIKE '%image%' OR o.name LIKE '%aks%' OR o.name LIKE '%photo%' OR o.name LIKE '%sakht%' OR o.name LIKE '%masraf%' OR o.name LIKE '%havale%' OR o.name LIKE '%resid%') ORDER BY o.type_desc, o.name")
    rows = (out.get("s8_objects") or {}).get("rows", []) or []
    names = [r[0] for r in rows if r[1] in ("SQL_STORED_PROCEDURE", "SQL_SCALAR_FUNCTION", "SQL_TABLE_VALUED_FUNCTION", "SQL_INLINE_TABLE_VALUED_FUNCTION", "VIEW", "SQL_TRIGGER")]
    want = [n for n in names if any(k in n.lower() for k in ("mojodi", "mojoodi", "stock", "kardex", "tolid", "production", "formul", "montage", "gardesh"))][:40]
    for extra in ("UpdateMojodiInventory", "UpdateMojodiInventoryAnbars", "InvoiceTrigger"):
        if extra not in want: want.append(extra)
    for n in want:
        q("s8_def_" + n, "SELECT LEFT(OBJECT_DEFINITION(OBJECT_ID(N'dbo.%s')), 24000)" % n.replace("'", ""))
    q("s8_triggers", "SELECT t.name, OBJECT_NAME(t.parent_id) FROM sys.triggers t WHERE t.parent_id<>0 ORDER BY 2,1")
    q("s8_ka_act_cols", "SELECT c.name, ty.name FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.ka_act') ORDER BY c.column_id")
    q("s8_ka_act_by_act", "SELECT act_id, COUNT(*) FROM dbo.ka_act GROUP BY act_id ORDER BY act_id")
    q("s8_ka_act_samples", "SELECT * FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY act_id ORDER BY (SELECT 1)) mrn FROM dbo.ka_act) x WHERE mrn<=3")
    q("s8_act_tables", "SELECT name FROM sys.tables WHERE name LIKE '%act%' OR name LIKE '%kind%' ORDER BY name")
    q("s8_inventory_cols", "SELECT c.name, ty.name FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.inventory') ORDER BY c.column_id")
    tabs = [r[0] for r in rows if r[1] == "USER_TABLE"]
    for t in tabs[:60]:
        q("s8_cols_" + t, "SELECT c.name, ty.name, (SELECT SUM(p.rows) FROM sys.partitions p WHERE p.object_id=c.object_id AND p.index_id IN (0,1)) FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.%s') ORDER BY c.column_id" % t.replace("'", ""))
    q("s8_prod_items", "SELECT TOP (60) i.shka, CAST(i.naka AS nvarchar(200)), i.group_rdf, i.mohvah, i.mojkavah, i.mojkajoz FROM dbo.inventory i WHERE i.group_rdf=3 ORDER BY i.shka")
    kc = [r[0] for r in (out.get("s8_ka_act_cols") or {}).get("rows", []) or []]
    lk = [x.lower() for x in kc]
    num = [x for x in kc if x.lower() in ("tedvah", "tedjoz", "ted", "tedad", "vared", "sader", "meghdar", "tedvahmain", "tedjozmain")]
    if "shka" in lk and "act_id" in lk:
        sums = ", ".join("SUM(CAST(ISNULL(k.[%s],0) AS decimal(19,3)))" % x for x in num) or "COUNT(*)"
        q("s8_prod_ledger", "SELECT k.shka, k.act_id, COUNT(*), %s FROM dbo.ka_act k WHERE k.shka IN (SELECT TOP (25) shka FROM dbo.inventory WHERE group_rdf=3 ORDER BY shka) GROUP BY k.shka, k.act_id ORDER BY 1,2" % sums)
        q("s8_ledger_rows_one", "SELECT TOP (80) k.* FROM dbo.ka_act k WHERE k.shka=(SELECT TOP (1) k2.shka FROM dbo.ka_act k2 JOIN dbo.inventory i ON i.shka=k2.shka WHERE i.group_rdf=3 GROUP BY k2.shka ORDER BY COUNT(*) DESC)")
        q("s8_top_ledger_items", "SELECT TOP (30) k.shka, CAST(MAX(i.naka) AS nvarchar(200)), COUNT(DISTINCT k.act_id), MAX(i.mojkavah), MAX(i.mojkajoz), MAX(i.mohvah) FROM dbo.ka_act k JOIN dbo.inventory i ON i.shka=k.shka "
                                  "WHERE k.act_id NOT IN (10,20) GROUP BY k.shka ORDER BY COUNT(*) DESC")
    q("s8_mojodi_anbars", "SELECT TOP (20) * FROM dbo.MojodiInventoryAnbars")
    q("s8_anbars", _sel6(cur, "anbars", top=40, order="1"))
    q("s8_name_chars", "SELECT SUM(CASE WHEN naka LIKE N'%ي%' THEN 1 ELSE 0 END), SUM(CASE WHEN naka LIKE N'%ك%' THEN 1 ELSE 0 END), SUM(CASE WHEN CHARINDEX(NCHAR(8204),naka)>0 THEN 1 ELSE 0 END), "
                       "SUM(CASE WHEN CHARINDEX(NCHAR(160),naka)>0 THEN 1 ELSE 0 END), SUM(CASE WHEN naka LIKE N'%ـ%' THEN 1 ELSE 0 END), SUM(CASE WHEN naka LIKE N'%  %' THEN 1 ELSE 0 END), "
                       "SUM(CASE WHEN naka LIKE N'%ة%' OR naka LIKE N'%أ%' OR naka LIKE N'%إ%' OR naka LIKE N'%ؤ%' OR naka LIKE N'%ئ%' THEN 1 ELSE 0 END), SUM(CASE WHEN naka LIKE N'%[0-9]%' THEN 1 ELSE 0 END), "
                       "COUNT(*) FROM dbo.inventory")
    q("s8_name_types", "SELECT c.name, ty.name, c.max_length, c.collation_name FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.inventory') AND c.name IN ('naka','pic','StuffCode','barcode','Barcode','code')")
    q("s8_name_sample", "SELECT TOP (60) shka, CAST(naka AS nvarchar(200)) FROM dbo.inventory ORDER BY NEWID()")
    q("s8_name_hex", "SELECT TOP (15) shka, CONVERT(varchar(400), CAST(CAST(naka AS nvarchar(100)) AS varbinary(200)), 2) FROM dbo.inventory ORDER BY shka DESC")
    q("s8_pic_stats", "SELECT COUNT(*), SUM(CASE WHEN DATALENGTH(pic)>20 THEN 1 ELSE 0 END), MAX(DATALENGTH(pic)), AVG(CAST(DATALENGTH(pic) AS bigint)) FROM dbo.inventory")
    q("s8_pic_magic", "SELECT TOP (20) shka, CAST(naka AS nvarchar(120)), DATALENGTH(pic), CONVERT(varchar(40), CAST(SUBSTRING(pic,1,12) AS varbinary(12)), 2) FROM dbo.inventory WHERE DATALENGTH(pic)>20")
    q("s8_image_tables", "SELECT t.name, c.name, ty.name, (SELECT SUM(p.rows) FROM sys.partitions p WHERE p.object_id=t.object_id AND p.index_id IN (0,1)) FROM sys.tables t JOIN sys.columns c ON c.object_id=t.object_id JOIN sys.types ty ON c.user_type_id=ty.user_type_id WHERE ty.name IN ('image','varbinary') ORDER BY 1,2")



def stage9(c, cur, q, out):
    """سامانه مودیان: everything Atiran keeps that a tax invoice needs. Read-only."""
    like = ["tax", "maliat", "moadian", "modian", "sstid", "shenase", "eghtesad", "economic", "melli", "national", "posti", "postal",
            "avarez", "vat", "fiscal", "memory", "hafeze", "irtax", "uniq", "sayad", "tins", "tinb", "khadamat", "stuff", "iran"]
    cond = " OR ".join("o.name LIKE '%%%s%%'" % k for k in like)
    q("s9_objects", "SELECT o.name, o.type_desc FROM sys.objects o WHERE o.is_ms_shipped=0 AND o.type IN ('P','FN','TF','IF','V','U','TR') AND (" + cond + ") ORDER BY o.type_desc, o.name")
    ccond = " OR ".join("c.name LIKE '%%%s%%'" % k for k in like + ["code", "cod_"])
    q("s9_columns", "SELECT t.name, c.name, ty.name, c.max_length FROM sys.tables t JOIN sys.columns c ON c.object_id=t.object_id JOIN sys.types ty ON c.user_type_id=ty.user_type_id "
                    "WHERE (" + ccond + ") ORDER BY t.name, c.column_id")
    for t in ("sailfact", "subsailfact", "CUSTOMERS", "inventory", "subsailtemp", "kagroup", "vahed", "units"):
        q("s9_cols_" + t, "SELECT c.name, ty.name, c.max_length FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.%s') ORDER BY c.column_id" % t)
    q("s9_tables_rows", "SELECT t.name, SUM(p.rows) FROM sys.tables t JOIN sys.partitions p ON p.object_id=t.object_id AND p.index_id IN (0,1) GROUP BY t.name HAVING SUM(p.rows)>0 ORDER BY t.name")
    rows = (out.get("s9_objects") or {}).get("rows", []) or []
    progs = [r[0] for r in rows if r[1] in ("SQL_STORED_PROCEDURE", "SQL_SCALAR_FUNCTION", "SQL_TABLE_VALUED_FUNCTION", "SQL_INLINE_TABLE_VALUED_FUNCTION", "VIEW", "SQL_TRIGGER")]
    for n in progs[:45]:
        q("s9_def_" + n, "SELECT LEFT(OBJECT_DEFINITION(OBJECT_ID(N'dbo.%s')), 16000)" % n.replace("'", ""))
    tabs = [r[0] for r in rows if r[1] == "USER_TABLE"]
    for t in tabs[:50]:
        tt = t.replace("'", "").replace("]", "")
        q("s9_tcols_" + t, "SELECT c.name, ty.name, c.max_length FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id WHERE c.object_id=OBJECT_ID(N'dbo.%s') ORDER BY c.column_id" % tt)
        q("s9_top_" + t, "SELECT TOP (12) * FROM dbo.[%s]" % tt)
    # invoice / line tax fields actually in use
    q("s9_sail_sample", "SELECT TOP (8) * FROM dbo.sailfact WHERE active='t' ORDER BY shfacfo DESC")
    q("s9_sub_sample", "SELECT TOP (12) * FROM dbo.subsailfact WHERE active='t' ORDER BY shfacfo DESC")
    q("s9_sub_tax_dist", "SELECT TOP (40) ISNULL(CAST(ptax AS nvarchar(20)),N'NULL'), ISNULL(CAST(PAvarez AS nvarchar(20)),N'NULL'), COUNT(*), SUM(CAST(ISNULL(tax,0) AS decimal(19,0))), SUM(CAST(ISNULL(avarez,0) AS decimal(19,0))) FROM dbo.subsailfact WHERE active='t' GROUP BY ptax, PAvarez ORDER BY 3 DESC")
    q("s9_sail_by_status", "SELECT ISNULL([Status],-1), ISNULL(DocumentSourceID,-1), COUNT(*), MIN([date]), MAX([date]) FROM dbo.sailfact WHERE active='t' GROUP BY [Status], DocumentSourceID ORDER BY 3 DESC")
    q("s9_rdf_kinds", "SELECT ISNULL(rdf__,-1), COUNT(*) FROM dbo.sailfact WHERE active='t' GROUP BY rdf__ ORDER BY 1")
    q("s9_cust_ids", "SELECT COUNT(*), SUM(CASE WHEN LEN(LTRIM(RTRIM(CAST(ISNULL(ecocode,'') AS nvarchar(40)))))>0 THEN 1 ELSE 0 END) FROM dbo.CUSTOMERS")
    q("s9_cust_sample", "SELECT TOP (10) * FROM dbo.CUSTOMERS WHERE active='t' ORDER BY NEWID()")
    q("s9_inv_sample", "SELECT TOP (10) * FROM dbo.inventory ORDER BY NEWID()")
    q("s9_settings_tables", "SELECT t.name FROM sys.tables t WHERE t.name LIKE '%setting%' OR t.name LIKE '%config%' OR t.name LIKE '%company%' OR t.name LIKE '%sherkat%' OR t.name LIKE '%moshakhasat%' OR t.name LIKE '%info%' OR t.name LIKE '%param%' ORDER BY 1")
    for t in [r[0] for r in (out.get("s9_settings_tables") or {}).get("rows", []) or []][:15]:
        q("s9_set_" + t, "SELECT TOP (60) * FROM dbo.[%s]" % t.replace("]", ""))
    q("s9_return_tables", "SELECT t.name FROM sys.tables t WHERE t.name LIKE '%ret%' OR t.name LIKE '%bargasht%' OR t.name LIKE '%marjoo%' OR t.name LIKE '%back%' ORDER BY 1")
    q("s9_vahed_tables", "SELECT t.name FROM sys.tables t WHERE t.name LIKE '%vahed%' OR t.name LIKE '%unit%' OR t.name LIKE '%measure%' ORDER BY 1")
    for t in [r[0] for r in (out.get("s9_vahed_tables") or {}).get("rows", []) or []][:6]:
        q("s9_vah_" + t, "SELECT TOP (60) * FROM dbo.[%s]" % t.replace("]", ""))


def stage10(c, cur, q, out):
    """Moadian, part 2: Atiran's own tax views/procs, returns, fill rates. Read-only."""
    q("s10_schemas", "SELECT s.name, t.name, (SELECT SUM(p.rows) FROM sys.partitions p WHERE p.object_id=t.object_id AND p.index_id IN (0,1)) FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id "
                     "WHERE t.name IN ('Tax','TaxExemptionType','Nationality','AtiranSettings','AtiranKindDocument','ActSubmittedTax','IrTaxID','TaxSystemLog','back_sanad','b_az_mosh_sanad','DeviceSettings','PublicSettings') OR t.name LIKE '%back%' OR t.name LIKE '%b_az%' OR s.name<>'dbo' ORDER BY 1,2")
    q("s10_modules", "SELECT OBJECT_SCHEMA_NAME(m.object_id), OBJECT_NAME(m.object_id), o.type_desc, LEN(m.definition) FROM sys.sql_modules m JOIN sys.objects o ON o.object_id=m.object_id "
                     "WHERE m.definition LIKE '%TaxUniqueID%' OR m.definition LIKE '%SubmittedTax%' OR m.definition LIKE '%IrTaxID%' OR m.definition LIKE '%TaxSystemLog%' OR m.definition LIKE '%UidTax%' OR m.definition LIKE '%StuffCode%' OR m.definition LIKE '%UnitCode%' ORDER BY 2")
    names = [(r[0], r[1]) for r in (out.get("s10_modules") or {}).get("rows", []) or []]
    for must in (("dbo", "Vw_HeaderSaleTax"), ("dbo", "Vw_Tax_tis"), ("dbo", "VW_AllBargashtiForTax"), ("dbo", "set_ptax_group")):
        if must not in names: names.insert(0, must)
    for sch, n in names[:40]:
        q("s10_def_" + n, "SELECT OBJECT_DEFINITION(OBJECT_ID(N'[%s].[%s]'))" % (sch.replace("'", ""), n.replace("'", "")))
    for sch, t, cnt in [(r[0], r[1], r[2]) for r in (out.get("s10_schemas") or {}).get("rows", []) or []][:60]:
        if t in ("Tax", "TaxExemptionType", "Nationality", "AtiranSettings", "AtiranKindDocument", "back_sanad", "back_sanad_kind", "b_az_mosh_sanad", "DeviceSettings", "PublicSettings", "subback_sanad", "b_az_mosh", "back_sail", "subbacksail") or "back" in t.lower() or "b_az" in t.lower() or "tax" in t.lower():
            q("s10_cols_%s_%s" % (sch, t), "SELECT c.name, ty.name, c.max_length FROM sys.columns c JOIN sys.types ty ON c.user_type_id=ty.user_type_id WHERE c.object_id=OBJECT_ID(N'[%s].[%s]') ORDER BY c.column_id" % (sch, t))
            q("s10_top_%s_%s" % (sch, t), "SELECT TOP (8) * FROM [%s].[%s]" % (sch, t))
    q("s10_sail_rdf", "SELECT rdf__, COUNT(*), MIN(shfacfo), MAX(shfacfo), SUM(CAST(ISNULL(all_fel,[all]) AS decimal(19,0))), SUM(CAST(ISNULL(tax,0) AS decimal(19,0))) FROM dbo.sailfact WHERE active='t' GROUP BY rdf__ ORDER BY 1")
    q("s10_sail_rdf_samples", "SELECT * FROM (SELECT rdf__, shfacfo, [date], time_, shmo, CAST(moname AS nvarchar(200)) mn, [all], all_fel, tax, avarez, tafif, userid, vis_rdf, tasvieh, man_gh, MabDaryaftFactor, ROW_NUMBER() OVER (PARTITION BY rdf__ ORDER BY shfacfo DESC) rn FROM dbo.sailfact WHERE active='t') x WHERE rn<=3")
    q("s10_sail_tax_state", "SELECT ISNULL(SubmittedTax,-1), ISNULL(TypeInvoiceSentToMoadiyan,-1), COUNT(*), SUM(CASE WHEN TaxUniqueID IS NULL OR TaxUniqueID='' THEN 0 ELSE 1 END), ISNULL(CAST(Deleted AS int),-1) FROM dbo.sailfact GROUP BY SubmittedTax, TypeInvoiceSentToMoadiyan, Deleted")
    q("s10_cust_fill", "SELECT COUNT(*), SUM(CASE WHEN LEN(ISNULL(c_mel,''))>0 THEN 1 ELSE 0 END), SUM(CASE WHEN LEN(ISNULL(c_egh,''))>0 THEN 1 ELSE 0 END), SUM(CASE WHEN LEN(ISNULL(c_pos,''))>0 THEN 1 ELSE 0 END), "
                       "SUM(CASE WHEN LEN(ISNULL(Shenaseh_Egh,''))>0 THEN 1 ELSE 0 END), SUM(CASE WHEN ISNULL(WithTax,0)=1 THEN 1 ELSE 0 END), SUM(CASE WHEN ISNULL(TaxInvoiceType,0)<>0 THEN 1 ELSE 0 END) FROM dbo.CUSTOMERS WHERE active='t'")
    q("s10_cust_person", "SELECT ISNULL(PersonalityType,-1), ISNULL(TaxInvoiceType,-1), COUNT(*) FROM dbo.CUSTOMERS WHERE active='t' GROUP BY PersonalityType, TaxInvoiceType")
    q("s10_cust_filled", "SELECT TOP (15) SHMO, CAST(MONAME AS nvarchar(200)), c_mel, c_egh, c_pos, Shenaseh_Egh, PersonalityType, TaxInvoiceType, WithTax, CustomerBranch FROM dbo.CUSTOMERS WHERE LEN(ISNULL(c_mel,''))>0 OR LEN(ISNULL(c_egh,''))>0 OR LEN(ISNULL(Shenaseh_Egh,''))>0")
    q("s10_inv_fill", "SELECT COUNT(*), SUM(CASE WHEN LEN(ISNULL(StuffCode,''))>0 THEN 1 ELSE 0 END), SUM(CASE WHEN ISNULL(isTaxProduct,0)=1 THEN 1 ELSE 0 END), SUM(CASE WHEN ISNULL(ptax,0)>0 THEN 1 ELSE 0 END), SUM(CASE WHEN ISNULL(PAvarez,0)>0 THEN 1 ELSE 0 END), SUM(CASE WHEN LEN(ISNULL(NtswCode,''))>0 THEN 1 ELSE 0 END) FROM dbo.inventory WHERE active='t'")
    q("s10_inv_units", "SELECT CAST(vahsanj AS nvarchar(60)), COUNT(*) FROM dbo.inventory WHERE active='t' GROUP BY vahsanj ORDER BY 2 DESC")
    q("s10_inv_bastebandi", "SELECT CAST(bastebandi AS nvarchar(60)), COUNT(*) FROM dbo.inventory WHERE active='t' GROUP BY bastebandi ORDER BY 2 DESC")
    q("s10_inv_taxed", "SELECT TOP (30) shka, CAST(naka AS nvarchar(200)), ptax, PAvarez, StuffCode, isTaxProduct, CAST(vahsanj AS nvarchar(40)) FROM dbo.inventory WHERE active='t' AND (ISNULL(ptax,0)>0 OR LEN(ISNULL(StuffCode,''))>0 OR ISNULL(isTaxProduct,0)=1)")
    q("s10_units_all", "SELECT * FROM dbo.UNITS")
    q("s10_sub_units", "SELECT CAST(BASTEBANDI AS nvarchar(40)), COUNT(*) FROM dbo.subsailfact WHERE active='t' GROUP BY BASTEBANDI")
    q("s10_sub_money", "SELECT TOP (10) s.shfacfo, s.[all], s.all_fel, s.sumlineall, s.sumlineall_fel, s.tafif, s.SumTafifAghlam, s.tax, s.avarez, s.barbari, s.tdf, (SELECT SUM(LINESUM) FROM dbo.subsailfact x WHERE x.shfacfo=s.shfacfo AND x.rdf__=s.rdf__ AND x.active='t'), "
                       "(SELECT SUM(ISNULL(linesum_fel,LINESUM)) FROM dbo.subsailfact x WHERE x.shfacfo=s.shfacfo AND x.rdf__=s.rdf__ AND x.active='t'), (SELECT SUM(TafifAghlam) FROM dbo.subsailfact x WHERE x.shfacfo=s.shfacfo AND x.rdf__=s.rdf__ AND x.active='t'), "
                       "(SELECT SUM(ISNULL(TafifLine,0)) FROM dbo.subsailfact x WHERE x.shfacfo=s.shfacfo AND x.rdf__=s.rdf__ AND x.active='t'), (SELECT SUM(litakhma) FROM dbo.subsailfact x WHERE x.shfacfo=s.shfacfo AND x.rdf__=s.rdf__ AND x.active='t') "
                       "FROM dbo.sailfact s WHERE s.active='t' AND (s.tafif<>0 OR s.SumTafifAghlam<>0 OR s.tax<>0) ORDER BY s.shfacfo DESC")
    q("s10_sub_disc_lines", "SELECT TOP (15) x.shfacfo, x.SHKA, x.TEDVAH, x.TEDJOZ, x.VAHPRICE, x.JOZPRICE, x.LINESUM, x.linesum_fel, x.PERTAFIF, x.TafifAghlam, x.TafifLine, x.TafifLineFel, x.litakhma, x.litakhma_fel, x.ptax, x.tax, x.tax_fel, x.Gift, x.TEDBASTEBANDI, x.tedvah_fel, x.tedjoz_fel "
                            "FROM dbo.subsailfact x WHERE x.active='t' AND (x.TafifAghlam<>0 OR x.tax<>0 OR ISNULL(x.TafifLine,0)<>0 OR x.Gift=1 OR x.TEDJOZ<>0) ORDER BY x.shfacfo DESC")
    q("s10_company", "SELECT name, C_meli, C_egh, C_pos, TaxMemoryID, Branch, CASE WHEN TaxPrivateKey IS NULL THEN 0 ELSE LEN(TaxPrivateKey) END, t_kind FROM dbo.Company")
    q("s10_public_settings", "SELECT TOP (200) * FROM dbo.PublicSettings")
    q("s10_devsettings", "SELECT TOP (20) * FROM dbo.DeviceSettings")
    q("s10_users", "SELECT TOP (30) * FROM dbo.sys_users")

def main():
    out = {"errors": []}
    c = connect("Atiran2")
    cur = c.cursor()

    def q(key, sql):
        out[key] = safe_rows(cur, out, sql)

    if os.environ.get("PROBE_STAGE") == "10":
        stage10(c, cur, q, out)
        json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
        print("stage 10 done; errors:", len(out["errors"]))
        return
    if os.environ.get("PROBE_STAGE") == "9":
        stage9(c, cur, q, out)
        json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
        print("stage 9 done; errors:", len(out["errors"]))
        return
    if os.environ.get("PROBE_STAGE") == "8":
        stage8(c, cur, q, out)
        json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
        print("stage 8 done; errors:", len(out["errors"]))
        return
    if os.environ.get("PROBE_STAGE") == "7":
        stage7(c, cur, q, out)
        json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
        print("stage 7 done; errors:", len(out["errors"]))
        return
    if os.environ.get("PROBE_STAGE") == "6":
        stage6(c, cur, q, out)
        json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
        print("stage 6 done; errors:", len(out["errors"]))
        return
    if os.environ.get("PROBE_STAGE") == "5":
        stage5(c, cur, q, out)
        json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
        print("stage 5 done; errors:", len(out["errors"]), "; tables:", len(out.get("s5_tables", [])), "; hits:", len(out.get("s5_mosaede_hits", [])))
        return
    if os.environ.get("PROBE_STAGE") == "4":
        stage4(c, cur, q, out)
        json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
        print("stage 4 done; errors:", len(out["errors"]), "; tables:", len(out.get("s4_tables", [])))
        return
    if os.environ.get("PROBE_STAGE") == "3":
        stage3(c, cur, q, out)
        json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
        print("stage 3 done; errors:", len(out["errors"]))
        return
    if os.environ.get("PROBE_STAGE") == "2":
        stage2(c, cur, q, out)
        json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
        print("stage 2 done; errors:", len(out["errors"]), "; tests:", {k: (v if isinstance(v, str) else "ok") for k, v in out.get("tests", {}).items()})
        return

    def cols(table):
        q("cols_" + table, """
            SELECT c.name, t.name AS type, c.max_length, c.is_nullable, c.is_identity,
                   OBJECT_DEFINITION(c.default_object_id) AS dflt, c.is_computed
            FROM sys.columns c JOIN sys.types t ON c.user_type_id=t.user_type_id
            WHERE c.object_id=OBJECT_ID(N'dbo.%s') ORDER BY c.column_id""" % table)

    for t in ["visitors", "sys_users", "CUSTOMERS", "masir", "regions", "Quarter", "CITYS", "GOODS", "overal_setting"]:
        cols(t)

    # Logins: every column except anything that looks like a password.
    for t in ["visitors", "sys_users"]:
        cur.execute("SELECT name FROM sys.columns WHERE object_id=OBJECT_ID(N'dbo.%s')" % t)
        names = [r[0] for r in cur.fetchall() if not any(k in r[0].lower() for k in ("pass", "pwd", "رمز"))]
        if names:
            q("rows_" + t, "SELECT TOP (200) " + ",".join("[%s]" % n for n in names) + " FROM dbo.[%s]" % t)

    q("cust_count", "SELECT COUNT(*) FROM dbo.CUSTOMERS")
    q("cust_like", """
        SELECT
          SUM(CASE WHEN MONAME LIKE N'%08%' OR MONAME LIKE N'%۰۸%' THEN 1 ELSE 0 END) AS name08,
          SUM(CASE WHEN MONAME LIKE N'%07%' OR MONAME LIKE N'%۰۷%' THEN 1 ELSE 0 END) AS name07,
          SUM(CASE WHEN CAST(SHMO AS nvarchar(50)) LIKE N'08%' THEN 1 ELSE 0 END) AS shmo08,
          SUM(CASE WHEN CAST(SHMO AS nvarchar(50)) LIKE N'07%' THEN 1 ELSE 0 END) AS shmo07
        FROM dbo.CUSTOMERS""")
    # Any text/number column whose value starts with 08 / 07 (a "code" column other than SHMO?).
    cur.execute("""SELECT c.name FROM sys.columns c JOIN sys.types t ON c.user_type_id=t.user_type_id
                   WHERE c.object_id=OBJECT_ID(N'dbo.CUSTOMERS') AND t.name IN ('nvarchar','varchar','nchar','char','int','bigint','numeric','decimal')""")
    starts = {}
    for (name,) in cur.fetchall():
        try:
            cur.execute("SELECT SUM(CASE WHEN LTRIM(CAST([%s] AS nvarchar(200))) LIKE N'08%%' THEN 1 ELSE 0 END), "
                        "SUM(CASE WHEN LTRIM(CAST([%s] AS nvarchar(200))) LIKE N'07%%' THEN 1 ELSE 0 END), "
                        "SUM(CASE WHEN CAST([%s] AS nvarchar(200)) LIKE N'%%08%%' THEN 1 ELSE 0 END), "
                        "SUM(CASE WHEN CAST([%s] AS nvarchar(200)) LIKE N'%%07%%' THEN 1 ELSE 0 END) FROM dbo.CUSTOMERS" % (name, name, name, name))
            r = cur.fetchone()
            if any(r):
                starts[name] = list(r)
        except Exception as ex:
            out["errors"].append("starts %s: %s" % (name, str(ex)[:120]))
    out["cust_code_like"] = starts
    q("cust_vis_08", "SELECT vis_rdf, COUNT(*) FROM dbo.CUSTOMERS WHERE MONAME LIKE N'%08%' OR MONAME LIKE N'%۰۸%' GROUP BY vis_rdf")
    q("cust_vis_07", "SELECT vis_rdf, COUNT(*) FROM dbo.CUSTOMERS WHERE MONAME LIKE N'%07%' OR MONAME LIKE N'%۰۷%' GROUP BY vis_rdf")
    q("cust_vis_all", "SELECT vis_rdf, COUNT(*) FROM dbo.CUSTOMERS GROUP BY vis_rdf")
    q("cust_sample_08", "SELECT TOP (25) * FROM dbo.CUSTOMERS WHERE MONAME LIKE N'%08%' OR MONAME LIKE N'%۰۸%' ORDER BY SHMO")
    q("cust_sample_07", "SELECT TOP (25) * FROM dbo.CUSTOMERS WHERE MONAME LIKE N'%07%' OR MONAME LIKE N'%۰۷%' ORDER BY SHMO")
    q("cust_latest", "SELECT TOP (8) * FROM dbo.CUSTOMERS ORDER BY SHMO DESC")
    q("cust_triggers", "SELECT name, OBJECT_DEFINITION(object_id) FROM sys.triggers WHERE parent_id=OBJECT_ID(N'dbo.CUSTOMERS')")
    q("cust_fks", """SELECT fk.name, COL_NAME(fc.parent_object_id, fc.parent_column_id), OBJECT_NAME(fc.referenced_object_id), COL_NAME(fc.referenced_object_id, fc.referenced_column_id)
                     FROM sys.foreign_keys fk JOIN sys.foreign_key_columns fc ON fk.object_id=fc.constraint_object_id
                     WHERE fk.parent_object_id=OBJECT_ID(N'dbo.CUSTOMERS') OR fk.referenced_object_id=OBJECT_ID(N'dbo.CUSTOMERS')""")
    q("cust_indexes", """SELECT i.name, i.is_unique, i.is_primary_key, COL_NAME(ic.object_id, ic.column_id)
                         FROM sys.indexes i JOIN sys.index_columns ic ON i.object_id=ic.object_id AND i.index_id=ic.index_id
                         WHERE i.object_id=OBJECT_ID(N'dbo.CUSTOMERS')""")
    # Atiran's own procedures that insert customers (and anything with an approval idea).
    q("procs_insert_customers", """
        SELECT o.name, o.type, LEN(m.definition), LEFT(m.definition, 6000)
        FROM sys.sql_modules m JOIN sys.objects o ON o.object_id=m.object_id
        WHERE m.definition LIKE N'%INSERT%CUSTOMERS%' OR o.name LIKE N'%cust%' OR o.name LIKE N'%moshtari%' OR o.name LIKE N'%shmo%'""")
    q("tables_like_customer", "SELECT name FROM sys.tables WHERE name LIKE N'%cust%' OR name LIKE N'%mosh%' OR name LIKE N'%meelano%' OR name LIKE N'%tafsil%' OR name LIKE N'%hesab%' OR name LIKE N'%kol%' OR name LIKE N'%moein%'")
    q("masir_rows", "SELECT TOP (80) * FROM dbo.masir")
    q("regions_rows", "SELECT TOP (80) * FROM dbo.regions")
    q("quarter_rows", "SELECT TOP (80) * FROM dbo.[Quarter]")
    q("citys_rows", "SELECT TOP (40) * FROM dbo.CITYS")
    q("overal_customer", "SELECT TOP (400) * FROM dbo.overal_setting")
    # Products and groups (photo matching).
    q("goods_count", "SELECT COUNT(*) FROM dbo.GOODS")
    q("goods_names", "SELECT TOP (700) * FROM dbo.GOODS")
    q("groups_tables", "SELECT name FROM sys.tables WHERE name LIKE N'%group%' OR name LIKE N'%goroh%' OR name LIKE N'%grp%'")
    json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
    print("errors:", len(out["errors"]))


if __name__ == "__main__":
    main()
