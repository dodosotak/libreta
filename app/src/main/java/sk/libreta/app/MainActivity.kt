package sk.libreta.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The office app: today's shifts, the day's deliveries, drivers, vacations and the monthly payroll overview. The accountant gets it read-only. */
class MainActivity : AppCompatActivity() {

    private lateinit var nav: BottomNavigationView
    private val listeners = Listeners()
    private val pending = mutableMapOf<String, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupScreen(padBottom = false) // the bottom navigation pads itself

        nav = findViewById(R.id.nav)
        nav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_deliveries -> DeliveriesFragment()
                R.id.nav_drivers -> DriversFragment()
                R.id.nav_vacations -> VacationsFragment()
                R.id.nav_month -> MonthFragment()
                else -> TodayFragment()
            }
            supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
            true
        }
        if (savedInstanceState == null) nav.selectedItemId = R.id.nav_today

        // The role can change on the server (e.g. the accountant's access was removed).
        Repo.checkRole { role ->
            if (role == Session.role(this)) return@checkRole
            if (role == null) {
                Toast.makeText(this, "Tento účet nemá prístup do kancelárie.", Toast.LENGTH_LONG).show()
                Session.signOut(this)
            } else {
                Session.save(this, role)
                recreate()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Session.showErrors(this)
        // Badge with the number of vacation requests waiting for the owner.
        // The owner's app keeps the drivers' phone list (for calling from "Polohy vodičov") in line with their records.
        if (Session.canEdit(this)) {
            var drivers: List<Driver>? = null
            var contacts: List<Contact>? = null
            fun sync() {
                val d = drivers ?: return
                val c = contacts ?: return
                Repo.syncContacts(d, c)
            }
            listeners += Repo.listenDrivers { drivers = it; sync() }
            listeners += Repo.listenContacts { contacts = it; sync() }
        }
        listeners += Repo.listenDrivers { drivers ->
            val ids = drivers.map { it.id }.toSet()
            pending.keys.retainAll(ids)
            listeners.sync(ids) { id ->
                Repo.listenPending(id) { requests ->
                    pending[id] = requests.size
                    showPendingBadge()
                }
            }
            showPendingBadge()
        }
    }

    override fun onStop() {
        super.onStop()
        listeners.clear()
    }

    private fun showPendingBadge() {
        val count = pending.values.sum()
        if (count == 0) nav.removeBadge(R.id.nav_vacations)
        else nav.getOrCreateBadge(R.id.nav_vacations).number = count
    }

    /** The boss's number the drivers call from "Polohy vodičov" (contacts/owner). */
    private fun showOwnerPhoneDialog() {
        val once = Listeners()
        once += Repo.listenContacts { list ->
            once.clear()
            val current = list.firstOrNull { it.id == Contact.OWNER }
            val name = textField(
                this, "Meno (ako ťa uvidia vodiči)",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, current?.name ?: "Šéf",
            )
            val phone = textField(this, "Telefónne číslo", InputType.TYPE_CLASS_PHONE, current?.phone.orEmpty())
            MaterialAlertDialogBuilder(this)
                .setTitle("Moje číslo pre vodičov")
                .setMessage("Vodiči ti zavolajú z appky v Polohy vodičov.")
                .setView(dialogForm(this, name, phone))
                .setPositiveButton("Uložiť") { _, _ ->
                    Repo.saveContact(Contact.OWNER, name.value.trim().ifBlank { "Šéf" }.take(60), phone.value.trim().take(30))
                }
                .setNegativeButton("Zrušiť", null)
                .show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (Session.canEdit(this)) {
            menu.add(Menu.NONE, MENU_LOCATIONS, Menu.NONE, "Polohy vodičov")
            menu.add(Menu.NONE, MENU_OWNER_PHONE, Menu.NONE, "Moje číslo pre vodičov")
            menu.add(Menu.NONE, MENU_VEHICLES, Menu.NONE, "Vozidlá")
            menu.add(Menu.NONE, MENU_ACCOUNTANTS, Menu.NONE, "Účtovníčka")
        }
        menu.add(Menu.NONE, MENU_SIGN_OUT, Menu.NONE, "Odhlásiť sa")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_LOCATIONS -> LocationsActivity.openForOffice(this)
            MENU_OWNER_PHONE -> showOwnerPhoneDialog()
            MENU_VEHICLES -> startActivity(Intent(this, VehiclesActivity::class.java))
            MENU_ACCOUNTANTS -> startActivity(Intent(this, AccountantsActivity::class.java))
            else -> Session.signOut(this)
        }
        return true
    }

    private companion object {
        const val MENU_VEHICLES = 1
        const val MENU_ACCOUNTANTS = 2
        const val MENU_SIGN_OUT = 3
        const val MENU_LOCATIONS = 4
        const val MENU_OWNER_PHONE = 5
    }
}
