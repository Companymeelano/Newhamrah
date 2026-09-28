#!/usr/bin/env python3
"""
READ-ONLY probe of the Atiran SQL Server database.

Purpose: learn exactly how Atiran stores pre-invoices (پیش‌فاکتور) so Meelano writes them in a
form Atiran's own pre-invoice list shows. Only SELECT statements are executed. Nothing is written.

The result is written as JSON to the path given on the command line. The CI job encrypts it with
a public key before committing it (this repository is public). Only counts are printed to the log.
"""
import datetime
import decimal
import json
import os
import re
import sys
import uuid

import pytds

SRC = "MEELANO-Android/app/src/main/java/ir/meelano/android/MainActivity.java"
OUT = sys.argv[1] if len(sys.argv) > 1 else "probe.json"
S_KEY = 73


def hidden(name, src):
    m = re.search(r"int\[\] " + name + r" = \{([^}]*)\}", src)
    return "".join(chr(int(x) ^ S_KEY) for x in m.group(1).replace("\n", "").split(",") if x.strip())


def mask(v):
    """Keep numbers, dates, flags and short codes; hide long text and binary."""
    if v is None:
        return None
    if isinstance(v, (bytes, bytearray)):
        return "<bin %d>" % len(v)
    if isinstance(v, (datetime.datetime, datetime.date, datetime.time)):
        return v.isoformat()
    if isinstance(v, decimal.Decimal):
        return float(v)
    if isinstance(v, uuid.UUID):
        return str(v)
    if isinstance(v, str):
        s = v.strip()
        if s.startswith("MEELANO-APP-"):
            return s
        return s if len(s) <= 24 else "<text %d>" % len(s)
    return v


def main():
    src = open(SRC, encoding="utf-8").read()
    conn = pytds.connect(server=hidden("S_HOST", src), port=1433, database=hidden("S_DB", src),
                         user=hidden("S_USER", src), password=hidden("S_PASS", src),
                         login_timeout=25, timeout=90, autocommit=True)
    out = {"errors": []}

    def q(sql, params=None, masked=False, limit=None):
        cur = conn.cursor()
        cur.execute(sql, params)
        if not cur.description:
            return {"cols": [], "rows": []}
        cols = [d[0] for d in cur.description]
        rows = []
        for r in cur.fetchall():
            rows.append([mask(x) if masked else (x.isoformat() if hasattr(x, "isoformat") else (float(x) if isinstance(x, decimal.Decimal) else x)) for x in r])
            if limit and len(rows) >= limit:
                break
        return {"cols": cols, "rows": rows}

    def safe(key, fn):
        try:
            out[key] = fn()
        except Exception as ex:  # keep going; record the reason
            out["errors"].append("%s: %s" % (key, str(ex)[:300]))

    q("SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED; SET LOCK_TIMEOUT 5000;")

    if os.environ.get("PROBE_STAGE") == "3":
        stage3(conn, q, safe, out)
        json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
        print("stage3 errors:", len(out["errors"]))
        return
    if os.environ.get("PROBE_STAGE") == "2":
        stage2(q, safe)
        json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
        print("stage2 sections:", len(out) - 1, "errors:", len(out["errors"]))
        return

    safe("server", lambda: q("SELECT CAST(SERVERPROPERTY('ProductVersion') AS nvarchar(40)) ver, CAST(SERVERPROPERTY('Edition') AS nvarchar(80)) edition, DB_NAME() db, CAST(SERVERPROPERTY('Collation') AS nvarchar(80)) collation, IS_SRVROLEMEMBER('sysadmin') sysadmin, IS_MEMBER('db_owner') dbo, HAS_PERMS_BY_NAME(NULL,NULL,'VIEW SERVER STATE') viewstate"))
    safe("tables", lambda: q("SELECT t.name, SUM(p.rows) row_count, t.create_date, t.modify_date FROM sys.tables t JOIN sys.partitions p ON p.object_id=t.object_id AND p.index_id IN (0,1) GROUP BY t.name, t.create_date, t.modify_date ORDER BY t.name"))
    safe("views", lambda: q("SELECT name FROM sys.views ORDER BY name"))
    safe("columns", lambda: q("""
        SELECT o.name tbl, c.column_id, c.name col, ty.name type, c.max_length, c.precision, c.scale, c.is_nullable,
               c.is_identity, c.is_computed, OBJECT_DEFINITION(c.default_object_id) default_def
        FROM sys.columns c JOIN sys.objects o ON o.object_id=c.object_id JOIN sys.types ty ON ty.user_type_id=c.user_type_id
        WHERE o.type='U' ORDER BY o.name, c.column_id"""))
    safe("keys", lambda: q("""
        SELECT o.name tbl, i.name idx, i.is_primary_key, i.is_unique, STUFF((SELECT ','+c.name FROM sys.index_columns ic JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id
               WHERE ic.object_id=i.object_id AND ic.index_id=i.index_id ORDER BY ic.key_ordinal FOR XML PATH('')),1,1,'') cols
        FROM sys.indexes i JOIN sys.objects o ON o.object_id=i.object_id
        WHERE o.type='U' AND (i.is_primary_key=1 OR i.is_unique=1) AND (o.name LIKE '%sail%' OR o.name LIKE '%pish%' OR o.name LIKE '%fact%')"""))
    safe("triggers", lambda: q("""
        SELECT OBJECT_NAME(tr.parent_id) tbl, tr.name, tr.is_disabled, LEFT(OBJECT_DEFINITION(tr.object_id), 6000) def
        FROM sys.triggers tr WHERE tr.parent_class=1"""))
    safe("modules_pish", lambda: q("""
        SELECT o.name, o.type_desc, LEFT(m.definition, 8000) def FROM sys.sql_modules m JOIN sys.objects o ON o.object_id=m.object_id
        WHERE m.definition LIKE '%pish%' OR m.definition LIKE '%sailfact%' ORDER BY o.name"""))
    # How Atiran's desktop program itself reads/writes pre-invoices (needs VIEW SERVER STATE).
    safe("plan_cache_pish", lambda: q("""
        SELECT TOP (80) qs.execution_count, qs.last_execution_time, LEFT(st.text, 4000) txt
        FROM sys.dm_exec_query_stats qs CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
        WHERE st.text LIKE '%pish%' AND st.text NOT LIKE '%meelano%' AND st.text NOT LIKE '%dm_exec_query_stats%'
        ORDER BY qs.last_execution_time DESC"""))
    safe("plan_cache_sailfact", lambda: q("""
        SELECT TOP (40) qs.execution_count, qs.last_execution_time, LEFT(st.text, 3000) txt
        FROM sys.dm_exec_query_stats qs CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
        WHERE st.text LIKE '%sailfact%' AND st.text NOT LIKE '%pish%' AND st.text NOT LIKE '%meelano%' AND st.text NOT LIKE '%dm_exec_query_stats%'
        ORDER BY qs.last_execution_time DESC"""))

    # Meelano's own record of what happened to each submitted pre-invoice.
    safe("meelano_prefactors", lambda: q("""
        SELECT TOP (25) id, created_at, status, native_prefactor_table, native_prefactor_no, native_sync_at, ready_for_invoice,
               invoice_status, system_convert_note, customer_code, visitor_id, grand_total
        FROM dbo.meelano_prefactors ORDER BY id DESC"""))
    safe("meelano_notes", lambda: q("""
        SELECT LEFT(ISNULL(system_convert_note,N''),160) note, COUNT(*) n, MAX(id) last_id FROM dbo.meelano_prefactors
        GROUP BY LEFT(ISNULL(system_convert_note,N''),160) ORDER BY n DESC"""))

    # Sample rows of every pre-invoice / sales header+detail table (masked).
    tables = [r[0] for r in out.get("tables", {}).get("rows", [])]
    cand = [t for t in tables if re.search(r"pish|sail|fact", t, re.I) and not t.lower().startswith("meelano")]
    out["samples"] = {}
    cols_by_tbl = {}
    for r in out.get("columns", {}).get("rows", []):
        cols_by_tbl.setdefault(r[0], []).append(r[2])
    for t in cand[:40]:
        cols = cols_by_tbl.get(t, [])
        order = next((c for c in cols if c.lower() in ("shfacfo", "shfac", "id", "radif")), cols[0] if cols else None)
        if not order:
            continue
        def sample(t=t, order=order):
            return q("SELECT TOP (8) * FROM dbo.[%s] ORDER BY [%s] DESC" % (t.replace("]", "]]"), order.replace("]", "]]")), masked=True)
        try:
            out["samples"][t] = sample()
        except Exception as ex:
            out["errors"].append("sample %s: %s" % (t, str(ex)[:200]))

    # Rows Meelano itself inserted into Atiran tables, found through the external id column.
    out["meelano_rows_in_atiran"] = {}
    for t in cand[:40]:
        for col in cols_by_tbl.get(t, []):
            if col.lower() in ("client_uuid", "mobile_uuid", "uuid", "app_uuid", "external_id", "external_code", "source_id", "meelano_id"):
                try:
                    out["meelano_rows_in_atiran"]["%s.%s" % (t, col)] = q(
                        "SELECT TOP (8) * FROM dbo.[%s] WHERE TRY_CONVERT(nvarchar(200),[%s]) LIKE N'MEELANO-APP-%%' ORDER BY 1 DESC" % (t, col), masked=True)
                except Exception as ex:
                    out["errors"].append("meelano rows %s: %s" % (t, str(ex)[:200]))
    # Native numbers Meelano reports, looked up in the table it says it used.
    out["reported_native_rows"] = []
    for r in out.get("meelano_prefactors", {}).get("rows", [])[:10]:
        tbl, no = r[3], r[4]
        if not tbl or not no or tbl not in cols_by_tbl:
            continue
        numcol = next((c for c in cols_by_tbl[tbl] if c.lower() in ("shfacfo", "shfac", "shfacpish", "shfac_pish", "pish_no", "factor_no", "no", "number")), None)
        if not numcol:
            continue
        try:
            res = q("SELECT TOP (2) * FROM dbo.[%s] WHERE TRY_CONVERT(nvarchar(120),[%s])=%%s" % (tbl, numcol), (str(no),), masked=True)
            out["reported_native_rows"].append({"meelano_id": r[0], "table": tbl, "no": no, "found": len(res["rows"]), "rows": res})
        except Exception as ex:
            out["errors"].append("reported row %s: %s" % (tbl, str(ex)[:200]))

    json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, default=str)
    print("probe ok: tables=%d columns=%d samples=%d plan_pish=%d errors=%d" % (
        len(tables), len(out.get("columns", {}).get("rows", [])), len(out["samples"]),
        len(out.get("plan_cache_pish", {}).get("rows", [])), len(out["errors"])))


def stage2(q, safe):
    """Second read-only pass: units/prices, warehouse, approval settings, join paths, full list procs."""
    safe("inv_stats", lambda: q("SELECT COUNT(*) n, SUM(CASE WHEN mohvah>1 THEN 1 ELSE 0 END) multi, SUM(CASE WHEN active='t' THEN 1 ELSE 0 END) act FROM dbo.inventory"))
    safe("inv_multi", lambda: q("SELECT TOP 20 shka, mohvah, vahsanj, bastebandi, tedbastebandi, inventory_price, FinalSalePrice, vahsp, buy_price, pure_buy_price, active, ptax, PAvarez FROM dbo.inventory WHERE mohvah>1 ORDER BY shka DESC"))
    safe("inv_single", lambda: q("SELECT TOP 10 shka, mohvah, vahsanj, bastebandi, tedbastebandi, inventory_price, FinalSalePrice, vahsp, active FROM dbo.inventory WHERE mohvah=1 AND active='t' ORDER BY shka DESC"))
    safe("sale_lines_vs_inv", lambda: q("""SELECT TOP 40 s.shfacfo, s.rdf__, s.RDF, s.SHKA, s.rdf_anbar, s.TEDVAH, s.TEDJOZ, s.VAHPRICE, s.JOZPRICE, s.LINESUM, s.BASTEBANDI, s.TEDBASTEBANDI, s.PERTAFIF, s.TafifAghlam, s.litakhma, s.ptax, s.tax,
        i.mohvah, i.vahsanj, i.bastebandi inv_bastebandi, i.tedbastebandi inv_tedbaste, i.inventory_price, i.FinalSalePrice, i.vahsp
        FROM dbo.subsailfact s JOIN dbo.inventory i ON i.shka=s.SHKA WHERE s.active='t' ORDER BY CASE WHEN i.mohvah>1 THEN 0 ELSE 1 END, s.shfacfo DESC"""))
    safe("sale_rdf_base", lambda: q("SELECT MIN(RDF) min_rdf, MAX(RDF) max_rdf, COUNT(*) n FROM dbo.subsailfact WHERE active='t' AND shfacfo IN (SELECT TOP 50 shfacfo FROM dbo.sailfact WHERE active='t' ORDER BY shfacfo DESC)"))
    safe("anbars", lambda: q("SELECT rdf_anbar, name, Active, Base FROM dbo.anbars ORDER BY rdf_anbar", masked=True))
    safe("anbar_usage", lambda: q("SELECT TOP 10 rdf_anbar, COUNT(*) n FROM dbo.subsailfact GROUP BY rdf_anbar ORDER BY n DESC"))
    safe("settings", lambda: q("SELECT id, dis, value FROM dbo.overal_setting WHERE id IN (77,78,95,98,135) OR dis LIKE N'%پيش%' OR dis LIKE N'%پیش%' OR dis LIKE N'%انبار%' OR dis LIKE N'%pish%' ORDER BY id"))
    safe("customers", lambda: q("""SELECT c.SHMO, c.code, c.active, c.man, c.RDF_masir, c.vis_rdf,
        (SELECT COUNT(*) FROM dbo.masir m WHERE m.rdf_masir=c.RDF_masir) has_masir,
        (SELECT COUNT(*) FROM dbo.masir m JOIN dbo.[Quarter] qq ON m.QuarterID=qq.ID JOIN dbo.regions r ON qq.RegionId=r.rdf_region JOIN dbo.CITYS ct ON r.rdf_city=ct.RDF WHERE m.rdf_masir=c.RDF_masir) list_join_ok
        FROM dbo.CUSTOMERS c WHERE c.SHMO IN (412,896,294,319,1310)"""))
    safe("cust_join_stats", lambda: q("""SELECT COUNT(*) n,
        SUM(CASE WHEN EXISTS(SELECT 1 FROM dbo.masir m JOIN dbo.[Quarter] qq ON m.QuarterID=qq.ID JOIN dbo.regions r ON qq.RegionId=r.rdf_region JOIN dbo.CITYS ct ON r.rdf_city=ct.RDF WHERE m.rdf_masir=c.RDF_masir) THEN 1 ELSE 0 END) list_ok
        FROM dbo.CUSTOMERS c WHERE c.active='t'"""))
    safe("visitors", lambda: q("SELECT vis_rdf, vis_name, active, Username, UserID FROM dbo.visitors ORDER BY vis_rdf"))
    safe("sys_users", lambda: q("SELECT user_id, user_name, active, role_id FROM dbo.sys_users ORDER BY user_id"))
    safe("pish_all", lambda: q("SELECT * FROM dbo.sailfact_pish ORDER BY shfacfo, rdf__"))
    safe("subpish_all", lambda: q("SELECT * FROM dbo.subsailfact_pish ORDER BY shfacfo, rdf__, RDF"))
    safe("meelano_items", lambda: q("SELECT TOP 40 prefactor_id, product_code, qty, price, amount, unit, pack_count, line_discount FROM dbo.meelano_prefactor_items ORDER BY prefactor_id DESC, id"))
    safe("today", lambda: q("SELECT CAST(dbo.UDF_Gregorian_To_Persian(GETDATE()) AS nvarchar(40)) p, GETDATE() g, OBJECT_ID('dbo.date_alan') da"))
    for name in ["ListPishFactor", "AddInvoice", "EditInvoice", "add_sail_pish", "sp_ListPishFactor"]:
        safe("def_" + name, lambda name=name: q("SELECT SUBSTRING(OBJECT_DEFINITION(OBJECT_ID(%s)), n*3500+1, 3500) part FROM (SELECT TOP 12 ROW_NUMBER() OVER (ORDER BY object_id)-1 n FROM sys.objects) x ORDER BY n", ("dbo." + name,)))
    safe("proc_stats", lambda: q("""SELECT TOP 40 OBJECT_NAME(ps.object_id, ps.database_id) name, ps.execution_count, ps.last_execution_time FROM sys.dm_exec_procedure_stats ps
        WHERE ps.database_id=DB_ID() ORDER BY ps.last_execution_time DESC"""))
    safe("pish_proc_stats", lambda: q("""SELECT OBJECT_NAME(ps.object_id, ps.database_id) name, ps.execution_count, ps.last_execution_time FROM sys.dm_exec_procedure_stats ps
        WHERE ps.database_id=DB_ID() AND (OBJECT_NAME(ps.object_id, ps.database_id) LIKE '%pish%' OR OBJECT_NAME(ps.object_id, ps.database_id) LIKE '%Invoice%')"""))
    safe("plan_views", lambda: q("""SELECT TOP 30 qs.execution_count, qs.last_execution_time, SUBSTRING(st.text,1,3000) txt FROM sys.dm_exec_query_stats qs CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
        WHERE (st.text LIKE '%pishfactor%' OR st.text LIKE '%ListPishFactor%' OR st.text LIKE '%VW_ListAllPishFactors%' OR st.text LIKE '%subsailfact_pish%' OR st.text LIKE '%add_sail_pish%')
        AND st.text NOT LIKE '%dm_exec_query_stats%' AND st.text NOT LIKE 'CREATE%' ORDER BY qs.last_execution_time DESC"""))


def stage3(conn, q, safe, out):
    """Rolled-back dry run: write one pre-invoice exactly like the app's new writer, ask Atiran's own
    ListPishFactor whether it is listed, then ROLLBACK. Nothing stays in the database."""
    cur = conn.cursor()
    steps = []
    try:
        cur.execute("BEGIN TRANSACTION")
        cur.execute("SELECT ISNULL(MAX(shfacfo),0)+1 FROM dbo.sailfact_pish WITH (UPDLOCK, HOLDLOCK)"); no = cur.fetchone()[0]
        cur.execute("SELECT CAST(dbo.UDF_Gregorian_To_Persian(GETDATE()) AS nvarchar(30))"); date = cur.fetchone()[0]
        cur.execute("SELECT man FROM dbo.CUSTOMERS WHERE SHMO=412"); man = cur.fetchone()[0]
        cur.execute("SELECT TOP (1) ISNULL(rdf_tahbarg,0), ISNULL(nah_par,0) FROM dbo.sailfact WHERE active='t' AND shmo=412 ORDER BY shfacfo DESC"); r = cur.fetchone(); tah, nah = (r if r else (0, 0))
        steps.append({"no": no, "date": date, "tahbarg": tah, "nah_par": nah})
        cur.execute("""INSERT INTO dbo.sailfact_pish(rdf__,shfacfo,USER__,[date],shmo,barbari,shfacthand,vis_rdf,sumlineall,[all],gainall,tafif,jamtakhgh,done_date,panevis,isret,ismodify,active,modpar,rdf_sarbarg,rdf_tahbarg,nah_par,mod_darsad_vis,nah_d_text,man_gh,sh_f,user_f,date_f,ted_rooz,taeed,taeedUser,sysid,TaedHesabdari,TaedForush,Rejected,tax,avarez,Promotion,Stamp)
            VALUES(1,%s,%s,%s,412,0,%s,6,%s,%s,0,0,0,%s,%s,'0','0','t',0,0,%s,%s,0,%s,%s,0,'--','--',30,0,'--',1,0,0,0,0,0,0,%s)""",
            (no, "latifi", date, "آزمايش ميلانو (برگشت داده مي‌شود)", 70 * 65000 + 2 * 7140000, 70 * 65000 + 2 * 7140000, date, "ذکر نشده", tah, nah, "اعتباري", man, "MEELANO-TEST"))
        lines = [(1796, 1, 10, 65000 * 60, 65000, "عدد", 70 * 65000, 0), (667, 2, 0, 7140000, 7140000, "", 2 * 7140000, 1)]
        for shka, tv, tj, vp, jp, bb, ls, rdf in lines:
            cur.execute("""INSERT INTO dbo.subsailfact_pish(rdf__,shfacfo,SHKA,rdf_anbar,TEDVAH,TEDJOZ,VAHPRICE,JOZPRICE,BASTEBANDI,TEDBASTEBANDI,LINESUM,LINEGAIN,ISRET,PERTAFIF,RDF,jozgain,PERVIS,litakhma,active,amani,Pavarez,Avarez,Ptax,Tax,Mp,PerPromotion)
                VALUES(1,%s,%s,1,%s,%s,%s,%s,%s,0,%s,0,'0',0,%s,0,0,0,'t',0,0,0,0,0,0,0)""", (no, shka, tv, tj, vp, jp, bb, ls, rdf))
        cur.execute("UPDATE dbo.sailfact_pish SET TaedHesabdari=1, UserTaedHesabdari=N'اتوماتيك', DateTaedHesabdari=%s WHERE shfacfo=%s AND active='t' AND (SELECT value FROM dbo.overal_setting WHERE id=77)=1", (date, no))
        cur.execute("UPDATE dbo.sailfact_pish SET TaedForush=1, UserTaedForush=N'اتوماتيك', DateTaedForush=%s WHERE shfacfo=%s AND active='t' AND (SELECT value FROM dbo.overal_setting WHERE id=78)=1", (date, no))
        cur.execute("SELECT rdf__,shfacfo,USER__,[date],shmo,vis_rdf,[all],active,Rejected,sh_f,TaedHesabdari,TaedForush,DateRecive,TimeRecive,Stamp FROM dbo.sailfact_pish WHERE shfacfo=%s", (no,))
        steps.append({"header_after_triggers": [list(map(str, x)) for x in cur.fetchall()]})
        for mod in (1, 2, 4):
            try:
                cur.execute("EXEC dbo.ListPishFactor @mydate=%s, @Mod=%s", (date, mod))
                found = []
                while True:
                    if cur.description:
                        cols = [d[0] for d in cur.description]
                        for row in cur.fetchall():
                            rec = dict(zip(cols, row))
                            if str(rec.get("shfacfo")) == str(no):
                                found.append({k: str(rec.get(k)) for k in ("shfacfo", "date", "MONAME", "vis_name", "all", "Weight") if k in rec})
                    if not cur.nextset():
                        break
                steps.append({"ListPishFactor_mod": mod, "listed": found})
            except Exception as ex:
                steps.append({"ListPishFactor_mod": mod, "error": str(ex)[:300]})
        cur.execute("SELECT COUNT(*) FROM dbo.pishfactors WHERE shfacfo=%s", (no,)); steps.append({"view_pishfactors": cur.fetchone()[0]})
        cur.execute("SELECT COUNT(*) FROM dbo.pishfactor_body WHERE shfacfo=%s", (no,)); steps.append({"view_pishfactor_body": cur.fetchone()[0]})
        cur.execute("SELECT COUNT(*) FROM dbo.VwListPishfactorhayeTeadNashodeh WHERE shfacfo=%s", (no,)); steps.append({"view_teadnashode": cur.fetchone()[0]})
    except Exception as ex:
        out["errors"].append("stage3: " + str(ex)[:400])
    finally:
        try:
            cur.execute("IF @@TRANCOUNT>0 ROLLBACK TRANSACTION")
        except Exception as ex:
            out["errors"].append("rollback: " + str(ex)[:200])
        try:
            cur.execute("SELECT @@TRANCOUNT, (SELECT COUNT(*) FROM dbo.sailfact_pish WHERE Stamp='MEELANO-TEST')"); steps.append({"after_rollback_trancount_and_test_rows": list(cur.fetchone())})
        except Exception as ex:
            out["errors"].append("verify: " + str(ex)[:200])
    out["stage3"] = steps


if __name__ == "__main__":
    try:
        main()
    except Exception as ex:  # never print connection details
        json.dump({"errors": ["fatal: %s" % type(ex).__name__, str(ex)[:120].replace(".", "·")]}, open(OUT, "w"))
        print("probe failed:", type(ex).__name__)
