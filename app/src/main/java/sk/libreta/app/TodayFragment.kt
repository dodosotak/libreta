package sk.libreta.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Who is on shift right now (live, including shifts drivers start on their phones); the owner can start/end too.
 * Vehicle deadlines (STK, insurance…) that ran out or end within a month are listed first.
 */
class TodayFragment : Fragment(R.layout.fragment_list) {

    private lateinit var empty: TextView
    private var drivers = emptyList<Driver>()
    private var vehicles = emptyList<Vehicle>()
    private val open = mutableMapOf<String, Shift?>()
    private val listeners = Listeners()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000)
        }
    }

    /** A vehicle deadline that ran out or is close – shown above the drivers. */
    private class VehicleWarning(val vehicle: Vehicle, val deadline: VehicleDeadline, val date: LocalDate)

    private val adapter = RowAdapter<Any> { row, entry ->
        if (entry is VehicleWarning) return@RowAdapter bindWarning(row, entry)
        @Suppress("UNCHECKED_CAST")
        val pair = entry as Pair<Driver, Shift?>
        val (driver, shift) = pair
        row.value.setTextColor(com.google.android.material.color.MaterialColors.getColor(row.value, android.R.attr.textColorPrimary))
        row.title.setTextColor(com.google.android.material.color.MaterialColors.getColor(row.title, android.R.attr.textColorPrimary))
        val canEdit = Session.canEdit(requireContext())
        row.title.text = driver.name
        row.action.visibility = if (canEdit) View.VISIBLE else View.GONE
        if (shift == null) {
            row.subtitle.text = "${driver.type.label} · voľno"
            row.value.text = ""
            row.action.text = "Začať"
            row.action.setOnClickListener {
                showStartShiftDialog(requireContext(), vehicles) { vehicle, km -> Repo.startShift(driver.id, byDriver = false, vehicle, km) }
            }
        } else {
            val now = LocalDateTime.now()
            val since = if (shift.start.toLocalDate() == LocalDate.now()) shift.start.format(Fmt.TIME)
            else "${shift.start.format(Fmt.DATE)} ${shift.start.format(Fmt.TIME)}"
            row.subtitle.text = listOfNotNull(
                "${driver.type.label} · na smene od $since",
                shift.vehicle.ifBlank { null },
                if (shift.isOverdue(now)) "⚠ asi zabudnutá – beží viac ako ${Shift.OVERDUE_MINUTES / 60} h" else null,
            ).joinToString(" · ")
            row.value.text = Fmt.hm(shift.minutesSoFar(now))
            val color = if (shift.isOverdue(now)) R.color.libreta_amber else R.color.on_shift
            row.value.setTextColor(ContextCompat.getColor(requireContext(), color))
            row.action.text = "Ukončiť"
            row.action.setOnClickListener {
                showEndShiftDialog(requireContext(), "Ukončiť smenu – ${driver.name}", shift, byDriver = false)
            }
        }
        row.itemView.setOnClickListener { ShiftsActivity.open(requireContext(), driver.id, YearMonth.now()) }
    }

    private fun bindWarning(row: RowHolder, w: VehicleWarning) {
        val today = LocalDate.now()
        row.title.text = w.vehicle.label
        row.title.setTextColor(ContextCompat.getColor(requireContext(), if (w.date.isBefore(today)) R.color.shift_stop else R.color.libreta_amber))
        row.subtitle.text = VehicleDeadline.text(w.deadline, w.date, today)
        row.value.text = ""
        if (Session.canEdit(requireContext())) {
            row.itemView.setOnClickListener { startActivity(android.content.Intent(requireContext(), VehiclesActivity::class.java)) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<RecyclerView>(R.id.list).setup(adapter)
        view.findViewById<FloatingActionButton>(R.id.fab).visibility = View.GONE
        empty = view.findViewById(R.id.empty)
        empty.text = "Zatiaľ nemáte žiadnych vodičov.\nPridajte ich v záložke Vodiči."
    }

    override fun onStart() {
        super.onStart()
        listeners += Repo.listenVehicles { vehicles = it; render() }
        listeners += Repo.listenDrivers { all ->
            drivers = all.filter { it.active }
            listeners.sync(drivers.map { it.id }.toSet()) { id -> Repo.listenOpenShift(id) { open[id] = it; render() } }
            render()
        }
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        requireActivity().title = "Dnes – ${LocalDate.now().format(Fmt.DATE)}"
    }

    override fun onStop() {
        super.onStop()
        listeners.clear()
        handler.removeCallbacks(tick)
    }

    private fun render() {
        val today = LocalDate.now()
        val warnings = vehicles.flatMap { v -> v.warnings(today).map { (d, date) -> VehicleWarning(v, d, date) } }.sortedBy { it.date }
        adapter.items = warnings + drivers.map { it to open[it.id] }
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }
}
