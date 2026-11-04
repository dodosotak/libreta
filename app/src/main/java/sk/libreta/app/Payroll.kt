package sk.libreta.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Minutes of a shift that qualify for Labour Code surcharges; a minute can be in several groups (e.g. Sunday night). */
data class Surcharges(
    val night: Long = 0,
    val saturday: Long = 0,
    val sunday: Long = 0,
    val holiday: Long = 0,
    val overtime: Long = 0,
) {
    operator fun plus(o: Surcharges) =
        Surcharges(night + o.night, saturday + o.saturday, sunday + o.sunday, holiday + o.holiday, overtime + o.overtime)
}

/**
 * Hours for Slovak payroll. Only counts hours – the surcharge amounts (based on the minimum wage) are left to the accountant.
 * - night work 22:00–6:00, Saturday, Sunday and public holidays are counted by the clock,
 * - overtime is work over 8 hours in one shift; each shift counts on its own, even two shifts started the same day.
 */
object Payroll {
    const val SHIFT_MINUTES = 8 * 60L
    private val NIGHT_START: LocalTime = LocalTime.of(22, 0)
    private val NIGHT_END: LocalTime = LocalTime.of(6, 0)
    /** The owner's pay rule (not the Labour Code): a shift started 5:00–16:59 is a day shift, 17:00–4:59 a night shift. */
    val DAY_SHIFT_START: LocalTime = LocalTime.of(5, 0)
    val NIGHT_SHIFT_START: LocalTime = LocalTime.of(17, 0)

    /**
     * Days off (dni pracovného pokoja) in Slovakia. Changes from the 2024–2026 consolidation:
     * 1 Sep is not a day off since 2025, 17 Nov not since 2026, 8 May and 15 Sep are suspended for 2026.
     * The accountant should check this list when the law changes again.
     */
    fun holidays(year: Int): Set<LocalDate> {
        val fixed = mutableListOf(
            MonthDay.of(1, 1), MonthDay.of(1, 6), MonthDay.of(5, 1), MonthDay.of(5, 8), MonthDay.of(7, 5),
            MonthDay.of(8, 29), MonthDay.of(9, 1), MonthDay.of(9, 15), MonthDay.of(11, 1), MonthDay.of(11, 17),
            MonthDay.of(12, 24), MonthDay.of(12, 25), MonthDay.of(12, 26),
        )
        if (year >= 2025) fixed -= MonthDay.of(9, 1)
        if (year >= 2026) fixed -= MonthDay.of(11, 17)
        if (year == 2026) fixed -= listOf(MonthDay.of(5, 8), MonthDay.of(9, 15))
        val easter = easterSunday(year)
        return fixed.map { it.atYear(year) }.toSet() + easter.minusDays(2) + easter.plusDays(1)
    }

    fun isHoliday(date: LocalDate) = date in holidays(date.year)

    fun isWorkingDay(date: LocalDate) =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && !isHoliday(date)

    /** Working days from [first] to [last] (inclusive); 0 if [last] is before [first]. */
    fun workingDays(first: LocalDate, last: LocalDate): Int =
        generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.count { isWorkingDay(it) }

    /** Working days of the month – the fund of working time (fond pracovného času) in days. */
    fun workingDays(month: YearMonth): Int = workingDays(month.atDay(1), month.atEndOfMonth())

    /** Night/weekend/holiday minutes of one finished shift (overtime is added by [surcharges]). */
    fun clockSurcharges(shift: Shift): Surcharges {
        var night = 0L
        var saturday = 0L
        var sunday = 0L
        var holiday = 0L
        for ((t, minutes) in pieces(shift)) {
            val time = t.toLocalTime()
            if (time >= NIGHT_START || time < NIGHT_END) night += minutes
            when (t.dayOfWeek) {
                DayOfWeek.SATURDAY -> saturday += minutes
                DayOfWeek.SUNDAY -> sunday += minutes
                else -> Unit
            }
            if (isHoliday(t.toLocalDate())) holiday += minutes
        }
        return Surcharges(night, saturday, sunday, holiday)
    }

    /**
     * The owner's own rule (not the Labour Code night work 22:00–6:00): the start decides the whole shift.
     * Started 5:00–16:59 = day shift (fixed day rate, however long and even if it ends in the evening),
     * started 17:00–4:59 = night shift (every hour at the night rate).
     */
    fun isNightShift(shift: Shift): Boolean =
        shift.start.toLocalTime().let { it >= NIGHT_SHIFT_START || it < DAY_SHIFT_START }

    /** All worked minutes of a night shift ([isNightShift]), paid to employees at [Driver.nightRate]; 0 for a day shift. */
    fun nightShiftMinutes(shift: Shift): Long = if (isNightShift(shift)) shift.workedMinutes else 0

    /**
     * Days on which a finished day shift started – an employee gets the fixed day rate once for each,
     * however long they worked (two day shifts the same day count once).
     */
    fun dayShiftDays(shifts: List<Shift>): Int =
        shifts.filter { it.end != null && !isNightShift(it) }.map { it.start.toLocalDate() }.distinct().size

    /**
     * The shift from start to end in pieces that never cross midnight, 6:00 or 22:00, so each piece has one
     * category set: (piece start, minutes). Empty for a running shift.
     */
    private fun pieces(shift: Shift): List<Pair<LocalDateTime, Long>> {
        val end = shift.end ?: return emptyList()
        val result = mutableListOf<Pair<LocalDateTime, Long>>()
        var t = shift.start
        while (t.isBefore(end)) {
            val day = t.toLocalDate()
            val next = listOf(end, day.plusDays(1).atStartOfDay(), day.atTime(NIGHT_END), day.atTime(NIGHT_START))
                .filter { it.isAfter(t) }.min()
            result += t to ChronoUnit.MINUTES.between(t, next)
            t = next
        }
        return result
    }

    /** All surcharge minutes of a driver's finished [shifts], including overtime over 8 hours in a shift. */
    fun surcharges(shifts: List<Shift>): Surcharges {
        val finished = shifts.filter { it.end != null }
        val clock = finished.fold(Surcharges()) { acc, s -> acc + clockSurcharges(s) }
        val overtime = finished.sumOf { maxOf(0, it.workedMinutes - SHIFT_MINUTES) }
        return clock.copy(overtime = overtime)
    }

    /** Anonymous Gregorian algorithm. */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }
}
