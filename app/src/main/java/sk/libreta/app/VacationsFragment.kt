package sk.libreta.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import java.time.LocalDate
import java.time.YearMonth

/**
 * Vacations of all drivers: requests (vacation, doctor's visits) from their phones waiting for the owner, this year's
 * vacation balance and doctor's hours per employee and the approved vacations ahead. The accountant sees it read-only.
 */
class VacationsFragment : Fragment(R.layout.fragment_list) {

    private class Header(val text: String)
    private class Balance(val driver: Driver, val used: Int, val planned: Int, val left: Double?)

    private lateinit var empty: TextView
    private var drivers = emptyList<Driver>()
    private val pendingByDriver = mutableMapOf<String, List<Absence>>()
    private val absencesByDriver = mutableMapOf<String, List<Absence>>()
    private val listeners = Listeners()
    private val year get() = LocalDate.now().year

    private val adapter = RowAdapter<Any> { row, entry ->
        val header = entry is Header
        row.title.setTypeface(null, if (header) Typeface.BOLD else Typeface.NORMAL)
        row.title.setTextColor(
            MaterialColors.getColor(row.title, if (header) androidx.appcompat.R.attr.colorPrimary else android.R.attr.textColorPrimary)
        )
        when (entry) {
            is Header -> {
                row.title.text = entry.text
                row.subtitle.text = ""
                row.value.text = ""
            }
            is Absence -> {
                val name = drivers.find { it.id == entry.driverId }?.name.orEmpty()
                row.title.text = name
                row.subtitle.text = listOfNotNull(
                    if (entry.approved) entry.type.label else "${entry.type.label} · ${entry.status.label}",
                    dateRange(entry),
                    entry.note.ifBlank { null },
                ).joinToString(" · ")
                row.value.text = entry.amountText()
                row.itemView.setOnClickListener { onAbsenceClick(entry, name) }
            }
            is Balance -> {
                row.title.text = entry.driver.name
                val d = entry.driver
                row.subtitle.text = "Nárok ${d.vacationEntitlement(year)} d/rok" + (d.vacationReason(year)?.let { " ($it)" } ?: "") +
                    " · čerpané ${entry.used} · naplánované ${entry.planned}" +
                    (d.vacationAsOf?.let { " · zostatok k ${it.format(Fmt.DATE)}" } ?: "") +
                    "\n🩺 " + Doctor.summary(d, absencesByDriver[d.id].orEmpty(), year)
                row.value.text = entry.left?.let { "${Vacation.days(it)} d" } ?: "–"
                row.itemView.setOnClickListener {
                    if (Session.canEdit(requireContext())) showDriverDialog(requireContext(), entry.driver)
                    else ShiftsActivity.open(requireContext(), entry.driver.id, YearMonth.now())
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<RecyclerView>(R.id.list).setup(adapter)
        view.findViewById<View>(R.id.fab).visibility = View.GONE
        empty = view.findViewById(R.id.empty)
        empty.text = "Žiadni zamestnanci s dovolenkou.\nŽiadosti, ktoré pošlú vodiči z telefónu, sa zobrazia tu."
    }

    override fun onStart() {
        super.onStart()
        listeners += Repo.listenDrivers { all ->
            drivers = all
            syncListeners()
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().title = "Dovolenky"
    }

    override fun onStop() {
        super.onStop()
        listeners.clear()
    }

    /** Waiting requests of every driver; absences since the balance date of every employee (the key changes with it). */
    private fun syncListeners() {
        val keys = drivers.map { "p:${it.id}" } +
            drivers.filter { it.hasVacation }.map { "v:${it.id}:${Vacation.listenFrom(it, year)}" }
        val ids = drivers.map { it.id }.toSet()
        pendingByDriver.keys.retainAll(ids)
        absencesByDriver.keys.retainAll(drivers.filter { it.hasVacation }.map { it.id }.toSet())
        listeners.sync(keys.toSet()) { key ->
            val (kind, id) = key.split(':')
            if (kind == "p") Repo.listenPending(id) { pendingByDriver[id] = it; render() }
            else Repo.listenAbsencesAfter(id, LocalDate.parse(key.substringAfterLast(':'))) { absencesByDriver[id] = it; render() }
        }
    }

    private fun render() {
        val today = LocalDate.now()
        val pending = pendingByDriver.values.flatten().sortedBy { it.from }
        val balances = drivers.filter { it.hasVacation && it.active }.map { d ->
            val absences = absencesByDriver[d.id].orEmpty()
            val start = Vacation.countedFrom(d, year)
            Balance(
                driver = d,
                used = Vacation.used(absences, maxOf(start.plusDays(1), LocalDate.of(year, 1, 1)), today),
                planned = Vacation.used(absences, today.plusDays(1), LocalDate.of(year, 12, 31)),
                left = Vacation.left(d, absences, year),
            )
        }
        val ahead = absencesByDriver.values.flatten()
            .filter { it.type == AbsenceType.VACATION && it.approved && !it.to.isBefore(today) }
            .sortedBy { it.from }

        val items = mutableListOf<Any>()
        fun section(title: String, rows: List<Any>) {
            if (rows.isEmpty()) return
            items.add(Header(title))
            items.addAll(rows)
        }
        section("Čakajú na schválenie (${pending.size})", pending)
        section("Naplánované dovolenky", ahead)
        section("Zostatok dovolenky a lekár $year", balances)
        adapter.items = items
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onAbsenceClick(absence: Absence, name: String) {
        val context = requireContext()
        if (!Session.canEdit(context)) {
            Toast.makeText(context, "Žiadosti schvaľuje majiteľ.", Toast.LENGTH_SHORT).show()
            return
        }
        val driver = drivers.find { it.id == absence.driverId }
        if (absence.status == AbsenceStatus.PENDING) {
            val left = driver?.let { Vacation.left(it, absencesByDriver[it.id].orEmpty(), year) }
            showRequestDecisionDialog(context, absence, driver, left, absencesByDriver[absence.driverId].orEmpty())
        } else {
            ShiftsActivity.open(context, absence.driverId, YearMonth.from(absence.from))
        }
    }
}
