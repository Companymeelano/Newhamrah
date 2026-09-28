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


if __name__ == "__main__":
    try:
        main()
    except Exception as ex:  # never print connection details
        json.dump({"errors": ["fatal: %s" % type(ex).__name__, str(ex)[:120].replace(".", "·")]}, open(OUT, "w"))
        print("probe failed:", type(ex).__name__)
