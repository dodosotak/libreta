package sk.libreta.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException

/** Launcher: sends a known phone straight to its screen, otherwise lets a driver join with a code or the owner sign in. */
class StartActivity : AppCompatActivity() {

    private val auth get() = FirebaseAuth.getInstance()
    private lateinit var status: TextView
    private lateinit var buttons: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = auth.currentUser
        val driverId = Session.driverId(this)
        when {
            user != null && Session.role(this) == Session.DRIVER && driverId != null -> return open(DriverActivity::class.java)
            user != null && Session.role(this) in listOf(Session.OWNER, Session.ACCOUNTANT) -> return open(MainActivity::class.java)
        }

        setContentView(R.layout.activity_start)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        status = findViewById(R.id.status)
        val code = findViewById<EditText>(R.id.code)
        val email = findViewById<EditText>(R.id.email)
        val password = findViewById<EditText>(R.id.password)
        val join = findViewById<Button>(R.id.join)
        val login = findViewById<Button>(R.id.login)
        val register = findViewById<Button>(R.id.register)
        buttons = listOf(join, login, register)

        // Drivers shouldn't see the owner login; the owner long-presses the logo to show it.
        val owner = findViewById<View>(R.id.owner)
        findViewById<View>(R.id.logo).setOnLongClickListener {
            owner.visibility = if (owner.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }

        join.setOnClickListener {
            val text = code.text.toString().uppercase().filter { it.isLetterOrDigit() }
            if (text.length != 8) return@setOnClickListener showStatus("Kód má 8 znakov – dostaneš ho od majiteľa.")
            busy("Pripájam…")
            Repo.linkPhone(text) { id, error ->
                if (id == null) return@linkPhone showStatus(error!!)
                Session.save(this, Session.DRIVER, id)
                open(DriverActivity::class.java)
            }
        }

        login.setOnClickListener {
            val (mail, pass) = credentials(email, password) ?: return@setOnClickListener
            busy("Prihlasujem…")
            auth.signInWithEmailAndPassword(mail, pass)
                .addOnSuccessListener {
                    Repo.checkRole { role ->
                        if (role != null) return@checkRole officeReady(role)
                        auth.signOut()
                        showStatus("Tento účet nemá prístup do kancelárie.")
                    }
                }
                .addOnFailureListener { showStatus(authError(it)) }
        }

        register.setOnClickListener {
            val (mail, pass) = credentials(email, password) ?: return@setOnClickListener
            busy("Vytváram účet majiteľa…")
            auth.createUserWithEmailAndPassword(mail, pass)
                .addOnSuccessListener {
                    Repo.claimOwnership { ok ->
                        if (ok) return@claimOwnership officeReady(Session.OWNER)
                        auth.currentUser?.delete()
                        auth.signOut()
                        showStatus("Majiteľ už existuje. Prihláste sa jeho e-mailom a heslom.")
                    }
                }
                .addOnFailureListener { showStatus(authError(it)) }
        }
    }

    private fun credentials(email: EditText, password: EditText): Pair<String, String>? {
        val mail = email.text.toString().trim()
        val pass = password.text.toString()
        if (mail.isEmpty() || pass.length < 6) {
            showStatus("Zadajte e-mail a heslo (aspoň 6 znakov).")
            return null
        }
        return mail to pass
    }

    private fun authError(e: Exception) = when (e) {
        is FirebaseAuthWeakPasswordException -> "Heslo je príliš slabé (aspoň 6 znakov)."
        is FirebaseAuthUserCollisionException -> "Účet s týmto e-mailom už existuje – prihláste sa."
        is FirebaseAuthInvalidCredentialsException -> "Nesprávny e-mail alebo heslo."
        else -> "Nepodarilo sa: ${e.message}"
    }

    private fun officeReady(role: String) {
        Session.save(this, role)
        open(MainActivity::class.java)
    }

    private fun busy(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
        buttons.forEach { it.isEnabled = false }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
        buttons.forEach { it.isEnabled = true }
    }

    private fun open(activity: Class<*>) {
        startActivity(Intent(this, activity))
        finish()
    }
}
