package one.rarebit.heyarr.mobile

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.device.DeviceKeyring
import one.rarebit.heyarr.mobile.device.DevicePairingSteps
import one.rarebit.heyarr.mobile.device.PairingCoordinator
import one.rarebit.heyarr.mobile.device.PairingForegroundService
import one.rarebit.heyarr.mobile.device.PairingState
import one.rarebit.heyarr.mobile.device.PrefsPendingPairingStore
import one.rarebit.heyarr.mobile.net.OkHttpTransport
import one.rarebit.heyarr.mobile.net.OkHttpVoidbindTransport
import one.rarebit.heyarr.mobile.settings.PrefsSettingsStore

/**
 * The process-wide holders. The one that matters is [pairing]: the join → handshake
 * → confirm → enrol pipeline for a Cruciform invite runs here, on an app-wide scope,
 * so it survives the Activity, the Enrol screen leaving composition, and the app
 * being backgrounded while the user is over in Cruciform — and while it is live, a
 * foreground service ([PairingForegroundService]) keeps the process from being
 * killed. The Activity's ViewModel only observes it.
 */
class HeyarrApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The device keyring of the Activity currently in front (its biometric prompt binds
     * to that Activity). Set by [MainActivity] on create; read per pairing step.
     */
    @Volatile
    var deviceKeyring: DeviceKeyring? = null

    /** The signed-in credential, if any, offered to the admin-registration lane of `/enrol`. */
    @Volatile
    var credentialProvider: () -> one.rarebit.heyarr.mobile.auth.Credential? = { null }

    /** What to call this phone in the node's device registry. */
    @Volatile
    var deviceName: String = "heyarr-mobile"

    val pairing: PairingCoordinator by lazy {
        val settings = PrefsSettingsStore(this)
        PairingCoordinator(
            scope = appScope,
            store = PrefsPendingPairingStore(this),
            steps = {
                DevicePairingSteps(
                    keyring = { deviceKeyring },
                    relayTransport = OkHttpVoidbindTransport(),
                    nodeTransport = OkHttpTransport(),
                    baseUrl = { HeyarrConfig.resolve(settings.baseUrlOverride, settings.qualityProfileOverride).baseUrl },
                    deviceName = { deviceName },
                    credential = { credentialProvider() },
                )
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Keep a foreground service up for exactly as long as a pairing is in flight.
        appScope.launch {
            pairing.state
                .map { it is PairingState.Live || it is PairingState.Registering }
                .distinctUntilChanged()
                .collect { inFlight ->
                    val intent = Intent(this@HeyarrApp, PairingForegroundService::class.java)
                    if (inFlight) startForegroundService(intent) else stopService(intent)
                }
        }
    }
}
