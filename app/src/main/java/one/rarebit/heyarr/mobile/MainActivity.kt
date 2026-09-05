package one.rarebit.heyarr.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import one.rarebit.heyarr.mobile.device.EnrolUiState
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import one.rarebit.heyarr.mobile.nav.HeyarrNavHost
import one.rarebit.heyarr.mobile.device.AndroidBiometricGate
import one.rarebit.heyarr.mobile.device.DeviceKeyring
import one.rarebit.heyarr.mobile.device.EnrolScreen
import one.rarebit.heyarr.mobile.device.HandoffLauncher
import one.rarebit.heyarr.mobile.device.PairDeepLink
import one.rarebit.heyarr.mobile.login.LoginScreen
import one.rarebit.heyarr.mobile.login.LoginUiState
import one.rarebit.heyarr.mobile.login.VoidbindHandoff
import one.rarebit.heyarr.mobile.playback.MediaCodecCapabilities
import one.rarebit.heyarr.mobile.search.SessionAuthority
import one.rarebit.heyarr.mobile.settings.SettingsScreen

/**
 * A pairing invite that arrived by deep link (`heyarr-mobile://pair?invite=…`) — from
 * Cruciform's "Add a device" on this same phone (voidbind-kmp ADR-0006). [seq] makes two
 * identical links distinct so the second re-fires. Either a usable [inviteQr] or a
 * [problem] to show.
 */
private data class LinkedInvite(val inviteQr: String?, val problem: String?, val seq: Int, val done: Boolean = false)

/**
 * A [FragmentActivity] because `BiometricPrompt` — which gates every use of the
 * hardware-sealed device key — binds to one. `singleTop` so the authenticator's
 * `heyarr-mobile://login` callback (and its `heyarr-mobile://pair` handoff) foregrounds
 * this instance via [onNewIntent] instead of stacking; a cold start routes the launching
 * intent in [onCreate].
 */
@UnstableApi
class MainActivity : FragmentActivity() {

    private var linkedInvite by mutableStateOf<LinkedInvite?>(null)
    private var linkSeq = 0

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    /** Ask for POST_NOTIFICATIONS once a pairing starts, so its foreground notice can show. */
    private fun ensureNotificationPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeLink(intent)?.let { linkedInvite = it }
    }

    /** The `heyarr-mobile://pair` handoff, if this intent is one; null for anything else. */
    private fun routeLink(intent: Intent?): LinkedInvite? = when (val r = PairDeepLink.route(intent?.action, intent?.dataString)) {
        is PairDeepLink.Invite -> LinkedInvite(r.inviteQr, null, ++linkSeq)
        is PairDeepLink.Invalid -> LinkedInvite(null, r.message, ++linkSeq)
        // The one-tap return leg (voidbind-kmp ADR-0008): nothing to join, nothing to
        // trust — just bring the human back to the Device screen, where the app-scoped
        // pairing has (or is about to have) reached Enrolled on its own.
        is PairDeepLink.Done -> LinkedInvite(null, null, ++linkSeq, done = true)
        null -> null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge on every SDK (35+ forces it anyway); each screen keeps its
        // content inside the safe-drawing insets — Scaffold/TopAppBar/NavigationBar do
        // that themselves, the player overlay does it explicitly.
        enableEdgeToEdge()
        // Cold start from the deep link: route the launching intent. (On a recreation the
        // intent is the same one; re-routing re-parks/re-joins, which is right — the join
        // state it held was ephemeral.)
        linkedInvite = routeLink(intent)
        val appContext = applicationContext
        // This phone's device keys, biometric-gated through this activity. Attached
        // once per activity; the ViewModel outlives rotations and keeps the session.
        val keyring = DeviceKeyring(this, AndroidBiometricGate(this))
        val app = application as HeyarrApp
        // The app-scoped pairing holder signs (the possession proof) through the keyring
        // of the Activity in front — this one, until the next create replaces it.
        app.deviceKeyring = keyring
        app.deviceName = "heyarr-mobile on ${android.os.Build.MODEL}"
        val voidbindInstalled = HandoffLauncher.canOpen(this, "voidbind:login?id=probe&rp=probe")
        setContent {
            MaterialTheme {
                val vm: AppViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            AppViewModel(
                                settings = app.graph.settings,
                                pairing = app.pairing,
                                rawTransport = app.graph.rawTransport,
                                deviceIds = app.graph.deviceIds,
                                spaceRegistry = app.graph.spaceRegistry,
                            )
                        }
                    },
                )
                LaunchedEffect(vm) {
                    vm.deviceName = app.deviceName
                    app.credentialProvider = { vm.credentialOrNull() }
                    // Posters and range reads go out through the shared client and pick
                    // up the live credential here, without ever holding it themselves.
                    app.graph.authHeader.provider = { vm.liveAuthorizationHeader() }
                    vm.attachDevice(keyring)
                    // What this phone can decode, for the playback planner (#432).
                    vm.playback.capabilities = MediaCodecCapabilities.probe(appContext)
                    vm.attachAudio(app.graph.audio)
                    app.reporter = vm.progressReporter
                }
                // The "Pairing with Cruciform…" foreground-service notification needs this
                // on Android 13+; the service runs regardless, the notice just stays hidden.
                val enrolState by vm.enrolState.collectAsStateWithLifecycle()
                LaunchedEffect(enrolState is EnrolUiState.Joining) {
                    if (enrolState is EnrolUiState.Joining) ensureNotificationPermission()
                }
                val loginState by vm.loginState.collectAsStateWithLifecycle()
                val config by vm.configState.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showEnrol by rememberSaveable { mutableStateOf(false) }
                val parkedInvite by vm.parkedInvite.collectAsStateWithLifecycle()
                val context = LocalContext.current

                // Cruciform handed us an invite (or a broken link): route it into the same
                // join path a scan takes, and put the Enrol screen in front — the standalone
                // one before sign-in, the Device tab once signed in.
                var focusDevice by rememberSaveable { mutableStateOf(0) }
                val link = linkedInvite
                LaunchedEffect(link) {
                    link ?: return@LaunchedEffect
                    when {
                        // `pair-done`: Cruciform sent us back after authorising. The
                        // enrolment came through our own relay pipeline; this only puts
                        // the Device screen in front so the user sees the result.
                        link.done -> Unit
                        link.inviteQr != null -> vm.receiveInviteLink(link.inviteQr)
                        else -> vm.rejectInviteLink(link.problem ?: "bad invite link")
                    }
                    showSettings = false
                    showEnrol = true
                    focusDevice = link.seq
                }

                if (showSettings) {
                    BackHandler { showSettings = false }
                    Scaffold(topBar = { HeyarrTopBar(subtitle = config.baseUrl, onSettings = null) }) { padding ->
                        SettingsScreen(
                            config = config,
                            onSave = { url, profile -> vm.updateSettings(url, profile); showSettings = false },
                            onReset = { vm.resetSettings(); showSettings = false },
                            onClose = { showSettings = false },
                            modifier = Modifier.padding(padding),
                        )
                    }
                } else if (loginState is LoginUiState.Approved) {
                    HeyarrNavHost(vm = vm, httpClient = app.graph.okHttp, audio = app.graph.audio, focusDevice = focusDevice, onSettings = { showSettings = true })
                } else if (showEnrol) {
                    // Enrolment needs no session: pairing runs over the relay, and an enrolled
                    // phone then signs in with its cert instead of a QR login.
                    BackHandler { showEnrol = false }
                    Scaffold(topBar = { HeyarrTopBar(subtitle = config.baseUrl, onSettings = null) }) { padding ->
                        EnrolScreen(
                            state = enrolState,
                            onCreateKey = vm::provisionDevice,
                            onJoinInvite = vm::joinPairing,
                            onSasMatches = vm::confirmSas,
                            onSasMismatch = vm::rejectSas,
                            onRetry = vm::retryEnrol,
                            onForget = vm::forgetDevice,
                            // Finishing enrol here signs the phone in (adopts the Device
                            // credential → Approved → SignedInScaffold). Clear focusDevice
                            // so that scaffold's LaunchedEffect(focusDevice) doesn't see the
                            // stale deep-link seq and yank the user straight back to the
                            // Device tab — that bounce is what made the FIRST "Continue" look
                            // like a no-op and demand a second tap (#24). Land on Library.
                            onDone = { vm.useDeviceCredential(); showEnrol = false; focusDevice = 0 },
                            modifier = Modifier.padding(padding),
                            parkedInvite = parkedInvite,
                            onDiscardParked = vm::discardParkedInvite,
                            onCancelPairing = vm::cancelPairing,
                            onRegister = vm::registerDevice,
                        )
                    }
                } else {
                    Scaffold(topBar = { HeyarrTopBar(subtitle = config.baseUrl, onSettings = { showSettings = true }) }) { padding ->
                        LoginScreen(
                            state = loginState,
                            onSignIn = vm::signIn,
                            // Same-phone approval: hand the tuple to the Voidbind authenticator;
                            // the RP is still polled for the outcome.
                            onApproveOnThisPhone = if (voidbindInstalled) {
                                { tuple -> HandoffLauncher.open(context, VoidbindHandoff.loginUri(tuple)) }
                            } else null,
                            onEnrolDevice = { showEnrol = true },
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The app bar: title, a one-line subtitle (the node we point at, or the signed-in
 * identity + scope), and a gear that opens Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HeyarrTopBar(subtitle: String, onSettings: (() -> Unit)?) {
    TopAppBar(
        title = {
            Column {
                Text("heyarr", style = MaterialTheme.typography.titleLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            if (onSettings != null) {
                IconButton(onClick = onSettings) { Text("⚙") }
            }
        },
    )
}

/**
 * "Signed in as … · read-only" from the login result + `GET /api/v1/session`. An
 * enrolled device (`kind == "device"`) is named as such, and its scope is whatever
 * the node actually granted its key — read-only until an admin authorises it.
 */
internal fun sessionSubtitle(user: String?, authority: SessionAuthority?, baseUrl: String): String {
    val who = user?.takeIf { it.isNotBlank() }
        ?: authority?.principalId?.takeIf { it.isNotBlank() }?.let { shortPrincipal(it) }
    val scope = when {
        authority == null -> "read-only (session unverified)"
        authority.canWrite -> "can write"
        authority.isDevice -> "read-only (device not yet authorised)"
        else -> "read-only"
    }
    val how = if (authority?.isDevice == true) "Enrolled device" else "Signed in"
    val subject = if (who != null) "$how as $who" else how
    return "$subject · $scope · $baseUrl"
}

/** `ed25519:<hex>` → `ed25519:<first 8>…` so it fits a subtitle line. */
internal fun shortPrincipal(principal: String): String {
    val sep = principal.indexOf(':')
    if (sep < 0) return principal.take(12)
    val prefix = principal.substring(0, sep + 1)
    val body = principal.substring(sep + 1)
    return if (body.length <= 8) principal else "$prefix${body.take(8)}…"
}
