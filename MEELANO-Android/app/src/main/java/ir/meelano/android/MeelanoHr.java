package ir.meelano.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Attendance, overtime, lateness, leave and payroll rules of the Iranian Labour Law with the 1405 figures.
 * Pure Java (no Android types) so the same rules can be unit-tested and reused by the management panel.
 *
 * Sources of the figures (1405): Supreme Labour Council wage resolution (daily minimum wage 5,541,850 rial,
 * housing 30,000,000, grocery 22,000,000, marriage 5,000,000, child = 3 × daily minimum wage, seniority
 * 166,667 rial a day) and the 1405 budget law (salary tax exemption 40,000,000 toman a month, then 10–30 %).
 * Articles: 51 (44 hours a week → 7 h 20 min a day), 58 (night work +35 %), 59 (overtime +40 %, max 4 h a day),
 * 62 (work on the weekly/official holiday +40 %), 64 (26 working days of paid leave a year), 66 (max 9 days carried).
 * Weekday index: 0 = Saturday … 6 = Friday. Minutes are counted from midnight.
 */
final class MeelanoHr {
    private MeelanoHr() { }

    // ------------------------------------------------------------------------------------ 1405 figures (rial)
    static final long MIN_DAILY = 5_541_850L;
    static final long MIN_MONTH_30 = MIN_DAILY * 30;          // 166,255,500
    static final long HOUSING = 30_000_000L;
    static final long GROCERY = 22_000_000L;
    static final long MARRIAGE = 5_000_000L;
    static final long CHILD_EACH = MIN_DAILY * 3;             // 16,625,550
    static final long SENIORITY_DAILY = 166_667L;
    static final int DAY_MINUTES = 440;                       // 44 h ÷ 6 days
    static final double OVERTIME_RATE = 1.40;
    static final double NIGHT_EXTRA = 0.35;
    static final double HOLIDAY_RATE = 1.40;
    static final double INSURANCE_EMPLOYEE = 0.07;
    static final double INSURANCE_EMPLOYER = 0.23;            // 20 % + 3 % unemployment
    static final long INSURANCE_CEILING = MIN_MONTH_30 * 7;
    static final long[] TAX_STEPS = {400_000_000L, 800_000_000L, 1_000_000_000L, 1_200_000_000L, 1_400_000_000L};
    static final double[] TAX_RATES = {0.10, 0.15, 0.20, 0.25, 0.30};
    static final int LEAVE_DAYS_PER_YEAR = 26;
    static final int LEAVE_CARRY_MAX_DAYS = 9;
    static final double LEAVE_MINUTES_PER_MONTH = LEAVE_DAYS_PER_YEAR * (double) DAY_MINUTES / 12.0;

    /** Official holidays of 1405 (Institute of Geophysics calendar), used when the panel has not entered any. */
    static final String[][] HOLIDAYS_1405 = {
            {"1405/01/01", "عید نوروز و عید فطر"}, {"1405/01/02", "عید نوروز و تعطیل عید فطر"}, {"1405/01/03", "عید نوروز"},
            {"1405/01/04", "عید نوروز"}, {"1405/01/12", "روز جمهوری اسلامی"}, {"1405/01/13", "روز طبیعت"},
            {"1405/01/25", "شهادت امام جعفر صادق (ع)"}, {"1405/03/06", "عید قربان"}, {"1405/03/14", "عید غدیر و رحلت امام خمینی"},
            {"1405/03/15", "قیام ۱۵ خرداد"}, {"1405/04/03", "تاسوعا"}, {"1405/04/04", "عاشورا"},
            {"1405/05/13", "اربعین"}, {"1405/05/21", "رحلت پیامبر (ص) و شهادت امام حسن (ع)"}, {"1405/05/22", "شهادت امام رضا (ع)"},
            {"1405/05/30", "شهادت امام حسن عسکری (ع)"}, {"1405/06/08", "میلاد پیامبر (ص) و امام صادق (ع)"},
            {"1405/08/22", "شهادت حضرت فاطمه (س)"}, {"1405/10/02", "ولادت امام علی (ع)"}, {"1405/10/16", "مبعث"},
            {"1405/11/04", "نیمه شعبان"}, {"1405/11/22", "پیروزی انقلاب اسلامی"}, {"1405/12/09", "شهادت امام علی (ع)"},
            {"1405/12/19", "عید فطر"}, {"1405/12/20", "تعطیل عید فطر"}, {"1405/12/29", "ملی شدن صنعت نفت"}
    };

    static final String[] WEEKDAYS = {"شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه"};

    static int weekday(int day) { return ((day + 2) % 7 + 7) % 7; }

    static int daysInMonth(int jy, int jm) {
        if (jm <= 6) return 31;
        if (jm <= 11) return 30;
        return MeelanoJalali.toDay(jy + 1, 1, 1) - MeelanoJalali.toDay(jy, 12, 1);
    }

    // ------------------------------------------------------------------------------------ inputs
    /** Working hours per weekday. The default is the store's 07:15–15:30 (30 min rest) and Thursday 07:15–12:30 = 44 h. */
    static final class Shift {
        String title = "شیفت فروشگاه";
        final boolean[] work = new boolean[7];
        final int[] start = new int[7], end = new int[7], rest = new int[7];
        int graceLate = 0, graceEarly = 0, overtimeMin = 0, maxOvertimeDay = 240;
        boolean overtimeBeforeStart = false;
        int nightStart = 22 * 60, nightEnd = 6 * 60;

        static Shift storeDefault(int startMin, int endMin) {
            Shift s = new Shift();
            if (startMin < 0 || endMin <= startMin) { startMin = 7 * 60 + 15; endMin = 15 * 60 + 30; }
            for (int wd = 0; wd <= 4; wd++) { s.work[wd] = true; s.start[wd] = startMin; s.end[wd] = endMin; s.rest[wd] = 30; }
            s.work[5] = true; s.start[5] = startMin; s.end[5] = Math.min(endMin, startMin + 315); s.rest[5] = 0;
            s.work[6] = false;
            return s;
        }

        int required(int wd) { return work[wd] ? Math.max(0, end[wd] - start[wd] - rest[wd]) : 0; }

        int weeklyMinutes() { int t = 0; for (int wd = 0; wd < 7; wd++) t += required(wd); return t; }
    }

    static final class Staff {
        String username = "", name = "";
        long dailyWage = MIN_DAILY, housing = HOUSING, grocery = GROCERY, seniorityDaily = 0;
        boolean married = false, insured = true;
        int children = 0, leaveCarryMinutes = 0;
    }

    /** One entry («in») or exit («out») press; source is app or manager (a manager's correction). */
    static final class Event {
        final int day, minute; final boolean in; final String source;
        Event(int day, int minute, boolean in, String source) { this.day = day; this.minute = minute; this.in = in; this.source = source == null ? "app" : source; }
    }

    /** A mission (work outside the store). endDay &lt; 0 while it is still running. */
    static final class Mission {
        final long id; final int startDay, startMin, endDay, endMin;
        Mission(long id, int startDay, int startMin, int endDay, int endMin) { this.id = id; this.startDay = startDay; this.startMin = startMin; this.endDay = endDay; this.endMin = endMin; }
        boolean open() { return endDay < 0; }
    }

    /** Approved leave. kind: paid (daily), hourly, sick, unpaid. minutes is used by hourly leave. */
    static final class Leave {
        final int fromDay, toDay, minutes; final String kind;
        Leave(int fromDay, int toDay, String kind, int minutes) { this.fromDay = fromDay; this.toDay = Math.max(fromDay, toDay); this.kind = kind; this.minutes = minutes; }
    }

    static String leaveKind(String type) {
        String t = type == null ? "" : type;
        if (t.contains("ساعتی")) return "hourly";
        if (t.contains("استعلاجی")) return "sick";
        if (t.contains("بدون حقوق")) return "unpaid";
        if (t.contains("ماموریت") || t.contains("مأموریت")) return "mission";
        return "paid";
    }

    /** "2", "1:30", "۲٫۵", "۴۵ دقیقه", "۱ ساعت و ۳۰ دقیقه" → minutes (0 when nothing readable). */
    static int parseDuration(String text) {
        if (text == null) return 0;
        StringBuilder b = new StringBuilder();
        for (char ch : text.trim().toCharArray()) {
            if (ch >= '۰' && ch <= '۹') b.append((char) ('0' + (ch - '۰')));
            else if (ch >= '٠' && ch <= '٩') b.append((char) ('0' + (ch - '٠')));
            else if (ch == '٫' || ch == '/' || ch == ',') b.append('.');
            else b.append(ch);
        }
        String s = b.toString();
        try {
            java.util.regex.Matcher hm = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{1,2})").matcher(s);
            if (hm.find()) return Integer.parseInt(hm.group(1)) * 60 + Integer.parseInt(hm.group(2));
            List<Double> nums = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+(\\.\\d+)?").matcher(s);
            while (m.find()) nums.add(Double.parseDouble(m.group()));
            if (nums.isEmpty()) return 0;
            boolean hasHour = s.contains("ساعت"), hasMin = s.contains("دقیقه");
            if (hasHour && hasMin && nums.size() >= 2) return (int) Math.round(nums.get(0) * 60 + nums.get(1));
            if (hasMin && !hasHour) return (int) Math.round(nums.get(0));
            return (int) Math.round(nums.get(0) * 60);
        } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------------------------------ one day
    static final class Track {
        final List<int[]> intervals = new ArrayList<>();
        int missionMinutes = 0;
        boolean incomplete = false;
        String reason = "";
    }

    /**
     * Presence intervals of one day from entry/exit presses and missions. «finished» = the day is over (past day, or
     * today three hours after the shift). An entry without an exit, an exit without an entry, a mission that was never
     * ended, or a return from a mission before the end of the shift without an exit makes the day «تردد ناقص».
     */
    static Track track(int day, List<Event> events, List<Mission> missions, Shift shift, boolean finished) {
        Track t = new Track();
        int wd = weekday(day);
        List<Event> ev = new ArrayList<>();
        for (Event e : events) if (e.day == day) ev.add(e);
        Collections.sort(ev, (a, b) -> Integer.compare(a.minute, b.minute));
        List<int[]> ms = new ArrayList<>(); // {from, to} ; to = -1 while running
        for (Mission m : missions) {
            if (m.startDay > day) continue;
            if (!m.open() && m.endDay < day) continue;
            int from = m.startDay == day ? m.startMin : 0;
            int to = m.open() ? -1 : (m.endDay == day ? m.endMin : 1440);
            ms.add(new int[]{from, to});
        }
        Collections.sort(ms, (a, b) -> Integer.compare(a[0], b[0]));
        Integer open = null;
        int lastClose = -1;
        for (Event e : ev) {
            if (e.in) { if (open == null) open = e.minute; }
            else if (open != null) { t.intervals.add(new int[]{open, e.minute}); open = null; lastClose = e.minute; }
            else {
                // Exit without an entry: fine when the person came back from a mission (the mission end is the return).
                int back = -1;
                for (int[] p : ms) if (p[1] >= 0 && p[1] <= e.minute && p[1] >= lastClose) back = Math.max(back, p[1]);
                if (back >= 0) t.intervals.add(new int[]{back, e.minute});
                else if (!coveredByMission(ms, e.minute)) { t.incomplete = true; t.reason = "ورود ثبت نشده"; t.intervals.add(new int[]{e.minute, e.minute}); }
                lastClose = e.minute;
            }
        }
        int endShift = shift.work[wd] ? shift.end[wd] : 0;
        if (open != null) {
            int[] next = null;
            for (int[] p : ms) if (p[0] >= open) { next = p; break; }
            if (next != null) {
                t.intervals.add(new int[]{open, next[0]});
            } else if (finished) {
                t.incomplete = true; t.reason = "خروج ثبت نشده"; t.intervals.add(new int[]{open, open});
            }
        }
        for (int[] p : ms) {
            if (p[1] >= 0) { t.intervals.add(new int[]{p[0], p[1]}); t.missionMinutes += Math.max(0, p[1] - p[0]); }
            else if (finished) { t.incomplete = true; t.reason = "ماموریت پایان داده نشده"; t.intervals.add(new int[]{p[0], p[0]}); }
        }
        if (finished && shift.work[wd] && !t.incomplete) {
            // Back from a mission before the end of the shift (mission ended) but no exit afterwards.
            int lastMissionEnd = -1;
            for (int[] p : ms) if (p[1] >= 0 && p[1] < 1440) lastMissionEnd = Math.max(lastMissionEnd, p[1]);
            int lastEvent = ev.isEmpty() ? -1 : ev.get(ev.size() - 1).minute;
            if (lastMissionEnd >= 0 && lastMissionEnd > lastEvent && lastMissionEnd < endShift - shift.graceEarly) {
                t.incomplete = true; t.reason = "بعد از بازگشت از ماموریت خروج ثبت نشده";
            }
        }
        merge(t.intervals);
        return t;
    }

    private static boolean coveredByMission(List<int[]> ms, int minute) {
        for (int[] p : ms) if (minute >= p[0] - 1 && (p[1] < 0 || minute <= p[1] + 1)) return true;
        return false;
    }

    static void merge(List<int[]> iv) {
        Collections.sort(iv, (a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        List<int[]> out = new ArrayList<>();
        for (int[] x : iv) {
            int a = Math.max(0, Math.min(1440, x[0])), b = Math.max(0, Math.min(1440, x[1]));
            if (b < a) b = a;
            if (!out.isEmpty() && a <= out.get(out.size() - 1)[1]) out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], b);
            else out.add(new int[]{a, b});
        }
        iv.clear(); iv.addAll(out);
    }

    static int overlap(List<int[]> iv, int from, int to) {
        int t = 0;
        for (int[] x : iv) t += Math.max(0, Math.min(x[1], to) - Math.max(x[0], from));
        return t;
    }

    static final class Day {
        int day, weekday; String date = "", status = "", note = "", holiday = "";
        boolean workday, future, incomplete, absent, leaveDay, sickDay, unpaidDay;
        int presence, late, early, gap, overtime, overtimeExcess, night, holidayWork, mission, leaveMinutes, firstIn = -1, lastOut = -1;
    }

    static Day calcDay(int day, Track t, Shift shift, String holiday, List<Leave> leaves) {
        Day d = new Day();
        d.day = day; d.weekday = weekday(day); d.date = MeelanoJalali.format(day);
        d.holiday = holiday == null ? "" : holiday;
        d.workday = shift.work[d.weekday] && d.holiday.isEmpty();
        d.incomplete = t.incomplete; d.mission = t.missionMinutes;
        List<int[]> iv = new ArrayList<>();
        for (int[] x : t.intervals) if (x[1] > x[0]) iv.add(x);
        for (int[] x : iv) d.presence += x[1] - x[0];
        if (!t.intervals.isEmpty()) { d.firstIn = t.intervals.get(0)[0]; d.lastOut = t.intervals.get(t.intervals.size() - 1)[1]; }
        d.night = overlap(iv, 0, shift.nightEnd) + overlap(iv, shift.nightStart, 1440);
        Leave daily = null; int hourly = 0;
        for (Leave l : leaves) {
            if (day < l.fromDay || day > l.toDay) continue;
            if ("hourly".equals(l.kind)) hourly += l.minutes; else if (!"mission".equals(l.kind) && daily == null) daily = l;
        }
        if (!d.workday) {
            d.holidayWork = d.presence;
            d.status = d.presence > 0 ? "کار در روز تعطیل" : (!d.holiday.isEmpty() ? "تعطیل رسمی" : "تعطیل هفتگی");
            if (d.incomplete) d.status = "تردد ناقص (روز تعطیل)";
            return d;
        }
        int s = shift.start[d.weekday], e = shift.end[d.weekday];
        if (daily != null) {
            if ("sick".equals(daily.kind)) { d.sickDay = true; d.status = "مرخصی استعلاجی"; }
            else if ("unpaid".equals(daily.kind)) { d.unpaidDay = true; d.status = "مرخصی بدون حقوق"; }
            else { d.leaveDay = true; d.leaveMinutes = DAY_MINUTES; d.status = "مرخصی استحقاقی روزانه"; }
            if (d.presence > 0) d.note = "در روز مرخصی حضور ثبت شده است";
            return d;
        }
        if (d.incomplete) {
            if (d.firstIn >= 0) { int late = Math.max(0, d.firstIn - s); d.late = late > shift.graceLate ? late : 0; }
            d.status = "تردد ناقص";
            d.note = t.reason;
            return d;
        }
        if (d.presence == 0) {
            if (hourly >= shift.required(d.weekday) && hourly > 0) { d.leaveMinutes = hourly; d.status = "مرخصی ساعتی (کل روز)"; return d; }
            d.absent = true; d.status = "غیبت";
            return d;
        }
        int first = iv.get(0)[0], last = iv.get(iv.size() - 1)[1];
        int lateRaw = Math.max(0, first - s), earlyRaw = Math.max(0, e - last);
        int covered = overlap(iv, s, e);
        int missing = Math.max(0, (e - s) - covered);
        int gap = Math.max(0, missing - Math.min(lateRaw, e - s) - Math.min(earlyRaw, e - s) - shift.rest[d.weekday]);
        int late = lateRaw > shift.graceLate ? lateRaw : 0;
        int early = earlyRaw > shift.graceEarly ? earlyRaw : 0;
        int rem = hourly;
        int take = Math.min(rem, late); late -= take; rem -= take;
        take = Math.min(rem, early); early -= take; rem -= take;
        take = Math.min(rem, gap); gap -= take; rem -= take;
        d.leaveMinutes = hourly;
        d.late = late; d.early = early; d.gap = gap;
        int ot = overlap(iv, e, 1440) + (shift.overtimeBeforeStart ? overlap(iv, 0, s) : 0);
        if (ot < shift.overtimeMin) ot = 0;
        if (shift.maxOvertimeDay > 0 && ot > shift.maxOvertimeDay) { d.overtimeExcess = ot - shift.maxOvertimeDay; ot = shift.maxOvertimeDay; }
        d.overtime = ot;
        d.status = (late > 0 || early > 0 || gap > 0) ? "کسر کار" : "حضور کامل";
        if (ot > 0) d.status += " + اضافه‌کار";
        if (hourly > 0) d.note = "مرخصی ساعتی " + hoursText(hourly);
        return d;
    }

    // ------------------------------------------------------------------------------------ month
    static final class Month {
        int jy, jm, daysInMonth, computedDays;
        final List<Day> days = new ArrayList<>();
        int workdays, presentDays, absentDays, leaveDays, sickDays, unpaidDays, incompleteDays, holidayWorkDays;
        int late, early, gap, overtime, overtimeExcess, night, holidayWork, mission, presence, leaveMinutes;
        double hourly, paidDays;
        long base, seniority, overtimePay, nightPay, holidayPay, housing, grocery, marriage, child, deduction, gross, insurable, insurance, employerInsurance, taxable, tax, net;
    }

    /**
     * Whole month for one person. Days after «today» (and today until three hours after the shift) are not judged
     * and are paid as normal days, so the payroll is the month's expected pay with the deductions known so far.
     */
    static Month calc(int jy, int jm, int today, int nowMin, Shift shift, Map<Integer, String> holidays,
                      List<Event> events, List<Mission> missions, List<Leave> leaves, Staff staff) {
        Month m = new Month();
        m.jy = jy; m.jm = jm; m.daysInMonth = daysInMonth(jy, jm);
        int first = MeelanoJalali.toDay(jy, jm, 1);
        for (int k = 0; k < m.daysInMonth; k++) {
            int day = first + k;
            int wd = weekday(day);
            boolean finished = day < today || (day == today && shift.work[wd] && nowMin >= Math.min(1439, shift.end[wd] + 180));
            String hol = holidays == null ? null : holidays.get(day);
            if (!finished) {
                Day d = new Day(); d.day = day; d.weekday = wd; d.date = MeelanoJalali.format(day); d.future = true;
                d.holiday = hol == null ? "" : hol; d.workday = shift.work[wd] && d.holiday.isEmpty();
                d.status = day == today ? "امروز (در جریان)" : "آینده";
                m.days.add(d);
                continue;
            }
            Track t = track(day, events, missions, shift, true);
            Day d = calcDay(day, t, shift, hol, leaves);
            m.days.add(d);
            m.computedDays++;
            if (d.workday) m.workdays++;
            if (d.workday && (d.presence > 0 || d.incomplete) && !d.leaveDay && !d.sickDay && !d.unpaidDay) m.presentDays++;
            if (d.absent) m.absentDays++;
            if (d.leaveDay) m.leaveDays++;
            if (d.sickDay) m.sickDays++;
            if (d.unpaidDay) m.unpaidDays++;
            if (d.incomplete) m.incompleteDays++;
            if (d.holidayWork > 0) m.holidayWorkDays++;
            m.late += d.late; m.early += d.early; m.gap += d.gap; m.overtime += d.overtime; m.overtimeExcess += d.overtimeExcess;
            m.night += d.night; m.holidayWork += d.holidayWork; m.mission += d.mission; m.presence += d.presence; m.leaveMinutes += d.leaveMinutes;
        }
        payroll(m, staff == null ? new Staff() : staff);
        return m;
    }

    static void payroll(Month m, Staff s) {
        long daily = s.dailyWage > 0 ? s.dailyWage : MIN_DAILY;
        m.hourly = daily * 60.0 / DAY_MINUTES;
        m.paidDays = Math.max(0, m.daysInMonth - m.absentDays - m.sickDays - m.unpaidDays);
        double factor = m.daysInMonth == 0 ? 0 : m.paidDays / m.daysInMonth;
        m.base = Math.round(daily * m.paidDays);
        m.seniority = Math.round(s.seniorityDaily * m.paidDays);
        m.housing = Math.round(s.housing * factor);
        m.grocery = Math.round(s.grocery * factor);
        m.marriage = s.married ? Math.round(MARRIAGE * factor) : 0;
        m.child = Math.round(Math.max(0, s.children) * CHILD_EACH * factor);
        m.overtimePay = Math.round(m.overtime / 60.0 * m.hourly * OVERTIME_RATE);
        m.nightPay = Math.round(m.night / 60.0 * m.hourly * NIGHT_EXTRA);
        m.holidayPay = Math.round(m.holidayWork / 60.0 * m.hourly * HOLIDAY_RATE);
        m.deduction = Math.round((m.late + m.early + m.gap) / 60.0 * m.hourly);
        m.gross = m.base + m.seniority + m.overtimePay + m.nightPay + m.holidayPay + m.housing + m.grocery + m.marriage + m.child;
        m.insurable = Math.min(INSURANCE_CEILING, Math.max(0, m.base + m.seniority + m.overtimePay + m.nightPay + m.holidayPay + m.housing + m.marriage - m.deduction));
        m.insurance = s.insured ? Math.round(m.insurable * INSURANCE_EMPLOYEE) : 0;
        m.employerInsurance = s.insured ? Math.round(m.insurable * INSURANCE_EMPLOYER) : 0;
        m.taxable = Math.max(0, m.gross - m.deduction - Math.round(m.insurance * 2.0 / 7.0));
        m.tax = monthlyTax(m.taxable);
        m.net = m.gross - m.deduction - m.insurance - m.tax;
    }

    static long monthlyTax(long taxable) {
        double tax = 0;
        for (int i = 0; i < TAX_RATES.length; i++) {
            long lo = TAX_STEPS[i], hi = i + 1 < TAX_STEPS.length ? TAX_STEPS[i + 1] : Long.MAX_VALUE;
            if (taxable > lo) tax += (Math.min(taxable, hi) - lo) * TAX_RATES[i];
        }
        return Math.round(tax);
    }

    /** Paid leave used in [fromDay, toDay]: working days of daily leave × 440 min + hourly leave minutes. */
    static int leaveUsedMinutes(List<Leave> leaves, int fromDay, int toDay, Shift shift, Map<Integer, String> holidays) {
        int used = 0;
        for (Leave l : leaves) {
            if ("hourly".equals(l.kind)) { if (l.fromDay >= fromDay && l.fromDay <= toDay) used += l.minutes; continue; }
            if (!"paid".equals(l.kind)) continue;
            for (int d = Math.max(fromDay, l.fromDay); d <= Math.min(toDay, l.toDay); d++)
                if (shift.work[weekday(d)] && (holidays == null || !holidays.containsKey(d))) used += DAY_MINUTES;
        }
        return used;
    }

    /** Leave earned from the start of the year to the end of month jm (26 days a year) plus the carried balance. */
    static double leaveEarnedMinutes(Staff s, int jm) {
        int carry = Math.min(s == null ? 0 : s.leaveCarryMinutes, LEAVE_CARRY_MAX_DAYS * DAY_MINUTES);
        return carry + LEAVE_MINUTES_PER_MONTH * Math.max(0, Math.min(12, jm));
    }

    static Map<Integer, String> defaultHolidays() {
        Map<Integer, String> m = new HashMap<>();
        for (String[] h : HOLIDAYS_1405) m.put(MeelanoJalali.parse(h[0]), h[1]);
        return m;
    }

    static String hoursText(int minutes) {
        if (minutes <= 0) return "۰";
        int h = minutes / 60, mi = minutes % 60;
        String s = h > 0 ? h + " ساعت" + (mi > 0 ? " و " + mi + " دقیقه" : "") : mi + " دقیقه";
        return fa(s);
    }

    /** Days (of 440 minutes) and the rest in hours: "۳ روز و ۲ ساعت و ۲۰ دقیقه". */
    static String leaveText(double minutes) {
        int total = (int) Math.round(minutes);
        boolean neg = total < 0; total = Math.abs(total);
        int days = total / DAY_MINUTES, rest = total % DAY_MINUTES;
        String s = (days > 0 ? days + " روز" : "") + (rest > 0 ? (days > 0 ? " و " : "") + hoursText(rest) : (days == 0 ? "۰" : ""));
        return (neg ? "منفی " : "") + fa(s);
    }

    static String clock(int minute) {
        if (minute < 0) return "—";
        int m = Math.min(1439, minute);
        return fa(String.format(Locale.US, "%02d:%02d", m / 60, m % 60));
    }

    static String fa(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : (s == null ? "" : s).toCharArray()) b.append(ch >= '0' && ch <= '9' ? (char) ('۰' + (ch - '0')) : ch);
        return b.toString();
    }
}
