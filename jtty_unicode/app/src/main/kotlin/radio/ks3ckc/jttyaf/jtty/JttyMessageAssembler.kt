package radio.ks3ckc.jttyaf.jtty

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Chains accepted JTTY frames into messages (docs/jtty-protocol.md §4), the way WSJT-X
 * does on receive:
 *
 *  - A frame continues an open message when it starts `n` frame periods (n = 1..3,
 *    ±[CONTINUATION_TOLERANCE_S]) after that message's last frame and lies within
 *    ±10 Hz of the message's first-frame frequency. A skipped period (n > 1) inserts
 *    `...` in the text.
 *  - The EOM frame completes the message.
 *  - A message with no continuation for [FLUSH_PERIODS] frame periods is flushed as
 *    incomplete; at most [MAX_OPEN] messages are open at once (the oldest is flushed).
 *
 * JTTY Unicode extension frames (`frame.ext`) take a byte path instead: their four
 * bytes are concatenated before UTF-8 decoding, so a multi-byte character may safely
 * straddle a frame boundary. A missing frame inserts one four-byte placeholder
 * (U+FFFD + space). At EOM the trailing CRC-8 byte is checked; a mismatch appends
 * [DAMAGED] but keeps the decoded text.
 *
 * Pure Kotlin so the rules are unit-tested without audio. Times are in samples at
 * [sampleRate]; the caller supplies the frame's start sample and the stream position
 * for [tick].
 */
class JttyMessageAssembler(
    private val sampleRate: Int = 12_000,
    private val sampleToUtcMillis: (Long) -> Long,
) {

    /** An in-progress or finished message plus the bookkeeping to extend it. */
    private class Open(
        var message: JttyMessage,
        /** Atoms joined but not yet trimmed/collapsed — TEXT5 padding spaces matter here. */
        var rawText: String,
        /** Extension bytes accumulated so far; empty for native messages. */
        val bytes: ByteArrayOutputStream,
        var lastStartSample: Long,
        /** Frequency of the frame that opened this message; never updated. */
        var anchorFreqHz: Float,
        var lastWasText5: Boolean,
        val ext: Boolean,
    )

    private val open = ArrayList<Open>()
    private var nextId = 1L

    /** Frame period in samples (59 symbols × 384 samples at 12 kHz = 1.888 s). */
    private val framePeriod: Long = (FRAME_SECONDS * sampleRate).toLong()

    /**
     * Fold one accepted frame in. Returns the message it produced or extended (so the
     * caller can publish/replace that row), never null.
     */
    fun onFrame(frame: JttyCodec.DecodedFrame): JttyMessage {
        val ext = frame.ext
        val candidate = findContinuation(frame.startSample, frame.freqHz, ext)
        if (candidate != null) {
            val gapPeriods = periodsBetween(candidate.lastStartSample, frame.startSample)
            if (ext) {
                if (gapPeriods > 1) {
                    repeat(gapPeriods - 1) { candidate.bytes.write(GAP_BYTES) }
                }
                candidate.bytes.write(frame.data ?: ByteArray(0))
                val all = candidate.bytes.toByteArray()
                val text = if (frame.eom) finalExtText(all) else partialExtText(all)
                val merged = candidate.message.copy(
                    text = text,
                    snrDb = maxOf(candidate.message.snrDb, frame.snrDb),
                    frames = candidate.message.frames + 1,
                    complete = frame.eom,
                    ext = true,
                )
                candidate.message = merged
                candidate.lastStartSample = frame.startSample
                if (frame.eom) open.remove(candidate)
                return merged
            }
            val text5 = isText5(frame.payload)
            val joiner = if (gapPeriods > 1) " ... " else joinerFor(candidate.lastWasText5, text5)
            val raw = candidate.rawText + joiner + frame.text
            val merged = candidate.message.copy(
                text = display(raw),
                snrDb = maxOf(candidate.message.snrDb, frame.snrDb),
                frames = candidate.message.frames + 1,
                complete = frame.eom,
            )
            candidate.message = merged
            candidate.rawText = raw
            candidate.lastStartSample = frame.startSample
            candidate.lastWasText5 = text5
            if (frame.eom) open.remove(candidate)
            return merged
        }

        val initialText = if (ext) {
            val bytes = frame.data ?: ByteArray(0)
            if (frame.eom) finalExtText(bytes) else partialExtText(bytes)
        } else {
            display(frame.text)
        }
        val fresh = JttyMessage(
            id = nextId++,
            utcMillis = sampleToUtcMillis(frame.startSample),
            freqHz = frame.freqHz,
            snrDb = frame.snrDb,
            text = initialText,
            complete = frame.eom,
            ext = ext,
        )
        if (!frame.eom) {
            if (open.size >= MAX_OPEN) open.removeAt(0)
            val buffer = ByteArrayOutputStream()
            if (ext) buffer.write(frame.data ?: ByteArray(0))
            open.add(
                Open(
                    fresh,
                    if (ext) "" else frame.text,
                    buffer,
                    frame.startSample,
                    frame.freqHz,
                    if (ext) false else isText5(frame.payload),
                    ext,
                ),
            )
        }
        return fresh
    }

    /**
     * Advance the clock to [nowSample]: every open message whose last frame is more than
     * [FLUSH_PERIODS] periods old is closed as incomplete and returned so the caller can
     * mark its row.
     */
    fun tick(nowSample: Long): List<JttyMessage> {
        if (open.isEmpty()) return emptyList()
        val flushed = ArrayList<JttyMessage>()
        val it = open.iterator()
        while (it.hasNext()) {
            val o = it.next()
            if (nowSample - o.lastStartSample > (FLUSH_PERIODS + 1) * framePeriod) {
                val done = o.message.copy(complete = true, flushed = true)
                o.message = done
                flushed.add(done)
                it.remove()
            }
        }
        return flushed
    }

    /** Drop every open message (band change, operator Clear). */
    fun clear() {
        open.clear()
    }

    private fun findContinuation(startSample: Long, freqHz: Float, ext: Boolean): Open? {
        var best: Open? = null
        var bestFreqErr = Float.MAX_VALUE
        for (o in open) {
            if (o.ext != ext) continue
            val n = periodsBetween(o.lastStartSample, startSample)
            if (n < 1 || n > MAX_CONTINUATION_PERIODS) continue
            val expected = o.lastStartSample + n * framePeriod
            val timeErrS = Math.abs(startSample - expected) / sampleRate.toFloat()
            if (timeErrS > CONTINUATION_TOLERANCE_S) continue
            val freqErr = Math.abs(freqHz - o.anchorFreqHz)
            if (freqErr > continuationFreqToleranceHz(n)) continue
            if (freqErr < bestFreqErr) {
                bestFreqErr = freqErr
                best = o
            }
        }
        return best
    }

    /** Whole frame periods between two frame starts, rounded to the nearest. */
    private fun periodsBetween(fromSample: Long, toSample: Long): Int =
        Math.round((toSample - fromSample).toDouble() / framePeriod).toInt()

    companion object {
        const val FRAME_SECONDS = 1.888
        const val MAX_CONTINUATION_PERIODS = 3
        const val FLUSH_PERIODS = 3
        const val MAX_OPEN = 30
        /** ±0.1 s around the expected continuation start. */
        const val CONTINUATION_TOLERANCE_S = 0.1f

        /** Appended when the extension message CRC-8 does not match. */
        const val DAMAGED = "[校验缺失]"

        /** One missing extension frame: U+FFFD + space, four bytes to keep alignment. */
        val GAP_BYTES = byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte(), 0x20)

        private val SPACE_RUN = Regex(" {2,}")

        /** ±10 Hz for every continuation gap. */
        @Suppress("UNUSED_PARAMETER")
        fun continuationFreqToleranceHz(n: Int): Float = 10f

        /**
         * Whether a 34-bit payload is a TEXT5 atom: the top-level type `i2` is the two
         * low bits of the 32-bit source word, which sits above the reserved and EOM bits
         * (docs/jtty-protocol.md §3), and `i2 = 3` is free text.
         */
        fun isText5(payload34: Long): Boolean = ((payload34 ushr 2) and 3L) == 3L

        /**
         * How two consecutive atoms join. TEXT5 atoms are 5-character slices of one piece
         * of free text and concatenate directly (the codec keeps their padding spaces, so
         * `HELLO` + ` WORL` + `D    ` reads back as `HELLO WORLD`); any pair involving a
         * structured atom (`CQ K1ABC CQ`, `599 001`) is separated by a single space.
         */
        fun joinerFor(previousWasText5: Boolean, nextIsText5: Boolean): String =
            if (previousWasText5 && nextIsText5) "" else " "

        /** The operator-facing form of the joined atoms: trimmed, padding runs collapsed. */
        fun display(raw: String): String = raw.trim().replace(SPACE_RUN, " ")

        /** CRC-8/ATM (poly 0x07, init 0) over the first [length] bytes. */
        fun crc8(data: ByteArray, length: Int = data.size): Int {
            var crc = 0
            for (i in 0 until length) {
                crc = crc xor (data[i].toInt() and 0xFF)
                repeat(8) {
                    crc = if ((crc and 0x80) != 0) {
                        ((crc shl 1) xor 0x07) and 0xFF
                    } else {
                        (crc shl 1) and 0xFF
                    }
                }
            }
            return crc
        }

        /** Decode the bytes received so far; trailing padding spaces are hidden. */
        fun partialExtText(bytes: ByteArray): String =
            String(bytes, StandardCharsets.UTF_8).trimEnd(' ')

        /**
         * Decode a complete extension message: drop the CRC-8 byte, hide trailing
         * padding, and append [DAMAGED] when the CRC does not match.
         */
        fun finalExtText(bytes: ByteArray): String {
            if (bytes.size < 2) return DAMAGED
            val crcOk = crc8(bytes, bytes.size - 1) == (bytes.last().toInt() and 0xFF)
            val content = String(bytes, 0, bytes.size - 1, StandardCharsets.UTF_8).trimEnd(' ')
            return if (crcOk) content else content + DAMAGED
        }
    }
}
