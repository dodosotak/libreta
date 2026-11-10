package sk.libreta.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.time.LocalDate
import java.time.YearMonth

/** One driver's month in the office: shifts and absences; the owner adds, fixes or deletes them, the accountant only reads. */
class ShiftsActivity : AppCompatActivity() {

    private lateinit var driverId: String
    private lateinit var month: YearMonth
    private lateinit var empty: TextView
    private lateinit var summary: View
    private lateinit var fab: FloatingActionButton
    private var driver: Driver? = null
    private var shifts = emptyList<Shift>()
    private var absences = emptyList<Absence>()
    private var vehicles = emptyList<Vehicle>()
    private var closed = false
    /** Absences since the vacation balance was set – for the balance in the subtitle and when deciding a request. */
    private var vacationAbsences = emptyList<Absence>()
    private val listeners = Listeners()
    private val vacationListener = Listeners()

    /** The owner may edit unless the month is closed. */
    private val editable get() = Session.canEdit(this) && !closed

    private val adapter = RowAdapter<Any> { row, entry ->
        when (entry) {
            is Shift -> {
                bindShiftRow(row, entry, office = true)
                row.itemView.setOnClickListener {
                    if (checkEditable()) showShiftDialog(this, driverId, entry, entry.start.toLocalDate(), vehicles)
                }
            }
            is Absence -> {
                bindAbsenceRow(row, entry, month)
                row.itemView.setOnClickListener {
                    if (!checkEditable()) return@setOnClickListener
                    val d = driver
                    if (entry.status == AbsenceStatus.PENDING && d != null) {
                        showRequestDecisionDialog(this, entry, d, vacationLeft(), vacationAbsences)
                    } else {
                        showAbsenceDialog(this, driverId, entry, entry.from)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shifts)
        setupScreen(padBottom = true)
        driverId = intent.getStringExtra(EXTRA_DRIVER) ?: return finish()
        month = savedInstanceState?.getString(EXTRA_MONTH)?.let(YearMonth::parse)
            ?: YearMonth.parse(intent.getStringExtra(EXTRA_MONTH))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        summary = findViewById(R.id.summary)
        summary.findViewById<View>(R.id.prev).setOnClickListener { showMonth(month.minusMonths(1)) }
        summary.findViewById<View>(R.id.next).setOnClickListener { showMonth(month.plusMonths(1)) }

        findViewById<RecyclerView>(R.id.list).setup(adapter)
        empty = findViewById(R.id.empty)
        fab = findViewById(R.id.fab)
        fab.setOnClickListener {
            val today = LocalDate.now()
            val date = if (YearMonth.from(today) == month) today else month.atDay(1)
            MaterialAlertDialogBuilder(this)
                .setItems(arrayOf("Smena", "Neprítomnosť (dovolenka, PN…)")) { _, which ->
                    if (which == 0) showShiftDialog(this, driverId, null, date, vehicles)
                    else showAbsenceDialog(this, driverId, null, date)
                }
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        Session.showErrors(this)
        listeners += Repo.listenDriver(driverId) { d ->
            driver = d ?: return@listenDriver finish()
            title = d.name
            vacationListener["v"] = Repo.listenAbsencesAfter(driverId, Vacation.listenFrom(d, LocalDate.now().year)) {
                vacationAbsences = it
                render()
            }
            render()
        }
        listeners += Repo.listenVehicles { vehicles = it }
        showMonth(month)
    }

    override fun onStop() {
        super.onStop()
        listeners.clear()
        vacationListener.clear()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_MONTH, month.toString())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add("PDF výkaz na podpis")
            .setIcon(R.drawable.ic_document)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val d = driver ?: return true
        val file = PdfReport.create(this, month, listOf(PdfReport.Sheet(d, shifts, absences)), "vykaz_${month}_${d.name}")
        PdfReport.share(this, file, "Výkaz ${d.name} – ${Fmt.month(month)}")
        return true
    }

    private fun showMonth(newMonth: YearMonth) {
        month = newMonth
        shifts = emptyList()
        absences = emptyList()
        closed = false
        listeners.clearKeyed()
        listeners["shifts"] = Repo.listenShifts(driverId, month) { shifts = it; render() }
        listeners["absences"] = Repo.listenAbsences(driverId, month) { absences = it; render() }
        listeners["closed"] = Repo.listenClosed(month) { closed = it; render() }
        render()
    }

    private fun checkEditable(): Boolean {
        if (editable) return true
        val text = if (!Session.canEdit(this)) "Účtovníčka môže údaje len prezerať." else "Mesiac je uzavretý. Otvorí ho majiteľ v záložke Mesiac."
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        return false
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        adapter.items = monthEntries(shifts, absences)
        supportActionBar?.subtitle = listOfNotNull(
            driver?.type?.label,
            driver?.phone?.ifBlank { null },
            vacationLeft()?.let { "dovolenka ${Vacation.days(it)} d" },
        ).joinToString(" · ")
        bindMonthSummary(summary, month, driver, shifts, absences, showPay = true, closed = closed)
        fab.visibility = if (editable) View.VISIBLE else View.GONE
        empty.text = if (editable) "V tomto mesiaci nič nie je.\nSmenu alebo dovolenku doplníte tlačidlom +" else "V tomto mesiaci nič nie je."
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** This year's balance including planned vacations. */
    private fun vacationLeft(): Double? = driver?.let { Vacation.left(it, vacationAbsences, LocalDate.now().year) }

    companion object {
        private const val EXTRA_DRIVER = "driver"
        private const val EXTRA_MONTH = "month"

        fun open(context: Context, driverId: String, month: YearMonth) {
            context.startActivity(
                Intent(context, ShiftsActivity::class.java)
                    .putExtra(EXTRA_DRIVER, driverId)
                    .putExtra(EXTRA_MONTH, month.toString())
            )
        }
    }
}
