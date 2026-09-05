package one.rarebit.heyarr.mobile.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator

/**
 * Syncs a reader's EXACT position (a Readium Locator, JSON) through the encrypted
 * reading-position space, so a book resumes where you left it on ANOTHER device — the
 * device-side half of §45's reading-position surface (ADR-0049). It complements, and
 * does not replace, the two positions the reader already keeps: the local Locator
 * (`ReadingPositionStore`, the freshest same-device resume) and the coarse `page` the
 * node is told through the consumption reporter. The node still never sees plaintext;
 * the locator is encrypted on this device before it is stored.
 *
 * [NoOp] is the honest state for a device that cannot decrypt (not enrolled): resume
 * finds nothing and save is a no-op, so the reader falls back to the local Locator.
 */
interface ReadingPositionSync {
    /** The exact locator (JSON) last recorded for this publication anywhere, or null. */
    fun resume(pubId: String): String?

    /** Record this publication's exact locator to the encrypted space (fire-and-forget). */
    fun save(pubId: String, locatorJson: String)

    object NoOp : ReadingPositionSync {
        override fun resume(pubId: String): String? = null
        override fun save(pubId: String, locatorJson: String) {}
    }
}

/**
 * The production sync over a [PersonalStateCoordinator]. `resume` reads synchronously
 * (the reader calls it once, off the main thread, while opening); `save` fires on a
 * background scope so a locator write never blocks navigation. The coordinator is
 * resolved per call, so it follows a credential/node change and is null before enrolment.
 */
internal class CoordinatorReadingPositionSync(
    private val coordinator: () -> PersonalStateCoordinator?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ReadingPositionSync {
    override fun resume(pubId: String): String? =
        coordinator()?.let { c -> runCatching { c.readingPosition(pubId) }.getOrNull() }

    override fun save(pubId: String, locatorJson: String) {
        val c = coordinator() ?: return
        scope.launch { withContext(io) { runCatching { c.setReadingPosition(pubId, locatorJson) } } }
    }
}
