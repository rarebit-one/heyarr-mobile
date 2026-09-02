package one.rarebit.heyarr.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.HeyarrConfig

/**
 * Minimal per-install settings: the heyarr base URL and the quality profile. The
 * fields start from the *effective* [config]; Save persists both as overrides (a
 * value equal to the build default is stored as "no override"), Reset clears them.
 * Saving a changed base URL signs the app out — a session token is only good for
 * the node that minted it.
 */
@Composable
fun SettingsScreen(
    config: HeyarrConfig,
    onSave: (baseUrl: String, qualityProfile: String) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var baseUrl by rememberSaveable(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var profile by rememberSaveable(config.defaultQualityProfile) { mutableStateOf(config.defaultQualityProfile) }

    val normalized = HeyarrConfig.normalizeBaseUrl(baseUrl)
    val urlError = if (normalized == null) "Enter an absolute http:// or https:// URL." else null
    val cleartextHint = normalized?.startsWith("http://") == true

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Which heyarr node this app talks to. Build default: ${HeyarrConfig.DEFAULT_BASE_URL}",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            singleLine = true,
            isError = urlError != null,
            supportingText = {
                Text(
                    urlError ?: if (cleartextHint) {
                        "Plain http: release builds only allow cleartext to the build-default node; " +
                            "debug builds allow it everywhere."
                    } else {
                        "e.g. http://192.168.16.224:7777"
                    },
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = profile,
            onValueChange = { profile = it },
            label = { Text("Quality profile") },
            singleLine = true,
            supportingText = { Text("Named profile Get-once / Follow requests use (default: ${HeyarrConfig.DEFAULT_QUALITY_PROFILE}).") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = normalized != null,
                onClick = { normalized?.let { onSave(it, profile) } },
            ) { Text("Save") }
            OutlinedButton(onClick = onReset) { Text("Reset to defaults") }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }
}
