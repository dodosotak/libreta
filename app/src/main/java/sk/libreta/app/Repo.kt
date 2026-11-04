package sk.libreta.app

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.security.SecureRandom
import java.text.Collator
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Shared Firestore storage (project libreta-sklad). Layout:
 * drivers/{id}, drivers/{id}/shifts/{id}, drivers/{id}/absences/{id} (also the drivers' vacation requests), joinCodes/{code} -> driverId,
 * vehicles/{id}, closures/{yyyy-MM} (locked months), owners/{uid}, accountants/{uid}.
 * Firestore caches locally, so writes made offline appear immediately and sync later.
 * Times are local "yyyy-MM-ddTHH:mm" strings (dates "yyyy-MM-dd") so they sort and range-query as text.
 */
object Repo {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()
    private val drivers get() = db.collection("drivers")
    private fun shiftsOf(driverId: String) = drivers.document(driverId).collection("shifts")
    private fun absencesOf(driverId: String) = drivers.document(driverId).collection("absences")

    /** Set by the visible screen to show sync/permission problems. */
    var onError: (Exception) -> Unit = {}

    /**
     * Set by the driver's screen: a listener was refused by the server. Right after signing in this can be a passing
     * glitch (the new sign-in not yet everywhere), so the screen checks the link with the server instead of failing.
     */
    var onListenDenied: (() -> Unit)? = null

    private fun report(e: Exception) {
        Log.w("Libreta", "Firestore", e)
        onError(e)
    }

    private fun listenFailed(e: FirebaseFirestoreException) {
        val denied = onListenDenied
        if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED && denied != null) {
            Log.w("Libreta", "Listener denied, checking the link", e)
            denied()
        } else {
            report(e)
        }
    }

    /** Asks the server whether this phone is still linked to [driverId]: true/false, null if the server can't be reached. */
    fun checkLinked(driverId: String, onResult: (Boolean?) -> Unit) {
        drivers.document(driverId).get(Source.SERVER)
            .addOnSuccessListener { onResult(it.exists() && it.getString("uid") == auth.currentUser?.uid) }
            .addOnFailureListener { e ->
                onResult(if ((e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) false else null)
            }
    }

    // ---- office accounts: owner and accountants ----

    /** [Session.OWNER], [Session.ACCOUNTANT] or null for the signed-in email account. */
    fun checkRole(onResult: (String?) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onResult(null)
        db.document("owners/$uid").get().addOnSuccessListener { owner ->
            if (owner.exists()) return@addOnSuccessListener onResult(Session.OWNER)
            db.document("accountants/$uid").get()
                .addOnSuccessListener { onResult(if (it.exists()) Session.ACCOUNTANT else null) }
                .addOnFailureListener { onResult(null) }
        }.addOnFailureListener { onResult(null) }
    }

    fun listenAccountants(onChange: (List<Pair<String, String>>) -> Unit): ListenerRegistration =
        db.collection("accountants").addSnapshotListener { snap, e ->
            if (e != null) return@addSnapshotListener listenFailed(e)
            onChange(snap!!.documents.map { it.id to it.getString("email").orEmpty() }.sortedBy { it.second })
        }

    /**
     * Creates the accountant's email/password account without signing the owner out (a second Firebase app
     * instance does the sign-up) and grants read-only access. [onResult] gets an error text or null.
     */
    fun addAccountant(context: Context, email: String, password: String, onResult: (String?) -> Unit) {
        val app = FirebaseApp.getApps(context).find { it.name == "accounts" }
            ?: FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, "accounts")
        val secondAuth = FirebaseAuth.getInstance(app)
        secondAuth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val uid = result.user!!.uid
                secondAuth.signOut()
                db.document("accountants/$uid").set(mapOf("email" to email))
                    .addOnSuccessListener { onResult(null) }
                    .addOnFailureListener { onResult("Účet vznikol, ale nepodarilo sa mu dať prístup: ${it.message}") }
            }
            .addOnFailureListener { e ->
                onResult(
                    when (e) {
                        is FirebaseAuthUserCollisionException -> "Účet s týmto e-mailom už existuje."
                        is FirebaseAuthWeakPasswordException -> "Heslo je príliš slabé (aspoň 6 znakov)."
                        else -> "Nepodarilo sa: ${e.message}"
                    }
                )
            }
    }

    /** Takes the access away; the email account itself stays but can't read anything. */
    fun removeAccountant(uid: String) {
        db.document("accountants/$uid").delete().addOnFailureListener(::report)
    }

    // ---- locked months ----

    fun listenClosed(month: YearMonth, onChange: (Boolean) -> Unit): ListenerRegistration =
        db.document("closures/$month").addSnapshotListener { snap, e ->
            if (e != null) return@addSnapshotListener listenFailed(e)
            onChange(snap!!.exists())
        }

    /** A closed month can't be changed by anyone (rules), until the owner opens it again. */
    fun setClosed(month: YearMonth, closed: Boolean) {
        val ref = db.document("closures/$month")
        val task = if (closed) ref.set(mapOf("at" to FieldValue.serverTimestamp(), "by" to auth.currentUser?.uid)) else ref.delete()
        task.addOnFailureListener(::report)
    }

    // ---- vehicles ----

    fun listenVehicles(onChange: (List<Vehicle>) -> Unit): ListenerRegistration =
        db.collection("vehicles").addSnapshotListener { snap, e ->
            if (e != null) return@addSnapshotListener listenFailed(e)
            onChange(
                snap!!.documents.map {
                    Vehicle(it.id, it.getString("plate").orEmpty(), it.getString("name").orEmpty(), it.getBoolean("active") ?: true)
                }.sortedWith(compareBy<Vehicle> { !it.active }.thenBy { it.plate })
            )
        }

    fun saveVehicle(vehicle: Vehicle) {
        val data = mapOf("plate" to vehicle.plate, "name" to vehicle.name, "active" to vehicle.active)
        val collection = db.collection("vehicles")
        val task = if (vehicle.id.isEmpty()) collection.add(data) else collection.document(vehicle.id).set(data)
        task.addOnFailureListener(::report)
    }

    fun deleteVehicle(vehicle: Vehicle) {
        db.collection("vehicles").document(vehicle.id).delete().addOnFailureListener(::report)
    }

    // ---- absences ----

    /** Absences of the driver that overlap [month], including vacation requests that wait or were rejected. */
    fun listenAbsences(driverId: String, month: YearMonth, onChange: (List<Absence>) -> Unit): ListenerRegistration =
        absencesOf(driverId)
            .whereGreaterThanOrEqualTo("to", month.atDay(1).toString())
            .addSnapshotListener { snap, e ->
                if (e != null) return@addSnapshotListener listenFailed(e)
                onChange(snap!!.documents.mapNotNull { toAbsence(driverId, it) }.filter { !it.from.isAfter(month.atEndOfMonth()) }.sortedBy { it.from })
            }

    /** Absences ending after [date] – what the vacation balance counted from [date] needs. */
    fun listenAbsencesAfter(driverId: String, date: LocalDate, onChange: (List<Absence>) -> Unit): ListenerRegistration =
        absencesOf(driverId)
            .whereGreaterThan("to", date.toString())
            .addSnapshotListener { snap, e ->
                if (e != null) return@addSnapshotListener listenFailed(e)
                onChange(snap!!.documents.mapNotNull { toAbsence(driverId, it) }.sortedBy { it.from })
            }

    /** The driver's vacation requests waiting for the owner. */
    fun listenPending(driverId: String, onChange: (List<Absence>) -> Unit): ListenerRegistration =
        absencesOf(driverId)
            .whereEqualTo("status", AbsenceStatus.PENDING.name)
            .addSnapshotListener { snap, e ->
                if (e != null) return@addSnapshotListener listenFailed(e)
                onChange(snap!!.documents.mapNotNull { toAbsence(driverId, it) }.sortedBy { it.from })
            }

    /** The owner's entry; keeps the status, so editing a waiting request doesn't approve it. */
    fun saveAbsence(absence: Absence) {
        val data = mapOf(
            "type" to absence.type.name,
            "from" to absence.from.toString(),
            "to" to absence.to.toString(),
            "note" to absence.note,
            "status" to absence.status.name,
        )
        val collection = absencesOf(absence.driverId)
        val task = if (absence.id.isEmpty()) collection.add(data) else collection.document(absence.id).set(data, SetOptions.merge())
        task.addOnFailureListener(::report)
    }

    /** The driver asks for a vacation from the phone; the owner approves or rejects it with [decideRequest]. */
    fun requestVacation(driverId: String, from: LocalDate, to: LocalDate, note: String) {
        absencesOf(driverId).add(
            mapOf(
                "type" to AbsenceType.VACATION.name,
                "from" to from.toString(),
                "to" to to.toString(),
                "note" to note,
                "status" to AbsenceStatus.PENDING.name,
                "requestedAt" to FieldValue.serverTimestamp(),
            )
        ).addOnFailureListener(::report)
    }

    fun decideRequest(absence: Absence, approve: Boolean) {
        val status = if (approve) AbsenceStatus.APPROVED else AbsenceStatus.REJECTED
        absencesOf(absence.driverId).document(absence.id)
            .update(mapOf("status" to status.name, "decidedAt" to FieldValue.serverTimestamp()))
            .addOnFailureListener(::report)
    }

    /** The owner deletes any absence; the driver only a request that waits or was rejected (rules). */
    fun deleteAbsence(absence: Absence) {
        absencesOf(absence.driverId).document(absence.id).delete().addOnFailureListener(::report)
    }

    /** Makes the signed-in email account the owner; fails if an owner already exists. */
    fun claimOwnership(onResult: (Boolean) -> Unit) {
        val user = auth.currentUser ?: return onResult(false)
        db.batch()
            .set(db.document("owners/${user.uid}"), mapOf("email" to user.email))
            .set(db.document("meta/setup"), mapOf("owner" to user.uid, "at" to FieldValue.serverTimestamp()))
            .commit()
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    // ---- drivers ----

    fun listenDrivers(onChange: (List<Driver>) -> Unit): ListenerRegistration {
        val collator = Collator.getInstance(Fmt.SK)
        return drivers.addSnapshotListener { snap, e ->
            if (e != null) return@addSnapshotListener listenFailed(e)
            onChange(snap!!.documents.map(::toDriver).sortedWith(compareBy<Driver> { !it.active }.thenBy(collator) { it.name }))
        }
    }

    /** null when the record is gone or this phone has no access to it any more (unlinked by the owner). */
    fun listenDriver(driverId: String, onChange: (Driver?) -> Unit): ListenerRegistration =
        drivers.document(driverId).addSnapshotListener { snap, e ->
            when {
                e?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED && onListenDenied != null -> listenFailed(e)
                e?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> onChange(null)
                e != null -> report(e)
                else -> onChange(if (snap!!.exists()) toDriver(snap) else null)
            }
        }

    fun saveDriver(driver: Driver) {
        val data = mapOf(
            "name" to driver.name,
            "type" to driver.type.name,
            "rate" to driver.hourlyRate,
            "dayRate" to driver.dayRate,
            "nightRate" to driver.nightRate,
            "active" to driver.active,
            "phone" to driver.phone,
            "weeklyHours" to driver.weeklyHours,
            "vacationPerYear" to driver.vacationPerYear,
            "vacationBalance" to driver.vacationBalance,
            "vacationAsOf" to driver.vacationAsOf?.toString(),
        )
        val task = if (driver.id.isEmpty()) {
            val ref = drivers.document()
            val code = newJoinCode()
            db.batch()
                .set(ref, data + mapOf("uid" to null, "joinCode" to code))
                .set(db.document("joinCodes/$code"), mapOf("driverId" to ref.id))
                .commit()
        } else {
            drivers.document(driver.id).update(data)
        }
        task.addOnFailureListener(::report)
    }

    /** Deletes only a driver without any shifts; [onResult] gets false otherwise. */
    fun deleteDriver(driver: Driver, onResult: (Boolean) -> Unit) {
        shiftsOf(driver.id).limit(1).get().addOnSuccessListener { shifts ->
            if (!shifts.isEmpty) return@addOnSuccessListener onResult(false)
            db.batch()
                .delete(drivers.document(driver.id))
                .delete(db.document("joinCodes/${driver.joinCode}"))
                .commit()
                .addOnFailureListener(::report)
            onResult(true)
        }.addOnFailureListener(::report)
    }

    /** Lets the driver link a new phone with the same join code. */
    fun unlinkPhone(driver: Driver) {
        drivers.document(driver.id).update("uid", null).addOnFailureListener(::report)
    }

    // ---- driver's phone ----

    /** The driver's profile; the rules let a driver change only this field of their record. */
    fun savePhone(driverId: String, phone: String) {
        drivers.document(driverId).update("phone", phone).addOnFailureListener(::report)
    }

    /** Unlinks this phone from the driver before signing out, so the join code works again without the owner. */
    fun releasePhone(driverId: String, onResult: (ok: Boolean) -> Unit) {
        drivers.document(driverId).update("uid", null)
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    /** Signs the phone in anonymously and links it to the driver with [code]. Returns the driver id or an error text. */
    fun linkPhone(code: String, onResult: (driverId: String?, error: String?) -> Unit) {
        fun link() {
            val uid = auth.currentUser!!.uid
            db.document("joinCodes/$code").get().addOnSuccessListener { doc ->
                val driverId = doc.getString("driverId") ?: return@addOnSuccessListener onResult(null, "Neplatný kód")
                // The code proves the driver: the rules let it take the record over from an old phone,
                // which is then signed out (also after a reinstall, a new phone or a lost sign-in).
                drivers.document(driverId).update(mapOf("uid" to uid, "linkCode" to code))
                    .addOnSuccessListener { onResult(driverId, null) }
                    .addOnFailureListener { onResult(null, "Nepodarilo sa prepojiť telefón. Si pripojený na internet?") }
            }.addOnFailureListener { onResult(null, "Nepodarilo sa overiť kód. Si pripojený na internet?") }
        }
        val user = auth.currentUser
        if (user != null && user.isAnonymous) return link()
        auth.signInAnonymously()
            .addOnSuccessListener { link() }
            .addOnFailureListener { onResult(null, "Prihlásenie zlyhalo. Si pripojený na internet?") }
    }

    // ---- shifts ----

    fun listenOpenShift(driverId: String, onChange: (Shift?) -> Unit): ListenerRegistration =
        shiftsOf(driverId).whereEqualTo("end", null).addSnapshotListener { snap, e ->
            if (e != null) return@addSnapshotListener listenFailed(e)
            onChange(snap!!.documents.map { toShift(driverId, it) }.maxByOrNull { it.start })
        }

    /** Shifts that started in [month]; a shift over midnight belongs to the day it started. */
    fun listenShifts(driverId: String, month: YearMonth, onChange: (List<Shift>) -> Unit): ListenerRegistration =
        shiftsOf(driverId)
            .whereGreaterThanOrEqualTo("start", month.atDay(1).atStartOfDay().format(TIME))
            .whereLessThan("start", month.plusMonths(1).atDay(1).atStartOfDay().format(TIME))
            .orderBy("start", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, e ->
                if (e != null) return@addSnapshotListener listenFailed(e)
                onChange(snap!!.documents.map { toShift(driverId, it) })
            }

    /**
     * [byDriver] stamps the server time too, so the owner can spot times entered with a wrong phone clock.
     * A driver's shift is stored under its start ("2026-10-19T06:00"), so a double tap can't create two shifts;
     * the start button is offered only while no shift is running, so a driver may have several shifts a day.
     * Older app versions stored it under the date ("2026-10-19") – both kinds are read the same.
     */
    fun startShift(driverId: String, byDriver: Boolean, vehicle: String = "", kmStart: Int? = null) {
        val start = now()
        val data = hashMapOf<String, Any?>(
            "start" to start.format(TIME), "end" to null, "breakMin" to 0, "note" to "", "vehicle" to vehicle, "kmStart" to kmStart,
        )
        val task = if (byDriver) {
            data["startLoggedAt"] = FieldValue.serverTimestamp()
            shiftsOf(driverId).document(start.format(TIME)).set(data)
        } else {
            shiftsOf(driverId).add(data)
        }
        task.addOnFailureListener(::report)
    }

    fun endShift(shift: Shift, kmEnd: Int?, byDriver: Boolean) {
        val data = hashMapOf<String, Any?>("end" to now().format(TIME))
        if (kmEnd != null) data["kmEnd"] = kmEnd
        if (byDriver) data["endLoggedAt"] = FieldValue.serverTimestamp()
        shiftsOf(shift.driverId).document(shift.id).update(data).addOnFailureListener(::report)
    }

    /**
     * The driver adds a past shift they forgot to start. Like a live shift it is stored under its start; a shift that
     * overlaps one already recorded (also a running one) is refused. [onResult] gets an error text or null when saved.
     * The server time in "enteredAt" shows the owner it was added afterwards.
     */
    fun addPastShift(shift: Shift, onResult: (String?) -> Unit) {
        val end = shift.end ?: return onResult("Chýba koniec smeny")
        val shifts = shiftsOf(shift.driverId)
        // A shift started up to a day earlier may still run into this one (e.g. a night shift).
        shifts.whereGreaterThanOrEqualTo("start", shift.start.minusDays(1).format(TIME))
            .whereLessThan("start", end.format(TIME))
            .get()
            .addOnSuccessListener { snap ->
                val clash = snap.documents.map { toShift(shift.driverId, it) }
                    .firstOrNull { it.start < end && (it.end ?: now()) > shift.start }
                if (clash != null) return@addOnSuccessListener onResult("Prekrýva sa so smenou ${describe(clash)}. Opravu urobí majiteľ.")
                savePastShift(shifts.document(shift.start.format(TIME)), shift)
                onResult(null)
            }
            .addOnFailureListener { onResult("Nepodarilo sa overiť tvoje smeny. Si pripojený na internet?") }
    }

    private fun describe(shift: Shift) =
        "${shift.start.format(Fmt.DAY)} ${shift.start.format(Fmt.TIME)} – ${shift.end?.format(Fmt.TIME) ?: "…"}"

    private fun savePastShift(ref: DocumentReference, shift: Shift) {
        val data = hashMapOf<String, Any?>(
            "start" to shift.start.format(TIME),
            "end" to shift.end?.format(TIME),
            "breakMin" to 0,
            "note" to shift.note,
            "vehicle" to shift.vehicle,
            "kmStart" to shift.kmStart,
            "kmEnd" to shift.kmEnd,
            "enteredAt" to FieldValue.serverTimestamp(),
        )
        ref.set(data).addOnFailureListener {
            report(Exception("Smenu sa nepodarilo uložiť – mesiac je možno už uzavretý. Ozvi sa majiteľovi.", it))
        }
    }

    /** Owner's manual entry/correction. A changed time drops its phone stamp – the owner vouches for it. */
    fun saveShift(shift: Shift, original: Shift?) {
        val data = hashMapOf<String, Any?>(
            "start" to shift.start.format(TIME),
            "end" to shift.end?.format(TIME),
            "breakMin" to 0,
            "note" to shift.note,
            "vehicle" to shift.vehicle,
            "kmStart" to shift.kmStart,
            "kmEnd" to shift.kmEnd,
        )
        val task = if (original == null) {
            shiftsOf(shift.driverId).add(data)
        } else {
            if (shift.start != original.start) data["startLoggedAt"] = FieldValue.delete()
            if (shift.end != original.end) data["endLoggedAt"] = FieldValue.delete()
            shiftsOf(shift.driverId).document(shift.id).update(data)
        }
        task.addOnFailureListener(::report)
    }

    fun deleteShift(shift: Shift) {
        shiftsOf(shift.driverId).document(shift.id).delete().addOnFailureListener(::report)
    }

    // ---- mapping ----

    private fun now() = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)

    private fun newJoinCode(): String {
        val random = SecureRandom()
        return (1..8).map { CODE_CHARS[random.nextInt(CODE_CHARS.length)] }.joinToString("")
    }

    private fun toDriver(doc: DocumentSnapshot) = Driver(
        id = doc.id,
        name = doc.getString("name").orEmpty(),
        type = runCatching { WorkerType.valueOf(doc.getString("type")!!) }.getOrDefault(WorkerType.EMPLOYEE),
        hourlyRate = doc.getDouble("rate") ?: 0.0,
        dayRate = doc.getDouble("dayRate"),
        nightRate = doc.getDouble("nightRate"),
        active = doc.getBoolean("active") ?: true,
        uid = doc.getString("uid"),
        joinCode = doc.getString("joinCode").orEmpty(),
        phone = doc.getString("phone").orEmpty(),
        weeklyHours = doc.getDouble("weeklyHours") ?: 40.0,
        vacationPerYear = doc.getLong("vacationPerYear")?.toInt() ?: 20,
        vacationBalance = doc.getDouble("vacationBalance"),
        vacationAsOf = doc.getString("vacationAsOf")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    )

    /** Absences written before vacation requests existed have no status – they were entered by the owner. */
    private fun toAbsence(driverId: String, doc: DocumentSnapshot): Absence? = runCatching {
        Absence(
            id = doc.id,
            driverId = driverId,
            type = AbsenceType.valueOf(doc.getString("type")!!),
            from = LocalDate.parse(doc.getString("from")),
            to = LocalDate.parse(doc.getString("to")),
            note = doc.getString("note").orEmpty(),
            status = doc.getString("status")?.let { AbsenceStatus.valueOf(it) } ?: AbsenceStatus.APPROVED,
        )
    }.getOrNull()

    private fun toShift(driverId: String, doc: DocumentSnapshot) = Shift(
        id = doc.id,
        driverId = driverId,
        start = LocalDateTime.parse(doc.getString("start"), TIME),
        end = doc.getString("end")?.let { LocalDateTime.parse(it, TIME) },
        note = doc.getString("note").orEmpty(),
        startLoggedAt = doc.getTimestamp("startLoggedAt")?.toLocal(),
        endLoggedAt = doc.getTimestamp("endLoggedAt")?.toLocal(),
        vehicle = doc.getString("vehicle").orEmpty(),
        kmStart = doc.getLong("kmStart")?.toInt(),
        kmEnd = doc.getLong("kmEnd")?.toInt(),
        enteredAt = doc.getTimestamp("enteredAt")?.toLocal(),
    )

    private fun Timestamp.toLocal(): LocalDateTime = toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
}
