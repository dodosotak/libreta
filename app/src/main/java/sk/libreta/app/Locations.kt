package sk.libreta.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDateTime

/** The phone's current position, once (no tracking): the best enabled provider, at most ~30 s, else a recent last fix. */
object Gps {

    private const val TIMEOUT_MS = 30_000L
    private const val LAST_FIX_MAX_AGE_MS = 5 * 60_000L

    fun hasPermission(context: Context) =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun isEnabled(context: Context) = LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun current(context: Context, onResult: (Location?) -> Unit) {
        val lm = context.getSystemService(LocationManager::class.java)
        val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { it in lm.allProviders && runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val handler = Handler(Looper.getMainLooper())
        var done = false
        fun finish(location: Location?) {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            onResult(location ?: lastFix(lm, providers))
        }
        val provider = providers.firstOrNull() ?: return finish(null)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val cancel = CancellationSignal()
                lm.getCurrentLocation(provider, cancel, context.mainExecutor) { finish(it) }
                handler.postDelayed({ cancel.cancel(); finish(null) }, TIMEOUT_MS)
            } else {
                val listener = LocationListener { finish(it) }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                handler.postDelayed({ lm.removeUpdates(listener); finish(null) }, TIMEOUT_MS)
            }
        } catch (e: SecurityException) {
            finish(null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastFix(lm: LocationManager, providers: List<String>): Location? =
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < LAST_FIX_MAX_AGE_MS }
            .minByOrNull { it.accuracy }
}

/** Opens the dialer with [phone] filled in – the call itself goes through the phone's own app (no permission needed). */
fun dial(context: Context, phone: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone.trim()))))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Tento telefón nevie volať.", Toast.LENGTH_LONG).show()
    }
}

/** One row of "Polohy vodičov": the boss or a driver, with the last position and/or a number to call. */
data class Colleague(val id: String, val name: String, val phone: String, val location: DriverLocation?) {
    val isOwner get() = id == Contact.OWNER
}

/** Opens the position in the phone's maps (Google Maps, Waze…) with the driver's name as the pin. */
fun showOnMap(context: Context, location: DriverLocation) {
    val point = "${location.lat},${location.lng}"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point(${Uri.encode(location.name)})")))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$point")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "V telefóne nie je aplikácia s mapou.", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Where the drivers are. A driver sends the current position with one tap (no tracking in the background);
 * the other drivers and the owner see it for [DriverLocation.SHOWN_HOURS] hours and open it in the maps.
 * Each row can also be called (the boss is first). Opened from the office it only lists the drivers.
 */
class LocationsActivity : AppCompatActivity() {

    /** null in the office (the owner). */
    private var driverId: String? = null
    private var driver: Driver? = null
    private var locations = emptyList<DriverLocation>()
    private var contacts = emptyList<Contact>()
    private var locating = false
    private val listeners = Listeners()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000)
        }
    }

    private lateinit var empty: TextView
    private lateinit var send: MaterialButton
    private lateinit var ownStatus: TextView
    private lateinit var stop: MaterialButton
    private lateinit var auto: com.google.android.material.switchmaterial.SwitchMaterial
    private var shiftRunning = false

    private val adapter = RowAdapter<Colleague> { row, person ->
        val location = person.location
        row.title.text = if (person.isOwner) "👔 ${person.name.ifBlank { "Šéf" }}" else person.name
        row.subtitle.text = listOfNotNull(
            location?.ageText(LocalDateTime.now()) ?: if (person.isOwner) null else "poloha neposlaná",
            person.phone.ifBlank { null },
        ).joinToString("\n")
        row.value.text = ""
        if (location != null) {
            row.action.visibility = View.VISIBLE
            row.action.text = "Mapa"
            row.action.setOnClickListener { showOnMap(this, location) }
        }
        if (person.phone.isNotBlank()) {
            row.action2.visibility = View.VISIBLE
            row.action2.text = "Zavolať"
            row.action2.setOnClickListener { dial(this, person.phone) }
        }
        row.itemView.setOnClickListener {
            when {
                location != null -> showOnMap(this, location)
                person.phone.isNotBlank() -> dial(this, person.phone)
            }
        }
    }

    /** What to do once the location permission is granted: send now, or start the automatic sending. */
    private var afterPermission: (() -> Unit)? = null

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) afterPermission?.invoke()
        else MaterialAlertDialogBuilder(this)
            .setMessage("Bez povolenia na polohu ju nemôžem poslať. Povoľ ju v nastaveniach aplikácie Libreta → Povolenia → Poloha.")
            .setPositiveButton("Nastavenia") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driverId = if (intent.getBooleanExtra(EXTRA_OFFICE, false)) null else Session.driverId(this) ?: return finish()
        setContentView(R.layout.activity_locations)
        setupScreen(padBottom = true)
        title = "Polohy vodičov"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<RecyclerView>(R.id.list).setup(adapter)
        findViewById<View>(R.id.fab).visibility = View.GONE
        empty = findViewById(R.id.empty)
        send = findViewById(R.id.send)
        ownStatus = findViewById(R.id.own_status)
        stop = findViewById(R.id.stop)
        findViewById<View>(R.id.own).visibility = if (driverId == null) View.GONE else View.VISIBLE
        send.setOnClickListener { sendLocation() }
        findViewById<View>(R.id.show_map).setOnClickListener { DriversMapActivity.open(this, office = driverId == null) }
        stop.setOnClickListener { driverId?.let(Repo::deleteLocation) }
        auto = findViewById(R.id.auto)
        auto.isChecked = LocationService.isEnabled(this)
        auto.setOnCheckedChangeListener { _, on ->
            LocationService.setEnabled(this, on)
            if (on) withPermission { startAuto() }
            render()
        }
    }

    override fun onStart() {
        super.onStart()
        Session.showErrors(this)
        val id = driverId
        if (id == null) {
            listenLocations()
        } else {
            listeners += Repo.listenDriver(id) { d ->
                if (d == null) return@listenDriver finish()
                driver = d
                render()
            }
            listeners += Repo.listenOpenShift(id) { shift ->
                shiftRunning = shift != null
                if (shiftRunning) startAuto()
                render()
            }
            // The rules let a driver's phone see the locations only once its link is recorded.
            Repo.linkPhone(id) { ok ->
                if (isFinishing || isDestroyed) return@linkPhone
                if (ok) listenLocations()
                else Toast.makeText(this, "Polohy sa nepodarilo načítať. Si pripojený na internet?", Toast.LENGTH_LONG).show()
            }
        }
        handler.post(tick)
    }

    private fun listenLocations() {
        listeners["locations"] = Repo.listenLocations { locations = it; render() }
        listeners["contacts"] = Repo.listenContacts { list ->
            contacts = list
            // Fills in the own number for drivers who saved it before the phone list existed.
            val d = driver
            if (d != null && d.active && d.phone.isNotBlank() &&
                list.none { it.id == d.id && it.phone == d.phone.trim() && it.name == d.name }
            ) {
                Repo.saveContact(d.id, d.name, d.phone)
            }
            render()
        }
    }

    override fun onStop() {
        super.onStop()
        listeners.clear()
        handler.removeCallbacks(tick)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        val now = LocalDateTime.now()
        val fresh = locations.filter { it.isFresh(now) }
        val mine = fresh.firstOrNull { it.driverId == driverId }
        val byId = fresh.associateBy { it.driverId }
        val phones = contacts.associateBy { it.id }
        val people = (byId.keys + phones.keys)
            .filter { it != driverId && !(driverId == null && it == Contact.OWNER) }
            .map { id -> Colleague(id, phones[id]?.name ?: byId[id]?.name.orEmpty(), phones[id]?.phone.orEmpty(), byId[id]) }
        val collator = java.text.Collator.getInstance(Fmt.SK)
        // The boss first, then who sent a position (newest first), then the rest by name.
        adapter.items = people.sortedWith(
            compareBy<Colleague> { !it.isOwner }.thenBy { it.location == null }
                .thenByDescending { it.location?.let { l -> l.at ?: now } }
                .thenBy(collator) { it.name }
        )
        empty.text = if (driverId == null) "Žiadny vodič za posledných ${DriverLocation.SHOWN_HOURS} hodín neposlal polohu ani nemá zadané číslo."
        else "Ostatní vodiči za posledných ${DriverLocation.SHOWN_HOURS} hodín neposlali polohu ani nemajú zadané číslo."
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
        if (driverId != null) {
            val autoOn = LocationService.isEnabled(this) && Gps.hasPermission(this)
            ownStatus.text = when {
                locating -> "Zisťujem polohu… (vonku to ide rýchlejšie)"
                autoOn && shiftRunning -> "Počas smeny sa tvoja poloha posiela sama každých 5 minút." +
                    (mine?.let { "\nPosledná: ${it.ageText(now)}" } ?: "")
                autoOn && mine != null -> "Tvoja poloha: ${mine.ageText(now)}\nPočas smeny sa bude posielať sama."
                autoOn -> "Počas smeny sa poloha bude posielať sama. Mimo smeny ju pošleš ťuknutím.\nVidia ju ostatní vodiči a šéf."
                mine != null -> "Tvoja poloha: ${mine.ageText(now)}\nVidia ju ostatní vodiči a šéf."
                else -> "Polohu pošleš len keď ťukneš – appka ťa nesleduje.\nVidia ju ostatní vodiči a šéf ${DriverLocation.SHOWN_HOURS} hodín."
            }
            send.text = if (locating) "Zisťujem polohu…" else if (mine != null) "Poslať polohu znova" else "Poslať moju polohu"
            send.isEnabled = !locating && driver?.active == true
            stop.visibility = if (mine != null && !locating) View.VISIBLE else View.GONE
        }
    }

    private fun withPermission(action: () -> Unit) {
        if (Gps.hasPermission(this)) return action()
        afterPermission = action
        askPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun startAuto() {
        val id = driverId ?: return
        if (shiftRunning && driver?.active != false) LocationService.start(this, id)
        render()
    }

    private fun sendLocation() {
        val d = driver ?: return
        if (!Gps.hasPermission(this)) return withPermission(::sendLocation)
        if (!Gps.isEnabled(this)) {
            MaterialAlertDialogBuilder(this)
                .setMessage("Poloha je v telefóne vypnutá. Zapni ju a skús to znova.")
                .setPositiveButton("Zapnúť") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Zrušiť", null)
                .show()
            return
        }
        locating = true
        render()
        Gps.current(this) { location ->
            locating = false
            render()
            if (location == null) {
                Toast.makeText(this, "Polohu sa nepodarilo zistiť. Skús to znova, najlepšie vonku.", Toast.LENGTH_LONG).show()
                return@current
            }
            Repo.shareLocation(d, location.latitude, location.longitude, location.accuracy.toDouble())
            Toast.makeText(this, "Poloha odoslaná", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_OFFICE = "office"

        /** The driver's screen: send the own position and see the others'. */
        fun openForDriver(context: Context) = context.startActivity(Intent(context, LocationsActivity::class.java))

        /** The owner's view: all drivers' positions. */
        fun openForOffice(context: Context) =
            context.startActivity(Intent(context, LocationsActivity::class.java).putExtra(EXTRA_OFFICE, true))
    }
}
