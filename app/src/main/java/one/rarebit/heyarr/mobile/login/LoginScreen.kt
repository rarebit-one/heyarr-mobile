package one.rarebit.heyarr.mobile.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** UI state for the QR login screen. */
sealed interface LoginUiState {
    data object Idle : LoginUiState
    /** A login has started; [qrTuple] is the `voidbind:login?…` string to render as a QR. */
    data class AwaitingScan(val qrTuple: String) : LoginUiState
    data class Approved(val user: String?) : LoginUiState
    data class Error(val message: String) : LoginUiState
}

/**
 * The Voidbind QR login screen. Tapping "Sign in" begins a login against heyarr's
 * weblogin broker; the app shows the `voidbind:login?…` tuple for the user's
 * authenticator (voidbind-kmp) to scan; on approval the app holds a Bearer session
 * token and shows the library.
 *
 * SCAFFOLD: the tuple is shown as text. Rendering it as a real QR **bitmap** needs a
 * QR-encoding dependency and is a follow-up (README) — the login state machine and
 * the tuple contract are what this scaffold lands.
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("heyarr", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sign in with Voidbind — scan the code with your authenticator.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        when (state) {
            is LoginUiState.Idle, is LoginUiState.Error -> {
                if (state is LoginUiState.Error) {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                Button(onClick = onSignIn) { Text("Sign in with Voidbind") }
            }
            is LoginUiState.AwaitingScan -> {
                CircularProgressIndicator()
                Text(
                    "Scan this with your authenticator:",
                    modifier = Modifier.padding(top = 16.dp),
                )
                // TODO(qr-render): draw state.qrTuple as a QR bitmap (follow-up).
                Text(
                    state.qrTuple,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            is LoginUiState.Approved -> {
                Text("Signed in${state.user?.let { " as $it" } ?: ""}.")
            }
        }
    }
}
