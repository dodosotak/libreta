package sk.libreta.app

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.YearMonth

private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

/** Outlined text field with a floating label (style from the theme); read it via [value]. */
fun textField(context: Context, label: String, inputType: Int = InputType.TYPE_CLASS_TEXT, text: String = ""): TextInputLayout =
    TextInputLayout(context).apply {
        hint = label
        addView(TextInputEditText(this.context).apply {
            this.inputType = inputType
            setText(text)
        })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = context.dp(8) }
    }

val TextInputLayout.value: String get() = editText?.text?.toString().orEmpty()

/** Outlined button with an icon, for picking a date, time, vehicle… in dialogs. */
fun pickerButton(context: Context, @DrawableRes icon: Int): MaterialButton =
    MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        setIconResource(icon)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = context.dp(4) }
    }

/** Vertical dialog body with the usual dialog padding. */
fun dialogForm(context: Context, vararg views: View): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(context.dp(24), context.dp(8), context.dp(24), 0)
    views.forEach { addView(it) }
}

/**
 * Fills R.layout.month_summary for one driver: hours, surcharge hours, absences and km.
 * [showPay] adds the pay – office only, drivers don't see money.
 */
fun bindMonthSummary(
    view: View,
    month: YearMonth,
    driver: Driver?,
    shifts: List<Shift>,
    absences: List<Absence> = emptyList(),
    showPay: Boolean = false,
    closed: Boolean = false,
) {
    val s = Report.summarize(month, driver ?: Driver(name = "", type = WorkerType.EMPLOYEE, hourlyRate = 0.0), shifts, absences)
    view.findViewById<TextView>(R.id.month).text =
        Fmt.month(month).replaceFirstChar { it.uppercase() } + if (closed) " 🔒" else ""
    view.findViewById<TextView>(R.id.total_hours).text = "${Fmt.hm(s.minutes)} h"
    view.findViewById<TextView>(R.id.totals).text = listOfNotNull(
        buildString {
            append(Fmt.count(s.days, "deň", "dni", "dní"))
            append(" · ").append(Fmt.count(shifts.count { it.end != null }, "smena", "smeny", "smien"))
            if (s.days > 0) append(" · priemer ${Fmt.hm(s.minutes / s.days)} h/deň")
        },
        surchargeLine(s.surcharges),
        s.absenceDays.filterValues { it > 0 }.entries.joinToString(" · ") { (type, days) ->
            "${type.label} ${Fmt.count(days, "deň", "dni", "dní")}"
        }.ifEmpty { null },
        if (s.km > 0) "Najazdené: ${s.km} km" else null,
        if (driver?.type == WorkerType.EMPLOYEE) "Denné smeny: ${Fmt.count(s.dayShifts, "deň", "dni", "dní")} · nočné: ${Fmt.hm(s.nightShiftMinutes)} h" else null,
        if (showPay && driver != null) "Suma: ${Fmt.money(s.amount)} € (${driver.rateText})" else null,
        if (showPay && driver?.rateMissing == true) "⚠ Chýba denná alebo nočná sadzba – zadajte ju pri vodičovi" else null,
    ).joinToString("\n")
    val warnings = listOfNotNull(
        if (shifts.any { it.end == null }) "⚠ Neukončená smena sa do súčtu nezapočítava" else null,
    )
    view.findViewById<TextView>(R.id.warnings).apply {
        text = warnings.joinToString("\n")
        visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE
    }
}

/** "Noc 4:15 · So 2:15 · Nadčas 1:00", only the non-zero ones; null if none. */
fun surchargeLine(s: Surcharges): String? = listOf(
    "Noc" to s.night, "So" to s.saturday, "Ne" to s.sunday, "Sviatok" to s.holiday, "Nadčas" to s.overtime,
).filter { it.second > 0 }.joinToString(" · ") { "${it.first} ${Fmt.hm(it.second)}" }.ifEmpty { null }

/** One shift in a list; [office] adds the details only the owner/accountant needs. */
fun bindShiftRow(row: RowHolder, shift: Shift, office: Boolean) {
    val end = shift.end
    row.title.text = "${shift.start.format(Fmt.DAY)}   ${shift.start.format(Fmt.TIME)} – ${end?.format(Fmt.TIME) ?: "…"}"
    row.subtitle.text = listOfNotNull(
        if (end == null) "prebieha" else null,
        if (end != null && end.toLocalDate() != shift.start.toLocalDate()) "do ${end.format(Fmt.DATE)}" else null,
        shift.vehicle.ifBlank { null }?.let { v -> shift.km?.let { "$v · $it km" } ?: v },
        if (office) surchargeLine(Payroll.clockSurcharges(shift)) else null,
        if (office) (if (Payroll.isNightShift(shift)) "nočná smena" else null) else null,
        shift.note.ifBlank { null },
        shift.enteredAt?.let { if (office) "✎ doplnil vodič ${it.format(Fmt.DATE)}" else "✎ doplnená dodatočne" },
        if (office && shift.loggedLate) "⚠ v telefóne zapísané o " + listOfNotNull(shift.startLoggedAt, shift.endLoggedAt)
            .joinToString(" / ") { it.format(Fmt.TIME) } else null,
    ).joinToString(" · ")
    row.value.text = if (end == null) "" else "${Fmt.hm(shift.workedMinutes)} h"
}

/** A vacation request that waits or was rejected says so and is greyed out – it doesn't count in the totals. */
fun bindAbsenceRow(row: RowHolder, absence: Absence, month: YearMonth) {
    row.title.text = "🏖 ${absence.type.label}" + if (absence.approved) "" else " · ${absence.status.label}"
    val range = if (absence.from == absence.to) absence.from.format(Fmt.DAY)
    else "${absence.from.format(Fmt.DAY)} – ${absence.to.format(Fmt.DAY)}"
    row.subtitle.text = listOfNotNull(range, absence.note.ifBlank { null }).joinToString(" · ")
    row.value.text = Fmt.count(absence.workingDaysIn(month), "deň", "dni", "dní")
    row.itemView.alpha = if (absence.approved) 1f else 0.6f
}

/** Shifts and absences of a month in one list, newest first. */
fun monthEntries(shifts: List<Shift>, absences: List<Absence>): List<Any> =
    (shifts.map { it.start.toLocalDate() to it } + absences.map { it.from to it }).sortedByDescending { it.first }.map { it.second }

/**
 * Uses the screen's own toolbar and keeps content out from under the system bars
 * (Android 15+ always draws apps edge-to-edge). Expects R.id.screen as root with an included R.layout.toolbar.
 */
fun AppCompatActivity.setupScreen(padBottom: Boolean) {
    setSupportActionBar(findViewById(R.id.toolbar))
    val appBar = findViewById<View>(R.id.appbar)
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.screen)) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(bars.left, 0, bars.right, if (padBottom) bars.bottom else 0)
        appBar.setPadding(0, bars.top, 0, 0)
        insets
    }
}
