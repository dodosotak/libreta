package sk.libreta.app

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.time.LocalDate

/** Colours the status: free = default, on the way = blue, delivered = green and greyed out. */
fun bindDeliveryRow(row: RowHolder, delivery: Delivery, extra: String? = null) {
    row.title.text = delivery.title
    row.subtitle.text = listOfNotNull(
        extra,
        delivery.place.ifBlank { null },
        delivery.note.ifBlank { null },
        delivery.statusText,
    ).joinToString(" · ")
    row.value.text = ""
    row.itemView.alpha = if (delivery.status == DeliveryStatus.DONE) 0.55f else 1f
}

/** "5 zásielok · 2 voľné · 1 sa vezie · 2 doručené" */
fun deliveryCounts(deliveries: List<Delivery>): String {
    if (deliveries.isEmpty()) return ""
    val by = deliveries.groupingBy { it.status }.eachCount()
    return listOfNotNull(
        Fmt.count(deliveries.size, "zásielka", "zásielky", "zásielok"),
        by[DeliveryStatus.OPEN]?.let { "voľné $it" },
        by[DeliveryStatus.IN_PROGRESS]?.let { "vezú sa $it" },
        by[DeliveryStatus.DONE]?.let { "doručené $it" },
    ).joinToString(" · ")
}

/** Opens the place in the phone's navigation (Google Maps, Waze…). */
fun navigateTo(context: Context, place: String) {
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
    try {
        context.startActivity(geo)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(place)))
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "V telefóne nie je aplikácia na navigáciu.", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * The owner adds a delivery for [date] or edits [existing]. "Uložiť a ďalšia" keeps the dialog open for the next one,
 * so a whole morning's list goes in quickly. For an existing delivery the owner can also correct its status.
 */
fun showDeliveryDialog(context: Context, date: LocalDate, existing: Delivery?) {
    val title = textField(
        context, "Zásielka – obchody (napr. Sinsay, Cropp, Town)",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, existing?.title.orEmpty(),
    )
    val place = textField(
        context, "Kam – miesto alebo adresa (napr. OC Max Trnava)",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, existing?.place.orEmpty(),
    )
    val note = textField(context, "Poznámka (nepovinné)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, existing?.note.orEmpty())
    val status = RadioGroup(context).apply {
        DeliveryStatus.entries.forEach { s ->
            addView(RadioButton(context).apply {
                id = View.generateViewId()
                tag = s
                text = when (s) {
                    DeliveryStatus.OPEN -> "Voľná (nikto ju nemá)"
                    DeliveryStatus.IN_PROGRESS -> "Vezie ${existing?.driverName.orEmpty()}"
                    DeliveryStatus.DONE -> "Doručená"
                }
                // "On the way" needs a driver – only kept, never set by the owner.
                isEnabled = s != DeliveryStatus.IN_PROGRESS || existing?.driverId != null
                isChecked = s == existing?.status
            })
        }
        visibility = if (existing == null) View.GONE else View.VISIBLE
    }
    val form = dialogForm(context, title, place, note, status)
    val builder = MaterialAlertDialogBuilder(context)
        .setTitle(if (existing == null) "Nová zásielka – ${date.format(Fmt.DAY)}" else "Upraviť zásielku")
        .setIcon(R.drawable.ic_package)
        .setView(form)
        .setPositiveButton("Uložiť", null)
        .setNegativeButton("Zrušiť", null)
    if (existing == null) {
        builder.setNeutralButton("Uložiť a ďalšia", null)
    } else {
        builder.setNeutralButton("Odstrániť") { _, _ ->
            MaterialAlertDialogBuilder(context)
                .setMessage("Odstrániť zásielku ${existing.title}?")
                .setPositiveButton("Odstrániť") { _, _ -> Repo.deleteDelivery(existing) }
                .setNegativeButton("Zrušiť", null)
                .show()
        }
    }
    val dialog = builder.show()

    fun save(): Boolean {
        val t = title.value.trim()
        if (t.isEmpty()) {
            title.error = "Zadajte, čo sa vezie"
            return false
        }
        title.error = null
        Repo.saveDelivery(
            (existing ?: Delivery(date = date, title = t)).copy(title = t.take(200), place = place.value.trim().take(200), note = note.value.trim().take(200))
        )
        val chosen = status.findViewById<RadioButton>(status.checkedRadioButtonId)?.tag as? DeliveryStatus
        if (existing != null && chosen != null && chosen != existing.status) Repo.setDeliveryStatus(existing, chosen)
        return true
    }
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { if (save()) dialog.dismiss() }
    if (existing == null) {
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            if (!save()) return@setOnClickListener
            Toast.makeText(context, "Pridané: ${title.value.trim()}", Toast.LENGTH_SHORT).show()
            // The place often stays the same (several shops in one mall), so only the shops are cleared.
            title.editText?.setText("")
            note.editText?.setText("")
            title.requestFocus()
        }
    }
}

/** The owner's tab: deliveries of a day; the accountant sees them read-only. */
class DeliveriesFragment : Fragment(R.layout.fragment_deliveries) {

    private var date: LocalDate = LocalDate.now()
    private val listeners = Listeners()
    private lateinit var empty: TextView
    private lateinit var day: TextView
    private lateinit var counts: TextView

    private val adapter = RowAdapter<Delivery> { row, delivery ->
        bindDeliveryRow(row, delivery)
        if (Session.canEdit(requireContext())) row.itemView.setOnClickListener { showDeliveryDialog(requireContext(), date, delivery) }
        else if (delivery.place.isNotBlank()) row.itemView.setOnClickListener { navigateTo(requireContext(), delivery.place) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        savedInstanceState?.getString(KEY_DATE)?.let { date = LocalDate.parse(it) }
        view.findViewById<RecyclerView>(R.id.list).setup(adapter)
        empty = view.findViewById(R.id.empty)
        day = view.findViewById(R.id.day)
        counts = view.findViewById(R.id.counts)
        view.findViewById<View>(R.id.prev).setOnClickListener { showDay(date.minusDays(1)) }
        view.findViewById<View>(R.id.next).setOnClickListener { showDay(date.plusDays(1)) }
        day.setOnClickListener {
            DatePickerDialog(requireContext(), { _, y, m, d -> showDay(LocalDate.of(y, m + 1, d)) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        val fab = view.findViewById<FloatingActionButton>(R.id.fab)
        fab.visibility = if (Session.canEdit(requireContext())) View.VISIBLE else View.GONE
        fab.setOnClickListener { showDeliveryDialog(requireContext(), date, null) }
        val copy = view.findViewById<View>(R.id.copy)
        copy.visibility = if (Session.canEdit(requireContext())) View.VISIBLE else View.GONE
        copy.setOnClickListener { pickCopySource() }
    }

    /** Which day to copy from – the day before the shown one is offered first. */
    private fun pickCopySource() {
        val target = date
        val source = target.minusDays(1)
        DatePickerDialog(requireContext(), { _, y, m, d -> chooseToCopy(LocalDate.of(y, m + 1, d), target) },
            source.year, source.monthValue - 1, source.dayOfMonth).apply {
            setTitle("Skopírovať zásielky z dňa…")
        }.show()
    }

    /**
     * Lists [source]'s deliveries to copy to [target] (all ticked, except ones already there with the same shops and place),
     * and copies the chosen ones as new free deliveries.
     */
    private fun chooseToCopy(source: LocalDate, target: LocalDate) {
        if (source == target) {
            Toast.makeText(requireContext(), "Vyberte iný deň, než na ktorý kopírujete.", Toast.LENGTH_SHORT).show()
            return
        }
        Repo.getDeliveries(source) { list ->
            val context = context ?: return@getDeliveries
            if (list == null) return@getDeliveries
            if (list.isEmpty()) {
                MaterialAlertDialogBuilder(context)
                    .setMessage("${source.format(Fmt.DAY)} nie sú žiadne zásielky.")
                    .setPositiveButton("Vybrať iný deň") { _, _ -> pickCopySource() }
                    .setNegativeButton("Zrušiť", null)
                    .show()
                return@getDeliveries
            }
            val present = adapter.items.map { it.title.trim().lowercase() to it.place.trim().lowercase() }.toSet()
            val already = list.map { (it.title.trim().lowercase() to it.place.trim().lowercase()) in present }
            val checked = already.map { !it }.toBooleanArray()
            val labels = list.mapIndexed { i, d ->
                listOfNotNull(d.title, d.place.ifBlank { null }?.let { "→ $it" }, if (already[i]) "(už je)" else null).joinToString(" ")
            }.toTypedArray()
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle("Z ${source.format(Fmt.DAY)} na ${target.format(Fmt.DAY)}")
                .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
                .setPositiveButton("Skopírovať", null)
                .setNegativeButton("Zrušiť", null)
                .show()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val chosen = list.filterIndexed { i, _ -> checked[i] }
                if (chosen.isEmpty()) return@setOnClickListener
                Repo.copyDeliveries(chosen, target)
                Toast.makeText(context, "Skopírované: ${Fmt.count(chosen.size, "zásielka", "zásielky", "zásielok")}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DATE, date.toString())
    }

    override fun onStart() {
        super.onStart()
        showDay(date)
    }

    override fun onResume() {
        super.onResume()
        requireActivity().title = "Zásielky"
    }

    override fun onStop() {
        super.onStop()
        listeners.clear()
    }

    private fun showDay(newDate: LocalDate) {
        date = newDate
        val today = LocalDate.now()
        day.text = when (date) {
            today -> "Dnes, ${date.format(Fmt.DAY)}"
            today.plusDays(1) -> "Zajtra, ${date.format(Fmt.DAY)}"
            today.minusDays(1) -> "Včera, ${date.format(Fmt.DAY)}"
            else -> date.format(Fmt.DAY)
        }
        adapter.items = emptyList()
        render()
        listeners["day"] = Repo.listenDeliveries(date) {
            adapter.items = it
            render()
        }
    }

    private fun render() {
        counts.text = deliveryCounts(adapter.items)
        counts.visibility = if (adapter.items.isEmpty()) View.GONE else View.VISIBLE
        empty.text = if (Session.canEdit(requireContext())) {
            "Na tento deň nie sú žiadne zásielky.\nPridajte ich tlačidlom + – vodiči si ich potom rozoberú."
        } else {
            "Na tento deň nie sú žiadne zásielky."
        }
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private companion object {
        const val KEY_DATE = "date"
    }
}

/**
 * The driver's deliveries: the work day's list shared by all drivers plus deliveries left over from earlier days.
 * Take a free one ("Beriem"), mark it delivered ("Doručené"), or give it back.
 */
class DeliveriesActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var driverId: String
    private lateinit var workDay: LocalDate
    private var driver: Driver? = null
    private var dayList = emptyList<Delivery>()
    private var unfinished = emptyList<Delivery>()
    private val listeners = Listeners()
    private lateinit var empty: TextView

    private val adapter = RowAdapter<Delivery> { row, delivery ->
        val mine = delivery.driverId == driverId
        bindDeliveryRow(row, delivery, extra = if (delivery.date != workDay) "z ${delivery.date.format(Fmt.DAY)}" else null)
        if (mine && delivery.status == DeliveryStatus.IN_PROGRESS) {
            row.title.setTextColor(ContextCompat.getColor(this, R.color.on_shift))
        } else {
            row.title.setTextColor(com.google.android.material.color.MaterialColors.getColor(row.title, android.R.attr.textColorPrimary))
        }
        when {
            delivery.status == DeliveryStatus.OPEN && driver?.active == true -> {
                row.action.visibility = View.VISIBLE
                row.action.text = "Beriem"
                row.action.setOnClickListener { take(delivery) }
            }
            mine && delivery.status == DeliveryStatus.IN_PROGRESS -> {
                row.action.visibility = View.VISIBLE
                row.action.text = "Doručené"
                row.action.setOnClickListener { markDone(delivery) }
            }
        }
        row.itemView.setOnClickListener { showDetail(delivery) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driverId = Session.driverId(this) ?: return finish()
        workDay = intent.getStringExtra(EXTRA_DAY)?.let(LocalDate::parse) ?: LocalDate.now()
        setContentView(R.layout.activity_list)
        setupScreen(padBottom = true)
        title = "Zásielky – ${workDay.format(Fmt.DAY)}"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<RecyclerView>(R.id.list).setup(adapter)
        findViewById<View>(R.id.fab).visibility = View.GONE
        empty = findViewById(R.id.empty)
        empty.text = "Na dnes zatiaľ nie sú žiadne zásielky.\nMajiteľ ich zadá pred smenou."
    }

    override fun onStart() {
        super.onStart()
        Session.showErrors(this)
        listeners += Repo.listenDriver(driverId) { d ->
            if (d == null) return@listenDriver finish()
            driver = d
            render()
        }
        listeners += Repo.listenDeliveries(workDay) { dayList = it; render() }
        listeners += Repo.listenUnfinishedDeliveries { unfinished = it; render() }
    }

    override fun onStop() {
        super.onStop()
        listeners.clear()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        val all = (dayList + unfinished.filter { it.date < workDay }).distinctBy { it.id }
        // Mine on the way first, then free ones, then others on the way, delivered last.
        adapter.items = all.sortedWith(
            compareBy<Delivery> { if (it.status == DeliveryStatus.IN_PROGRESS && it.driverId == driverId) 0 else it.status.ordinal + 1 }
                .thenBy { it.date }.thenBy { it.order }
        )
        supportActionBar?.subtitle = deliveryCounts(all).ifEmpty { null }
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun take(delivery: Delivery) {
        val d = driver ?: return
        Repo.takeDelivery(delivery, d) { error ->
            if (error != null) MaterialAlertDialogBuilder(this).setMessage(error).setPositiveButton("OK", null).show()
        }
    }

    private fun markDone(delivery: Delivery) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Doručené?")
            .setMessage(listOf(delivery.title, delivery.place).filter { it.isNotBlank() }.joinToString("\n"))
            .setPositiveButton("Áno, doručené") { _, _ -> Repo.finishDelivery(delivery) }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun showDetail(delivery: Delivery) {
        val mine = delivery.driverId == driverId
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(delivery.title)
            .setIcon(R.drawable.ic_package)
            .setMessage(
                listOfNotNull(
                    delivery.place.ifBlank { null }?.let { "📍 $it" },
                    delivery.note.ifBlank { null },
                    if (delivery.date != workDay) "Zásielka z ${delivery.date.format(Fmt.DATE)}" else null,
                    delivery.statusText,
                ).joinToString("\n\n")
            )
        if (delivery.place.isNotBlank()) builder.setPositiveButton("Navigovať") { _, _ -> navigateTo(this, delivery.place) }
        when {
            delivery.status == DeliveryStatus.OPEN && driver?.active == true ->
                builder.setNeutralButton("Beriem") { _, _ -> take(delivery) }
            mine && delivery.status == DeliveryStatus.IN_PROGRESS ->
                builder.setNeutralButton("Vrátiť – neveziem") { _, _ -> Repo.releaseDelivery(delivery) }
        }
        builder.setNegativeButton("Zavrieť", null).show()
    }

    companion object {
        private const val EXTRA_DAY = "day"

        /** [workDay]: the day of the running shift (a night shift goes past midnight), otherwise today. */
        fun open(context: Context, workDay: LocalDate) {
            context.startActivity(Intent(context, DeliveriesActivity::class.java).putExtra(EXTRA_DAY, workDay.toString()))
        }
    }
}
