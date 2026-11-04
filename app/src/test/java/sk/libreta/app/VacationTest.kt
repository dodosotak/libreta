package sk.libreta.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class VacationTest {
    private val anna = Driver("1", "Anna Nová", WorkerType.EMPLOYEE, 8.0, vacationPerYear = 25)
    // Mon 26.10 – Fri 30.10.2026: 5 working days
    private val october = Absence("a", "1", AbsenceType.VACATION, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 30))
    private val waiting = Absence("b", "1", AbsenceType.VACATION, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), status = AbsenceStatus.PENDING)
    private val sick = Absence("c", "1", AbsenceType.SICK, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 13))

    @Test
    fun withoutBalanceTheWholeYearlyEntitlementCounts() {
        assertEquals(20.0, Vacation.left(anna, listOf(october, waiting, sick), 2026)!!, 0.001)
        assertEquals(2, Vacation.pending(listOf(october, waiting, sick)))
    }

    @Test
    fun balanceFromThePayslipIsTheStartingPoint() {
        // "Dovolenka-kal.rok 25-24=1" on the September payslip
        val withBalance = anna.copy(vacationBalance = 1.0, vacationAsOf = LocalDate.of(2026, 9, 30))
        assertEquals(-4.0, Vacation.left(withBalance, listOf(october), 2026)!!, 0.001)
        assertEquals(1.0, Vacation.left(withBalance, listOf(october), 2026, LocalDate.of(2026, 9, 30))!!, 0.001)
        // a new year adds the yearly entitlement
        assertEquals(21.0, Vacation.left(withBalance, listOf(october), 2027)!!, 0.001)
        // a report for a month before the balance date can't say anything
        assertNull(Vacation.left(withBalance, listOf(october), 2026, LocalDate.of(2026, 8, 31)))
    }

    @Test
    fun halfDaysAndWorkersOnAgreement() {
        assertEquals("4,5", Vacation.days(4.5))
        assertEquals("8", Vacation.days(8.0))
        assertNull(Vacation.left(anna.copy(type = WorkerType.PART_TIME), emptyList(), 2026))
    }

    @Test
    fun fundOfWorkingTimeFollowsTheContract() {
        // September 2026: 1.9. and 15.9. are working days again → 22 days (as on the payslip)
        assertEquals(22, Payroll.workingDays(YearMonth.of(2026, 9)))
        val halfTime = anna.copy(weeklyHours = 20.0)
        val s = Report.summarize(YearMonth.of(2026, 10), halfTime, emptyList(), listOf(october, waiting))
        assertEquals(22, s.fundDays)
        assertEquals(88 * 60L, s.fundMinutes)
        // the waiting request is not counted
        assertEquals(mapOf(AbsenceType.VACATION to 5), s.absenceDays)
        assertEquals(20 * 60L, s.absenceMinutes(AbsenceType.VACATION))
    }
}
