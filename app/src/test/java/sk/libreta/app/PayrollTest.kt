package sk.libreta.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PayrollTest {
    private fun shift(start: LocalDateTime, end: LocalDateTime) = Shift(driverId = "d", start = start, end = end)

    @Test
    fun easterAndHolidays2026() {
        assertEquals(LocalDate.of(2026, 4, 5), Payroll.easterSunday(2026))
        val h = Payroll.holidays(2026)
        assertEquals(11, h.size)
        assertTrue(LocalDate.of(2026, 4, 3) in h) // Good Friday
        assertTrue(LocalDate.of(2026, 4, 6) in h) // Easter Monday
        assertFalse(LocalDate.of(2026, 5, 8) in h)
        assertFalse(LocalDate.of(2026, 9, 1) in h)
        assertFalse(LocalDate.of(2026, 11, 17) in h)
        assertTrue(LocalDate.of(2025, 11, 17) in Payroll.holidays(2025))
    }

    @Test
    fun overtimeIsCountedPerShift() {
        // Tuesday 0:30–5:00 and 22:00–6:00: two shifts started the same day, neither over 8 h
        val twoNights = listOf(
            shift(LocalDateTime.of(2026, 10, 6, 0, 30), LocalDateTime.of(2026, 10, 6, 5, 0)),
            shift(LocalDateTime.of(2026, 10, 6, 22, 0), LocalDateTime.of(2026, 10, 7, 6, 0)),
        )
        assertEquals(0, Payroll.surcharges(twoNights).overtime)
        // One 9 h shift the same day: 1 h overtime
        val long = shift(LocalDateTime.of(2026, 10, 8, 6, 0), LocalDateTime.of(2026, 10, 8, 15, 0))
        assertEquals(60, Payroll.surcharges(twoNights + long).overtime)
    }

    @Test
    fun theStartDecidesDayOrNightShift() {
        // Started during the day: a day shift, even if it ends in the evening
        assertEquals(0, Payroll.nightShiftMinutes(shift(LocalDateTime.of(2026, 10, 6, 6, 0), LocalDateTime.of(2026, 10, 6, 18, 0))))
        assertEquals(0, Payroll.nightShiftMinutes(shift(LocalDateTime.of(2026, 10, 6, 16, 0), LocalDateTime.of(2026, 10, 6, 23, 0))))
        // Started from 17:00: a night shift, all its hours count (20:00 – 8:00 = 12 h)
        assertEquals(300, Payroll.nightShiftMinutes(shift(LocalDateTime.of(2026, 10, 6, 17, 0), LocalDateTime.of(2026, 10, 6, 22, 0))))
        assertEquals(720, Payroll.nightShiftMinutes(shift(LocalDateTime.of(2026, 10, 6, 20, 0), LocalDateTime.of(2026, 10, 7, 8, 0))))
        // The day shift starts at 5:00: 5:00 – 13:00 is a day shift, 4:30 – 12:00 still a night one
        assertEquals(0, Payroll.nightShiftMinutes(shift(LocalDateTime.of(2026, 10, 6, 5, 0), LocalDateTime.of(2026, 10, 6, 13, 0))))
        assertEquals(450, Payroll.nightShiftMinutes(shift(LocalDateTime.of(2026, 10, 6, 4, 30), LocalDateTime.of(2026, 10, 6, 12, 0))))
        assertEquals(0, Payroll.nightShiftMinutes(Shift(driverId = "d", start = LocalDateTime.of(2026, 10, 6, 17, 0), end = null)))
    }

    @Test
    fun nightShift() {
        // Tuesday 22:00 – Wednesday 6:00: 8 h, all of it night work
        val s = Payroll.clockSurcharges(shift(LocalDateTime.of(2026, 10, 6, 22, 0), LocalDateTime.of(2026, 10, 7, 6, 0)))
        assertEquals(480, s.night)
        assertEquals(0, s.saturday)
    }

    @Test
    fun sundayHolidayAndOvertime() {
        // 1.11.2026 is Sunday and a holiday; 6:00–18:00 without break = 12 h, 4 h over 8
        val s = Payroll.surcharges(listOf(shift(LocalDateTime.of(2026, 11, 1, 6, 0), LocalDateTime.of(2026, 11, 1, 18, 0))))
        assertEquals(Surcharges(night = 0, sunday = 720, holiday = 720, overtime = 240), s)
    }

    @Test
    fun workingDays() {
        assertFalse(Payroll.isWorkingDay(LocalDate.of(2026, 12, 24)))
        assertTrue(Payroll.isWorkingDay(LocalDate.of(2026, 11, 17)))
    }
}
