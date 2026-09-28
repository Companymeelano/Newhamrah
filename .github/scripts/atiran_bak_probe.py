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


def main():
    out = {"errors": []}
    c = connect("Atiran2")
    cur = c.cursor()

    def q(key, sql):
        out[key] = safe_rows(cur, out, sql)

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
