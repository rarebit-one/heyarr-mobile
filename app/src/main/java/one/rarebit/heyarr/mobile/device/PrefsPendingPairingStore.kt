package one.rarebit.heyarr.mobile.device

import android.content.Context
import android.content.SharedPreferences

/**
 * [PendingPairingStore] over SharedPreferences: the invite tuple, its session id, the
 * same-device flag and the join time. No secret is ever written — an invite is public
 * (it names a relay session and the identity), and the handshake state itself is not
 * serialisable, which is exactly why the record only ever says "interrupted".
 */
class PrefsPendingPairingStore(context: Context) : PendingPairingStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("heyarr-pairing", Context.MODE_PRIVATE)

    override var pending: PendingPairing?
        get() {
            val session = prefs.getString(KEY_SESSION, null) ?: return null
            val invite = prefs.getString(KEY_INVITE, null) ?: return null
            return PendingPairing(
                session = session,
                inviteQr = invite,
                sameDevice = prefs.getBoolean(KEY_SAME_DEVICE, false),
                startedAtMillis = prefs.getLong(KEY_STARTED_AT, 0L),
            )
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    clear()
                } else {
                    putString(KEY_SESSION, value.session)
                    putString(KEY_INVITE, value.inviteQr)
                    putBoolean(KEY_SAME_DEVICE, value.sameDevice)
                    putLong(KEY_STARTED_AT, value.startedAtMillis)
                }
            }.apply()
        }

    private companion object {
        const val KEY_SESSION = "session"
        const val KEY_INVITE = "invite"
        const val KEY_SAME_DEVICE = "same_device"
        const val KEY_STARTED_AT = "started_at"
    }
}
