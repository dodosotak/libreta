package sk.libreta.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration

/** Which role this phone has: the owner's, the accountant's (read-only office view) or a driver's. */
object Session {
    const val OWNER = "owner"
    const val ACCOUNTANT = "accountant"
    const val DRIVER = "driver"

    private fun prefs(context: Context) = context.getSharedPreferences("libreta", Context.MODE_PRIVATE)

    fun role(context: Context): String? = prefs(context).getString("role", null)

    /** Only the owner changes data; the accountant sees the same screens read-only (also enforced by the rules). */
    fun canEdit(context: Context) = role(context) == OWNER

    fun driverId(context: Context): String? = prefs(context).getString("driverId", null)

    fun save(context: Context, role: String, driverId: String? = null) {
        prefs(context).edit().putString("role", role).putString("driverId", driverId).apply()
    }

    fun signOut(activity: Activity) {
        FirebaseAuth.getInstance().signOut()
        prefs(activity).edit().clear().apply()
        activity.startActivity(
            Intent(activity, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    fun showErrors(activity: Activity) {
        Repo.onError = { e -> Toast.makeText(activity, describe(e), Toast.LENGTH_LONG).show() }
    }

    private fun describe(e: Exception): String {
        val code = (e as? FirebaseFirestoreException)?.code ?: return e.message ?: "Neznáma chyba"
        val text = when (code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> "Server zmenu odmietol – mesiac je možno uzavretý alebo na to nemáš oprávnenie."
            FirebaseFirestoreException.Code.UNAVAILABLE -> "Server je nedostupný – skontroluj pripojenie na internet."
            else -> "Chyba synchronizácie."
        }
        return "$text ($code)"
    }
}

/** Firestore listeners of one screen, removed together when the screen stops. */
class Listeners {
    private val all = mutableListOf<ListenerRegistration>()
    private val keyed = mutableMapOf<String, ListenerRegistration>()

    operator fun plusAssign(registration: ListenerRegistration) {
        all += registration
    }

    /** Replaces the listener under [key]; [clearKeyed] removes it with the other keyed ones. */
    operator fun set(key: String, registration: ListenerRegistration) {
        keyed.put(key, registration)?.remove()
    }

    /** Keeps exactly one listener per key in [keys]: adds the missing ones, removes the rest. */
    fun sync(keys: Set<String>, create: (String) -> ListenerRegistration) {
        (keyed.keys - keys).forEach { keyed.remove(it)?.remove() }
        (keys - keyed.keys).forEach { keyed[it] = create(it) }
    }

    fun clearKeyed() {
        keyed.values.forEach { it.remove() }
        keyed.clear()
    }

    fun clear() {
        all.forEach { it.remove() }
        all.clear()
        clearKeyed()
    }
}
