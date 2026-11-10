package sk.libreta.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** The driver's own phone: one big button to start/end the shift and this month's shifts. */
class DriverActivity : AppCompatActivity() {

    private lateinit var driverId: String
    private var driver: Driver? = null
    private var open: Shift? = null
    /** This month's shifts – for "Dnes odpracované". */
    private var currentMonthShifts = emptyList<Shift>()
    /** The month shown in the overview; the driver can browse back. */
    private var month: YearMonth = YearMonth.now()
    private var askedForPhone = false
    private var vehicles = emptyList<Vehicle>()
    private var monthShifts = emptyList<Shift>()
    private var monthAbsences = emptyList<Absence>()
    /** Absences since the vacation balance was set, for "Dovolenka: zostáva …". */
    private var vacationAbsences = emptyList<Absence>()
    private val listeners = Listeners()
    private val vacationListener = Listeners()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000)
        }
    }

    private lateinit var status: TextView
    private lateinit var since: TextView
    private lateinit var button: MaterialButton
    private lateinit var vacationButton: MaterialButton
    private lateinit var deliveriesButton: MaterialButton
    /** Deliveries not delivered yet (all days), for the count on the button. */
    private var unfinishedDeliveries = emptyList<Delivery>()
    private lateinit var addShiftButton: MaterialButton
    private lateinit var summary: View

    private val adapter = RowAdapter<Any> { row, entry ->
        when (entry) {
            is Shift -> bindShiftRow(row, entry, office = false)
            is Absence -> {
                bindAbsenceRow(row, entry, month)
                if (!entry.approved) row.itemView.setOnClickListener { showOwnRequestDialog(this, entry) }
            }
        }
    }

    private var askedForLocation = false

    private val askLocation = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> if (result.values.any { it } && open != null) LocationService.start(this, driverId) }

    /**
     * During a shift the position goes out automatically (unless the driver turned it off in "Polohy vodičov").
     * Without the permission yet, the driver is asked once per screen opening – with the reason first.
     */
    private fun shareDuringShift(shift: Shift?) {
        if (shift == null || driver?.active == false) return LocationService.stop(this)
        if (!LocationService.isEnabled(this)) return
        if (Gps.hasPermission(this)) return LocationService.start(this, driverId)
        if (askedForLocation || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        askedForLocation = true
        MaterialAlertDialogBuilder(this)
            .setTitle("Zdieľať polohu počas smeny?")
            .setMessage(
                "Počas smeny sa tvoja poloha každých 5 minút pošle ostatným vodičom a šéfovi. " +
                    "Po ukončení smeny sa to vypne. Vypnúť sa to dá aj v Polohy vodičov."
            )
            .setPositiveButton("Povoliť") { _, _ ->
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            .setNegativeButton("Nie") { _, _ -> LocationService.setEnabled(this, false) }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driverId = Session.driverId(this) ?: return Session.signOut(this)
        setContentView(R.layout.activity_driver)
        setupScreen(padBottom = true)
        status = findViewById(R.id.status)
        since = findViewById(R.id.since)
        button = findViewById(R.id.button)
        deliveriesButton = findViewById(R.id.deliveries)
        deliveriesButton.setOnClickListener { DeliveriesActivity.open(this, workDay()) }
        findViewById<View>(R.id.locations).setOnClickListener { LocationsActivity.openForDriver(this) }
        vacationButton = findViewById(R.id.vacation)
        vacationButton.setOnClickListener { showVacationRequestDialog(this, driverId, vacationLeft()) }
        addShiftButton = findViewById(R.id.add_shift)
        addShiftButton.setOnClickListener {
            showShiftDialog(this, driverId, null, LocalDate.now().minusDays(1), vehicles, byDriver = true)
        }
        summary = findViewById(R.id.summary)
        summary.findViewById<View>(R.id.prev).setOnClickListener { showMonth(month.minusMonths(1)) }
        summary.findViewById<View>(R.id.next).setOnClickListener { showMonth(month.plusMonths(1)) }
        findViewById<RecyclerView>(R.id.list).setup(adapter)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        button.setOnClickListener {
            val shift = open
            if (shift == null) {
                showStartShiftDialog(this, vehicles) { vehicle, km -> Repo.startShift(driverId, byDriver = true, vehicle, km) }
            }
            else showEndShiftDialog(this, "Ukončiť smenu", shift, byDriver = true)
        }
    }

    override fun onStart() {
        super.onStart()
        Session.showErrors(this)
        Repo.onListenDenied = ::accessDenied
        startListening()
        handler.post(tick)
    }

    /** How many times the listeners were restarted after the server confirmed the link (avoids an endless loop). */
    private var restarts = 0
    private var checking = false

    /** A listener was refused: unlinked for real, or just a passing glitch right after signing in? Ask the server. */
    private fun accessDenied() {
        if (checking) return
        checking = true
        listeners.clear()
        vacationListener.clear()
        handler.postDelayed({
            Repo.checkLinked(driverId) { linked ->
                checking = false
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@checkLinked
                when {
                    linked == false -> unlinked()
                    restarts++ < 3 -> startListening()
                    else -> Toast.makeText(this, "Nepodarilo sa načítať údaje. Zatvor a znova otvor aplikáciu.", Toast.LENGTH_LONG).show()
                }
            }
        }, 1500)
    }

    private fun startListening() {
        listeners += Repo.listenDriver(driverId) { d ->
            if (d == null) return@listenDriver unlinked()
            if (driver == null) Repo.linkPhone(driverId) // lets this phone see the other drivers' locations
            driver = d
            title = d.name
            vacationListener["v"] = Repo.listenAbsencesAfter(driverId, Vacation.countedFrom(d, LocalDate.now().year)) {
                vacationAbsences = it
                render()
            }
            render()
            renderMonth()
            if (d.phone.isBlank() && !askedForPhone) {
                askedForPhone = true
                showProfileDialog(this, d)
            }
        }
        listeners += Repo.listenOpenShift(driverId) { shift ->
            open = shift
            if (shift != null) ShiftReminder.schedule(this, shift.start) else ShiftReminder.cancel(this)
            shareDuringShift(shift)
            render()
        }
        listeners += Repo.listenVehicles { vehicles = it }
        listeners += Repo.listenUnfinishedDeliveries { unfinishedDeliveries = it; render() }
        listeners += Repo.listenShifts(driverId, YearMonth.now()) { shifts ->
            currentMonthShifts = shifts
            render()
        }
        showMonth(month)
    }

    private fun showMonth(newMonth: YearMonth) {
        month = newMonth
        listeners.clearKeyed()
        monthShifts = emptyList()
        monthAbsences = emptyList()
        renderMonth()
        listeners["shifts"] = Repo.listenShifts(driverId, month) { monthShifts = it; renderMonth() }
        listeners["absences"] = Repo.listenAbsences(driverId, month) { monthAbsences = it; renderMonth() }
    }

    private fun renderMonth() {
        adapter.items = monthEntries(monthShifts, monthAbsences)
        bindMonthSummary(summary, month, driver, monthShifts, monthAbsences)
    }

    override fun onStop() {
        super.onStop()
        if (Repo.onListenDenied == ::accessDenied) Repo.onListenDenied = null
        listeners.clear()
        vacationListener.clear()
        handler.removeCallbacks(tick)
    }

    private fun render() {
        val d = driver ?: return
        val shift = open
        vacationButton.visibility = if (d.hasVacation && d.active) View.VISIBLE else View.GONE
        addShiftButton.visibility = if (d.active) View.VISIBLE else View.GONE
        renderDeliveriesButton()
        supportActionBar?.subtitle = vacationLeft()?.let { left ->
            val waiting = Vacation.pending(vacationAbsences)
            "Dovolenka: zostáva ${Vacation.days(left)} d" + if (waiting > 0) " · čaká $waiting d" else ""
        }
        when {
            !d.active && shift == null -> {
                status.text = "Účet je neaktívny"
                since.text = "Ozvi sa majiteľovi."
                button.isEnabled = false
            }
            shift == null -> {
                val today = currentMonthShifts.filter { it.end != null && it.start.toLocalDate() == LocalDate.now() }
                status.text = "Nie si na smene"
                since.text = if (today.isEmpty()) "" else "Dnes odpracované ${Fmt.hm(today.sumOf { it.workedMinutes })} h"
                showButton(start = true)
                button.isEnabled = true
            }
            else -> {
                status.text = "Na smene ${Fmt.hm(shift.minutesSoFar(LocalDateTime.now()))} h"
                since.text = if (shift.start.toLocalDate() == LocalDate.now()) "od ${shift.start.format(Fmt.TIME)}"
                else "od ${shift.start.format(Fmt.DATE)} ${shift.start.format(Fmt.TIME)}"
                showButton(start = false)
                button.isEnabled = true
            }
        }
    }

    /** Deliveries belong to the day of the running shift – a night shift goes on past midnight. */
    private fun workDay(): LocalDate = open?.start?.toLocalDate() ?: LocalDate.now()

    /** "Zásielky · vezieš 1 · voľné 3" */
    private fun renderDeliveriesButton() {
        val day = workDay()
        val relevant = unfinishedDeliveries.filter { !it.date.isAfter(day) }
        val mine = relevant.count { it.status == DeliveryStatus.IN_PROGRESS && it.driverId == driverId }
        val free = relevant.count { it.status == DeliveryStatus.OPEN }
        deliveriesButton.text = listOfNotNull(
            "Zásielky",
            if (mine > 0) "vezieš $mine" else null,
            if (free > 0) "voľné $free" else null,
        ).joinToString(" · ")
    }

    /** This year's vacation left after approved (also planned) vacations; null for workers without vacation. */
    private fun vacationLeft(): Double? = driver?.let { Vacation.left(it, vacationAbsences, LocalDate.now().year) }

    /** Big green ▶ to start, red ■ to end. */
    private fun showButton(start: Boolean) {
        button.text = if (start) "Začať smenu" else "Ukončiť smenu"
        button.setIconResource(if (start) R.drawable.ic_play else R.drawable.ic_stop)
        button.backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0x33888888, ContextCompat.getColor(this, if (start) R.color.on_shift else R.color.shift_stop)),
        )
    }

    private fun unlinked() {
        listeners.clear()
        MaterialAlertDialogBuilder(this)
            .setMessage("Tento telefón bol odpojený od tvojho účtu. Zadaj znova kód od majiteľa.")
            .setPositiveButton("OK") { _, _ -> Session.signOut(this) }
            .setCancelable(false)
            .show()
    }

    /** Releases the driver's record first (needs the internet), so the same code links a phone again without the owner. */
    private fun signOut() {
        listeners.clear()
        vacationListener.clear()
        var done = false
        fun failed() {
            if (done) return
            done = true
            Toast.makeText(this, "Odhlásenie zlyhalo. Si pripojený na internet?", Toast.LENGTH_LONG).show()
            recreate()
        }
        handler.postDelayed(::failed, 15_000)
        Repo.releasePhone(driverId) { ok ->
            if (!ok) return@releasePhone failed()
            if (done) return@releasePhone
            done = true
            Session.signOut(this)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_PROFILE, Menu.NONE, "Môj profil")
        menu.add(Menu.NONE, MENU_SIGN_OUT, Menu.NONE, "Odhlásiť sa")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_PROFILE) {
            driver?.let { showProfileDialog(this, it) }
            return true
        }
        MaterialAlertDialogBuilder(this)
            .setMessage("Znova sa prihlásiš rovnakým kódom od majiteľa. Naozaj sa odhlásiť?")
            .setPositiveButton("Odhlásiť") { _, _ -> signOut() }
            .setNegativeButton("Zrušiť", null)
            .show()
        return true
    }

    private companion object {
        const val MENU_PROFILE = 1
        const val MENU_SIGN_OUT = 2
    }
}
