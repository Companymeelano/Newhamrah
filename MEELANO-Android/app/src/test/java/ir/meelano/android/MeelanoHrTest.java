package ir.meelano.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.Test;

/** Labour-law rules of 1405 on a hand-calculated month (Mehr 1405: the 1st is a Wednesday). */
public class MeelanoHrTest {
    private static int d(int day) { return MeelanoJalali.toDay(1405, 7, day); }
    private static int t(int h, int m) { return h * 60 + m; }

    @Test public void figures() {
        assertEquals(166_255_500L, MeelanoHr.MIN_MONTH_30);
        assertEquals(16_625_550L, MeelanoHr.CHILD_EACH);
        MeelanoHr.Shift s = MeelanoHr.Shift.storeDefault(t(7, 15), t(15, 30));
        assertEquals(44 * 60, s.weeklyMinutes());
        assertEquals(4, MeelanoHr.weekday(d(1)));  // چهارشنبه
        assertEquals(6, MeelanoHr.weekday(d(3)));  // جمعه
        assertEquals(30, MeelanoHr.daysInMonth(1405, 7));
        assertEquals(31, MeelanoHr.daysInMonth(1405, 1));
    }

    @Test public void tax() {
        assertEquals(0, MeelanoHr.monthlyTax(400_000_000L));
        assertEquals(10_000_000L, MeelanoHr.monthlyTax(500_000_000L));
        assertEquals(190_000_000L, MeelanoHr.monthlyTax(1_500_000_000L));
    }

    @Test public void durations() {
        assertEquals(120, MeelanoHr.parseDuration("2"));
        assertEquals(90, MeelanoHr.parseDuration("1:30"));
        assertEquals(150, MeelanoHr.parseDuration("۲٫۵"));
        assertEquals(45, MeelanoHr.parseDuration("۴۵ دقیقه"));
        assertEquals(90, MeelanoHr.parseDuration("۱ ساعت و ۳۰ دقیقه"));
        assertEquals(0, MeelanoHr.parseDuration(""));
        assertEquals("hourly", MeelanoHr.leaveKind("مرخصی ساعتی"));
        assertEquals("sick", MeelanoHr.leaveKind("مرخصی استعلاجی"));
        assertEquals("paid", MeelanoHr.leaveKind("مرخصی استحقاقی"));
    }

    @Test public void month() {
        MeelanoHr.Shift s = MeelanoHr.Shift.storeDefault(t(7, 15), t(15, 30));
        List<MeelanoHr.Event> ev = new ArrayList<>();
        ev.add(new MeelanoHr.Event(d(1), t(7, 15), true, "app"));  ev.add(new MeelanoHr.Event(d(1), t(15, 30), false, "app"));   // complete
        ev.add(new MeelanoHr.Event(d(2), t(7, 30), true, "app"));  ev.add(new MeelanoHr.Event(d(2), t(13, 0), false, "app"));    // Thu: late 15, OT 30
        ev.add(new MeelanoHr.Event(d(3), t(9, 0), true, "app"));   ev.add(new MeelanoHr.Event(d(3), t(11, 0), false, "app"));    // Friday work 120
        // d(4) Saturday: nothing → absent
        ev.add(new MeelanoHr.Event(d(5), t(7, 15), true, "app"));                                                              // mission 10:00–17:00 → OT 90
        ev.add(new MeelanoHr.Event(d(6), t(7, 10), true, "app"));                                                              // no exit → incomplete
        // d(7): approved daily leave
        ev.add(new MeelanoHr.Event(d(8), t(7, 15), true, "app"));  ev.add(new MeelanoHr.Event(d(8), t(13, 30), false, "app"));   // 2 h hourly leave
        ev.add(new MeelanoHr.Event(d(9), t(5, 0), true, "app"));   ev.add(new MeelanoHr.Event(d(9), t(12, 30), false, "app"));   // Thu: 60 night minutes
        ev.add(new MeelanoHr.Event(d(10), t(7, 15), true, "app"));                                                             // today, in progress
        List<MeelanoHr.Mission> ms = new ArrayList<>();
        ms.add(new MeelanoHr.Mission(1, d(5), t(10, 0), d(5), t(17, 0)));
        List<MeelanoHr.Leave> lv = new ArrayList<>();
        lv.add(new MeelanoHr.Leave(d(7), d(7), "paid", 0));
        lv.add(new MeelanoHr.Leave(d(8), d(8), "hourly", 120));
        MeelanoHr.Staff staff = new MeelanoHr.Staff();

        MeelanoHr.Month m = MeelanoHr.calc(1405, 7, d(10), t(10, 0), s, new HashMap<>(), ev, ms, lv, staff);
        assertEquals(9, m.computedDays);
        assertEquals(15, m.late);
        assertEquals(0, m.early);
        assertEquals(0, m.gap);
        assertEquals(120, m.overtime);
        assertEquals(120, m.holidayWork);
        assertEquals(60, m.night);
        assertEquals(1, m.absentDays);
        assertEquals(1, m.leaveDays);
        assertEquals(1, m.incompleteDays);
        assertEquals(560, m.leaveMinutes);
        assertEquals(6, m.presentDays);
        assertEquals(420, m.mission);
        assertTrue(m.days.get(5).incomplete);
        assertEquals("خروج ثبت نشده", m.days.get(5).note);
        assertFalse(m.days.get(4).incomplete);
        assertTrue(m.days.get(9).future);

        assertEquals(29.0, m.paidDays, 0.0001);
        assertEquals(160_713_650L, m.base);
        assertEquals(29_000_000L, m.housing);
        assertEquals(21_266_667L, m.grocery);
        assertEquals(2_115_979L, m.overtimePay);
        assertEquals(264_497L, m.nightPay);
        assertEquals(2_115_979L, m.holidayPay);
        assertEquals(188_927L, m.deduction);
        assertEquals(215_476_772L, m.gross);
        assertEquals(194_021_178L, m.insurable);
        assertEquals(13_581_482L, m.insurance);
        assertEquals(0, m.tax);
        assertEquals(201_706_363L, m.net);

        assertEquals(560, MeelanoHr.leaveUsedMinutes(lv, d(1), d(30), s, new HashMap<>()));
        assertEquals(6673.33, MeelanoHr.leaveEarnedMinutes(staff, 7), 0.01);
    }

    @Test public void missionEdgeCases() {
        MeelanoHr.Shift s = MeelanoHr.Shift.storeDefault(t(7, 15), t(15, 30));
        List<MeelanoHr.Event> ev = new ArrayList<>();
        List<MeelanoHr.Mission> ms = new ArrayList<>();
        // Mission from home 08:00–12:00, then exit at 15:40 without an entry: the mission end is the return.
        ms.add(new MeelanoHr.Mission(1, d(11), t(8, 0), d(11), t(12, 0)));
        ev.add(new MeelanoHr.Event(d(11), t(15, 40), false, "app"));
        MeelanoHr.Track tr = MeelanoHr.track(d(11), ev, ms, s, true);
        assertFalse(tr.incomplete);
        MeelanoHr.Day day = MeelanoHr.calcDay(d(11), tr, s, null, new ArrayList<>());
        assertEquals(45, day.late);
        assertEquals(10, day.overtime);
        // Back from a mission at 11:00 with no exit afterwards → incomplete.
        ms.add(new MeelanoHr.Mission(2, d(12), t(9, 0), d(12), t(11, 0)));
        ev.add(new MeelanoHr.Event(d(12), t(7, 15), true, "app"));
        assertTrue(MeelanoHr.track(d(12), ev, ms, s, true).incomplete);
        // A mission that was never ended.
        ms.add(new MeelanoHr.Mission(3, d(13), t(9, 0), -1, -1));
        assertTrue(MeelanoHr.track(d(13), ev, ms, s, true).incomplete);
        // Manager's correction (exit added by the manager) completes the day.
        ev.add(new MeelanoHr.Event(d(12), t(15, 30), false, "manager"));
        assertFalse(MeelanoHr.track(d(12), ev, ms, s, true).incomplete);
    }
}
