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

/** Who is on shift right now (live, including shifts drivers start on their phones); the owner can start/end too. */
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

    private val adapter = RowAdapter<Pair<Driver, Shift?>> { row, (driver, shift) ->
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<RecyclerView>(R.id.list).setup(adapter)
        view.findViewById<FloatingActionButton>(R.id.fab).visibility = View.GONE
        empty = view.findViewById(R.id.empty)
        empty.text = "Zatiaľ nemáte žiadnych vodičov.\nPridajte ich v záložke Vodiči."
    }

    override fun onStart() {
        super.onStart()
        listeners += Repo.listenVehicles { vehicles = it }
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
        adapter.items = drivers.map { it to open[it.id] }
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }
}
