package sk.libreta.app

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth

/**
 * PDFs for the office: the monthly working-time record (evidencia pracovného času) per driver with signature lines,
 * and the payroll basis (podklady k mzdám) for the accountant – the numbers she types into the payroll program.
 */
object PdfReport {

    class Sheet(val driver: Driver, val shifts: List<Shift>, val absences: List<Absence>)

    private const val A4_SHORT = 595
    private const val A4_LONG = 842
    private const val MARGIN = 36f
    private const val ROW = 15f

    // Table columns: title to x position
    private val COLUMNS = listOf(
        "Dátum" to 0f, "Začiatok" to 70f, "Koniec" to 120f, "Odprac." to 190f,
        "Noc" to 260f, "So/Ne/Sv" to 300f, "Vozidlo" to 360f, "Km" to 430f, "Poznámka" to 465f,
    )

    // Payroll basis, landscape: days / hours of the contract like on the payslip
    private val BASIS_COLUMNS = listOf(
        "Meno" to 0f, "Úväzok" to 115f, "Fond d / h" to 160f, "Odprac. d / h" to 230f, "Dovol. d / h" to 300f,
        "PN" to 362f, "OČR" to 390f, "N. voľno" to 420f, "Nepl." to 462f, "Noc h" to 495f, "So h" to 533f,
        "Ne h" to 566f, "Sviatok h" to 599f, "Nadčas h" to 645f, "Nočná h" to 690f, "Zost. dov." to 730f,
    )

    private val PART_TIME_COLUMNS = listOf(
        "Meno" to 0f, "Dni" to 160f, "Hodiny" to 200f, "Noc h" to 250f, "So h" to 295f, "Ne h" to 340f,
        "Sviatok h" to 385f, "Sadzba €/h" to 495f, "Suma €" to 635f,
    )

    /** One sheet (one or more pages) per driver in a single file under cache/exports. */
    fun create(context: Context, month: YearMonth, sheets: List<Sheet>, name: String): File {
        val doc = PdfDocument()
        var pageNo = 0
        for (sheet in sheets) {
            val writer = Writer(doc, A4_SHORT, A4_LONG, COLUMNS) { ++pageNo }
            drawSheet(writer, month, sheet)
            writer.finish()
        }
        return save(context, doc, name)
    }

    /**
     * Payroll basis for the whole month: employees (fund, worked, absences, surcharges, vacation left) and workers
     * on an agreement (hours × rate). [absences] may include requests – only approved ones count, waiting ones are
     * listed as a warning. [vacationLeft] is the balance at the end of the month per driver id.
     */
    fun createBasis(
        context: Context,
        month: YearMonth,
        summaries: List<DriverSummary>,
        shifts: List<Shift>,
        absences: List<Absence>,
        vacationLeft: Map<String, Double?>,
        name: String,
    ): File {
        val doc = PdfDocument()
        var pageNo = 0
        val employees = summaries.filter { it.driver.type == WorkerType.EMPLOYEE }
        val partTime = summaries.filter { it.driver.type == WorkerType.PART_TIME }
        val w = Writer(doc, A4_LONG, A4_SHORT, BASIS_COLUMNS) { ++pageNo }
        w.text("Podklady k mzdám – ${Fmt.month(month)}", w.title)
        w.text("Fond pracovného času: " + Fmt.count(Payroll.workingDays(month), "pracovný deň", "pracovné dni", "pracovných dní") +
            " (bez sobôt, nedieľ a sviatkov)", w.normal)

        if (employees.isNotEmpty()) {
            w.gap(8f)
            w.text(WorkerType.EMPLOYEE.plural, w.bold)
            w.tableHeader()
            for (s in employees) {
                val sur = s.surcharges
                w.row(
                    listOf(
                        s.driver.name,
                        "${Fmt.decimal(s.driver.weeklyHours)} h/t",
                        "${s.fundDays} / ${Fmt.hours(s.fundMinutes)}",
                        "${s.days} / ${Fmt.hours(s.minutes)}",
                        daysHours(s, AbsenceType.VACATION),
                        days(s, AbsenceType.SICK),
                        days(s, AbsenceType.CARE),
                        days(s, AbsenceType.COMP_OFF),
                        days(s, AbsenceType.UNPAID),
                        hours(sur.night),
                        hours(sur.saturday),
                        hours(sur.sunday),
                        hours(sur.holiday),
                        hours(sur.overtime),
                        hours(s.nightShiftMinutes),
                        vacationLeft[s.driver.id]?.let(Vacation::days) ?: "–",
                    )
                )
            }
            w.endTable()
        }

        if (partTime.isNotEmpty()) {
            w.gap(12f)
            w.text("${WorkerType.PART_TIME.plural} (dohody)", w.bold)
            w.tableHeader(PART_TIME_COLUMNS)
            for (s in partTime) {
                val sur = s.surcharges
                w.row(
                    listOf(
                        s.driver.name, s.days.toString(), Fmt.hours(s.minutes), hours(sur.night), hours(sur.saturday),
                        hours(sur.sunday), hours(sur.holiday), Fmt.money(s.driver.hourlyRate), Fmt.money(s.amount),
                    )
                )
            }
            w.endTable()
            w.text("Spolu ${Fmt.hours(partTime.sumOf { it.minutes })} h · ${Fmt.money(partTime.sumOf { it.amount })} €", w.bold)
        }

        val names = summaries.associate { it.driver.id to it.driver.name }
        val approved = absences.filter { it.approved && it.driverId in names }
            .sortedWith(compareBy({ names[it.driverId] }, { it.from }))
        if (approved.isNotEmpty()) {
            w.gap(12f)
            w.text("Neprítomnosti", w.bold)
            for (a in approved) {
                w.text("${names[a.driverId]} – ${a.type.label}: ${a.from.format(Fmt.DATE)} – ${a.to.format(Fmt.DATE)} " +
                    "(${Fmt.count(a.workingDaysIn(month), "pracovný deň", "pracovné dni", "pracovných dní")} v mesiaci)" +
                    a.note.ifBlank { null }?.let { " · $it" }.orEmpty(), w.normal)
            }
        }

        val warnings = listOfNotNull(
            absences.count { it.status == AbsenceStatus.PENDING && it.driverId in names }.takeIf { it > 0 }
                ?.let { "Žiadosti o dovolenku čakajú na schválenie ($it) – nie sú započítané." },
            shifts.count { it.end == null && it.driverId in names }.takeIf { it > 0 }
                ?.let { "Neukončené smeny ($it) nie sú započítané." },
            shifts.count { it.addedLater && it.end != null && it.driverId in names }.takeIf { it > 0 }
                ?.let { "Smeny doplnené vodičom dodatočne: $it – skontrolujte ich v aplikácii." },
        )
        if (warnings.isNotEmpty()) {
            w.gap(12f)
            w.text("Upozornenia", w.bold)
            warnings.forEach { w.text("⚠ $it", w.normal) }
        }

        w.gap(12f)
        w.text("Hodiny sú v desatinnom tvare. Fond = pracovné dni × úväzok / 5, dovolenka v hodinách = dni × úväzok / 5, " +
            "nadčas = práca nad 8 h v jednej smene, nočná = smeny začaté od 17:00 do 5:00 (vlastná sadzba firmy).", w.small)
        w.text("Zostatok dovolenky k ${month.atEndOfMonth().format(Fmt.DATE)} vrátane čerpania v tomto mesiaci. " +
            "Vytvorené v aplikácii Libreta ${LocalDate.now().format(Fmt.DATE)}", w.small)
        w.finish()
        return save(context, doc, name)
    }

    private fun days(s: DriverSummary, type: AbsenceType) = (s.absenceDays[type] ?: 0).let { if (it == 0) "" else it.toString() }

    private fun daysHours(s: DriverSummary, type: AbsenceType) =
        (s.absenceDays[type] ?: 0).let { if (it == 0) "" else "$it / ${Fmt.hours(s.absenceMinutes(type))}" }

    private fun hours(minutes: Long) = if (minutes == 0L) "" else Fmt.hours(minutes)

    private fun save(context: Context, doc: PdfDocument, name: String): File {
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, fileName(name))
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun share(context: Context, file: File, subject: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Odoslať / vytlačiť PDF"))
    }

    private fun drawSheet(w: Writer, month: YearMonth, sheet: Sheet) {
        val d = sheet.driver
        val summary = Report.summarize(month, d, sheet.shifts, sheet.absences)

        w.text("Evidencia pracovného času", w.title)
        w.text("${Fmt.month(month).replaceFirstChar { it.uppercase() }} · ${d.name} · ${d.type.label}", w.bold)
        w.gap(6f)
        w.tableHeader()
        for (shift in sheet.shifts.sortedBy { it.start }) {
            val s = Payroll.clockSurcharges(shift)
            val end = shift.end
            w.row(
                listOf(
                    shift.start.format(Fmt.DAY),
                    shift.start.format(Fmt.TIME),
                    end?.let { if (it.toLocalDate() != shift.start.toLocalDate()) "${it.format(Fmt.TIME)} +1" else it.format(Fmt.TIME) } ?: "…",
                    if (end == null) "" else Fmt.hm(shift.workedMinutes),
                    if (s.night > 0) Fmt.hm(s.night) else "",
                    listOfNotNull(
                        if (s.saturday > 0) "So" else null, if (s.sunday > 0) "Ne" else null, if (s.holiday > 0) "Sv" else null,
                    ).joinToString(" "),
                    shift.vehicle,
                    shift.km?.toString().orEmpty(),
                    listOfNotNull(
                        shift.note.ifBlank { null }, if (shift.addedLater) "doplnená" else null,
                    ).joinToString(", "),
                )
            )
        }
        w.endTable()
        if (sheet.shifts.isEmpty()) w.text("Žiadne smeny.", w.normal)

        val absences = sheet.absences.filter { it.approved }
        if (absences.isNotEmpty()) {
            w.gap(10f)
            w.text("Neprítomnosti", w.bold)
            for (a in absences.sortedBy { it.from }) {
                w.text("${a.type.label}: ${a.from.format(Fmt.DATE)} – ${a.to.format(Fmt.DATE)} " +
                    "(${Fmt.count(a.workingDaysIn(month), "pracovný deň", "pracovné dni", "pracovných dní")})" +
                    a.note.ifBlank { null }?.let { " · $it" }.orEmpty(), w.normal)
            }
        }

        w.gap(10f)
        w.text("Spolu: ${Fmt.hm(summary.minutes)} h · ${Fmt.count(summary.days, "deň", "dni", "dní")}", w.bold)
        surchargeLine(summary.surcharges)?.let { w.text(it, w.normal) }
        if (d.type == WorkerType.EMPLOYEE) {
            w.text("Denné smeny: ${Fmt.count(summary.dayShifts, "deň", "dni", "dní")} · nočné smeny: ${Fmt.hm(summary.nightShiftMinutes)} h", w.normal)
        }
        if (summary.km > 0) w.text("Najazdené: ${summary.km} km", w.normal)

        w.ensureSpace(80f)
        w.gap(50f)
        w.signatures()
        w.text("Vytvorené v aplikácii Libreta ${LocalDate.now().format(Fmt.DATE)}", w.small)
    }

    private fun fileName(name: String): String {
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return ascii.replace(Regex("[^A-Za-z0-9_-]+"), "_") + ".pdf"
    }

    /** Draws lines top to bottom and starts a new page when one is full. */
    private class Writer(
        private val doc: PdfDocument,
        private val width: Int,
        private val height: Int,
        private var columns: List<Pair<String, Float>>,
        private val nextPage: () -> Int,
    ) {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; typeface = Typeface.DEFAULT_BOLD }
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7f; color = 0xFF777777.toInt() }
        private val line = Paint().apply { strokeWidth = 0.5f; color = 0xFF999999.toInt() }

        private var page: PdfDocument.Page? = null
        private lateinit var canvas: Canvas
        private var y = 0f
        private var inTable = false

        private fun startPage() {
            page?.let { doc.finishPage(it) }
            val p = doc.startPage(PdfDocument.PageInfo.Builder(width, height, nextPage()).create())
            page = p
            canvas = p.canvas
            y = MARGIN
            if (inTable) tableHeader()
        }

        fun ensureSpace(height: Float) {
            if (page == null || y + height > this.height - MARGIN) startPage()
        }

        fun gap(height: Float) {
            ensureSpace(height)
            y += height
        }

        fun text(text: String, paint: Paint) {
            ensureSpace(paint.textSize + 6f)
            y += paint.textSize + 4f
            canvas.drawText(text, MARGIN, y, paint)
        }

        /** A later table can switch to other [columns]; rows use the columns of the last header. */
        fun tableHeader(columns: List<Pair<String, Float>> = this.columns) {
            this.columns = columns
            inTable = true
            ensureSpace(ROW * 2)
            y += ROW
            columns.forEach { (name, x) -> canvas.drawText(name, MARGIN + x, y, bold) }
            canvas.drawLine(MARGIN, y + 4f, width - MARGIN, y + 4f, line)
        }

        fun row(cells: List<String>) {
            ensureSpace(ROW)
            y += ROW
            cells.zip(columns).forEach { (text, column) ->
                val maxWidth = (columns.getOrNull(columns.indexOf(column) + 1)?.second ?: (width - 2 * MARGIN)) - column.second - 4f
                canvas.drawText(ellipsize(text, maxWidth), MARGIN + column.second, y, normal)
            }
        }

        /** Later pages no longer repeat the table header. */
        fun endTable() {
            inTable = false
        }

        fun signatures() {
            val half = (width - 2 * MARGIN) / 2
            canvas.drawLine(MARGIN, y, MARGIN + half - 30f, y, line)
            canvas.drawLine(MARGIN + half + 30f, y, width - MARGIN, y, line)
            canvas.drawText("Podpis zamestnanca", MARGIN, y + 12f, normal)
            canvas.drawText("Podpis zamestnávateľa", MARGIN + half + 30f, y + 12f, normal)
            y += 20f
        }

        fun finish() {
            page?.let { doc.finishPage(it) }
            page = null
        }

        private fun ellipsize(text: String, maxWidth: Float): String {
            if (normal.measureText(text) <= maxWidth) return text
            var t = text
            while (t.isNotEmpty() && normal.measureText("$t…") > maxWidth) t = t.dropLast(1)
            return "$t…"
        }
    }
}
