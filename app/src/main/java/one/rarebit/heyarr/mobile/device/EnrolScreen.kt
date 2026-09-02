package one.rarebit.heyarr.mobile.device

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.rarebit.heyarr.mobile.login.QrCode

/** UI state for the "Enrol this device" screen. */
sealed interface EnrolUiState {
    /** Keys are being provisioned / read (may be waiting on the biometric prompt). */
    data object Loading : EnrolUiState

    /** Fresh install: no device key yet. Creating one shows the user-presence prompt. */
    data object Unprovisioned : EnrolUiState

    /** Keys exist; no cert yet. Waiting for the user to start or join a pairing. */
    data class Ready(val info: DeviceKeyInfo) : EnrolUiState

    /**
     * The handshake is running over the relay. Either this device opened the session and
     * is showing the invite for the authorising side to scan ([joined] = false), or it
     * **joined** an invite the authorising side printed — scanned or pasted — and is
     * connecting to that relay ([joined] = true). [inviteQr] is the `voidbind:pair?…` tuple.
     */
    data class Inviting(val info: DeviceKeyInfo, val inviteQr: String, val joined: Boolean = false) : EnrolUiState

    /** Handshake done: show the SAS for the human to compare on both screens. */
    data class CompareSas(val info: DeviceKeyInfo, val sas: String) : EnrolUiState

    /** The cert was delivered, verified and stored; [registration] says whether the node knows it yet. */
    data class Enrolled(val info: DeviceKeyInfo, val registration: String, val needsAdmin: Boolean) : EnrolUiState

    data class Error(val info: DeviceKeyInfo?, val message: String) : EnrolUiState
}

/**
 * The "Enrol this device" screen. This phone is the **new device** — voidbind-client's
 * `DevicePairing`, the relay *responder*: it shows its device key + honest hardware
 * tier, opens a relay session and renders the `voidbind:pair?…` invite (as a QR, and
 * as an "Open in Cruciform" hand-off to the authenticator app on this same phone), runs the
 * commit-before-reveal handshake once the authorising side joins, shows the SAS for
 * the human to compare, and — only after they match — receives the user-signed
 * enrolment cert sealed to this device's X25519 key. An invite the *other* side
 * created (the Mac's `voidbind pair-initiate` QR) can be **scanned with the camera**
 * or pasted instead — both go through [PairInvite] (the library's parser) before the
 * join.
 *
 * Until a cert exists the app keeps using the QR web-login session (read-only).
 */
@Composable
fun EnrolScreen(
    state: EnrolUiState,
    onCreateKey: () -> Unit,
    onStartPairing: () -> Unit,
    onJoinInvite: (inviteQr: String) -> Unit,
    /** Same-phone hand-off of the invite to the authenticator; null hides the button. */
    onOpenInVoidbind: ((inviteQr: String) -> Unit)?,
    onSasMatches: () -> Unit,
    onSasMismatch: () -> Unit,
    onRetry: () -> Unit,
    onForget: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Enrol this device", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Turn this phone into an enrolled heyarr device: its own hardware-sealed key, " +
                "authorised by your Voidbind identity. Until then you're signed in with a " +
                "read-only QR session.",
            style = MaterialTheme.typography.bodyMedium,
        )

        val info = when (state) {
            is EnrolUiState.Ready -> state.info
            is EnrolUiState.Inviting -> state.info
            is EnrolUiState.CompareSas -> state.info
            is EnrolUiState.Enrolled -> state.info
            is EnrolUiState.Error -> state.info
            EnrolUiState.Loading, EnrolUiState.Unprovisioned -> null
        }
        if (info != null) DeviceKeyCard(info)

        when (state) {
            EnrolUiState.Loading -> {
                CircularProgressIndicator()
                Text("Preparing the device key… confirm the prompt if asked.")
            }
            EnrolUiState.Unprovisioned -> {
                Text("No device key yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Create this phone's Ed25519 device key. It is generated once and sealed by a " +
                        "non-extractable key in the secure hardware (StrongBox where the phone has one, " +
                        "otherwise the TEE), so you'll be asked to confirm it's you.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onCreateKey) { Text("Create device key") }
            }
            is EnrolUiState.Ready -> {
                Text("Pair with your identity", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Start pairing here, then approve it from a device that holds your Voidbind " +
                        "identity — the Cruciform app on this phone, or `voidbind pair-initiate` on your Mac.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onStartPairing) { Text("Start pairing") }
                InviteEntry(onJoinInvite)
            }
            is EnrolUiState.Inviting if state.joined -> {
                Text("Joining the pairing…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Connecting to the relay the invite names and running the handshake. " +
                        "Next you'll compare a security code with the one on the Mac's terminal.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Relay: " + ((PairInvite.check(state.inviteQr) as? PairInvite.Valid)?.relay ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            is EnrolUiState.Inviting -> {
                Text("Waiting for the authorising device…", style = MaterialTheme.typography.titleMedium)
                if (onOpenInVoidbind != null) {
                    Button(onClick = { onOpenInVoidbind(state.inviteQr) }) { Text("Open in Cruciform") }
                    Text("…or scan this invite from the authorising device:", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Scan this invite from the authorising device:", style = MaterialTheme.typography.bodySmall)
                }
                QrImage(state.inviteQr, modifier = Modifier.align(Alignment.CenterHorizontally))
                SelectionContainer {
                    Text(state.inviteQr, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            is EnrolUiState.CompareSas -> {
                Text("Compare the security code", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The Mac's terminal (or Cruciform) is showing a code too.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SasCard(state.sas)
                Text(
                    "Tap \"Codes match\" only if both show the SAME number — that comparison " +
                        "is what stops an attacker on the network from standing in the middle.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSasMatches) { Text("Codes match") }
                    OutlinedButton(onClick = onSasMismatch) { Text("They differ") }
                }
            }
            is EnrolUiState.Enrolled -> {
                Text("Enrolled.", style = MaterialTheme.typography.titleMedium)
                Text(state.registration, style = MaterialTheme.typography.bodySmall,
                    color = if (state.needsAdmin) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.needsAdmin && info?.certToken != null) {
                    Text("Certificate to register:", style = MaterialTheme.typography.labelSmall)
                    SelectionContainer {
                        Text(info.certToken, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                Text(
                    "This device now signs in with its own certificate. Whether it can also " +
                        "manage follows depends on the grant an admin gives its key.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDone) { Text("Continue") }
                    OutlinedButton(onClick = onForget) { Text("Forget enrolment") }
                }
            }
            is EnrolUiState.Error -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text("Try again") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DeviceKeyCard(info: DeviceKeyInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("This device's key", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(info.deviceKey, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Text(
                "Key storage: " + when (info.tier) {
                    KeyTier.STRONGBOX -> "StrongBox secure element (hardware-sealed, user-presence gated)"
                    KeyTier.TEE -> "TEE-backed Android Keystore (hardware-sealed, user-presence gated; this device has no StrongBox)"
                    KeyTier.SOFTWARE -> "software only — NOT hardware-backed"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (info.tier == KeyTier.SOFTWARE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (info.isEnrolled) "Status: enrolled (holds a user-signed certificate)" else "Status: not enrolled yet",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The short authentication string, big enough to read across the room and compare
 * digit-by-digit against the Mac's terminal. Monospace + letter-spacing so `1`/`7` and
 * `0`/`8` don't blur together; a single line that never wraps.
 */
@Composable
private fun SasCard(sas: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 12.dp)) {
            Text(
                "SECURITY CODE",
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                sas,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 6.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

/**
 * Join an invite the authorising side printed: **scan** the Mac's QR with the camera, or
 * **paste** its text. Both go through [PairInvite.check] — the library's parser — and
 * anything that isn't a `voidbind:pair?…` invite is refused inline with a reason, while
 * the scanner keeps looking so the user can just point at the right code.
 */
@Composable
private fun InviteEntry(onJoin: (String) -> Unit) {
    var invite by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }

    /** Returns true when the text was a valid invite and the join was started. */
    fun submit(raw: String): Boolean = when (val r = PairInvite.check(raw)) {
        is PairInvite.Valid -> {
            problem = null
            scanning = false
            onJoin(r.inviteQr)
            true
        }
        is PairInvite.Invalid -> {
            problem = r.message
            false
        }
    }

    Text("…or join an invite from your Mac", style = MaterialTheme.typography.titleSmall)
    Text(
        "`voidbind pair-initiate` on a machine holding your identity prints an invite QR. " +
            "Scan it here, or paste the voidbind:pair?… text.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (scanning) {
        QrScanner(
            onQr = { raw -> submit(raw) },
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
            noPermission = {
                Text(
                    "Camera permission is needed to scan the invite — allow it, or paste the invite below.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
        OutlinedButton(onClick = { scanning = false }) { Text("Stop scanning") }
    } else {
        Button(onClick = { problem = null; scanning = true }) { Text("Scan invite QR") }
    }
    OutlinedTextField(
        value = invite,
        onValueChange = { invite = it },
        label = { Text("voidbind:pair?v=2&relay=…&session=…&salt=…") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { submit(invite) }, enabled = invite.isNotBlank()) {
        Text("Join pairing")
    }
    problem?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private val QR_SIZE = 220.dp

@Composable
private fun QrImage(text: String, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { QR_SIZE.roundToPx() }
    val image = remember(text, px) { QrCode.bitmap(text, px).asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = "Pairing invite QR",
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.None,
        modifier = modifier.size(QR_SIZE).background(Color.White),
    )
}
