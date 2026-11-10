package sk.libreta.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime

/**
 * All drivers' last positions on one map (OpenStreetMap via Leaflet in a WebView – no API key).
 * A tap on a pin shows the name and age with "Navigovať" and "Zavolať". The driver sees themselves as "Ty";
 * opened from the office ([openForOffice]) it shows only the drivers.
 */
class DriversMapActivity : AppCompatActivity() {

    /** null in the office (the owner). */
    private var driverId: String? = null
    private var locations = emptyList<DriverLocation>()
    private var contacts = emptyList<Contact>()
    private var ready = false
    private val listeners = Listeners()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private val tick = object : Runnable {
        override fun run() {
            push()
            handler.postDelayed(this, 30_000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driverId = if (intent.getBooleanExtra(EXTRA_OFFICE, false)) null else Session.driverId(this) ?: return finish()
        setContentView(R.layout.activity_list)
        setupScreen(padBottom = true)
        title = "Mapa vodičov"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // The list layout's content is replaced by the map.
        val root = findViewById<FrameLayout>(R.id.root)
        root.removeAllViews()
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = open(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                ready = true
                push()
            }
        }
        val html = assets.open("map/map.html").bufferedReader().readText()
            .replace("/*LEAFLET_CSS*/", assets.open("map/leaflet.css").bufferedReader().readText())
            .replace("/*LEAFLET_JS*/", assets.open("map/leaflet.js").bufferedReader().readText())
        // An https base gives the tile requests a referrer, as the OpenStreetMap tile policy asks.
        web.loadDataWithBaseURL("https://libreta-sklad.web.app/", html, "text/html", "utf-8", null)
    }

    override fun onStart() {
        super.onStart()
        Session.showErrors(this)
        val id = driverId
        if (id == null) listen()
        else Repo.linkPhone(id) { ok ->
            if (isFinishing || isDestroyed) return@linkPhone
            if (ok) listen() else Toast.makeText(this, "Polohy sa nepodarilo načítať. Si pripojený na internet?", Toast.LENGTH_LONG).show()
        }
        handler.post(tick)
    }

    private fun listen() {
        listeners["locations"] = Repo.listenLocations { locations = it; push() }
        listeners["contacts"] = Repo.listenContacts { contacts = it; push() }
    }

    override fun onStop() {
        super.onStop()
        listeners.clear()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Sends the fresh positions to the page (also every 30 s, so "pred 5 min" stays right). */
    private fun push() {
        if (!ready) return
        val now = LocalDateTime.now()
        val phones = contacts.associateBy { it.id }
        val people = JSONArray()
        locations.filter { it.isFresh(now) }.forEach { l ->
            people.put(
                JSONObject()
                    .put("id", l.driverId)
                    .put("name", phones[l.driverId]?.name ?: l.name)
                    .put("lat", l.lat)
                    .put("lng", l.lng)
                    .put("accuracy", l.accuracy)
                    .put("age", l.ageText(now))
                    .put("phone", phones[l.driverId]?.phone.orEmpty())
                    .put("me", l.driverId == driverId)
                    .put("old", l.at != null && Duration.between(l.at, now).toMinutes() > 30)
            )
        }
        web.evaluateJavascript("setPeople($people)", null)
    }

    /** Links from the popups: navigate to a driver or call them; anything else stays out of the WebView. */
    private fun open(uri: Uri): Boolean {
        val id = uri.getQueryParameter("id")
        when {
            uri.scheme == "libreta" && uri.host == "map" -> locations.firstOrNull { it.driverId == id }?.let { showOnMap(this, it) }
            uri.scheme == "libreta" && uri.host == "call" -> contacts.firstOrNull { it.id == id }?.phone?.let { dial(this, it) }
            // OpenStreetMap attribution link and the like: the phone's browser.
            uri.scheme == "https" -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
        return true
    }

    companion object {
        private const val EXTRA_OFFICE = "office"

        fun open(context: Context, office: Boolean) =
            context.startActivity(Intent(context, DriversMapActivity::class.java).putExtra(EXTRA_OFFICE, office))
    }
}
