package one.rarebit.heyarr.mobile.consumption

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import one.rarebit.heyarr.mobile.playback.ClientCapabilities

/**
 * Where playback has reached, told to the node as a consumption session (§67). The
 * player calls this from its own thread with what it knows; nothing here blocks it.
 */
interface ProgressReporter {
    /** Something started: open a session for [assetId] under [verb] (`watch` | `listen` | `read`). */
    fun begin(assetId: String, verb: String)
    fun progress(seconds: Double)
    fun pause(seconds: Double)
    fun resume(seconds: Double)
    /** Playback ended: [completed] when the end was reached, else a stop that keeps the position. */
    fun end(seconds: Double, completed: Boolean)

    object NoOp : ProgressReporter {
        override fun begin(assetId: String, verb: String) {}
        override fun progress(seconds: Double) {}
        override fun pause(seconds: Double) {}
        override fun resume(seconds: Double) {}
        override fun end(seconds: Double, completed: Boolean) {}
    }
}

/**
 * Decides which progress ticks are worth a write: one every [intervalSeconds] of wall
 * time, and only when the position moved by at least [minDeltaSeconds]. Pure, tested.
 */
class ProgressThrottle(private val intervalSeconds: Double = 15.0, private val minDeltaSeconds: Double = 5.0) {
    private var lastAt = Double.NEGATIVE_INFINITY
    private var lastPos = Double.NEGATIVE_INFINITY

    fun reset() { lastAt = Double.NEGATIVE_INFINITY; lastPos = Double.NEGATIVE_INFINITY }

    /** True when a tick at wall-clock [nowSeconds] with position [pos] should be sent (and records it). */
    fun accept(nowSeconds: Double, pos: Double): Boolean {
        if (nowSeconds - lastAt < intervalSeconds) return false
        if (kotlin.math.abs(pos - lastPos) < minDeltaSeconds) return false
        lastAt = nowSeconds; lastPos = pos
        return true
    }

    /** A state change (pause/resume/end) always sends, and re-anchors the throttle. */
    fun mark(nowSeconds: Double, pos: Double) { lastAt = nowSeconds; lastPos = pos }
}

/**
 * The [ProgressReporter] over the node: registers this phone as a device once per
 * node, opens a session per playback, and serialises every transition through one
 * worker so they arrive in order. Silent when the credential cannot write (a QR
 * session): the node would 403 and there is nothing the user can do about it here.
 * A 409 (the node disagrees about the state) drops the session — the next begin
 * opens a fresh one — rather than fighting.
 */
class ConsumptionReporter(
    private val client: ConsumptionClient,
    private val store: DeviceIdStore,
    private val scope: CoroutineScope,
    private val baseUrl: () -> String,
    private val canWrite: () -> Boolean,
    private val enrolledDeviceKey: () -> String?,
    private val deviceName: () -> String,
    private val capabilities: () -> ClientCapabilities?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val throttle: ProgressThrottle = ProgressThrottle(),
) : ProgressReporter {

    private sealed interface Cmd {
        data class Begin(val assetId: String, val verb: String) : Cmd
        data class Move(val transition: String, val seconds: Double) : Cmd
    }

    private val queue = Channel<Cmd>(Channel.UNLIMITED)

    /** The open session id, or null; visible for tests. */
    @Volatile var sessionId: String? = null
        private set

    init {
        scope.launch {
            for (cmd in queue) withContext(io) { handle(cmd) }
        }
    }

    override fun begin(assetId: String, verb: String) {
        if (!canWrite()) return
        throttle.reset()
        queue.trySend(Cmd.Begin(assetId, verb))
    }

    override fun progress(seconds: Double) {
        if (sessionId == null || !throttle.accept(clock(), seconds)) return
        queue.trySend(Cmd.Move("progress", seconds))
    }

    override fun pause(seconds: Double) = move("pause", seconds)
    override fun resume(seconds: Double) = move("resume", seconds)
    override fun end(seconds: Double, completed: Boolean) = move(if (completed) "complete" else "stop", seconds)

    private fun move(transition: String, seconds: Double) {
        if (sessionId == null) return
        throttle.mark(clock(), seconds)
        queue.trySend(Cmd.Move(transition, seconds))
    }

    private fun handle(cmd: Cmd) {
        when (cmd) {
            is Cmd.Begin -> {
                // Close whatever was open first: a new play is a stop of the old one.
                sessionId?.let { runCatching { client.transition(it, "stop", null) } }
                sessionId = null
                val deviceId = ensureDevice() ?: return
                val out = runCatching { client.createSession(cmd.assetId, deviceId, cmd.verb) }.getOrNull()
                val id = (out as? ConsumptionClient.Outcome.Ok)?.id ?: return
                if (runCatching { client.transition(id, "start", null) }.getOrNull() is ConsumptionClient.Outcome.Ok) sessionId = id
            }
            is Cmd.Move -> {
                val id = sessionId ?: return
                val out = runCatching { client.transition(id, cmd.transition, cmd.seconds) }.getOrNull()
                if (out is ConsumptionClient.Outcome.Refused && out.status == 409) sessionId = null
                if (cmd.transition == "stop" || cmd.transition == "complete") sessionId = null
            }
        }
    }

    private fun ensureDevice(): String? {
        val base = baseUrl()
        store.deviceId(base)?.let { return it }
        val key = enrolledDeviceKey()?.takeIf { it.isNotBlank() } ?: store.deviceKey()
        val out = runCatching { client.registerDevice(key, deviceName(), capabilities()) }.getOrNull()
        val id = (out as? ConsumptionClient.Outcome.Ok)?.id ?: return null
        store.putDeviceId(base, id)
        return id
    }
}
