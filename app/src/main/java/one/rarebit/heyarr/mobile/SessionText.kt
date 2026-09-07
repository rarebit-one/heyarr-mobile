package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.search.SessionAuthority

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
