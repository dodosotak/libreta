package sk.libreta.app

import java.time.LocalDate

/**
 * Vacation balance (zostatok dovolenky) of an employee. It starts from the balance the owner copied from the last payslip
 * (or from 0 on 31 December of the previous year), adds [Driver.vacationPerYear] for every new year and takes off
 * the working days of approved vacations after that date.
 */
object Vacation {

    /** The day the balance is counted from; absences ending after it are needed (see [Repo.listenAbsencesAfter]). */
    fun countedFrom(driver: Driver, year: Int): LocalDate = driver.vacationAsOf ?: LocalDate.of(year - 1, 12, 31)

    /**
     * Days left in [year] after the approved vacations up to [until] (inclusive). Null for workers without vacation
     * or when the owner's balance is dated after [until] (a report for an older month).
     */
    fun left(driver: Driver, absences: List<Absence>, year: Int, until: LocalDate = LocalDate.of(year, 12, 31)): Double? {
        if (!driver.hasVacation) return null
        val start = countedFrom(driver, year)
        if (start.isAfter(until) || start.year > year) return null
        val base = if (driver.vacationAsOf != null) driver.vacationBalance ?: 0.0 else 0.0
        return base + driver.vacationPerYear * (year - start.year) - used(absences, start.plusDays(1), until)
    }

    /** Working days of approved vacations between [first] and [last]. */
    fun used(absences: List<Absence>, first: LocalDate, last: LocalDate): Int =
        absences.filter { it.type == AbsenceType.VACATION && it.approved }.sumOf { it.workingDaysBetween(first, last) }

    /** Working days of the driver's vacation requests that are still waiting. */
    fun pending(absences: List<Absence>): Int =
        absences.filter { it.type == AbsenceType.VACATION && it.status == AbsenceStatus.PENDING }.sumOf { it.workingDays }

    /** 4.5 -> "4,5", 8.0 -> "8" */
    fun days(value: Double): String = Fmt.decimal(value)
}
