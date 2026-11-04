package sk.libreta.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/** The office app: today's shifts, drivers, vacations and the monthly payroll overview. The accountant gets it read-only. */
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (Session.canEdit(this)) {
            menu.add(Menu.NONE, MENU_VEHICLES, Menu.NONE, "Vozidlá")
            menu.add(Menu.NONE, MENU_ACCOUNTANTS, Menu.NONE, "Účtovníčka")
        }
        menu.add(Menu.NONE, MENU_SIGN_OUT, Menu.NONE, "Odhlásiť sa")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
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
    }
}
