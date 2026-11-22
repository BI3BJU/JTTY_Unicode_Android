package radio.ks3ckc.jttyaf.jtty

/**
 * Collapses the receiver's duplicate decodes of one signal.
 *
 * The streaming receiver searches several channels (priority, narrow, wide) and can
 * accept the SAME frame more than once with wildly different frequency estimates — the
 * field log shows one 1600 Hz frame reported at 1420, 1600 and 1780 Hz, and a 1500 Hz one
 * at 1320/1440/1500/1560/1680 Hz. Start sample is identical in every copy, so TIME is the
 * reliable key here: JTTY is asynchronous, and two independent stations effectively never
 * put their frame starts within 50 ms of each other, while copies of one frame share it
 * exactly.
 *
 * Two rules:
 *  - frames: same [JttyCodec.DecodedFrame.ext] flag and start within [TIME_TOLERANCE_S]
 *    — keep the copy with the best S/N;
 *  - messages: same ext/own-TX flag, and either first-frame times within
 *    [MESSAGE_TIME_TOLERANCE_MS], or overlapping text (one is a prefix of the other) within
 *    [FAMILY_WINDOW_MS] when at least one of them is still incomplete — replace the row
 *    only with a better message.
 */
object JttyFrameDeduper {

    /** Same-frame start-time window (copies share the start sample within a few samples). */
    const val TIME_TOLERANCE_S = 0.05f

    /** Same-message first-frame window. */
    const val MESSAGE_TIME_TOLERANCE_MS = 200L

    /** How far apart two fragments of one over may start. */
    const val FAMILY_WINDOW_MS = 30_000L

    /** Minimum common prefix before two incomplete rows are treated as one signal. */
    const val FAMILY_MIN_PREFIX = 4

    /** Widest frequency spread accepted between two copies of one signal. */
    const val FAMILY_MAX_FREQ_HZ = 500f

    /** Deep copy: the native receiver fills one mutable holder for every pop. */
    fun snapshot(frame: JttyCodec.DecodedFrame): JttyCodec.DecodedFrame =
        JttyCodec.DecodedFrame().apply {
            payload = frame.payload
            eom = frame.eom
            ext = frame.ext
            data = frame.data?.copyOf()
            text = frame.text
            freqHz = frame.freqHz
            startSample = frame.startSample
            dtSec = frame.dtSec
            snrDb = frame.snrDb
            syncScore = frame.syncScore
            listRank = frame.listRank
            pass = frame.pass
        }

    /** Drop duplicate frames from one decode batch, keeping the strongest copy. */
    fun dedupe(
        frames: List<JttyCodec.DecodedFrame>,
        sampleRate: Int,
    ): List<JttyCodec.DecodedFrame> {
        if (frames.size < 2) return frames
        val kept = ArrayList<JttyCodec.DecodedFrame>(frames.size)
        for (frame in frames) {
            val index = kept.indexOfFirst { sameSignal(it, frame, sampleRate) }
            if (index < 0) {
                kept.add(frame)
            } else if (frameBetter(frame, kept[index])) {
                kept[index] = frame
            }
        }
        return kept
    }

    /** Whether two frames look like copies of the same transmission (time only). */
    fun sameSignal(
        a: JttyCodec.DecodedFrame,
        b: JttyCodec.DecodedFrame,
        sampleRate: Int,
    ): Boolean {
        val maxStartDelta = (TIME_TOLERANCE_S * sampleRate).toLong()
        return a.ext == b.ext && Math.abs(a.startSample - b.startSample) <= maxStartDelta
    }

    /** Whether two message rows describe the same transmission. */
    fun sameFamily(a: JttyMessage, b: JttyMessage): Boolean {
        if (a.ext != b.ext || a.isOwnTx != b.isOwnTx) return false
        val delta = Math.abs(a.utcMillis - b.utcMillis)
        if (delta <= MESSAGE_TIME_TOLERANCE_MS) return true
        if (delta > FAMILY_WINDOW_MS) return false
        // Copies are reported within a few hundred hertz of each other; a wider gap is
        // a different signal, not a copy.
        if (Math.abs(a.freqHz - b.freqHz) > FAMILY_MAX_FREQ_HZ) return false
        // Fragments starting at different frames of one over: same text prefix, at least
        // one still incomplete (two complete messages are separate overs).
        if (a.complete && b.complete) return false
        return commonPrefixLength(a.text, b.text) >= FAMILY_MIN_PREFIX
    }

    /** Whether [a] should replace [b] as the row for one signal family. */
    fun better(a: JttyMessage, b: JttyMessage): Boolean {
        if (a.complete != b.complete) return a.complete
        if (a.text.length != b.text.length) return a.text.length > b.text.length
        if (a.snrDb != b.snrDb) return a.snrDb > b.snrDb
        return a.utcMillis >= b.utcMillis
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = Math.min(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) ++i
        return i
    }

    private fun frameBetter(a: JttyCodec.DecodedFrame, b: JttyCodec.DecodedFrame): Boolean =
        if (a.snrDb != b.snrDb) {
            a.snrDb > b.snrDb
        } else {
            a.text.length > b.text.length
        }
}
