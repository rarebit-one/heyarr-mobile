package one.rarebit.heyarr.mobile.personalstate

/**
 * A pure-Kotlin BLAKE3-256 hasher — the one primitive heyarr-core's personal-state
 * plane needs on the device that voidbind-client does not ship (it has X25519 and
 * XChaCha20, not BLAKE3). The content-addressed change/snapshot id is a
 * length-framed BLAKE3 digest (`protocol/change.go` `computeID`); the node
 * re-derives it and refuses a mismatch, so a change this phone mints is only
 * stored if this hash is byte-identical to Go's `github.com/zeebo/blake3`.
 *
 * This is the reference incremental tree hasher (BLAKE3 §2, the C/Rust "portable"
 * reference), not a one-block shortcut: inputs over one 1024-byte chunk (a large
 * snapshot ciphertext) fold through the CV stack exactly as Go does. All arithmetic
 * is on [Int], which is 32-bit two's-complement — modular add and the rotate/xor
 * mixing are identical to unsigned `uint32`. It is deliberately I/O-free and holds
 * no key; [ChangeId] frames the fields and calls [hash256].
 *
 * Verified against Go-minted KATs (empty, "abc", one block, one chunk, a
 * multi-chunk tree) and the committed change-id parity vectors in [ChangeIdTest].
 */
internal object Blake3 {
    private const val OUT_LEN = 32
    private const val BLOCK_LEN = 64
    private const val CHUNK_LEN = 1024

    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val PARENT = 4
    private const val ROOT = 8

    private val IV = intArrayOf(
        0x6A09E667, -0x4498517B, 0x3C6EF372, -0x5AB00AC6,
        0x510E527F, -0x64FA9774, 0x1F83D9AB, 0x5BE0CD19,
    )

    private val MSG_PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

    /** The 256-bit digest of [input], as 32 raw bytes. */
    fun hash256(input: ByteArray): ByteArray {
        val h = Hasher()
        h.update(input)
        return h.finalizeBytes()
    }

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    private fun g(s: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
        s[a] = s[a] + s[b] + mx
        s[d] = rotr(s[d] xor s[a], 16)
        s[c] = s[c] + s[d]
        s[b] = rotr(s[b] xor s[c], 12)
        s[a] = s[a] + s[b] + my
        s[d] = rotr(s[d] xor s[a], 8)
        s[c] = s[c] + s[d]
        s[b] = rotr(s[b] xor s[c], 7)
    }

    private fun round(s: IntArray, m: IntArray) {
        // columns
        g(s, 0, 4, 8, 12, m[0], m[1])
        g(s, 1, 5, 9, 13, m[2], m[3])
        g(s, 2, 6, 10, 14, m[4], m[5])
        g(s, 3, 7, 11, 15, m[6], m[7])
        // diagonals
        g(s, 0, 5, 10, 15, m[8], m[9])
        g(s, 1, 6, 11, 12, m[10], m[11])
        g(s, 2, 7, 8, 13, m[12], m[13])
        g(s, 3, 4, 9, 14, m[14], m[15])
    }

    private fun permute(m: IntArray): IntArray {
        val out = IntArray(16)
        for (i in 0 until 16) out[i] = m[MSG_PERMUTATION[i]]
        return out
    }

    /** The compression function; returns all 16 state words (the first 8 are the CV). */
    private fun compress(cv: IntArray, blockWords: IntArray, counter: Long, blockLen: Int, flags: Int): IntArray {
        val s = intArrayOf(
            cv[0], cv[1], cv[2], cv[3], cv[4], cv[5], cv[6], cv[7],
            IV[0], IV[1], IV[2], IV[3],
            counter.toInt(), (counter ushr 32).toInt(), blockLen, flags,
        )
        var m = blockWords
        for (r in 0 until 7) {
            round(s, m)
            if (r < 6) m = permute(m)
        }
        for (i in 0 until 8) {
            s[i] = s[i] xor s[i + 8]
            s[i + 8] = s[i + 8] xor cv[i]
        }
        return s
    }

    private fun first8(words: IntArray): IntArray = words.copyOfRange(0, 8)

    private fun wordsFromLE(block: ByteArray): IntArray {
        val w = IntArray(16)
        for (i in 0 until 16) {
            val o = i * 4
            w[i] = (block[o].toInt() and 0xFF) or
                ((block[o + 1].toInt() and 0xFF) shl 8) or
                ((block[o + 2].toInt() and 0xFF) shl 16) or
                ((block[o + 3].toInt() and 0xFF) shl 24)
        }
        return w
    }

    /** A finished node, ready to chain or to root-finalise. */
    private class Output(
        val inputCV: IntArray,
        val blockWords: IntArray,
        val counter: Long,
        val blockLen: Int,
        val flags: Int,
    ) {
        fun chainingValue(): IntArray = first8(compress(inputCV, blockWords, counter, blockLen, flags))

        fun rootBytes(): ByteArray {
            // We only ever need OUT_LEN (32) bytes: one XOF block at counter 0.
            val words = compress(inputCV, blockWords, 0L, blockLen, flags or ROOT)
            val out = ByteArray(OUT_LEN)
            for (i in 0 until OUT_LEN / 4) {
                val v = words[i]
                out[i * 4] = (v and 0xFF).toByte()
                out[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
                out[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
                out[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
            }
            return out
        }
    }

    private class ChunkState(val key: IntArray, val chunkCounter: Long) {
        var cv: IntArray = key.copyOf()
        val block = ByteArray(BLOCK_LEN)
        var blockLen = 0
        var blocksCompressed = 0

        fun len(): Int = BLOCK_LEN * blocksCompressed + blockLen

        private fun startFlag(): Int = if (blocksCompressed == 0) CHUNK_START else 0

        fun update(input: ByteArray, from: Int, to: Int) {
            var i = from
            while (i < to) {
                if (blockLen == BLOCK_LEN) {
                    cv = first8(compress(cv, wordsFromLE(block), chunkCounter, BLOCK_LEN, startFlag()))
                    blocksCompressed++
                    block.fill(0)
                    blockLen = 0
                }
                val take = minOf(BLOCK_LEN - blockLen, to - i)
                System.arraycopy(input, i, block, blockLen, take)
                blockLen += take
                i += take
            }
        }

        fun output(): Output {
            val padded = block.copyOf() // already zero-padded to 64
            return Output(cv, wordsFromLE(padded), chunkCounter, blockLen, startFlag() or CHUNK_END)
        }
    }

    private class Hasher {
        private val key = IV
        private var chunk = ChunkState(key, 0L)
        private val cvStack = Array(54) { IntArray(8) }
        private var cvStackLen = 0

        private fun pushCV(cv: IntArray) {
            cvStack[cvStackLen] = cv
            cvStackLen++
        }

        private fun popCV(): IntArray {
            cvStackLen--
            return cvStack[cvStackLen]
        }

        private fun parentOutput(left: IntArray, right: IntArray): Output {
            val block = IntArray(16)
            for (i in 0 until 8) {
                block[i] = left[i]
                block[i + 8] = right[i]
            }
            return Output(key, block, 0L, BLOCK_LEN, PARENT)
        }

        private fun parentCV(left: IntArray, right: IntArray): IntArray = parentOutput(left, right).chainingValue()

        private fun addChunkCV(newCV: IntArray, totalChunks: Long) {
            var cv = newCV
            var total = totalChunks
            while (total and 1L == 0L) {
                cv = parentCV(popCV(), cv)
                total = total ushr 1
            }
            pushCV(cv)
        }

        fun update(input: ByteArray) {
            var i = 0
            val n = input.size
            while (i < n) {
                if (chunk.len() == CHUNK_LEN) {
                    val cv = chunk.output().chainingValue()
                    val totalChunks = chunk.chunkCounter + 1
                    addChunkCV(cv, totalChunks)
                    chunk = ChunkState(key, totalChunks)
                }
                val want = CHUNK_LEN - chunk.len()
                val take = minOf(want, n - i)
                chunk.update(input, i, i + take)
                i += take
            }
        }

        fun finalizeBytes(): ByteArray {
            var output = chunk.output()
            var remaining = cvStackLen
            while (remaining > 0) {
                remaining--
                output = parentOutput(cvStack[remaining], output.chainingValue())
            }
            return output.rootBytes()
        }
    }
}
