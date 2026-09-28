#!/usr/bin/env python3
"""
End-to-end test of Meelano against a RESTORED COPY of the Atiran2 backup (never the live server).

  restore <out.json>  restore Atiran2.bak into the local SQL Server container, create the app's SQL
                      login there, give visitor 'latifi' a throw-away test password, record "before".
  verify  <out.json>  after the app's self-test ran on the emulator: read what the app wrote, ask
                      Atiran's own ListPishFactor whether it is listed, compare the header with a row
                      made by Atiran's own add_sail_pish (inside a rolled-back transaction).

Output JSON is encrypted by the workflow (the repository is public). The log shows PASS/FAIL only.
"""
import datetime
import decimal
import json
import os
import re
import sys
import time

import pytds

SRC = "MEELANO-Android/app/src/main/java/ir/meelano/android/MainActivity.java"
S_KEY = 73


def hidden(name, src):
    m = re.search(r"int\[\] " + name + r" = \{([^}]*)\}", src)
    return "".join(chr(int(x) ^ S_KEY) for x in m.group(1).replace("\n", "").split(",") if x.strip())


def val(x):
    if isinstance(x, (datetime.datetime, datetime.date, datetime.time)):
        return x.isoformat()
    if isinstance(x, decimal.Decimal):
        return float(x)
    if isinstance(x, (bytes, bytearray)):
        return "<bin %d>" % len(x)
    return x


def connect(db="master", tries=40):
    last = None
    for _ in range(tries):
        try:
            return pytds.connect(server="127.0.0.1", port=1433, database=db, user="sa",
                                 password=os.environ["SA_PASS"], autocommit=True, timeout=900, login_timeout=10)
        except Exception as ex:  # server still starting
            last = ex
            time.sleep(4)
    raise last


def rows(cur, sql, params=None):
    cur.execute(sql, params)
    if not cur.description:
        return {"cols": [], "rows": []}
    cols = [d[0] for d in cur.description]
    return {"cols": cols, "rows": [[val(v) for v in r] for r in cur.fetchall()]}


def safe_rows(cur, out, sql, params=None):
    try:
        return rows(cur, sql, params)
    except Exception as ex:
        out["errors"].append("%s: %s" % (sql[:60], str(ex)[:200]))
        return {"cols": [], "rows": []}


def wait_ready():
    """SQL Server finishes its own upgrade scripts after it first accepts logins; restoring during
    that window made the server drop the connection. Wait for three clean answers in a row."""
    ok = 0
    for _ in range(60):
        try:
            c = connect(tries=5)
            cur = c.cursor()
            cur.execute("SELECT COUNT(*) FROM sys.databases WHERE state_desc='ONLINE'")
            cur.fetchall()
            c.close()
            ok += 1
            if ok >= 3:
                return
        except Exception:
            ok = 0
        time.sleep(5)


def restore(out_path):
    out = {"errors": []}
    wait_ready()
    c = connect()
    cur = c.cursor()
    files = rows(cur, "RESTORE FILELISTONLY FROM DISK = N'/var/opt/mssql/backup/Atiran2.bak'")
    out["filelist"] = [[r[0], r[2]] for r in files["rows"]]
    moves = []
    for r in files["rows"]:
        logical, ftype = r[0], r[2]
        target = "/var/opt/mssql/data/Atiran2%s" % ("_log.ldf" if ftype == "L" else (".mdf" if not moves else "_%d.ndf" % len(moves)))
        moves.append("MOVE N'%s' TO N'%s'" % (logical.replace("'", "''"), target))
    cur.execute("RESTORE DATABASE [Atiran2] FROM DISK = N'/var/opt/mssql/backup/Atiran2.bak' WITH REPLACE, RECOVERY, " + ", ".join(moves))
    while cur.nextset():
        pass
    out["header"] = rows(cur, "RESTORE HEADERONLY FROM DISK = N'/var/opt/mssql/backup/Atiran2.bak'")["rows"][0][:30]
    src = open(SRC, encoding="utf-8").read()
    user, pw = hidden("S_USER", src), hidden("S_PASS", src)
    if user.lower() == "sa":
        cur.execute("ALTER LOGIN [sa] WITH PASSWORD = N'%s', CHECK_POLICY = OFF" % pw.replace("'", "''"))
        os.environ["SA_PASS"] = pw
        with open(os.environ.get("GITHUB_ENV", "/dev/null"), "a") as f:
            f.write("SA_PASS=%s\n" % pw)
    else:
        cur.execute("IF SUSER_ID(N'%s') IS NULL CREATE LOGIN [%s] WITH PASSWORD = N'%s', CHECK_POLICY = OFF, DEFAULT_DATABASE=[Atiran2]"
                    % (user.replace("'", "''"), user.replace("]", "]]"), pw.replace("'", "''")))
        cur.execute("ALTER SERVER ROLE sysadmin ADD MEMBER [%s]" % user.replace("]", "]]"))
    c.close()
    c = connect("Atiran2")
    cur = c.cursor()
    cur.execute("UPDATE dbo.visitors SET Password=%s WHERE Username='latifi'", (os.environ["E2E_PASS"],))
    out["before_pish"] = rows(cur, "SELECT shfacfo, rdf__, active, USER__, [date], shmo, vis_rdf, [all], Stamp FROM dbo.sailfact_pish ORDER BY shfacfo, rdf__")
    out["before_meelano"] = safe_rows(cur, out, "SELECT id, status, native_prefactor_table, native_prefactor_no, system_convert_note FROM dbo.meelano_prefactors ORDER BY id")
    out["db"] = rows(cur, "SELECT DB_NAME(), DATABASEPROPERTYEX(DB_NAME(),'Collation'), compatibility_level, (SELECT COUNT(*) FROM sys.tables) FROM sys.databases WHERE name=DB_NAME()")
    json.dump(out, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, default=str)
    print("restore OK; tables:", out["db"]["rows"][0][3], "; pre-invoices before:", len(out["before_pish"]["rows"]))


def listed(cur, date, mod):
    cur.execute("SET NOCOUNT ON; EXEC dbo.ListPishFactor @mydate=%s, @Mod=%s", (date, mod))
    found = set()
    while True:
        if cur.description:
            cols = [d[0] for d in cur.description]
            if "shfacfo" in cols:
                i = cols.index("shfacfo")
                for r in cur.fetchall():
                    found.add(int(r[i]))
            else:
                cur.fetchall()
        if not cur.nextset():
            break
    return sorted(found)


def verify(out_path):
    out = {"errors": [], "checks": {}}
    checks = out["checks"]
    st = []
    try:
        for line in open("e2e/selftest.txt", encoding="utf-8", errors="replace"):
            if "MEELANO_SELFTEST" in line:
                st.append(line.split("MEELANO_SELFTEST", 1)[1].lstrip(": ").strip())
    except Exception as ex:
        out["errors"].append("selftest log: %s" % ex)
    out["selftest"] = st
    joined = "\n".join(st)
    checks["app_started"] = any(s.startswith("START") for s in st)
    checks["app_login"] = "STEP login OK" in joined
    checks["app_finished"] = any(s == "DONE" for s in st)
    res1 = next((s for s in st if s.startswith("RESULT1")), "")
    res2 = next((s for s in st if s.startswith("RESULT2")), "")
    checks["submit_reached_atiran"] = "در آتیران ثبت شد" in res1
    n1 = re.findall(r"شماره آتیران: (\d+)", res1)
    n2 = re.findall(r"شماره آتیران: (\d+)", res2)
    checks["resubmit_same_number"] = bool(n1) and n1 == n2
    failed_steps = [s for s in st if s.startswith("STEP") and " FAIL" in s]
    out["failed_steps"] = failed_steps
    checks["all_read_steps_ok"] = not failed_steps

    c = connect("Atiran2")
    cur = c.cursor()
    date = rows(cur, "SELECT CAST(dbo.UDF_Gregorian_To_Persian(GETDATE()) AS nvarchar(30))")["rows"][0][0]
    out["pish"] = rows(cur, "SELECT * FROM dbo.sailfact_pish ORDER BY shfacfo, rdf__")
    out["pish_lines"] = rows(cur, "SELECT * FROM dbo.subsailfact_pish ORDER BY shfacfo, rdf__, RDF")
    out["meelano"] = safe_rows(cur, out, "SELECT id, status, native_prefactor_table, native_prefactor_no, system_convert_note, customer_code, grand_total FROM dbo.meelano_prefactors ORDER BY id")
    stamped = rows(cur, "SELECT shfacfo FROM dbo.sailfact_pish WHERE active='t' AND Stamp LIKE 'MEELANO-%' ORDER BY shfacfo")["rows"]
    stamped = [int(r[0]) for r in stamped]
    out["stamped"] = stamped
    legacy = rows(cur, "SELECT COUNT(*) FROM dbo.sailfact_pish WHERE active NOT IN ('t','f')")["rows"][0][0]
    checks["legacy_rows_disabled"] = legacy == 0
    lists = {}
    for mod in (1, 2, 3, 4):
        try:
            lists[mod] = listed(cur, date, mod)
        except Exception as ex:
            out["errors"].append("ListPishFactor %s: %s" % (mod, str(ex)[:200]))
    out["listed"] = lists
    newest = int(n1[0]) if n1 else (stamped[-1] if stamped else 0)
    checks["new_prefactor_in_atiran_list_mod1"] = newest in lists.get(1, [])
    checks["new_prefactor_in_atiran_list_mod4"] = newest in lists.get(4, [])
    checks["all_meelano_prefactors_listed"] = bool(stamped) and all(s in lists.get(4, []) for s in stamped)
    dup = rows(cur, "SELECT Stamp, COUNT(*) FROM dbo.sailfact_pish WHERE active='t' AND Stamp LIKE 'MEELANO-%' GROUP BY Stamp HAVING COUNT(*)>1")["rows"]
    checks["no_duplicates"] = not dup
    lines = rows(cur, "SELECT d.shfacfo, d.RDF, d.SHKA, d.rdf_anbar, d.TEDVAH, d.TEDJOZ, d.VAHPRICE, d.JOZPRICE, d.LINESUM, i.mohvah, CAST((d.TEDVAH*i.mohvah+ISNULL(d.TEDJOZ,0))*d.JOZPRICE AS decimal(19,2)) calc FROM dbo.subsailfact_pish d JOIN dbo.inventory i ON i.shka=d.SHKA WHERE d.shfacfo=%s AND d.active='t' ORDER BY d.RDF", (newest,))
    out["new_lines"] = lines
    checks["line_sums_match_atiran_formula"] = bool(lines["rows"]) and all(abs(float(r[8]) - float(r[10])) < 1 for r in lines["rows"])
    checks["rdf_zero_based"] = bool(lines["rows"]) and [r[1] for r in lines["rows"]] == list(range(len(lines["rows"])))
    checks["warehouse_exists"] = bool(lines["rows"]) and all(rows(cur, "SELECT COUNT(*) FROM dbo.anbars WHERE rdf_anbar=%s", (r[3],))["rows"][0][0] == 1 for r in lines["rows"])

    # Compare with a header written by Atiran's own procedure (rolled back).
    try:
        cur.execute("BEGIN TRANSACTION")
        cur.execute("""SET NOCOUNT ON; DECLARE @id bigint;
            EXEC dbo.add_sail_pish @date=%s, @shmo=412, @barbari=0, @tozih='e2e', @vis_rdf=6, @sumlineall=100, @all=100, @gainall=0, @tafif=0,
                 @jamtakhgh=0, @done_date=%s, @user='latifi', @rdf_sarbarg=0, @rdf_tahbarg=0, @modpar=0, @ph_kh=0, @mod=1, @mod_darsad_vis=0,
                 @nah_par=0, @sh_fac=0, @id_en=@id OUTPUT, @ted_rooz=30, @sysid=1, @tax=0, @avarez=0, @Promption=0;
            SELECT * FROM dbo.sailfact_pish WHERE shfacfo=@id""", (date, date))
        native = None
        while True:
            if cur.description:
                cols = [d[0] for d in cur.description]
                r = cur.fetchall()
                if r:
                    native = dict(zip(cols, [val(v) for v in r[0]]))
            if not cur.nextset():
                break
        cur.execute("IF @@TRANCOUNT>0 ROLLBACK TRANSACTION")
        ours = None
        if newest:
            o = rows(cur, "SELECT * FROM dbo.sailfact_pish WHERE shfacfo=%s AND active='t'", (newest,))
            if o["rows"]:
                ours = dict(zip(o["cols"], o["rows"][0]))
        skip = {"shfacfo", "Stamp", "shfacthand", "sumlineall", "all", "tafif", "jamtakhgh", "tax", "TimeRecive", "DateRecive",
                "man_gh", "rdf_tahbarg", "nah_par", "nah_d_text", "ted_rooz", "USER__", "shmo", "vis_rdf"}
        diffs = {}
        if native and ours:
            for k in native:
                if k in skip:
                    continue
                a, b = native.get(k), ours.get(k)
                if str(a).strip() != str(b).strip():
                    diffs[k] = {"atiran": a, "meelano": b}
        out["native_vs_meelano_diffs"] = diffs
        out["native_row"] = native
        checks["same_as_atiran_add_sail_pish"] = native is not None and ours is not None and not diffs
    except Exception as ex:
        out["errors"].append("native compare: %s" % str(ex)[:300])
        try:
            cur.execute("IF @@TRANCOUNT>0 ROLLBACK TRANSACTION")
        except Exception:
            pass
    # New in v5.2.0: customers by name tag, and new customer request -> approval -> Atiran.
    try:
        like = lambda tag: "(MONAME LIKE N'%" + tag + "%' OR MONAME LIKE N'%" + tag.translate(str.maketrans("0123456789", "۰۱۲۳۴۵۶۷۸۹")) + "%')"
        test_names = "MONAME LIKE N'%" + "آزمون خودكار" + "%'"
        db08 = rows(cur, "SELECT COUNT(*) FROM dbo.CUSTOMERS WHERE " + like("08") + " AND NOT (" + test_names + ")")["rows"][0][0]
        db07 = rows(cur, "SELECT COUNT(*) FROM dbo.CUSTOMERS WHERE " + like("07") + " AND NOT (" + test_names + ")")["rows"][0][0]
        out["db_tag_counts"] = {"08": db08, "07": db07}
        scope = {m.group(1): (int(m.group(2)), int(m.group(3))) for m in re.finditer(r"SCOPE login=(\w+) tag=\d+ customers=(\d+) tagged=(\d+)", joined)}
        out["app_scope"] = scope
        checks["latifi_sees_all_08_customers"] = scope.get("latifi") == (db08, db08)
        checks["khodayar_sees_only_07_customers"] = scope.get("khodayar") == (db07, db07)
        pc = re.findall(r"PRODUCTS count=(\d+)", joined)
        inv = rows(cur, "SELECT COUNT(*) FROM dbo.inventory")["rows"][0][0]
        out["products"] = {"app": int(pc[0]) if pc else None, "inventory_rows": inv}
        checks["products_not_capped_at_320"] = bool(pc) and (int(pc[0]) > 320 or int(pc[0]) >= inv)
        checks["custreq_duplicate_blocked"] = "CUSTREQ duplicate BLOCKED" in joined
        checks["custreq_double_approve_blocked"] = "CUSTREQ double_approve BLOCKED" in joined
        m = re.search(r"CUSTREQ RESULT id=(\d+) status=(\w+) shmo=(\d+) code=(\S*) visibleToVisitor=(\w+)", joined)
        checks["custreq_approved"] = bool(m) and m.group(2) == "approved" and int(m.group(3)) > 0
        checks["custreq_visible_to_visitor"] = bool(m) and m.group(5) == "true"
        shmo = int(m.group(3)) if m else 0
        new = rows(cur, "SELECT SHMO, MONAME, code, vis_rdf, defi_vis, RDF_masir, group_rdf, sh_i_m, user_d, [date], Lat, Lng, TafsilCode, TafsilID, active, kind, CustomerTypeTtmsId, cell, addre FROM dbo.CUSTOMERS WHERE SHMO=%s", (shmo,))
        out["new_customer"] = new
        r = dict(zip(new["cols"], new["rows"][0])) if new["rows"] else {}
        checks["atiran_row_exists"] = bool(r)
        checks["atiran_row_visitor_is_latifi"] = r.get("vis_rdf") == 6 and r.get("defi_vis") == 6
        checks["atiran_name_arabic_letters_and_tag"] = bool(r) and "08" in r["MONAME"] and "ی" not in r["MONAME"] and "ک" not in r["MONAME"]
        prev = rows(cur, "SELECT TOP (1) code, sh_i_m FROM dbo.CUSTOMERS WHERE RDF_masir=%s AND SHMO<>%s AND sh_i_m IS NOT NULL ORDER BY sh_i_m DESC", (r.get("RDF_masir", 0), shmo))["rows"]
        out["route_previous"] = prev
        checks["atiran_code_continues_route"] = bool(r) and bool(prev) and r["sh_i_m"] == prev[0][1] + 1 and len(r["code"]) == len(prev[0][0])
        checks["atiran_cus_image_row"] = rows(cur, "SELECT COUNT(*) FROM dbo.cus_image WHERE shmo=%s", (shmo,))["rows"][0][0] == 1
        checks["atiran_cust_act_row"] = rows(cur, "SELECT COUNT(*) FROM dbo.cust_act WHERE shmo=%s", (shmo,))["rows"][0][0] >= 1
        checks["atiran_sys_cus_row"] = rows(cur, "SELECT COUNT(*) FROM dbo.sys_cus WHERE Shmo=%s", (shmo,))["rows"][0][0] == 1
        checks["atiran_region_chain"] = rows(cur, "SELECT COUNT(*) FROM dbo.CUSTOMERS cu JOIN dbo.masir m ON cu.RDF_masir=m.rdf_masir JOIN dbo.[Quarter] qq ON m.QuarterID=qq.ID JOIN dbo.regions rg ON qq.RegionId=rg.rdf_region JOIN dbo.CITYS ct ON rg.rdf_city=ct.RDF WHERE cu.SHMO=%s", (shmo,))["rows"][0][0] == 1
        out["new_customer_requests"] = safe_rows(cur, out, "SELECT id, status, visitor_username, visitor_id, customer_name, masir_rdf, group_rdf, atiran_shmo, atiran_code, decided_by FROM dbo.meelano_customer_requests ORDER BY id")
        views = {}
        for v, key in (("VW_ListCustomer", "shmo"), ("vw_customer", "shmo"), ("moshtari", "shmo")):
            try:
                views[v] = rows(cur, "SELECT COUNT(*) FROM dbo.[%s] WHERE [%s]=%s" % (v, key, "%s"), (shmo,))["rows"][0][0]
            except Exception as ex:
                views[v] = "error: " + str(ex)[:120]
        out["new_customer_in_views"] = views
    except Exception as ex:
        out["errors"].append("customer checks: %s" % str(ex)[:300])
    json.dump(out, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, default=str)
    for k, v in checks.items():
        print(("PASS " if v else "FAIL ") + k)
    print("failed read steps:", len(failed_steps), "| errors:", len(out["errors"]))


if __name__ == "__main__":
    {"restore": restore, "verify": verify}[sys.argv[1]](sys.argv[2])
