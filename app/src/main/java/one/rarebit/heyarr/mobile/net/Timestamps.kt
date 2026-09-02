package one.rarebit.heyarr.mobile.net

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * heyarr renders every timestamp as Go's RFC 3339 (`2026-09-01T10:20:30.123456Z`,
 * or with a zone offset). These helpers turn one into an epoch for ordering
 * ("recent first" with no settings) and into a short local display string. Both are
 * tolerant: an unparseable or absent timestamp is null, and sorts last.
 */
object Timestamps {

    /** Epoch milliseconds for an RFC 3339 timestamp, or null. */
    fun epochMillis(rfc3339: String?): Long? {
        val s = rfc3339?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(s).toEpochMilli() }
            .getOrNull()
    }

    private val display: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

    /** `1 Sep 2026, 18:20` in the device zone, or null. */
    fun short(rfc3339: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
        val millis = epochMillis(rfc3339) ?: return null
        return runCatching { display.format(Instant.ofEpochMilli(millis).atZone(zone)) }.getOrNull()
    }

    /**
     * Order [items] most-recent first by the timestamp [stamp] yields; items with no
     * readable timestamp keep their relative order at the end. Stable.
     */
    fun <T> recentFirst(items: List<T>, stamp: (T) -> String?): List<T> {
        val keyed = items.map { it to epochMillis(stamp(it)) }
        val known = keyed.filter { it.second != null }.sortedByDescending { it.second }
        val unknown = keyed.filter { it.second == null }
        return (known + unknown).map { it.first }
    }
}
