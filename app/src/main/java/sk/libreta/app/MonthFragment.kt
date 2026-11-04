package sk.libreta.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** Monthly totals per driver, payroll exports (CSV, PDF) and closing the month. */
class MonthFragment : Fragment(R.layout.fragment_month) {

    private var month: YearMonth = YearMonth.now()
    private var filter: WorkerType? = null
    private var drivers = emptyList<Driver>()
    private val shiftsByDriver = mutableMapOf<String, List<Shift>>()
    private val absencesByDriver = mutableMapOf<String, List<Absence>>()
    /** Absences since each employee's vacation balance date – for the balance at the end of the month. */
    private val vacationAbsences = mutableMapOf<String, List<Absence>>()
    private var summaries = emptyList<DriverSummary>()
    private var shifts = emptyList<Shift>()
    private var absences = emptyList<Absence>()
    private var closed = false
    private val listeners = Listeners()

    private val adapter = RowAdapter<DriverSummary> { row, s ->
        row.title.text = s.driver.name
        row.subtitle.text = listOfNotNull(
            "${s.driver.type.label} · ${Fmt.count(s.days, "deň", "dni", "dní")} · ${s.driver.rateText}",
            surchargeLine(s.surcharges),
            s.absenceDays.filterValues { it > 0 }.entries.joinToString(" · ") { "${it.key.label} ${it.value} d" }.ifEmpty { null },
        ).joinToString("\n")
        row.value.text = "${Fmt.hm(s.minutes)} h\n${Fmt.money(s.amount)} €"
        row.itemView.setOnClickListener { ShiftsActivity.open(requireContext(), s.driver.id, month) }
    }

    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        requireContext().contentResolver.openOutputStream(uri)?.use { it.write(csvBytes()) }
        Toast.makeText(requireContext(), "Výkaz uložený", Toast.LENGTH_SHORT).show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        savedInstanceState?.getString("month")?.let { month = YearMonth.parse(it) }
        view.findViewById<RecyclerView>(R.id.list).setup(adapter)
        view.findViewById<View>(R.id.prev).setOnClickListener { changeMonth(month.minusMonths(1)) }
        view.findViewById<View>(R.id.next).setOnClickListener { changeMonth(month.plusMonths(1)) }
        view.findViewById<MaterialButtonToggleGroup>(R.id.filter).addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            filter = when (id) {
                R.id.filter_employees -> WorkerType.EMPLOYEE
                R.id.filter_part_time -> WorkerType.PART_TIME
                else -> null
            }
            render()
        }
        view.findViewById<View>(R.id.export).setOnClickListener { showExportMenu() }
        view.findViewById<Button>(R.id.lock).apply {
            visibility = if (Session.canEdit(requireContext())) View.VISIBLE else View.GONE
            setOnClickListener { toggleClosed() }
        }
    }

    override fun onStart() {
        super.onStart()
        listeners += Repo.listenDrivers { all ->
            drivers = all
            syncMonthListeners()
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().title = "Mesačný prehľad"
    }

    override fun onStop() {
        super.onStop()
        listeners.clear()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("month", month.toString())
    }

    private fun changeMonth(newMonth: YearMonth) {
        month = newMonth
        listeners.clearKeyed()
        shiftsByDriver.clear()
        absencesByDriver.clear()
        vacationAbsences.clear()
        closed = false
        syncMonthListeners()
        render()
    }

    /** One shift and one absence listener per driver, the vacation balance of employees, plus the month's lock. */
    private fun syncMonthListeners() {
        val ids = drivers.map { it.id }.toSet()
        shiftsByDriver.keys.retainAll(ids)
        absencesByDriver.keys.retainAll(ids)
        vacationAbsences.keys.retainAll(drivers.filter { it.hasVacation }.map { it.id }.toSet())
        val keys = ids.flatMap { listOf("s:$it", "a:$it") }.toSet() + "closed" +
            drivers.filter { it.hasVacation }.map { "v:${it.id}:${Vacation.countedFrom(it, month.year)}" }
        listeners.sync(keys) { key ->
            val id = key.split(':').getOrElse(1) { "" }
            when {
                key == "closed" -> Repo.listenClosed(month) { closed = it; render() }
                key.startsWith("s:") -> Repo.listenShifts(id, month) { shiftsByDriver[id] = it; render() }
                key.startsWith("v:") -> Repo.listenAbsencesAfter(id, LocalDate.parse(key.substringAfterLast(':'))) {
                    vacationAbsences[id] = it
                    render()
                }
                else -> Repo.listenAbsences(id, month) { absencesByDriver[id] = it; render() }
            }
        }
    }

    /** Vacation days left at the end of the month per employee. */
    private fun vacationLeft(): Map<String, Double?> = summaries.associate { s ->
        s.driver.id to Vacation.left(s.driver, vacationAbsences[s.driver.id].orEmpty(), month.year, month.atEndOfMonth())
    }

    private fun render() {
        val view = view ?: return
        val monthShifts = shiftsByDriver.values.flatten()
        val monthAbsences = absencesByDriver.values.flatten()
        summaries = Report.summarize(month, drivers, monthShifts, monthAbsences).filter { filter == null || it.driver.type == filter }
        val ids = summaries.map { it.driver.id }.toSet()
        shifts = monthShifts.filter { it.driverId in ids }
        absences = monthAbsences.filter { it.driverId in ids }
        adapter.items = summaries

        view.findViewById<TextView>(R.id.month).text = Fmt.month(month).replaceFirstChar { it.uppercase() } + if (closed) " 🔒" else ""
        view.findViewById<TextView>(R.id.total_hours).text = totalLine(summaries)
        view.findViewById<TextView>(R.id.totals).text = listOfNotNull(
            if (filter != null) filter!!.plural else
                WorkerType.entries.joinToString("\n") { type -> "${type.plural}: ${totalLine(summaries.filter { it.driver.type == type })}" },
            surchargeLine(summaries.fold(Surcharges()) { acc, it -> acc + it.surcharges }),
            if (closed) "Mesiac je uzavretý – zmeny nie sú možné." else null,
        ).joinToString("\n")
        val warnings = listOfNotNull(
            absences.count { it.status == AbsenceStatus.PENDING }.takeIf { it > 0 }
                ?.let { "⚠ Žiadosti o dovolenku čakajú na schválenie ($it) – záložka Dovolenky" },
            shifts.count { it.end == null }.takeIf { it > 0 }?.let { "⚠ Neukončené smeny ($it) sa nezapočítavajú" },
            shifts.count { it.loggedLate }.takeIf { it > 0 }?.let { "⚠ Smeny zapísané s iným časom ako na serveri: $it" },
        )
        view.findViewById<TextView>(R.id.warnings).apply {
            text = warnings.joinToString("\n")
            visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<Button>(R.id.lock).text = if (closed) "Otvoriť mesiac" else "Uzavrieť mesiac"
    }

    private fun toggleClosed() {
        val message = if (closed) "Otvoriť ${Fmt.month(month)}? Smeny a neprítomnosti sa budú dať znova meniť."
        else "Uzavrieť ${Fmt.month(month)}? Nikto (ani šoféri) už v ňom nič nezmení, kým ho znova neotvoríte."
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(message)
            .setPositiveButton(if (closed) "Otvoriť" else "Uzavrieť") { _, _ -> Repo.setClosed(month, !closed) }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun showExportMenu() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Export – ${Fmt.month(month)}")
            .setItems(
                arrayOf("Podklady k mzdám (PDF pre účtovníčku)", "Uložiť CSV (Excel)", "Odoslať CSV (Excel)", "PDF výkazy na podpis (všetci)")
            ) { _, which ->
                when (which) {
                    0 -> shareBasis()
                    1 -> saveCsv.launch(fileName("csv"))
                    2 -> shareCsv()
                    else -> sharePdf()
                }
            }
            .show()
    }

    private fun totalLine(items: List<DriverSummary>) =
        "${Fmt.hm(items.sumOf { it.minutes })} h · ${Fmt.money(items.sumOf { it.amount })} €"

    /** UTF-8 with BOM so Excel shows Slovak diacritics correctly. */
    private fun csvBytes() = ("﻿" + Report.csv(month, summaries, shifts, absences, vacationLeft())).toByteArray(Charsets.UTF_8)

    private fun fileName(extension: String) = when (filter) {
        WorkerType.EMPLOYEE -> "vykaz_${month}_zamestnanci.$extension"
        WorkerType.PART_TIME -> "vykaz_${month}_brigadnici.$extension"
        null -> "vykaz_$month.$extension"
    }

    private fun shareCsv() {
        val context = requireContext()
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, fileName("csv"))
        file.writeBytes(csvBytes())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Výkaz hodín – ${Fmt.month(month)}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Odoslať výkaz"))
    }

    private fun shareBasis() {
        if (summaries.isEmpty()) {
            Toast.makeText(requireContext(), "V tomto mesiaci nie je čo vytlačiť.", Toast.LENGTH_SHORT).show()
            return
        }
        val name = fileName("pdf").removeSuffix(".pdf").replace("vykaz_", "podklady_mzdy_")
        val file = PdfReport.createBasis(requireContext(), month, summaries, shifts, absences, vacationLeft(), name)
        PdfReport.share(requireContext(), file, "Podklady k mzdám – ${Fmt.month(month)}")
    }

    private fun sharePdf() {
        val sheets = summaries.map { s ->
            PdfReport.Sheet(s.driver, shifts.filter { it.driverId == s.driver.id }, absences.filter { it.driverId == s.driver.id && it.approved })
        }.filter { it.shifts.isNotEmpty() || it.absences.isNotEmpty() }
        if (sheets.isEmpty()) {
            Toast.makeText(requireContext(), "V tomto mesiaci nie je čo vytlačiť.", Toast.LENGTH_SHORT).show()
            return
        }
        val file = PdfReport.create(requireContext(), month, sheets, fileName("pdf").removeSuffix(".pdf"))
        PdfReport.share(requireContext(), file, "Výkazy – ${Fmt.month(month)}")
    }
}
