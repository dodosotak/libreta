package sk.libreta.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.time.YearMonth

class DriversFragment : Fragment(R.layout.fragment_list) {

    private lateinit var empty: TextView
    private val listeners = Listeners()

    private val adapter = RowAdapter<Driver> { row, driver ->
        row.title.text = driver.name
        row.subtitle.text = buildString {
            append(driver.type.label).append(" · ").append(driver.rateText)
            if (driver.phone.isNotEmpty()) append(" · ☎ ").append(driver.phone)
            append(if (driver.uid != null) " · 📱 prepojený" else " · kód ${driver.joinCode}")
            if (!driver.active) append(" · neaktívny")
        }
        row.value.text = ""
        row.itemView.alpha = if (driver.active) 1f else 0.5f
        if (Session.canEdit(requireContext())) {
            row.action.visibility = View.VISIBLE
            row.action.text = "Kód"
            row.action.setOnClickListener { showJoinCodeDialog(requireContext(), driver) }
            row.itemView.setOnClickListener { showDriverDialog(requireContext(), driver) }
        } else {
            row.itemView.setOnClickListener { ShiftsActivity.open(requireContext(), driver.id, YearMonth.now()) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<RecyclerView>(R.id.list).setup(adapter)
        view.findViewById<FloatingActionButton>(R.id.fab).apply {
            visibility = if (Session.canEdit(requireContext())) View.VISIBLE else View.GONE
            setOnClickListener { showDriverDialog(requireContext(), null) }
        }
        empty = view.findViewById(R.id.empty)
        empty.text = "Pridajte prvého vodiča tlačidlom +"
    }

    override fun onStart() {
        super.onStart()
        listeners += Repo.listenDrivers { drivers ->
            adapter.items = drivers
            empty.visibility = if (drivers.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().title = "Vodiči"
    }

    override fun onStop() {
        super.onStop()
        listeners.clear()
    }
}
