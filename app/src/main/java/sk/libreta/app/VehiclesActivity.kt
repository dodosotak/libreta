package sk.libreta.app

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton

/** The owner's list of vehicles drivers choose from when they start a shift. */
class VehiclesActivity : AppCompatActivity() {

    private val listeners = Listeners()
    private lateinit var empty: TextView

    private val adapter = RowAdapter<Vehicle> { row, vehicle ->
        row.title.text = vehicle.plate
        row.subtitle.text = listOf(vehicle.name, if (vehicle.active) "" else "vyradené").filter { it.isNotBlank() }.joinToString(" · ")
        row.value.text = ""
        row.itemView.alpha = if (vehicle.active) 1f else 0.5f
        row.itemView.setOnClickListener { showVehicleDialog(vehicle) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setupScreen(padBottom = true)
        title = "Vozidlá"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<RecyclerView>(R.id.list).setup(adapter)
        empty = findViewById(R.id.empty)
        empty.text = "Pridajte vozidlá tlačidlom +.\nŠoféri si ich vyberú pri začatí smeny."
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { showVehicleDialog(null) }
    }

    override fun onStart() {
        super.onStart()
        Session.showErrors(this)
        listeners += Repo.listenVehicles { vehicles ->
            adapter.items = vehicles
            empty.visibility = if (vehicles.isEmpty()) View.VISIBLE else View.GONE
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

    private fun showVehicleDialog(existing: Vehicle?) {
        val plate = textField(
            this, "ŠPZ (napr. BA123AB)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, existing?.plate.orEmpty(),
        )
        val name = textField(this, "Popis (napr. Scania R450)", text = existing?.name.orEmpty())
        val active = MaterialCheckBox(this).apply {
            text = "V prevádzke"
            isChecked = existing?.active ?: true
            visibility = if (existing == null) View.GONE else View.VISIBLE
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Nové vozidlo" else "Upraviť vozidlo")
            .setIcon(R.drawable.ic_truck)
            .setView(dialogForm(this, plate, name, active))
            .setPositiveButton("Uložiť", null)
            .setNegativeButton("Zrušiť", null)
        if (existing != null) builder.setNeutralButton("Odstrániť") { _, _ -> Repo.deleteVehicle(existing) }
        val dialog = builder.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val p = plate.value.uppercase().filter { !it.isWhitespace() }
            if (p.isEmpty()) {
                plate.error = "Zadajte ŠPZ"
                return@setOnClickListener
            }
            Repo.saveVehicle(Vehicle(existing?.id.orEmpty(), p, name.value.trim(), active.isChecked))
            dialog.dismiss()
        }
    }
}
