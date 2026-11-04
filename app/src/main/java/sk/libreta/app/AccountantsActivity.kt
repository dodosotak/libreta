package sk.libreta.app

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton

/** The owner gives the accountant a read-only login: sees all drivers, shifts and exports, changes nothing. */
class AccountantsActivity : AppCompatActivity() {

    private val listeners = Listeners()
    private lateinit var empty: TextView

    private val adapter = RowAdapter<Pair<String, String>> { row, (uid, email) ->
        row.title.text = email
        row.subtitle.text = "len na čítanie · prehľady a export"
        row.value.text = ""
        row.itemView.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage("Odobrať prístup účtu $email?")
                .setPositiveButton("Odobrať") { _, _ -> Repo.removeAccountant(uid) }
                .setNegativeButton("Zrušiť", null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setupScreen(padBottom = true)
        title = "Účtovníčka"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<RecyclerView>(R.id.list).setup(adapter)
        empty = findViewById(R.id.empty)
        empty.text = "Pridajte účtovníčke prístup tlačidlom +.\nUvidí všetko, ale nič nezmení."
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { showAddDialog() }
    }

    override fun onStart() {
        super.onStart()
        Session.showErrors(this)
        listeners += Repo.listenAccountants { accountants ->
            adapter.items = accountants
            empty.visibility = if (accountants.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onStop() {
        super.onStop()
        listeners.clear()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showAddDialog() {
        val email = textField(this, "E-mail účtovníčky", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = textField(this, "Heslo", InputType.TYPE_CLASS_TEXT).apply { helperText = "Aspoň 6 znakov" }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Prístup pre účtovníčku")
            .setIcon(R.drawable.ic_key)
            .setMessage("Vytvorí sa jej prihlasovací účet. E-mail a heslo jej odovzdajte; v Librete podrží logo a prihlási sa.")
            .setView(dialogForm(this, email, password))
            .setPositiveButton("Vytvoriť", null)
            .setNegativeButton("Zrušiť", null)
            .show()
        val create = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        create.setOnClickListener {
            val mail = email.value.trim()
            val pass = password.value
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches()) return@setOnClickListener run { email.error = "Neplatný e-mail" }
            if (pass.length < 6) return@setOnClickListener run { password.error = "Aspoň 6 znakov" }
            create.isEnabled = false
            Repo.addAccountant(this, mail, pass) { error ->
                if (error == null) {
                    dialog.dismiss()
                    Toast.makeText(this, "Prístup vytvorený pre $mail", Toast.LENGTH_LONG).show()
                } else {
                    create.isEnabled = true
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
