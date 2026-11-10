package sk.libreta.app

import android.app.DatePickerDialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.time.LocalDate

/** The owner's list of vehicles drivers choose from when they start a shift, with their deadlines (STK, insurance…). */
class VehiclesActivity : AppCompatActivity() {

    private val listeners = Listeners()
    private lateinit var empty: TextView

    private val adapter = RowAdapter<Vehicle> { row, vehicle ->
        val today = LocalDate.now()
        val warnings = vehicle.warnings(today)
        row.title.text = vehicle.plate
        row.subtitle.text = listOfNotNull(
            listOf(vehicle.name, if (vehicle.active) "" else "vyradené").filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
            warnings.joinToString("\n") { (d, date) -> VehicleDeadline.text(d, date, today) }.ifBlank { null },
            VehicleDeadline.entries.filter { it in vehicle.deadlines && warnings.none { w -> w.first == it } }
                .joinToString(" · ") { "${it.label} do ${vehicle.deadlines.getValue(it).format(Fmt.DATE)}" }.ifBlank { null },
        ).joinToString("\n")
        row.value.text = if (warnings.any { it.second.isBefore(today) }) "⛔" else if (warnings.isNotEmpty()) "⚠" else ""
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
        // Deadlines: tap to pick the last valid day, "Nesledovať" clears it.
        val deadlines = existing?.deadlines.orEmpty().toMutableMap()
        val deadlineButtons = VehicleDeadline.entries.map { deadline ->
            pickerButton(this, R.drawable.ic_event).also { button ->
                fun show() {
                    button.text = "${deadline.label}: " + (deadlines[deadline]?.let { "platí do ${it.format(Fmt.DATE)}" } ?: "nesledované")
                }
                show()
                button.setOnClickListener {
                    val current = deadlines[deadline] ?: LocalDate.now().plusYears(1)
                    DatePickerDialog(this, { _, y, m, d -> deadlines[deadline] = LocalDate.of(y, m + 1, d); show() },
                        current.year, current.monthValue - 1, current.dayOfMonth).apply {
                        setTitle("${deadline.label} – platí do")
                        setButton(DialogInterface.BUTTON_NEUTRAL, "Nesledovať") { _, _ -> deadlines.remove(deadline); show() }
                    }.show()
                }
            }
        }
        val deadlinesTitle = TextView(this).apply {
            text = "Termíny – upozorníme ${VehicleDeadline.WARN_DAYS} dní vopred"
            setPadding(0, 32, 0, 4)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val active = MaterialCheckBox(this).apply {
            text = "V prevádzke"
            isChecked = existing?.active ?: true
            visibility = if (existing == null) View.GONE else View.VISIBLE
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Nové vozidlo" else "Upraviť vozidlo")
            .setIcon(R.drawable.ic_truck)
            .setView(android.widget.ScrollView(this).apply {
                addView(dialogForm(this@VehiclesActivity, plate, name, active, deadlinesTitle, *deadlineButtons.toTypedArray()))
            })
            .setPositiveButton("Uložiť", null)
            .setNegativeButton("Zrušiť", null)
        if (existing != null) builder.setNeutralButton("Odstrániť") { _, _ -> Repo.deleteVehicle(existing) }
        val dialog = builder.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val p = plate.value.uppercase().filter { !it.isWhitespace() }
            if (p.isEmpty()) {
                plate.error = "Zadajte ŠPZ"
                return@setOnClickListener
            }
            Repo.saveVehicle(Vehicle(existing?.id.orEmpty(), p, name.value.trim(), active.isChecked, deadlines.toMap()))
            dialog.dismiss()
        }
    }
}
