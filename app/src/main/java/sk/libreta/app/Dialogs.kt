package sk.libreta.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

fun showDriverDialog(context: Context, existing: Driver?) {
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_driver, null)
    val nameLayout = view.findViewById<TextInputLayout>(R.id.name_layout)
    val name = view.findViewById<EditText>(R.id.name)
    val phone = view.findViewById<EditText>(R.id.phone)
    val rate = view.findViewById<EditText>(R.id.rate)
    val rateLayout = view.findViewById<TextInputLayout>(R.id.rate_layout)
    val nightRateLayout = view.findViewById<TextInputLayout>(R.id.night_rate_layout)
    val nightRate = view.findViewById<EditText>(R.id.night_rate)
    val rateHint = view.findViewById<TextView>(R.id.rate_hint)
    val type = view.findViewById<RadioGroup>(R.id.type)
    val active = view.findViewById<CheckBox>(R.id.active)
    val employeeFields = view.findViewById<View>(R.id.employee_fields)
    val weeklyHours = view.findViewById<EditText>(R.id.weekly_hours)
    val vacationPerYear = view.findViewById<EditText>(R.id.vacation_per_year)
    val vacationBalance = view.findViewById<EditText>(R.id.vacation_balance)
    val asOfButton = view.findViewById<Button>(R.id.vacation_as_of)
    val defaults = existing ?: Driver(name = "", type = WorkerType.EMPLOYEE, hourlyRate = 0.0)
    var asOf: LocalDate = defaults.vacationAsOf ?: LocalDate.now().withDayOfMonth(1).minusDays(1)

    // The first rate field is the hourly rate of a part-time worker or the day rate of an employee.
    var employee = defaults.type == WorkerType.EMPLOYEE
    fun money(e: EditText) = e.text.toString().replace(',', '.').toDoubleOrNull()
    var hourly: Double = defaults.hourlyRate
    var day: Double? = defaults.dayRate

    fun showRates() {
        rate.setText((if (employee) day else hourly.takeIf { existing != null })?.let(Fmt::money).orEmpty())
        rateLayout.hint = if (employee) "Denná suma (fix)" else "Hodinová sadzba"
        rateLayout.suffixText = if (employee) "€" else "€/h"
        nightRateLayout.visibility = if (employee) View.VISIBLE else View.GONE
        rateHint.text = if (employee) {
            "Smena začatá od 5:00 do 17:00 je denná – pevná suma za deň, bez ohľadu na počet hodín. " +
                "Smena začatá od 17:00 do 5:00 je nočná – každá hodina nočnou sadzbou."
        } else {
            "Všetky hodiny brigádnika sa platia hodinovou sadzbou."
        }
    }

    fun refresh() {
        asOfButton.text = "k ${asOf.format(Fmt.DATE)}"
        val nowEmployee = type.checkedRadioButtonId == R.id.type_employee
        if (nowEmployee != employee) {
            if (employee) day = money(rate) else hourly = money(rate) ?: 0.0
            employee = nowEmployee
            showRates()
        }
        employeeFields.visibility = if (employee) View.VISIBLE else View.GONE
    }

    weeklyHours.setText(Fmt.decimal(defaults.weeklyHours))
    vacationPerYear.setText(defaults.vacationPerYear.toString())
    vacationBalance.setText(defaults.vacationBalance?.let(Fmt::decimal).orEmpty())
    if (existing != null) {
        name.setText(existing.name)
        phone.setText(existing.phone)
        nightRate.setText(existing.nightRate?.let(Fmt::money).orEmpty())
        type.check(if (existing.type == WorkerType.EMPLOYEE) R.id.type_employee else R.id.type_part_time)
        active.isChecked = existing.active
    } else {
        active.visibility = View.GONE
    }
    showRates()
    type.setOnCheckedChangeListener { _, _ -> refresh() }
    asOfButton.setOnClickListener {
        DatePickerDialog(context, { _, y, m, d -> asOf = LocalDate.of(y, m + 1, d); refresh() }, asOf.year, asOf.monthValue - 1, asOf.dayOfMonth).show()
    }
    refresh()

    val builder = MaterialAlertDialogBuilder(context)
        .setTitle(if (existing == null) "Nový vodič" else "Upraviť vodiča")
        .setIcon(R.drawable.ic_people)
        .setView(ScrollView(context).apply { addView(view) })
        .setPositiveButton("Uložiť", null)
        .setNegativeButton("Zrušiť", null)
    if (existing != null) {
        builder.setNeutralButton("Odstrániť") { _, _ ->
            Repo.deleteDriver(existing) { deleted ->
                if (!deleted) {
                    Toast.makeText(
                        context, "Vodič má zapísané smeny – namiesto odstránenia odškrtnite „Aktívny“.", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    val dialog = builder.show()
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val trimmed = name.text.toString().trim()
        if (trimmed.isEmpty()) {
            nameLayout.error = "Zadajte meno"
            return@setOnClickListener
        }
        val balance = vacationBalance.text.toString().replace(',', '.').toDoubleOrNull()
        if (employee) day = money(rate) else hourly = money(rate) ?: 0.0
        Repo.saveDriver(
            Driver(
                id = existing?.id.orEmpty(),
                name = trimmed,
                type = if (type.checkedRadioButtonId == R.id.type_part_time) WorkerType.PART_TIME else WorkerType.EMPLOYEE,
                hourlyRate = hourly,
                dayRate = day,
                nightRate = money(nightRate),
                active = existing == null || active.isChecked,
                phone = phone.text.toString().trim(),
                weeklyHours = weeklyHours.text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 60 } ?: 40.0,
                vacationPerYear = vacationPerYear.text.toString().toIntOrNull() ?: 20,
                vacationBalance = balance,
                vacationAsOf = if (balance != null) asOf else null,
            )
        )
        dialog.dismiss()
    }
}

/** The driver's own profile on their phone: name and type are read-only, the phone number is editable. */
fun showProfileDialog(context: Context, driver: Driver) {
    val phone = textField(context, "Telefónne číslo", InputType.TYPE_CLASS_PHONE, driver.phone)
    MaterialAlertDialogBuilder(context)
        .setTitle("Môj profil")
        .setIcon(R.drawable.ic_people)
        .setMessage("${driver.name} · ${driver.type.label}\n\nTvoje telefónne číslo uvidí majiteľ.")
        .setView(dialogForm(context, phone))
        .setPositiveButton("Uložiť") { _, _ -> Repo.savePhone(driver, phone.value.trim().take(30)) }
        .setNegativeButton("Zrušiť", null)
        .show()
}

/** The code a driver types into Libreta on their phone; the owner can send it or unlink a lost phone. */
fun showJoinCodeDialog(context: Context, driver: Driver) {
    val linked = driver.uid != null
    val code = TextView(context).apply {
        text = driver.joinCode.chunked(4).joinToString(" ")
        textSize = 32f
        letterSpacing = 0.1f
        typeface = android.graphics.Typeface.MONOSPACE
        gravity = android.view.Gravity.CENTER
        setTextIsSelectable(true)
    }
    val hint = TextView(context).apply {
        text = if (linked) "✓ Telefón vodiča je prepojený." else "Vodič si nainštaluje Libretu, zvolí „Som vodič“ a zadá tento kód."
        gravity = android.view.Gravity.CENTER
        setPadding(0, 16, 0, 0)
    }
    val builder = MaterialAlertDialogBuilder(context)
        .setTitle(driver.name)
        .setIcon(R.drawable.ic_key)
        .setView(dialogForm(context, code, hint))
        .setPositiveButton("Poslať kód") { _, _ ->
            val text = "Ahoj ${driver.name.substringBefore(' ')}, nainštaluj si aplikáciu Libreta, " +
                "zvoľ „Som vodič“ a zadaj kód: ${driver.joinCode}"
            context.startActivity(
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Poslať kód")
            )
        }
        .setNegativeButton("Zavrieť", null)
    if (linked) {
        builder.setNeutralButton("Odpojiť telefón") { _, _ ->
            MaterialAlertDialogBuilder(context)
                .setTitle("Odpojiť telefón?")
                .setMessage("${driver.name} bude musieť znova zadať kód (napr. na novom telefóne).")
                .setPositiveButton("Odpojiť") { _, _ -> Repo.unlinkPhone(driver) }
                .setNegativeButton("Zrušiť", null)
                .show()
        }
    }
    builder.show()
}

/** Lets the driver pick the vehicle and its odometer when starting a shift. */
fun showStartShiftDialog(context: Context, vehicles: List<Vehicle>, onStart: (vehicle: String, kmStart: Int?) -> Unit) {
    val active = vehicles.filter { it.active }
    if (active.isEmpty()) return onStart("", null)
    val labels = (active.map { it.label } + "Bez vozidla").toTypedArray()
    var chosen = 0
    val km = textField(context, "Stav tachometra (nepovinné)", InputType.TYPE_CLASS_NUMBER).apply { suffixText = "km" }
    MaterialAlertDialogBuilder(context)
        .setTitle("Ktorým vozidlom ideš?")
        .setIcon(R.drawable.ic_truck)
        .setSingleChoiceItems(labels, chosen) { _, which -> chosen = which }
        .setView(dialogForm(context, km))
        .setPositiveButton("Začať smenu") { _, _ ->
            onStart(active.getOrNull(chosen)?.plate.orEmpty(), km.value.toIntOrNull())
        }
        .setNegativeButton("Zrušiť", null)
        .show()
}

/** Ends a running shift; with a vehicle the driver also enters the km at the end. */
fun showEndShiftDialog(context: Context, title: String, shift: Shift, byDriver: Boolean) {
    val now = LocalDateTime.now()
    val kmField = textField(context, "Km na konci", InputType.TYPE_CLASS_NUMBER).apply {
        suffixText = "km"
        helperText = shift.kmStart?.let { "Na začiatku: $it km" }
    }
    val builder = MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setIcon(R.drawable.ic_stop)
        .setMessage("${shift.start.format(Fmt.TIME)} – ${now.format(Fmt.TIME)} · ${Fmt.hm(shift.minutesSoFar(now))} h")
        .setPositiveButton("Ukončiť", null)
        .setNegativeButton("Zrušiť", null)
    if (shift.vehicle.isNotEmpty()) builder.setView(dialogForm(context, kmField))
    val dialog = builder.show()
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val kmEnd = kmField.value.toIntOrNull()
        if (kmEnd != null && shift.kmStart != null && kmEnd < shift.kmStart) {
            kmField.error = "Menej ako na začiatku (${shift.kmStart})"
            return@setOnClickListener
        }
        Repo.endShift(shift, kmEnd, byDriver)
        dialog.dismiss()
    }
}

/**
 * The driver asks for a vacation on the phone. [left] is the balance after the approved vacations (null if unknown);
 * the owner sees the request in the Dovolenky tab.
 */
fun showVacationRequestDialog(context: Context, driverId: String, left: Double?) {
    var from = LocalDate.now().plusDays(1)
    var to = from
    val fromButton = pickerButton(context, R.drawable.ic_event)
    val toButton = pickerButton(context, R.drawable.ic_event)
    val note = textField(context, "Poznámka (nepovinné)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
    val info = TextView(context).apply {
        setPadding(0, 24, 0, 0)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    fun days() = Payroll.workingDays(from, to)
    fun refresh() {
        fromButton.text = "Od ${from.format(Fmt.DAY)} ${from.year}"
        toButton.text = "Do ${to.format(Fmt.DAY)} ${to.year}"
        info.text = when {
            to.isBefore(from) -> "Koniec je pred začiatkom"
            else -> "Pracovných dní: ${days()}" + (left?.let { "\nZostatok dovolenky: ${Vacation.days(it)} → po schválení ${Vacation.days(it - days())}" } ?: "")
        }
    }
    fromButton.setOnClickListener {
        DatePickerDialog(context, { _, y, m, d ->
            from = LocalDate.of(y, m + 1, d)
            if (to.isBefore(from)) to = from
            refresh()
        }, from.year, from.monthValue - 1, from.dayOfMonth).show()
    }
    toButton.setOnClickListener {
        DatePickerDialog(context, { _, y, m, d -> to = LocalDate.of(y, m + 1, d); refresh() }, to.year, to.monthValue - 1, to.dayOfMonth).show()
    }
    refresh()
    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle("Žiadosť o dovolenku")
        .setIcon(R.drawable.ic_beach)
        .setMessage("Žiadosť pošleš majiteľovi na schválenie.")
        .setView(dialogForm(context, fromButton, toButton, note, info))
        .setPositiveButton("Poslať žiadosť", null)
        .setNegativeButton("Zrušiť", null)
        .show()
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        if (to.isBefore(from)) return@setOnClickListener
        if (days() == 0) {
            info.text = "V zvolenom období nie je žiadny pracovný deň."
            return@setOnClickListener
        }
        Repo.requestVacation(driverId, from, to, note.value.trim().take(200))
        Toast.makeText(context, "Žiadosť odoslaná", Toast.LENGTH_SHORT).show()
        dialog.dismiss()
    }
}

/** The driver withdraws a waiting request or clears away a rejected one. */
fun showOwnRequestDialog(context: Context, absence: Absence) {
    val waiting = absence.status == AbsenceStatus.PENDING
    MaterialAlertDialogBuilder(context)
        .setTitle(if (waiting) "Zrušiť žiadosť?" else "Žiadosť bola zamietnutá")
        .setIcon(R.drawable.ic_beach)
        .setMessage("${absence.type.label} ${dateRange(absence)} · ${Fmt.count(absence.workingDays, "pracovný deň", "pracovné dni", "pracovných dní")}")
        .setPositiveButton(if (waiting) "Zrušiť žiadosť" else "Odstrániť zo zoznamu") { _, _ -> Repo.deleteAbsence(absence) }
        .setNegativeButton("Späť", null)
        .show()
}

/** The owner approves or rejects a driver's vacation request; [left] is the driver's balance before it. */
fun showRequestDecisionDialog(context: Context, absence: Absence, driverName: String, left: Double?) {
    val days = absence.workingDays
    val message = listOfNotNull(
        "${dateRange(absence)} · ${Fmt.count(days, "pracovný deň", "pracovné dni", "pracovných dní")}",
        absence.note.ifBlank { null }?.let { "„$it“" },
        left?.let { "Zostatok dovolenky: ${Vacation.days(it)} → po schválení ${Vacation.days(it - days)}" },
    ).joinToString("\n")
    MaterialAlertDialogBuilder(context)
        .setTitle("$driverName – ${absence.type.label.lowercase()}")
        .setIcon(R.drawable.ic_beach)
        .setMessage(message)
        .setPositiveButton("Schváliť") { _, _ -> Repo.decideRequest(absence, approve = true) }
        .setNegativeButton("Zamietnuť") { _, _ ->
            MaterialAlertDialogBuilder(context)
                .setMessage("Zamietnuť žiadosť? $driverName to uvidí v aplikácii.")
                .setPositiveButton("Zamietnuť") { _, _ -> Repo.decideRequest(absence, approve = false) }
                .setNegativeButton("Späť", null)
                .show()
        }
        .setNeutralButton("Neskôr", null)
        .show()
}

fun dateRange(absence: Absence): String =
    if (absence.from == absence.to) absence.from.format(Fmt.DATE) else "${absence.from.format(Fmt.DATE)} – ${absence.to.format(Fmt.DATE)}"

/** The owner records a vacation, sick leave etc. for a range of days. */
fun showAbsenceDialog(context: Context, driverId: String, existing: Absence?, defaultDate: LocalDate) {
    var type = existing?.type ?: AbsenceType.VACATION
    var from = existing?.from ?: defaultDate
    var to = existing?.to ?: defaultDate
    val typeButton = pickerButton(context, R.drawable.ic_label)
    val fromButton = pickerButton(context, R.drawable.ic_event)
    val toButton = pickerButton(context, R.drawable.ic_event)
    val note = textField(context, "Poznámka", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, existing?.note.orEmpty())
    val info = TextView(context).apply {
        setPadding(0, 24, 0, 0)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    fun refresh() {
        typeButton.text = type.label
        fromButton.text = "Od ${from.format(Fmt.DAY)} ${from.year}"
        toButton.text = "Do ${to.format(Fmt.DAY)} ${to.year}"
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.count { Payroll.isWorkingDay(it) }
        info.text = if (to.isBefore(from)) "Koniec je pred začiatkom" else "Pracovných dní: $days"
    }
    typeButton.setOnClickListener {
        MaterialAlertDialogBuilder(context)
            .setTitle("Druh neprítomnosti")
            .setItems(AbsenceType.entries.map { it.label }.toTypedArray()) { _, which -> type = AbsenceType.entries[which]; refresh() }
            .show()
    }
    fromButton.setOnClickListener {
        DatePickerDialog(context, { _, y, m, d ->
            from = LocalDate.of(y, m + 1, d)
            if (to.isBefore(from)) to = from
            refresh()
        }, from.year, from.monthValue - 1, from.dayOfMonth).show()
    }
    toButton.setOnClickListener {
        DatePickerDialog(context, { _, y, m, d -> to = LocalDate.of(y, m + 1, d); refresh() }, to.year, to.monthValue - 1, to.dayOfMonth).show()
    }
    refresh()
    val builder = MaterialAlertDialogBuilder(context)
        .setTitle(if (existing == null) "Neprítomnosť" else "Upraviť neprítomnosť")
        .setIcon(R.drawable.ic_event_busy)
        .setView(dialogForm(context, typeButton, fromButton, toButton, note, info))
        .setPositiveButton("Uložiť", null)
        .setNegativeButton("Zrušiť", null)
    if (existing != null) builder.setNeutralButton("Vymazať") { _, _ -> Repo.deleteAbsence(existing) }
    val dialog = builder.show()
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        if (to.isBefore(from)) return@setOnClickListener
        val status = existing?.status ?: AbsenceStatus.APPROVED
        Repo.saveAbsence(Absence(existing?.id.orEmpty(), driverId, type, from, to, note.value.trim(), status))
        dialog.dismiss()
    }
}

/**
 * Manual entry or correction of a shift. An end time earlier than the start means the shift ended the next day.
 * [byDriver]: the driver adds a past shift they forgot to start – only new ones, not in the future, one per day.
 */
fun showShiftDialog(
    context: Context,
    driverId: String,
    existing: Shift?,
    defaultDate: LocalDate,
    vehicles: List<Vehicle>,
    byDriver: Boolean = false,
) {
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_shift, null)
    val dateButton = view.findViewById<Button>(R.id.date)
    val startButton = view.findViewById<Button>(R.id.start)
    val endButton = view.findViewById<Button>(R.id.end)
    val noteInput = view.findViewById<EditText>(R.id.note)
    val info = view.findViewById<TextView>(R.id.info)
    val vehicleButton = view.findViewById<Button>(R.id.vehicle)
    val kmStartInput = view.findViewById<EditText>(R.id.km_start)
    val kmEndInput = view.findViewById<EditText>(R.id.km_end)
    var vehicle = existing?.vehicle.orEmpty()
    kmStartInput.setText(existing?.kmStart?.toString().orEmpty())
    kmEndInput.setText(existing?.kmEnd?.toString().orEmpty())

    var date = existing?.start?.toLocalDate() ?: defaultDate
    var start: LocalTime = existing?.start?.toLocalTime() ?: LocalTime.of(6, 0)
    var end: LocalTime? = if (existing == null) LocalTime.of(14, 0) else existing.end?.toLocalTime()
    noteInput.setText(existing?.note.orEmpty())

    fun build(): Shift {
        val startAt = date.atTime(start)
        val endAt: LocalDateTime? = end?.let { e -> date.atTime(e).let { if (it.isBefore(startAt)) it.plusDays(1) else it } }
        return Shift(
            id = existing?.id.orEmpty(),
            driverId = driverId,
            start = startAt,
            end = endAt,
            note = noteInput.text.toString().trim(),
            startLoggedAt = existing?.startLoggedAt,
            endLoggedAt = existing?.endLoggedAt,
            vehicle = vehicle,
            kmStart = kmStartInput.text.toString().toIntOrNull(),
            kmEnd = kmEndInput.text.toString().toIntOrNull(),
        )
    }

    fun refresh() {
        vehicleButton.text = vehicle.ifEmpty { "Bez vozidla" }
        dateButton.text = "${date.format(Fmt.DAY)} ${date.year}"
        startButton.text = "Od ${start.format(Fmt.TIME)}"
        endButton.text = "Do ${end?.format(Fmt.TIME) ?: "–"}"
        val shift = build()
        info.text = when (val e = shift.end) {
            null -> "Smena ešte prebieha"
            else -> "Odpracované: ${Fmt.hm(shift.workedMinutes)} h" +
                if (e.toLocalDate() != date) " (koniec nasledujúci deň)" else ""
        }
    }

    dateButton.setOnClickListener {
        val picker = DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d); refresh() }, date.year, date.monthValue - 1, date.dayOfMonth)
        if (byDriver) picker.datePicker.maxDate = System.currentTimeMillis()
        picker.show()
    }
    startButton.setOnClickListener {
        TimePickerDialog(context, { _, h, min -> start = LocalTime.of(h, min); refresh() }, start.hour, start.minute, true).show()
    }
    endButton.setOnClickListener {
        val initial = end ?: LocalTime.now()
        TimePickerDialog(context, { _, h, min -> end = LocalTime.of(h, min); refresh() }, initial.hour, initial.minute, true).show()
    }
    vehicleButton.setOnClickListener {
        val plates = vehicles.filter { it.active || it.plate == vehicle }.map { it.plate }
        MaterialAlertDialogBuilder(context)
            .setTitle("Vozidlo")
            .setItems((plates + "Bez vozidla").toTypedArray()) { _, which -> vehicle = plates.getOrElse(which) { "" }; refresh() }
            .show()
    }
    refresh()

    val builder = MaterialAlertDialogBuilder(context)
        .setTitle(if (byDriver) "Doplniť smenu" else if (existing == null) "Nová smena" else "Upraviť smenu")
        .setIcon(R.drawable.ic_today)
        .setView(view)
        .setMessage(if (byDriver) "Smena, ktorú si zabudol začať v aplikácii. Majiteľ uvidí, že je doplnená dodatočne." else null)
        .setPositiveButton("Uložiť", null)
        .setNegativeButton("Zrušiť", null)
    if (existing != null && !byDriver) {
        builder.setNeutralButton("Vymazať") { _, _ ->
            MaterialAlertDialogBuilder(context)
                .setTitle("Vymazať smenu?")
                .setMessage("${existing.start.format(Fmt.DAY)} ${existing.start.format(Fmt.TIME)} – ${existing.end?.format(Fmt.TIME) ?: "…"}")
                .setPositiveButton("Vymazať") { _, _ -> Repo.deleteShift(existing) }
                .setNegativeButton("Zrušiť", null)
                .show()
        }
    }
    val dialog = builder.show()
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val shift = build()
        if (shift.end != null && shift.workedMinutes <= 0) {
            info.text = "Skontrolujte časy – odpracovaný čas musí byť väčší ako 0"
            return@setOnClickListener
        }
        if (shift.kmStart != null && shift.kmEnd != null && shift.kmEnd < shift.kmStart) {
            kmEndInput.error = "Menej ako na začiatku"
            return@setOnClickListener
        }
        if (!byDriver) {
            Repo.saveShift(shift, existing)
            dialog.dismiss()
            return@setOnClickListener
        }
        val end = shift.end
        if (end == null || end.isAfter(LocalDateTime.now())) {
            info.text = "Smena ešte neskončila – doplniť sa dá len odpracovaná smena. Dnešnú začni tlačidlom Začať smenu."
            return@setOnClickListener
        }
        Repo.addPastShift(shift) { error ->
            if (error != null) {
                info.text = error
            } else {
                Toast.makeText(context, "Smena doplnená", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
    }
}
