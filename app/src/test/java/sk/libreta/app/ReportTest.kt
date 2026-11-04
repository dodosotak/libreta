package sk.libreta.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class ReportTest {
    private val october = YearMonth.of(2026, 10)
    private val anna = Driver("1", "Anna Nová", WorkerType.EMPLOYEE, 0.0, dayRate = 110.0, nightRate = 10.0)
    private val boris = Driver("2", "Boris; Brigáda", WorkerType.PART_TIME, 6.5)
    private val shifts = listOf(
        // Thursday, 8 h
        Shift("1", "1", LocalDateTime.of(2026, 10, 1, 6, 0), LocalDateTime.of(2026, 10, 1, 14, 0),
            vehicle = "BA123AB", kmStart = 1000, kmEnd = 1250),
        // Friday night into Saturday: all night work, 2:15 h on Saturday
        Shift("2", "1", LocalDateTime.of(2026, 10, 2, 22, 0), LocalDateTime.of(2026, 10, 3, 2, 15)),
        Shift("3", "2", LocalDateTime.of(2026, 10, 5, 7, 0), LocalDateTime.of(2026, 10, 5, 11, 0)),
        Shift("4", "2", LocalDateTime.of(2026, 10, 6, 7, 0), null),
    )
    // Mon 26.10 – Fri 30.10 is 5 working days; 31.10 Saturday is not counted
    private val absences = listOf(Absence("a", "1", AbsenceType.VACATION, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 31)))

    @Test
    fun summaryCountsFinishedShiftsOnly() {
        val (a, b) = Report.summarize(october, listOf(boris, anna), shifts, absences)
        assertEquals(anna, a.driver)
        assertEquals(2, a.days)
        assertEquals(8 * 60 + 4 * 60 + 15L, a.minutes)
        // One day with day work (110 € fix) + night 22:00–2:15 = 4:15 h × 10 €
        assertEquals(1, a.dayShifts)
        assertEquals(152.5, a.amount, 0.001)
        assertEquals(Surcharges(night = 255, saturday = 135), a.surcharges)
        assertEquals(mapOf(AbsenceType.VACATION to 5), a.absenceDays)
        assertEquals(250, a.km)
        assertEquals(1, b.days)
        assertEquals(26.0, b.amount, 0.001)
    }

    @Test
    fun employeeDayShareAndNightHours() {
        val cyril = Driver("3", "Cyril", WorkerType.EMPLOYEE, 0.0, dayRate = 110.0, nightRate = 10.0)
        // 14:00–20:00 started during the day: only the day fix
        val shift = Shift("5", "3", LocalDateTime.of(2026, 10, 7, 14, 0), LocalDateTime.of(2026, 10, 7, 20, 0))
        val s = Report.summarize(october, cyril, listOf(shift), emptyList())
        assertEquals(0, s.nightShiftMinutes)
        assertEquals(110.0, s.amount, 0.001)
        // The fix doesn't depend on the hours: 5 h or a whole day is the same, two day shifts the same day count once
        val short = Shift("6", "3", LocalDateTime.of(2026, 10, 8, 6, 0), LocalDateTime.of(2026, 10, 8, 11, 0))
        val whole = Shift("7", "3", LocalDateTime.of(2026, 10, 9, 6, 0), LocalDateTime.of(2026, 10, 9, 17, 0))
        assertEquals(110.0, Report.summarize(october, cyril, listOf(short), emptyList()).amount, 0.001)
        assertEquals(110.0, Report.summarize(october, cyril, listOf(whole), emptyList()).amount, 0.001)
        val twoA = Shift("9", "3", LocalDateTime.of(2026, 10, 12, 6, 0), LocalDateTime.of(2026, 10, 12, 10, 0))
        val twoB = Shift("10", "3", LocalDateTime.of(2026, 10, 12, 12, 0), LocalDateTime.of(2026, 10, 12, 16, 0))
        assertEquals(110.0, Report.summarize(october, cyril, listOf(twoA, twoB), emptyList()).amount, 0.001)
        // A pure night shift 22:00–6:00: no fix, 8 h × 10 €
        val night = Shift("11", "3", LocalDateTime.of(2026, 10, 13, 22, 0), LocalDateTime.of(2026, 10, 14, 6, 0))
        assertEquals(80.0, Report.summarize(october, cyril, listOf(night), emptyList()).amount, 0.001)
        // 6:00–18:00: still only the fix; a day shift and a night shift the same day: fix + night hours
        val long = Shift("12", "3", LocalDateTime.of(2026, 10, 15, 6, 0), LocalDateTime.of(2026, 10, 15, 18, 0))
        assertEquals(110.0, Report.summarize(october, cyril, listOf(long), emptyList()).amount, 0.001)
        val evening = Shift("13", "3", LocalDateTime.of(2026, 10, 15, 19, 0), LocalDateTime.of(2026, 10, 15, 23, 0))
        assertEquals(150.0, Report.summarize(october, cyril, listOf(long, evening), emptyList()).amount, 0.001)
        // Missing rates pay 0 and are flagged
        assertEquals(0.0, Report.summarize(october, cyril.copy(dayRate = null, nightRate = null), listOf(shift), emptyList()).amount, 0.001)
        assertTrue(cyril.copy(nightRate = null).rateMissing)
        // A part-time worker gets every hour at the hourly rate, also in the evening (14:00–20:00 = 6 h × 8 €)
        assertEquals(48.0, Report.summarize(october, cyril.copy(type = WorkerType.PART_TIME, hourlyRate = 8.0), listOf(shift), emptyList()).amount, 0.001)
    }

    @Test
    fun csvUsesDecimalCommaAndEscapesNames() {
        val csv = Report.csv(october, Report.summarize(october, listOf(anna, boris), shifts, absences), shifts, absences)
        assertTrue(csv.contains("Anna Nová;Zamestnanec;2;12:15;12,25;4,25;2,25;0,00;0,00;0,00;4,25;1;5;0;0;0;0;250;;110,00;10,00;152,50;40;22;176,00;40,00;\r\n"))
        assertTrue(csv.contains("\"Boris; Brigáda\";Brigádnik;1;4:00;4,00;0,00;0,00;0,00;0,00;0,00;0,00;1;0;0;0;0;0;0;6,50;;;26,00;;;;;\r\n"))
        assertTrue(csv.contains("\"Boris; Brigáda\";Brigádnik"))
        assertTrue(csv.contains("SPOLU;;3;16:15;16,25;"))
        assertTrue(csv.contains("3.10.2026 02:15"))
        assertTrue(csv.contains("neukončená"))
        assertTrue(csv.contains("BA123AB;1000;1250;250"))
        assertTrue(csv.contains("Anna Nová;Zamestnanec;Dovolenka;26.10.2026;31.10.2026;5"))
    }
}
