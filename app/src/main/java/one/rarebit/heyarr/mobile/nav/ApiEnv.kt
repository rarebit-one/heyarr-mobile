package one.rarebit.heyarr.mobile.nav

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport

/**
 * One immutable snapshot of "who we are and where the node is", handed to every
 * screen's clients. Screens build their ViewModel under [key], so that when the node
 * or the credential SHAPE changes (enrolling swaps the Bearer session for a Device
 * cert) the ViewModel is rebuilt rather than kept on a stale client — the same
 * discipline the Search ViewModel already followed by hand.
 */
data class ApiEnv(
    val baseUrl: String,
    val qualityProfile: String,
    val credential: Credential,
    val transport: HttpTransport,
) {
    val key: String get() = "$baseUrl:$qualityProfile:${credential.javaClass.simpleName}"
}
