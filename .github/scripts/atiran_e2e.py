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
    cur.execute("UPDATE dbo.visitors SET Password=%s WHERE Username IN ('latifi','mahmodi','nazari')", (os.environ["E2E_PASS"],))
    out["before_store"] = {
        "stock": rows(cur, "SELECT shka, mojkavah, mojkajoz, mohvah FROM dbo.inventory WHERE shka IN (667, 621) ORDER BY shka"),
        "man412": rows(cur, "SELECT man FROM dbo.CUSTOMERS WHERE SHMO=412")["rows"][0][0],
        "max_shfacfo": rows(cur, "SELECT ISNULL(MAX(shfacfo),0) FROM dbo.sailfact")["rows"][0][0],
        "debtors_all": rows(cur, "SELECT COUNT(*), SUM(man) FROM dbo.CUSTOMERS WHERE man > 0")["rows"][0],
        "debtors": store_scope_customers(cur)["debtors"],
        "customers": rows(cur, "SELECT COUNT(*) FROM dbo.CUSTOMERS")["rows"][0][0],
        "counter": safe_rows(cur, out, "SELECT * FROM dbo.InvoiceNumberCounter"),
    }
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
        checks["atiran_code_unique"] = bool(r) and rows(cur, "SELECT COUNT(*) FROM dbo.CUSTOMERS WHERE code=%s", (r["code"],))["rows"][0][0] == 1
        er = rows(cur, "SELECT SHMO, code, sh_i_m, RDF_masir FROM dbo.CUSTOMERS WHERE RDF_masir=4")
        out["empty_route_customer"] = er
        checks["atiran_empty_route_code"] = len(er["rows"]) == 1 and er["rows"][0][1] == "004001" and er["rows"][0][2] == 1
        checks["atiran_sys_cus_user_is_approver"] = rows(cur, "SELECT COUNT(*) FROM dbo.sys_cus WHERE Shmo=%s AND UserID=1 AND SysID=1", (shmo,))["rows"][0][0] == 1
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
    try:
        verify_store(cur, out, checks)
    except Exception as ex:
        out["errors"].append("store checks: %s" % str(ex)[:300])
    json.dump(out, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, default=str)
    for k, v in checks.items():
        print(("PASS " if v else "FAIL ") + k)
    print("failed read steps:", len(failed_steps), "| errors:", len(out["errors"]))


_DIGITS = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")


def store_scope(cur, own_user="mahmodi"):
    """v5.5.1: the store staff see their own visitor row plus latifi and khodayar. Same rule as the visitor app:
    a name holding 08 belongs to latifi, 07 to khodayar, otherwise the customer's own visitor (CUSTOMERS.vis_rdf)."""
    vis = rows(cur, "SELECT vis_rdf, LOWER(LTRIM(RTRIM(ISNULL(CAST(Username AS nvarchar(120)),N'')))), ISNULL(CAST(vis_name AS nvarchar(250)),N'') FROM dbo.visitors")["rows"]
    lat = next((int(r[0]) for r in vis if r[1] == "latifi"), 0)
    kho = next((int(r[0]) for r in vis if r[1] == "khodayar"), 0)
    own = next((int(r[0]) for r in vis if r[1] == own_user), 0)
    names = [str(r[2]) for r in vis if r[1] in ("latifi", "khodayar", own_user)]
    return lat, kho, names, own


def store_customer_vis(name, v, lat, kho, own):
    d = str(name or "").translate(_DIGITS)
    v = int(v or 0)
    if lat and "08" in d:
        return lat
    if kho and "07" in d:
        return kho
    return v if v > 0 and v in (lat, kho, own) else 0


def store_scope_customers(cur):
    lat, kho, _, own = store_scope(cur)
    cs = rows(cur, "SELECT ISNULL(CAST(MONAME AS nvarchar(500)),N''), ISNULL(man,0), ISNULL(vis_rdf,0) FROM dbo.CUSTOMERS")["rows"]
    total, debtors, debt = 0, 0, 0.0
    for name, man, v in cs:
        if not store_customer_vis(name, v, lat, kho, own):
            continue
        total += 1
        if float(man) > 0:
            debtors += 1
            debt += float(man)
    return {"total": total, "debtors": [debtors, debt]}


def _fa_norm(x):
    return str(x or "").replace("ي", "ی").replace("ك", "ک").replace("\u200c", " ").strip()


def verify_store(cur, out, checks):
    """Store edition (v5.3.0): final sales invoice written through Atiran's AddInvoice / subsailtemp /
    FactorConfirmation path, only store staff may log in, reports match the database, GPS attendance."""
    st = []
    try:
        for line in open("e2e/store-selftest.txt", encoding="utf-8", errors="replace"):
            if "MEELANO_SELFTEST" in line:
                st.append(line.split("MEELANO_SELFTEST", 1)[1].lstrip(": ").strip())
    except Exception as ex:
        out["errors"].append("store selftest log: %s" % ex)
    out["store_selftest"] = st
    joined = "\n".join(st)
    before = {}
    try:
        before = json.load(open(os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), "restore.json"), encoding="utf-8")).get("before_store", {})
    except Exception as ex:
        out["errors"].append("restore.json: %s" % ex)
    out["store_before"] = before
    checks["store_login_mahmodi"] = "STEP login OK" in joined
    checks["store_rejects_visitor_latifi"] = "STEP store_reject_visitor OK" in joined
    checks["store_finished"] = any(s == "DONE" for s in st)
    out["store_failed_steps"] = [s for s in st if s.startswith("STEP") and " FAIL" in s]
    checks["store_no_failed_steps"] = bool(st) and not out["store_failed_steps"]
    m = re.search(r"STORE staff key=(\w+) vis=(\d+) uid=(\d+)", joined)
    checks["store_staff_is_mahmodi_vis3_user6"] = bool(m) and m.group(1) == "mahmodi" and m.group(2) == "3" and m.group(3) == "6"
    d = re.search(r"STORE data today=\S+ debtors=(\d+) overdue=(\d+) overdueSum=(-?\d+) customersTotal=(-?\d+) debt=(-?\d+)", joined)
    if d and before.get("debtors"):
        checks["store_debtors_match_db"] = int(d.group(1)) == int(before["debtors"][0]) and abs(int(d.group(5)) - float(before["debtors"][1])) < 2
        # the visitor test before this one adds its test customers, so compare with the current count
        checks["store_customers_total_match_db"] = int(d.group(4)) == store_scope_customers(cur)["total"]
        checks["store_overdue_found"] = int(d.group(2)) > 0 and 0 < int(d.group(3)) <= float(before["debtors"][1]) + 1
    g = re.search(r"STORE groups (.*)", joined)
    if g and before.get("debtors"):
        total = sum(int(x.rsplit("/", 1)[1]) for x in g.group(1).split(";") if "/" in x)
        checks["store_debt_groups_add_up"] = abs(total - float(before["debtors"][1])) < 10

    # v5.4.1: only visitors (and the signed-in person) are named; office staff become «سایر»; no attendance history.
    bvm = re.search(r"STORE byVisitor (.*) attendanceInData=(\w+)", joined)
    names_txt = (g.group(1) if g else "") + ";" + (bvm.group(1) if bvm else "")
    out["store_visitor_names"] = names_txt
    checks["store_reports_only_visitor_names"] = bool(g) and bool(bvm) and not any(x in names_txt for x in ("حمدان", "مدير", "مدیر", "سيستم", "سیستم"))
    checks["store_no_attendance_history_in_app"] = bool(bvm) and bvm.group(2) == "false"
    # v5.5.1: mahmodi / nazari see their own visitor row + latifi + khodayar, each separately.
    lat, kho, scope_names, own = store_scope(cur)
    allowed = [_fa_norm(n).split("/")[0].strip() for n in scope_names]
    out["store_scope"] = {"latifi": lat, "khodayar": kho, "own": own, "names": scope_names}

    def only_scope(txt):
        parts = [p for p in txt.split(";") if p.strip()]
        got = [_fa_norm(p.split("=", 1)[0]) for p in parts]
        return bool(parts) and all(any(a and (a in g or g in a) for a in allowed) for g in got)
    checks["store_debt_groups_only_own_latifi_khodayar"] = bool(g) and only_scope(g.group(1))
    checks["store_sales_by_visitor_only_own_latifi_khodayar"] = bool(bvm) and (not bvm.group(1).strip() or only_scope(bvm.group(1)))
    scm = re.search(r"STORE scope latifi=(\d+) khodayar=(\d+) own=(\d+)", joined)
    checks["store_scope_is_own_latifi_khodayar"] = bool(scm) and (int(scm.group(1)), int(scm.group(2)), int(scm.group(3))) == (lat, kho, own) and own == 3
    group_names = [_fa_norm(p.split("=", 1)[0]) for p in (g.group(1).split(";") if g else []) if p.strip()]
    checks["store_debt_groups_separate_own_first"] = len(group_names) == 3 and "(خودم)" in group_names[0]
    # Unsettled overdue invoices: every listed invoice is a real, active invoice of an in-scope customer,
    # overdue, its open part is not more than the invoice, and per customer the open parts fit in the balance.
    oim = re.search(r"STORE overdueInvoices n=(\d+) listed=(\d+) sum=(-?\d+) sample=(.*)", joined)
    out["store_overdue_invoices"] = oim.group(0)[:600] if oim else None
    ok_inv = bool(oim) and int(oim.group(1)) > 0
    per_cust = {}
    if oim:
        for part in [p for p in oim.group(4).split(";") if p.strip()]:
            no, code, days, opn, vis = [int(x) for x in part.split(":")]
            r = rows(cur, "SELECT TOP (1) ISNULL(all_fel,[all]), shmo FROM dbo.sailfact WHERE active='t' AND ISNULL(Deleted,0)=0 AND shfacfo=%d" % no)["rows"]
            cu = rows(cur, "SELECT ISNULL(CAST(MONAME AS nvarchar(500)),N''), ISNULL(vis_rdf,0), ISNULL(man,0) FROM dbo.CUSTOMERS WHERE SHMO=%d" % code)["rows"]
            if not r or not cu or int(r[0][1]) != code or days <= 0 or opn > float(r[0][0]) + 1:
                ok_inv = False
                continue
            if store_customer_vis(cu[0][0], cu[0][1], lat, kho, own) != vis:
                ok_inv = False
            per_cust.setdefault(code, [0.0, float(cu[0][2])])[0] += opn
    checks["store_overdue_invoices_real_and_in_scope"] = ok_inv and all(v[0] <= v[1] + 1 for v in per_cust.values())
    inv = [s for s in st if s.startswith("STORE INVOICE1 ")]
    inv2 = [s for s in st if s.startswith("STORE INVOICE2 ")]
    r1 = json.loads(inv[0].split(" ", 2)[2]) if inv else {}
    r2 = json.loads(inv2[0].split(" ", 2)[2]) if inv2 else {}
    out["store_invoice_results"] = [r1, r2]
    no = int(r1.get("no", 0) or 0)
    checks["store_invoice_number_returned"] = no > 0
    checks["store_resend_returns_same_invoice"] = no > 0 and int(r2.get("no", 0) or 0) == no and bool(r2.get("duplicate"))
    hdr = rows(cur, "SELECT * FROM dbo.sailfact WHERE UniqueID LIKE 'MEELANO-STORE-%' ORDER BY shfacfo")
    out["store_headers"] = hdr
    checks["store_exactly_one_invoice"] = len(hdr["rows"]) == 1
    h = dict(zip(hdr["cols"], hdr["rows"][0])) if hdr["rows"] else {}
    checks["store_invoice_is_final_status1"] = str(h.get("Status")) in ("1", "True")
    checks["store_invoice_customer_412"] = h.get("shmo") == 412
    checks["store_invoice_user_mahmodi"] = h.get("userid") == 6 and h.get("vis_rdf") == 3
    checks["store_invoice_new_number"] = bool(h) and int(h.get("shfacfo", 0)) > int(before.get("max_shfacfo", 0) or 0) and int(h.get("shfacfo", 0)) == no
    lines = rows(cur, "SELECT d.RDF, d.SHKA, d.TEDVAH, d.TEDJOZ, d.VAHPRICE, d.JOZPRICE, d.LINESUM, d.rdf_anbar, d.active FROM dbo.subsailfact d WHERE d.shfacfo=%s ORDER BY d.RDF", (no,))
    out["store_lines"] = lines
    checks["store_two_lines_in_subsailfact"] = len(lines["rows"]) == 2 and sorted(int(r[1]) for r in lines["rows"]) == [621, 667]
    out["ka_act_latest"] = safe_rows(cur, out, "SELECT TOP (6) * FROM dbo.ka_act ORDER BY 1 DESC")
    kcols = out["ka_act_latest"].get("cols", [])
    kcol = next((c for c in kcols if c.lower() in ("shfacfo", "sh_fac", "shfac", "ghno", "sh_f", "shfactor", "factorno", "docnumber")), None)
    out["ka_act_invoice_column"] = kcol
    if kcol:
        ka = safe_rows(cur, out, "SELECT * FROM dbo.ka_act WHERE [%s]=%s" % (kcol, no))
        ka816 = safe_rows(cur, out, "SELECT * FROM dbo.ka_act WHERE [%s]=816" % kcol)
        out["store_ka_act"] = ka
        out["ka_act_816"] = ka816
        checks["store_stock_movements_ka_act"] = len(ka.get("rows", [])) >= 2
    after_stock = rows(cur, "SELECT shka, mojkavah, mojkajoz, mohvah FROM dbo.inventory WHERE shka IN (667, 621) ORDER BY shka")
    out["store_stock_after"] = after_stock
    try:
        b = {int(r[0]): float(r[1] or 0) * max(1, float(r[3] or 1)) + float(r[2] or 0) for r in before["stock"]["rows"]}
        a = {int(r[0]): float(r[1] or 0) * max(1, float(r[3] or 1)) + float(r[2] or 0) for r in after_stock["rows"]}
        out["store_stock_pieces"] = {"before": b, "after": a}
        checks["store_stock_decreased_667_by_2"] = abs((b[667] - a[667]) - 2) < 0.01
        checks["store_stock_decreased_621_by_1"] = abs((b[621] - a[621]) - 1) < 0.01
    except Exception as ex:
        out["errors"].append("stock compare: %s" % ex)
    man_after = rows(cur, "SELECT man FROM dbo.CUSTOMERS WHERE SHMO=412")["rows"][0][0]
    out["store_man412"] = {"before": before.get("man412"), "after": man_after, "invoice_all": h.get("all")}
    # v5.4.0: invoice fields (freight, settlement days, visitor) and the receipts written right after it.
    def logged(prefix):
        x = [s for s in st if s.startswith(prefix + " ")]
        try:
            return json.loads(x[0].split(" ", 2)[2]) if x else {}
        except Exception:
            return {}
    rc1, rc1dup, rc2 = logged("STORE RECEIPT1"), logged("STORE RECEIPT1_DUP"), logged("STORE RECEIPT2")
    out["store_receipts"] = {"r1": rc1, "r1dup": rc1dup, "r2": rc2}
    sub = re.search(r"STORE SUBMIT \S+ total=(\d+)", joined)
    checks["store_invoice_barbari_150000"] = bool(h) and abs(float(h.get("barbari") or 0) - 150000) < 1
    checks["store_invoice_modpar_30"] = bool(h) and int(h.get("modpar") or 0) == 30 and str(h.get("t_date")) != str(h.get("date"))
    checks["store_invoice_total_includes_barbari"] = bool(sub) and bool(h) and abs(float(h.get("all") or 0) - (float(sub.group(1)) + 150000)) < 2
    refm = re.search(r"STORE REF myVis=(\d+) visitors=(\S*) banks=(\d+) bankNames=(\d+) terms=(\d+) anbars=(\d+)", joined)
    checks["store_ref_default_visitor_3_and_lists"] = bool(refm) and refm.group(1) == "3" and refm.group(2).startswith("3,") and int(refm.group(3)) > 0 and int(refm.group(4)) > 0 and int(refm.group(6)) > 0
    checks["store_ref_only_visitors"] = bool(refm) and all(v not in ("1",) for v in refm.group(2).split(",") if v) and len([v for v in refm.group(2).split(",") if v]) <= 6
    g1, g2 = int(rc1.get("ghno", 0) or 0), int(rc2.get("ghno", 0) or 0)
    dar = safe_rows(cur, out, "SELECT ghno, shmo, naghd, mab, mabcheck, ted_chk, shfac, UniqueID, IsFinal, Active, darDescriptionTypeID, CAST(d_p_dis AS nvarchar(300)) FROM dbo.dar WHERE UniqueID LIKE 'MEELANO-DAR-%' ORDER BY ghno")
    out["store_dar"] = dar
    dr = dar.get("rows", [])
    checks["store_receipt2_ran"] = g2 > 0
    checks["store_receipt_saved_once_each"] = g1 > 0 and len(dr) == (2 if g2 else 1) and int(rc1dup.get("ghno", 0) or 0) == g1 and bool(rc1dup.get("duplicate"))
    d1 = next((r for r in dr if int(r[0]) == g1), None)
    checks["store_receipt1_cash_and_total"] = bool(d1) and abs(float(d1[2] or 0) - 1000000) < 1 and abs(float(rc1.get("total", 0) or 0) - 5800000) < 1
    checks["store_receipt1_for_new_invoice"] = bool(d1) and int(d1[6] or 0) == no
    pos = safe_rows(cur, out, "SELECT MabPos, PosBankRdf, ShPeigiri, IsHavaleh, CAST(PosDesc AS nvarchar(300)) FROM dbo.PosDetails WHERE ghno=%s ORDER BY ID" % g1)
    out["store_pos"] = pos
    pr = pos.get("rows", [])
    checks["store_receipt1_pos_trf_hav_rows"] = [int(float(r[0])) for r in pr] == [2000000, 500000, 300000] and [str(r[3]) in ("1", "True") for r in pr] == [False, True, True]
    chk = safe_rows(cur, out, "SELECT getchkmab, sardate, CAST(getchbank AS nvarchar(100)), shgetchk, ShenaseSayad, RegistrationInquiry, CheckTypeID, shmo, ghno FROM dbo.getchk WHERE ghno=%s ORDER BY rdf" % g1)
    out["store_getchk"] = chk
    cr = chk.get("rows", [])
    checks["store_receipt1_two_cheques"] = len(cr) == 2 and sorted(int(float(r[0])) for r in cr) == [800000, 1200000]
    checks["store_receipt1_cheque_sayad_flags"] = len(cr) == 2 and any(str(r[4]).strip() == "1234567890123456" and str(r[5]) in ("1", "True") for r in cr) and any(str(r[5]) in ("0", "False", "None") for r in cr)
    out["store_tpl_cheque"] = safe_rows(cur, out, "SELECT * FROM dbo.TemplateDaryaftCheque WHERE Ghno=%s" % g1)
    ca = safe_rows(cur, out, "SELECT act_id, act_bes, act_bed, ghno FROM dbo.cust_act WHERE shmo=412 AND ghno=%s ORDER BY rdf_" % g1)
    out["store_receipt_cust_act"] = ca
    checks["store_receipt_cust_act_rows"] = len(ca.get("rows", [])) >= 3
    inv_now = safe_rows(cur, out, "SELECT MabDaryaftFactor, tasvieh FROM dbo.sailfact WHERE shfacfo=%s" % no)
    out["store_invoice_after_receipt"] = inv_now
    checks["store_invoice_mabdaryaft_5800000"] = bool(inv_now.get("rows")) and abs(float(inv_now["rows"][0][0] or 0) - 5800000) < 1
    if g2:
        mf = safe_rows(cur, out, "SELECT Shfacfo, Price, IsTasvieh FROM dbo.DaryaftMultiFactor WHERE GhnoDar=%s ORDER BY Shfacfo" % g2)
        out["store_multifactor"] = mf
        checks["store_receipt2_multifactor_two_rows"] = len(mf.get("rows", [])) == 2
        if mf.get("rows"):
            ts = safe_rows(cur, out, "SELECT shfacfo, tasvieh, MabDaryaftFactor, [all] FROM dbo.sailfact WHERE active='t' AND shfacfo IN (%s)" % ",".join(str(int(r[0])) for r in mf["rows"]))
            out["store_multifactor_invoices"] = ts
            checks["store_receipt2_invoices_settled"] = len(ts.get("rows", [])) == 2 and all(str(r[1]) == "t" for r in ts["rows"])
    try:
        paid = float(rc1.get("total", 0) or 0)
        out["store_man412"]["receipts"] = paid
        checks["store_customer_debt_increased_by_invoice"] = abs(float(man_after) - float(before.get("man412")) - float(h.get("all")) + paid) < 1
        checks["store_receipt_man_matches"] = abs(float(rc1.get("man", 0) or 0) - float(man_after)) < 1
        s2 = re.search(r"STORE RECEIPT2_SPEC shmo=(\d+) sum=(\d+)", joined)
        if s2 and g2:
            man2 = rows(cur, "SELECT man FROM dbo.CUSTOMERS WHERE SHMO=%s" % int(s2.group(1)))["rows"][0][0]
            out["store_receipt2_customer"] = {"shmo": int(s2.group(1)), "sum": int(s2.group(2)), "man_after": man2}
            checks["store_receipt2_man_matches"] = abs(float(rc2.get("man", 0) or 0) - float(man2)) < 1 and abs(float(rc2.get("total", 0) or 0) - int(s2.group(2))) < 1
    except Exception:
        checks["store_customer_debt_increased_by_invoice"] = False
    out["store_cust_act"] = safe_rows(cur, out, "SELECT TOP (5) * FROM dbo.cust_act WHERE shmo=412 ORDER BY 1 DESC")
    out["store_confirmation"] = safe_rows(cur, out, "SELECT * FROM dbo.FactorConfirmation WHERE Shfacfo=%s" % no)
    out["confirmation_816"] = safe_rows(cur, out, "SELECT * FROM dbo.FactorConfirmation WHERE Shfacfo=816")
    out["confirmation_total"] = safe_rows(cur, out, "SELECT COUNT(*) FROM dbo.FactorConfirmation")
    # Atiran's own confirmed invoices are the reference: same row handling as invoice 816.
    checks["store_confirmation_like_atiran"] = len(out["store_confirmation"].get("rows", [])) == len(out["confirmation_816"].get("rows", [])) and str(h.get("Status")) in ("1", "True")
    checks["store_taeed_user_readable"] = bool(h) and "\ufffd" not in str(h.get("TaeedUser")) and "?" not in str(h.get("TaeedUser"))
    out["store_counter_after"] = safe_rows(cur, out, "SELECT * FROM dbo.InvoiceNumberCounter")
    # Same shape as an invoice written by the Atiran program itself (816, by nazari).
    ref = rows(cur, "SELECT * FROM dbo.sailfact WHERE shfacfo=816")
    if ref["rows"] and h:
        r816 = dict(zip(ref["cols"], ref["rows"][0]))
        out["store_vs_816"] = {k: {"atiran": r816.get(k), "meelano": h.get(k)} for k in r816 if str(r816.get(k)).strip() != str(h.get(k)).strip()}
    # v5.5.0 attendance: zones come from the manager (GPS area or the store's modem), identity is checked
    # with the phone's fingerprint/screen lock, no working-hour window, lateness / overtime are computed.
    zm = re.search(r"STORE HR zones gps=(\d+) wifi=(\d+)", joined)
    gz, wz = (int(zm.group(1)), int(zm.group(2))) if zm else (-1, -1)
    att = safe_rows(cur, out, "SELECT event_type, distance_m, zone_id, biometric, source, lat, lng, accuracy_m FROM dbo.meelano_attendance WHERE username='mahmodi' ORDER BY id")
    out["store_attendance"] = att
    app_rows = [r for r in att.get("rows", []) if str(r[4]) == "app"]
    checks["store_hr_tables_ready"] = "STORE HR tables ok" in joined and bool(zm)
    checks["store_attendance_needs_manager_zone"] = "STEP store_att_nozone OK" in joined
    checks["store_attendance_rules"] = all("STEP store_att_%s OK" % k in joined for k in ("far", "inaccurate", "wrong_wifi", "dup"))
    checks["store_attendance_in_and_out_saved"] = [r[0] for r in app_rows] == ["in", "out"]
    checks["store_attendance_gps_inside_zone"] = len(app_rows) == 2 and app_rows[0][1] is not None and float(app_rows[0][1]) <= 120 and int(app_rows[0][2] or 0) == gz
    checks["store_attendance_wifi_zone"] = len(app_rows) == 2 and app_rows[1][1] is None and int(app_rows[1][2] or 0) == wz
    checks["store_attendance_identity_checked"] = len(app_rows) == 2 and all(str(r[3]) in ("1", "True") for r in app_rows)
    mis = safe_rows(cur, out, "SELECT reason, details, status, start_time, end_time, start_bio, end_bio FROM dbo.meelano_hr_mission WHERE username='mahmodi' ORDER BY id")
    out["store_missions"] = mis
    mr = mis.get("rows", [])
    checks["store_mission_flow"] = all("STEP store_mission_%s OK" % k in joined for k in ("start", "double", "end")) and "STEP store_att_during_mission OK" in joined and "open_mission=true" in joined
    checks["store_mission_saved_and_ended"] = len(mr) == 1 and mr[0][4] is not None and bool(str(mr[0][0]).strip())
    inbox = safe_rows(cur, out, "SELECT kind, title, body FROM dbo.meelano_hr_inbox WHERE username='mahmodi' ORDER BY id")
    out["store_manager_inbox"] = inbox
    kinds = [str(r[0]) for r in inbox.get("rows", [])]
    checks["store_mission_sent_to_manager"] = "mission_start" in kinds and "mission_end" in kinds
    im = re.search(r"STORE HR incomplete date=(\S+) status=(\w+)", joined)
    fm = re.search(r"STORE HR incomplete_after_fix date=(\S+) status=(\w+)", joined)
    checks["store_incomplete_reported_to_manager"] = bool(im) and im.group(2) == "open" and "incomplete" in kinds
    checks["store_incomplete_fixed_by_manager_only"] = bool(fm) and fm.group(2) == "fixed"
    mm = re.search(r"STORE HR month (\d+)/(\d+) present=(\d+) late=(\d+) overtime=(\d+) mission=(\d+) incomplete=(\d+) gross=(-?\d+) insurance=(-?\d+) tax=(-?\d+) net=(-?\d+)", joined)
    out["store_hr_month"] = mm.group(0) if mm else None
    months = safe_rows(cur, out, "SELECT jy, jm, present_days, late_min, overtime_min, gross, deduction, insurance, tax, net FROM dbo.meelano_hr_month WHERE username='mahmodi' ORDER BY jy, jm")
    out["store_hr_month_rows"] = months
    checks["store_month_computed_and_saved"] = bool(mm) and int(mm.group(3)) >= 1 and bool(months.get("rows"))
    mrows = months.get("rows") or []
    checks["store_pay_math_consistent"] = bool(mrows) and all(int(r[9] or 0) == int(r[5] or 0) - int(r[6] or 0) - int(r[7] or 0) - int(r[8] or 0) for r in mrows)
    checks["store_app_hides_times_and_pay"] = "app_shows_no_times_or_pay=true" in joined
    checks["store_hr_step_ok"] = "STEP store_hr OK" in joined


if __name__ == "__main__":
    {"restore": restore, "verify": verify}[sys.argv[1]](sys.argv[2])
