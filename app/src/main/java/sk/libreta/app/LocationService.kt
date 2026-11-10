package sk.libreta.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.ListenerRegistration

/**
 * Sends the driver's position every [INTERVAL_MS] while a shift is running (Android only – the iPhone web app can't).
 * A foreground service with a notification the driver always sees; it stops itself when the shift ends
 * (also when the office ends it) or the driver turns the automatic sending off.
 * Started by the driver screen while it is open, because Android lets location services start only from the foreground.
 */
class LocationService : Service() {

    private var driverId: String? = null
    private var driver: Driver? = null
    private var last: Location? = null
    private var lastSentAt = 0L
    private val registrations = mutableListOf<ListenerRegistration>()
    private val listener = LocationListener { onLocation(it) }
    private var updating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_DRIVER) ?: Session.driverId(this)
        if (id == null || !Gps.hasPermission(this) || !isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, notification())
        } catch (e: Exception) {
            // Android refuses a location service started from the background or without the permission.
            Log.w("Libreta", "LocationService start", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (id != driverId) {
            stopListening()
            driverId = id
            registrations += Repo.listenDriver(id) { d ->
                if (d == null || !d.active) return@listenDriver stopSelf()
                driver = d
                sendLast()
            }
            registrations += Repo.listenOpenShift(id) { shift -> if (shift == null) stopSelf() }
            startUpdates()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        val lm = getSystemService(LocationManager::class.java)
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val provider = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.firstOrNull { it in lm.allProviders && runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return stopSelf()
        try {
            lm.requestLocationUpdates(provider, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            updating = true
        } catch (e: SecurityException) {
            stopSelf()
        }
        // The first position right away, not only after the first interval.
        Gps.current(this) { it?.let(::onLocation) }
    }

    private fun onLocation(location: Location) {
        last = location
        // Several providers/fixes can come close together – one write per interval is enough.
        if (System.currentTimeMillis() - lastSentAt < INTERVAL_MS - 30_000) return
        sendLast()
    }

    private fun sendLast() {
        val d = driver ?: return
        val l = last ?: return
        if (System.currentTimeMillis() - lastSentAt < 60_000) return
        lastSentAt = System.currentTimeMillis()
        Repo.shareLocation(d, l.latitude, l.longitude, l.accuracy.toDouble())
    }

    private fun stopListening() {
        registrations.forEach { it.remove() }
        registrations.clear()
    }

    override fun onDestroy() {
        stopListening()
        if (updating) getSystemService(LocationManager::class.java).removeUpdates(listener)
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_location)
        .setContentTitle("Zdieľaš polohu počas smeny")
        .setContentText("Ostatní vodiči a šéf ju vidia. Po ukončení smeny sa zdieľanie vypne.")
        .setStyle(NotificationCompat.BigTextStyle().bigText("Ostatní vodiči a šéf ju vidia. Po ukončení smeny sa zdieľanie vypne."))
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, LocationsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .build()
        .also {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Zdieľanie polohy počas smeny", NotificationManager.IMPORTANCE_LOW)
            )
        }

    companion object {
        private const val CHANNEL = "location_sharing"
        private const val NOTIFICATION_ID = 2
        private const val INTERVAL_MS = 5 * 60_000L
        private const val EXTRA_DRIVER = "driver"
        private const val PREFS = "locations"
        private const val KEY_AUTO = "auto"

        /** The driver's choice on this phone; on unless turned off. */
        fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTO, enabled).apply()
            if (!enabled) stop(context)
        }

        /** Call only while a screen of the app is visible (Android forbids starting it from the background). */
        fun start(context: Context, driverId: String) {
            if (!isEnabled(context) || !Gps.hasPermission(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java).putExtra(EXTRA_DRIVER, driverId))
            } catch (e: Exception) {
                Log.w("Libreta", "LocationService.start", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }
    }
}
