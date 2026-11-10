package sk.libreta.app

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class WorkerType(val label: String, val plural: String) {
    EMPLOYEE("Zamestnanec", "Zamestnanci"),
    PART_TIME("Brigádnik", "Brigádnici"),
}

data class Driver(
    val id: String = "",
    val name: String,
    val type: WorkerType,
    /** Part-time workers (brigádnici): every hour is paid at this rate. */
    val hourlyRate: Double,
    /** Employees: a fixed sum for each day with a day shift (started 5:00–16:59), however many hours ([Payroll.dayShiftDays]). */
    val dayRate: Double? = null,
    /** Employees: rate per hour of the night shift (started 17:00–4:59). */
    val nightRate: Double? = null,
    val active: Boolean = true,
    /** Firebase user of the driver's phone; null until the driver enters [joinCode] in the app. */
    val uid: String? = null,
    val joinCode: String = "",
    /** Filled in by the driver in their profile (or by the owner). */
    val phone: String = "",
    /** Contract hours per week (úväzok); the monthly fund of working time is derived from it. */
    val weeklyHours: Double = 40.0,
    /** Vacation days per calendar year (nárok). */
    val vacationPerYear: Int = 20,
    /** Vacation days left after [vacationAsOf], copied from the last payslip; null = only [vacationPerYear] from 1 January. */
    val vacationBalance: Double? = null,
    val vacationAsOf: LocalDate? = null,
    /** For the vacation entitlement: from the year the employee turns 33 it is 25 days. */
    val birthYear: Int? = null,
    /** Permanently cares for a child – 25 days of vacation whatever the age. */
    val caresForChild: Boolean = false,
) {
    /**
     * Vacation days for [year] (Zákonník práce § 103): 25 if the employee turns 33 by the end of that year or cares
     * for a child, otherwise [vacationPerYear] (20, or more if the owner gives more).
     */
    fun vacationEntitlement(year: Int): Int {
        val longer = caresForChild || (birthYear != null && year - birthYear >= 33)
        return if (longer) maxOf(vacationPerYear, Vacation.LONGER_DAYS) else vacationPerYear
    }

    /** Why the entitlement is 25 – shown next to it. */
    fun vacationReason(year: Int): String? = when {
        vacationEntitlement(year) == vacationPerYear -> null
        caresForChild -> "stará sa o dieťa"
        else -> "vek 33+"
    }

    /** Hours of one working day under the contract, e.g. 4 h for a 20 h week. */
    val dailyMinutes: Long get() = Math.round(weeklyHours * 60 / 5)

    /** "8,00 €/h" for a part-time worker, "deň 110,00 € · noc 10,00 €/h" for an employee. */
    val rateText: String
        get() = when {
            type == WorkerType.PART_TIME -> "${Fmt.money(hourlyRate)} €/h"
            dayRate == null && nightRate == null -> "sadzba nezadaná"
            else -> "deň ${dayRate?.let(Fmt::money) ?: "–"} € · noc ${nightRate?.let(Fmt::money) ?: "–"} €/h"
        }

    /** Employees whose day or night rate is missing would be paid 0 for those hours. */
    val rateMissing: Boolean get() = type == WorkerType.EMPLOYEE && (dayRate == null || nightRate == null)

    /** Workers on an agreement (dohoda) have no paid vacation. */
    val hasVacation: Boolean get() = type == WorkerType.EMPLOYEE
}

data class Vehicle(
    val id: String = "",
    /** License plate (ŠPZ), e.g. "BA123AB". */
    val plate: String,
    val name: String = "",
    val active: Boolean = true,
    /** Last valid day of each tracked deadline (STK, insurance…); a deadline that isn't tracked is missing. */
    val deadlines: Map<VehicleDeadline, LocalDate> = emptyMap(),
) {
    val label: String get() = if (name.isBlank()) plate else "$plate · $name"

    /** Deadlines that ran out or end within [VehicleDeadline.WARN_DAYS] days, soonest first. */
    fun warnings(today: LocalDate): List<Pair<VehicleDeadline, LocalDate>> =
        if (!active) emptyList()
        else deadlines.entries.filter { !it.value.isAfter(today.plusDays(VehicleDeadline.WARN_DAYS)) }
            .sortedBy { it.value }.map { it.key to it.value }
}

/** What runs out on a vehicle; [key] is the field in vehicles/{id}.deadlines. */
enum class VehicleDeadline(val key: String, val label: String) {
    STK("stk", "STK"),
    EK("ek", "Emisná kontrola"),
    PZP("pzp", "PZP"),
    KASKO("kasko", "Havarijné poistenie"),
    VIGNETTE("vignette", "Diaľničná známka");

    companion object {
        /** The owner is warned this many days ahead. */
        const val WARN_DAYS = 30L

        fun byKey(key: String) = entries.firstOrNull { it.key == key }

        /** "STK o 12 dní (24.11.2026)", "STK prepadla 3.11.2026", "STK končí dnes". */
        fun text(deadline: VehicleDeadline, date: LocalDate, today: LocalDate): String {
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
            return when {
                days < 0 -> "⛔ ${deadline.label} prepadla ${date.format(Fmt.DATE)}"
                days == 0L -> "⚠ ${deadline.label} končí dnes"
                else -> "⚠ ${deadline.label} o ${Fmt.count(days.toInt(), "deň", "dni", "dní")} (${date.format(Fmt.DATE)})"
            }
        }
    }
}

/** [byHours]: may be entered in hours (a doctor's visit often takes only part of a day). [short] is for table columns. */
enum class AbsenceType(val label: String, val short: String, val byHours: Boolean = false) {
    VACATION("Dovolenka", "Dovol."),
    SICK("PN", "PN"),
    CARE("OČR", "OČR"),
    COMP_OFF("Náhradné voľno", "N. voľno"),
    UNPAID("Neplatené voľno", "Nepl."),
    /** Own doctor's visit – paid up to [Doctor.DAYS_PER_YEAR] days a year. */
    DOCTOR("Lekár", "Lekár", byHours = true),
    /** Accompanying a family member to the doctor – also up to [Doctor.DAYS_PER_YEAR] days a year. */
    DOCTOR_FAMILY("Lekár – sprevádzanie", "Sprev.", byHours = true);

    /** What a driver may ask for from the phone (the owner approves it). */
    val requestable: Boolean get() = this == VACATION || byHours

    companion object {
        val DOCTOR_TYPES = listOf(DOCTOR, DOCTOR_FAMILY)
    }
}

/**
 * Doctor's visits (paragraf, "P-čko") of an employee: 7 days a year for themselves and 7 for accompanying a family
 * member, counted in hours of the contract (7 × 8 h = 56 h for a full-time employee) because visits often take hours.
 */
object Doctor {
    const val DAYS_PER_YEAR = 7

    fun allowanceMinutes(driver: Driver): Long = DAYS_PER_YEAR * driver.dailyMinutes

    /** Minutes of approved visits of [type] in [year]. */
    fun usedMinutes(driver: Driver, absences: List<Absence>, type: AbsenceType, year: Int): Long =
        absences.filter { it.type == type && it.approved }
            .sumOf { it.minutesBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), driver.dailyMinutes) }

    fun leftMinutes(driver: Driver, absences: List<Absence>, type: AbsenceType, year: Int): Long =
        allowanceMinutes(driver) - usedMinutes(driver, absences, type, year)

    /** "Lekár 10,5 / 56 h · sprevádzanie 0 / 56 h" */
    fun summary(driver: Driver, absences: List<Absence>, year: Int): String {
        val total = Fmt.hoursShort(allowanceMinutes(driver))
        return "Lekár ${Fmt.hoursShort(usedMinutes(driver, absences, AbsenceType.DOCTOR, year))} / $total h · " +
            "sprevádzanie ${Fmt.hoursShort(usedMinutes(driver, absences, AbsenceType.DOCTOR_FAMILY, year))} / $total h"
    }
}

enum class AbsenceStatus(val label: String) {
    PENDING("čaká na schválenie"),
    APPROVED("schválená"),
    REJECTED("zamietnutá"),
}

/**
 * A day range (inclusive) when the driver did not work. The owner enters it directly (approved),
 * or the driver asks for a vacation from the phone and the owner approves or rejects the request.
 */
data class Absence(
    val id: String = "",
    val driverId: String,
    val type: AbsenceType,
    val from: LocalDate,
    val to: LocalDate,
    val note: String = "",
    val status: AbsenceStatus = AbsenceStatus.APPROVED,
    /** Only for [AbsenceType.byHours] on a single day: the hours away (e.g. 2.5); null = whole working days. */
    val hours: Double? = null,
) {
    /** Only approved absences count in reports and the vacation balance. */
    val approved: Boolean get() = status == AbsenceStatus.APPROVED

    /** Minutes away between [first] and [last]: the hours, or working days × the contract's day. */
    fun minutesBetween(first: LocalDate, last: LocalDate, dailyMinutes: Long): Long {
        val h = hours ?: return workingDaysBetween(first, last) * dailyMinutes
        return if (from.isBefore(first) || from.isAfter(last)) 0 else Math.round(h * 60)
    }

    fun minutesIn(month: YearMonth, dailyMinutes: Long): Long = minutesBetween(month.atDay(1), month.atEndOfMonth(), dailyMinutes)

    /** "2,5 h" for hours, "3 dni" for days (in [month] if given). */
    fun amountText(month: YearMonth? = null): String =
        hours?.let { "${Fmt.decimal(it)} h" }
            ?: Fmt.count(if (month != null) workingDaysIn(month) else workingDays, "deň", "dni", "dní")

    /** Working days (Mon–Fri, not a public holiday) of this absence that fall into [month]. */
    fun workingDaysIn(month: YearMonth): Int = workingDaysBetween(month.atDay(1), month.atEndOfMonth())

    /** Working days of this absence between [first] and [last] (inclusive). */
    fun workingDaysBetween(first: LocalDate, last: LocalDate): Int = Payroll.workingDays(maxOf(from, first), minOf(to, last))

    val workingDays: Int get() = Payroll.workingDays(from, to)
}

data class Shift(
    val id: String = "",
    val driverId: String,
    val start: LocalDateTime,
    val end: LocalDateTime?,
    val note: String = "",
    /** Server time when the driver's phone recorded the start/end; null for times entered by the owner. */
    val startLoggedAt: LocalDateTime? = null,
    val endLoggedAt: LocalDateTime? = null,
    /** License plate of the vehicle driven, "" if none. */
    val vehicle: String = "",
    val kmStart: Int? = null,
    val kmEnd: Int? = null,
    /** Server time when the driver added this past shift afterwards from the phone; null for shifts started/ended live or by the owner. */
    val enteredAt: LocalDateTime? = null,
) {
    /** The driver entered the whole shift afterwards (forgot to start it) – worth a look by the owner. */
    val addedLater: Boolean get() = enteredAt != null

    /** Worked minutes from start to end (breaks are not counted); 0 while the shift is still running. */
    val workedMinutes: Long
        get() = end?.let { maxOf(0, Duration.between(start, it).toMinutes()) } ?: 0

    /** Shifts running longer than this are probably forgotten. */
    fun isOverdue(now: LocalDateTime) = end == null && minutesSoFar(now) >= OVERDUE_MINUTES

    val km: Int?
        get() = if (kmStart != null && kmEnd != null && kmEnd >= kmStart) kmEnd - kmStart else null

    companion object {
        const val OVERDUE_MINUTES = 12 * 60L
    }

    /** The driver's phone time differs from the server time by more than 15 minutes – worth a look by the owner. */
    val loggedLate: Boolean
        get() = differs(start, startLoggedAt) || differs(end, endLoggedAt)

    fun minutesSoFar(now: LocalDateTime): Long = maxOf(0, Duration.between(start, now).toMinutes())

    private fun differs(time: LocalDateTime?, logged: LocalDateTime?) =
        time != null && logged != null && Math.abs(Duration.between(time, logged).toMinutes()) > 15
}

object Fmt {
    val SK: Locale = Locale.forLanguageTag("sk-SK")
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")
    val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d.M.", SK)

    /** 510 -> "8:30" */
    fun hm(minutes: Long) = "%d:%02d".format(minutes / 60, minutes % 60)

    /** 630 -> "10,5", 3360 -> "56" */
    fun hoursShort(minutes: Long): String = decimal(Math.round(minutes / 60.0 * 100) / 100.0)

    /** 510 -> "8,50" */
    fun hours(minutes: Long): String = String.format(SK, "%.2f", minutes / 60.0)

    fun money(value: Double): String = String.format(SK, "%.2f", value)

    /** 40.0 -> "40", 37.5 -> "37,5" */
    fun decimal(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format(SK, "%.2f", value).trimEnd('0')

    /** Slovak plural: 1 deň, 2 dni, 5 dní. */
    fun count(n: Int, one: String, few: String, many: String) = "$n " + when (n) {
        1 -> one
        in 2..4 -> few
        else -> many
    }

    fun month(month: YearMonth): String = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", SK))
}

/** A name and number in the phone list for calling (no pay data); [OWNER] is the boss. */
data class Contact(val id: String, val name: String, val phone: String) {
    companion object {
        const val OWNER = "owner"
    }
}

/** A driver's last sent position; shown to the owner and the other drivers for [SHOWN_HOURS] hours. */
data class DriverLocation(
    val driverId: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    /** Metres. */
    val accuracy: Double,
    /** Server time it was sent; null while it is still on its way (offline). */
    val at: LocalDateTime?,
) {
    fun isFresh(now: LocalDateTime): Boolean = at == null || Duration.between(at, now).toHours() < SHOWN_HOURS

    /** "pred 5 min (14:05) · ±20 m" */
    fun ageText(now: LocalDateTime): String {
        val sent = at ?: return "odosiela sa…"
        val minutes = maxOf(0, Duration.between(sent, now).toMinutes())
        val ago = when {
            minutes < 1 -> "práve teraz"
            minutes < 60 -> "pred $minutes min"
            else -> "pred ${Fmt.hm(minutes)} h"
        }
        val time = if (sent.toLocalDate() == now.toLocalDate()) sent.format(Fmt.TIME) else "${sent.format(Fmt.DAY)} ${sent.format(Fmt.TIME)}"
        return "$ago ($time) · ±${accuracy.toInt()} m"
    }

    companion object {
        const val SHOWN_HOURS = 8L
    }
}

enum class DeliveryStatus(val label: String) {
    OPEN("voľná"),
    IN_PROGRESS("vezie sa"),
    DONE("doručená"),
}

/**
 * A delivery (e.g. "Sinsay, Cropp, Town" to "OC Max Trnava") the owner enters for a day before the shift.
 * All drivers see the day's list: one takes a free delivery, marks it delivered, and the others see it is done.
 */
data class Delivery(
    val id: String = "",
    val date: LocalDate,
    /** What goes there – shops/orders, e.g. "Sinsay, Cropp, Town". */
    val title: String,
    /** Where – a place or address for the navigation, e.g. "OC Max Trnava". */
    val place: String = "",
    val note: String = "",
    /** Order in the day's list (creation time in millis). */
    val order: Long = 0,
    val status: DeliveryStatus = DeliveryStatus.OPEN,
    val driverId: String? = null,
    val driverName: String? = null,
    val takenAt: LocalDateTime? = null,
    val doneAt: LocalDateTime? = null,
) {
    /** "vezie Jano od 14:05", "doručil Jano 14:40", "voľná". */
    val statusText: String
        get() = when (status) {
            DeliveryStatus.OPEN -> "voľná"
            DeliveryStatus.IN_PROGRESS -> "🚚 vezie ${driverName.orEmpty()}" + (takenAt?.let { " od ${it.format(Fmt.TIME)}" } ?: "")
            DeliveryStatus.DONE -> "✅ doručil ${driverName.orEmpty()}" + (doneAt?.let { " ${it.format(Fmt.TIME)}" } ?: "")
        }

    companion object {
        /** Free ones first, then those on the way, delivered last; otherwise in the owner's order. */
        val BY_STATUS: Comparator<Delivery> = compareBy<Delivery> { it.status.ordinal }.thenBy { it.order }
    }
}
