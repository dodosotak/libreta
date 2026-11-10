package sk.libreta.app

import java.text.Collator
import java.time.YearMonth
import kotlin.math.roundToLong

data class DriverSummary(
    val driver: Driver,
    val days: Int,
    val minutes: Long,
    val surcharges: Surcharges = Surcharges(),
    /** Worked minutes of the night shifts ([Payroll.nightShiftMinutes]). */
    val nightShiftMinutes: Long = 0,
    /** Days with a day shift (started 5:00–16:59), each paid with the employee's fixed day rate. */
    val dayShifts: Int = 0,
    /** Working days of each absence type in the month (absences entered in hours not included). */
    val absenceDays: Map<AbsenceType, Int> = emptyMap(),
    /** Minutes of each absence type in the month: hours as entered, days × the contract's day. */
    val absenceMinutesByType: Map<AbsenceType, Long> = emptyMap(),
    val km: Int = 0,
    /** Fund of working time (fond pracovného času) under the driver's contract; 0 for workers on an agreement. */
    val fundDays: Int = 0,
    val fundMinutes: Long = 0,
) {
    /** Absence days converted to hours of the contract, as on the payslip ("Dovolenka 2 d 16 h"). */
    fun absenceMinutes(type: AbsenceType): Long = absenceMinutesByType[type] ?: 0

    /**
     * Part-time worker: all hours at the hourly rate. Employee: the fixed day rate for each day with a day shift
     * (however many hours), plus every hour of night shifts (started 17:00–4:59) at the night rate.
     */
    val amount: Double
        get() {
            val euros = when (driver.type) {
                WorkerType.PART_TIME -> minutes * driver.hourlyRate / 60.0
                WorkerType.EMPLOYEE -> dayShifts * (driver.dayRate ?: 0.0) +
                    nightShiftMinutes * (driver.nightRate ?: 0.0) / 60.0
            }
            return (euros * 100).roundToLong() / 100.0
        }
}

/** Monthly totals and the payroll CSV export (semicolon separated, decimal comma – opens directly in Slovak Excel). */
object Report {

    /**
     * Only finished shifts and approved absences count.
     * Inactive drivers are listed only if they worked or were absent that month.
     */
    fun summarize(
        month: YearMonth,
        drivers: List<Driver>,
        shifts: List<Shift>,
        absences: List<Absence> = emptyList(),
    ): List<DriverSummary> {
        val finished = shifts.filter { it.end != null }.groupBy { it.driverId }
        val absent = absences.filter { it.approved }.groupBy { it.driverId }
        val collator = Collator.getInstance(Fmt.SK)
        return drivers
            .filter { it.active || finished.containsKey(it.id) || absent.containsKey(it.id) }
            .map { driver -> summarize(month, driver, finished[driver.id].orEmpty(), absent[driver.id].orEmpty()) }
            .sortedWith(compareBy<DriverSummary> { it.driver.type }.thenBy(collator) { it.driver.name })
    }

    fun summarize(month: YearMonth, driver: Driver, shifts: List<Shift>, absences: List<Absence>): DriverSummary {
        val finished = shifts.filter { it.end != null }
        val fundDays = if (driver.type == WorkerType.EMPLOYEE) Payroll.workingDays(month) else 0
        return DriverSummary(
            driver = driver,
            days = finished.map { it.start.toLocalDate() }.distinct().size,
            minutes = finished.sumOf { it.workedMinutes },
            surcharges = Payroll.surcharges(finished),
            nightShiftMinutes = finished.sumOf { Payroll.nightShiftMinutes(it) },
            dayShifts = Payroll.dayShiftDays(finished),
            absenceDays = absences.filter { it.approved && it.hours == null }.groupBy { it.type }
                .mapValues { (_, list) -> list.sumOf { it.workingDaysIn(month) } },
            absenceMinutesByType = absences.filter { it.approved }.groupBy { it.type }
                .mapValues { (_, list) -> list.sumOf { it.minutesIn(month, driver.dailyMinutes) } },
            km = finished.sumOf { it.km ?: 0 },
            fundDays = fundDays,
            fundMinutes = fundDays * driver.dailyMinutes,
        )
    }

    /** [vacationLeft]: vacation days left at the end of the month per driver id (see [Vacation.left]). */
    fun csv(
        month: YearMonth,
        summaries: List<DriverSummary>,
        shifts: List<Shift>,
        absences: List<Absence> = emptyList(),
        vacationLeft: Map<String, Double?> = emptyMap(),
    ): String {
        val out = StringBuilder()
        fun row(vararg cells: Any) {
            out.append(cells.joinToString(";") { escape(it.toString()) }).append("\r\n")
        }
        fun summaryCells(items: List<DriverSummary>): Array<Any> {
            val s = items.fold(Surcharges()) { acc, it -> acc + it.surcharges }
            val minutes = items.sumOf { it.minutes }
            return arrayOf(
                items.sumOf { it.days }, Fmt.hm(minutes), Fmt.hours(minutes),
                Fmt.hours(s.night), Fmt.hours(s.saturday), Fmt.hours(s.sunday), Fmt.hours(s.holiday), Fmt.hours(s.overtime),
                Fmt.hours(items.sumOf { it.nightShiftMinutes }), items.sumOf { it.dayShifts },
                *AbsenceType.entries.map { type ->
                    if (type.byHours) Fmt.hours(items.sumOf { it.absenceMinutes(type) }) else items.sumOf { it.absenceDays[type] ?: 0 }
                }.toTypedArray(),
                items.sumOf { it.km },
            )
        }

        row("Výkaz odpracovaných hodín", Fmt.month(month))
        row("")
        row("SÚHRN")
        row(
            "Meno", "Typ", "Odpracované dni", "Hodiny (h:mm)", "Hodiny",
            "Noc (h)", "Sobota (h)", "Nedeľa (h)", "Sviatok (h)", "Nadčas nad 8 h/smenu (h)", "Nočné smeny (h)", "Denné smeny (dni)",
            *AbsenceType.entries.map { if (it.byHours) "${it.label} (h)" else "${it.label} (dni)" }.toTypedArray(),
            "Km", "Sadzba €/h (brigádnik)", "Denná suma €", "Nočná €/h", "Suma €",
            "Úväzok (h/týždeň)", "Fond (dni)", "Fond (h)", "Dovolenka (h)", "Zostatok dovolenky (dni)",
        )
        for (s in summaries) {
            val employee = s.driver.type == WorkerType.EMPLOYEE
            row(
                s.driver.name, s.driver.type.label, *summaryCells(listOf(s)), *rateCells(s.driver), Fmt.money(s.amount),
                if (employee) Fmt.decimal(s.driver.weeklyHours) else "",
                if (employee) s.fundDays else "",
                if (employee) Fmt.hours(s.fundMinutes) else "",
                if (employee) Fmt.hours(s.absenceMinutes(AbsenceType.VACATION)) else "",
                vacationLeft[s.driver.id]?.let(Vacation::days).orEmpty(),
            )
        }
        row("")
        for (type in WorkerType.entries) {
            val ofType = summaries.filter { it.driver.type == type }
            if (ofType.isNotEmpty()) {
                row("Spolu ${type.plural.lowercase()}", "", *summaryCells(ofType), "", "", "", Fmt.money(ofType.sumOf { it.amount }))
            }
        }
        row("SPOLU", "", *summaryCells(summaries), "", "", "", Fmt.money(summaries.sumOf { it.amount }))

        val order = summaries.withIndex().associate { (i, s) -> s.driver.id to i }
        val drivers = summaries.associate { it.driver.id to it.driver }

        row("")
        row("DETAIL SMIEN")
        row(
            "Meno", "Typ", "Dátum", "Začiatok", "Koniec", "Hodiny (h:mm)", "Hodiny",
            "Noc (h)", "Sobota (h)", "Nedeľa (h)", "Sviatok (h)", "Nočná smena (h)", "Vozidlo", "Km začiatok", "Km koniec", "Km", "Upozornenie", "Poznámka",
        )
        for (shift in shifts.filter { it.driverId in order }.sortedWith(compareBy({ order[it.driverId] }, { it.start }))) {
            val driver = drivers.getValue(shift.driverId)
            val end = shift.end
            val endText = when {
                end == null -> "neukončená"
                end.toLocalDate() != shift.start.toLocalDate() -> "${end.format(Fmt.DATE)} ${end.format(Fmt.TIME)}"
                else -> end.format(Fmt.TIME)
            }
            val s = Payroll.clockSurcharges(shift)
            val warnings = listOfNotNull(
                if (shift.loggedLate) "čas v telefóne nesedí so serverom" else null,
                shift.enteredAt?.let { "doplnil vodič dodatočne ${it.format(Fmt.DATE)}" },
            ).joinToString(", ")
            row(
                driver.name, driver.type.label, shift.start.format(Fmt.DATE), shift.start.format(Fmt.TIME), endText,
                Fmt.hm(shift.workedMinutes), Fmt.hours(shift.workedMinutes),
                Fmt.hours(s.night), Fmt.hours(s.saturday), Fmt.hours(s.sunday), Fmt.hours(s.holiday),
                Fmt.hours(Payroll.nightShiftMinutes(shift)), shift.vehicle, shift.kmStart ?: "", shift.kmEnd ?: "", shift.km ?: "", warnings, shift.note,
            )
        }

        val ownAbsences = absences.filter { it.driverId in order && it.approved }
        if (ownAbsences.isNotEmpty()) {
            row("")
            row("NEPRÍTOMNOSTI")
            row("Meno", "Typ", "Druh", "Od", "Do", "Pracovné dni v mesiaci", "Hodiny", "Poznámka")
            for (a in ownAbsences.sortedWith(compareBy({ order[it.driverId] }, { it.from }))) {
                val driver = drivers.getValue(a.driverId)
                row(
                    driver.name, driver.type.label, a.type.label, a.from.format(Fmt.DATE), a.to.format(Fmt.DATE),
                    if (a.hours == null) a.workingDaysIn(month) else "", Fmt.hours(a.minutesIn(month, driver.dailyMinutes)), a.note,
                )
            }
        }
        return out.toString()
    }

    /** Sadzba €/h (part-time), Denná suma €, Nočná €/h (employees). */
    private fun rateCells(d: Driver): Array<Any> = when (d.type) {
        WorkerType.PART_TIME -> arrayOf(Fmt.money(d.hourlyRate), "", "")
        WorkerType.EMPLOYEE -> arrayOf("", d.dayRate?.let(Fmt::money).orEmpty(), d.nightRate?.let(Fmt::money).orEmpty())
    }

    private fun escape(cell: String): String =
        if (cell.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
}
