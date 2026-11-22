package radio.ks3ckc.jttyaf.jtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The frame-to-message chaining rules (docs/jtty-protocol.md §4) without any audio:
 * continuation within n frame periods and 10+3(n-1) Hz, `...` on a skipped period,
 * EOM completion, flush after three silent periods, and the join rules between atoms.
 */
class JttyMessageAssemblerTest {

    private val period = (JttyMessageAssembler.FRAME_SECONDS * 12_000).toLong()

    /** A structured atom by default; [text5] marks the payload as free text (`i2 = 3`). */
    private fun frame(
        start: Long,
        text: String,
        eom: Boolean,
        freq: Float = 1500f,
        snr: Float = -5f,
        text5: Boolean = false,
    ) = JttyCodec.DecodedFrame().apply {
        startSample = start
        this.text = text
        this.eom = eom
        freqHz = freq
        snrDb = snr
        payload = (if (text5) TEXT5_PAYLOAD else CALL_PAYLOAD) or (if (eom) 1L else 0L)
    }

    private companion object {
        /** `CQ K1ABC CQ`, EOM clear (docs/jtty-protocol.md §3.4). */
        const val CALL_PAYLOAD = 0x026F78D40L
        /** TEXT5 `HELLO`, EOM clear. */
        const val TEXT5_PAYLOAD = 0x11395558CL
    }

    private fun assembler() = JttyMessageAssembler(12_000) { s -> s * 1000L / 12_000 }

    @Test
    fun singleFrameWithEomIsCompleteMessage() {
        val m = assembler().onFrame(frame(0, "CQ K1ABC CQ", eom = true))
        assertThat(m.text).isEqualTo("CQ K1ABC CQ")
        assertThat(m.complete).isTrue()
        assertThat(m.frames).isEqualTo(1)
        assertThat(m.freqHz).isEqualTo(1500f)
    }

    @Test
    fun consecutiveFramesChainIntoOneMessage() {
        val a = assembler()
        val first = a.onFrame(frame(1000, "W9XYZ", eom = false))
        val second = a.onFrame(frame(1000 + period + 12, "599 107", eom = true, freq = 1503f, snr = -2f))
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.text).isEqualTo("W9XYZ 599 107")
        assertThat(second.complete).isTrue()
        assertThat(second.frames).isEqualTo(2)
        // Strongest frame wins the reported S/N.
        assertThat(second.snrDb).isEqualTo(-2f)
    }

    @Test
    fun text5RunsConcatenateWithoutExtraSpaces() {
        val a = assembler()
        a.onFrame(frame(0, "HELLO", eom = false, text5 = true))
        val m = a.onFrame(frame(period, " WORL", eom = false, text5 = true))
        val n = a.onFrame(frame(2 * period, "D    ", eom = true, text5 = true))
        assertThat(m.text).isEqualTo("HELLO WORL")
        assertThat(n.text).isEqualTo("HELLO WORLD")
    }

    @Test
    fun structuredAtomAfterPaddedTextGetsOneSpace() {
        val a = assembler()
        a.onFrame(frame(0, "HI   ", eom = false, text5 = true))
        val m = a.onFrame(frame(period, "K1ABC", eom = true))
        assertThat(m.text).isEqualTo("HI K1ABC")
    }

    @Test
    fun skippedPeriodInsertsEllipsis() {
        val a = assembler()
        a.onFrame(frame(0, "CQ K1ABC CQ", eom = false))
        val m = a.onFrame(frame(2 * period, "FN42", eom = true))
        assertThat(m.text).isEqualTo("CQ K1ABC CQ ... FN42")
    }

    @Test
    fun frameOutsideFrequencyWindowStartsNewMessage() {
        val a = assembler()
        val first = a.onFrame(frame(0, "W9XYZ", eom = false, freq = 1500f))
        // 50 ms off the frame grid: timing is loose, so the frequency window applies.
        val other = a.onFrame(frame(period + 600, "599 001", eom = true, freq = 1530f))
        assertThat(other.id).isNotEqualTo(first.id)
        assertThat(other.text).isEqualTo("599 001")
    }

    @Test
    fun tightlyTimedFrameOutsideTenHertzStartsNewMessage() {
        val a = assembler()
        val first = a.onFrame(frame(0, "W9XYZ", eom = false, freq = 1600f))
        // Timing alone no longer bypasses the ±10 Hz window.
        val other = a.onFrame(frame(period + 2, "599 001", eom = true, freq = 1780f))
        assertThat(other.id).isNotEqualTo(first.id)
        assertThat(other.text).isEqualTo("599 001")
    }

    @Test
    fun frameOutsideTimingWindowStartsNewMessage() {
        val a = assembler()
        val first = a.onFrame(frame(0, "W9XYZ", eom = false))
        // Half a frame late: not a continuation.
        val other = a.onFrame(frame(period + period / 2, "599 001", eom = true))
        assertThat(other.id).isNotEqualTo(first.id)
    }

    @Test
    fun frequencyToleranceIsTenHertzForEveryGap() {
        assertThat(JttyMessageAssembler.continuationFreqToleranceHz(1)).isEqualTo(10f)
        assertThat(JttyMessageAssembler.continuationFreqToleranceHz(3)).isEqualTo(10f)
        val a = assembler()
        a.onFrame(frame(0, "W9XYZ", eom = false, freq = 1500f))
        // 50 ms off the frame grid: the fixed ±10 Hz window rejects 15 Hz.
        val far = a.onFrame(frame(2 * period + 600, "599 001", eom = true, freq = 1515f))
        assertThat(far.text).isEqualTo("599 001")
    }

    @Test
    fun openMessageIsFlushedAfterThreeSilentPeriods() {
        val a = assembler()
        val first = a.onFrame(frame(0, "W9XYZ", eom = false))
        assertThat(a.tick(3 * period)).isEmpty()
        val flushed = a.tick(5 * period)
        assertThat(flushed).hasSize(1)
        assertThat(flushed[0].id).isEqualTo(first.id)
        assertThat(flushed[0].complete).isTrue()
        assertThat(flushed[0].flushed).isTrue()
        // Once flushed it no longer accepts continuations.
        val later = a.onFrame(frame(5 * period, "599 001", eom = true))
        assertThat(later.id).isNotEqualTo(first.id)
    }

    @Test
    fun clearDropsOpenMessages() {
        val a = assembler()
        val first = a.onFrame(frame(0, "W9XYZ", eom = false))
        a.clear()
        val next = a.onFrame(frame(period, "599 001", eom = true))
        assertThat(next.id).isNotEqualTo(first.id)
    }

    @Test
    fun joinerRules() {
        assertThat(JttyMessageAssembler.joinerFor(previousWasText5 = true, nextIsText5 = true)).isEqualTo("")
        assertThat(JttyMessageAssembler.joinerFor(previousWasText5 = false, nextIsText5 = false)).isEqualTo(" ")
        assertThat(JttyMessageAssembler.joinerFor(previousWasText5 = true, nextIsText5 = false)).isEqualTo(" ")
        assertThat(JttyMessageAssembler.joinerFor(previousWasText5 = false, nextIsText5 = true)).isEqualTo(" ")
    }

    @Test
    fun text5IsReadFromThePayloadTypeBits() {
        assertThat(JttyMessageAssembler.isText5(TEXT5_PAYLOAD)).isTrue()
        assertThat(JttyMessageAssembler.isText5(TEXT5_PAYLOAD or 1L)).isTrue()
        assertThat(JttyMessageAssembler.isText5(CALL_PAYLOAD)).isFalse()
        // EXCH_NUM SERIAL 123 (i2 = 2) and ZONE_LOC3 (i2 = 0): structured.
        assertThat(JttyMessageAssembler.isText5(0x20007B009L)).isFalse()
        assertThat(JttyMessageAssembler.isText5(0x00B790D29L)).isFalse()
    }

    // ----- JTTY Unicode extension frames -------------------------------------

    private fun extFrame(
        start: Long,
        bytes: ByteArray,
        eom: Boolean,
        freq: Float = 1500f,
        snr: Float = -5f,
    ) = JttyCodec.DecodedFrame().apply {
        ext = true
        data = bytes
        this.eom = eom
        startSample = start
        freqHz = freq
        snrDb = snr
        text = ""
        payload = 0x2L or (if (eom) 1L else 0L)
    }

    /** Content padded to a whole number of frames with 0x20, then CRC-8. */
    private fun extMessage(content: ByteArray): ByteArray {
        val frames = (content.size + 1 + 3) / 4
        val total = frames * 4
        val padded = content.copyOf(total - 1).also { buf ->
            for (i in content.size until total - 1) buf[i] = 0x20
        }
        val out = padded.copyOf(total)
        out[total - 1] = JttyMessageAssembler.crc8(padded).toByte()
        return out
    }

    private fun chunks(message: ByteArray): List<ByteArray> =
        message.toList().chunked(4).map { it.toByteArray() }

    @Test
    fun extensionSingleFrameDecodes() {
        val message = extMessage("中".toByteArray(Charsets.UTF_8))
        val m = assembler().onFrame(extFrame(0, message, eom = true))
        assertThat(m.ext).isTrue()
        assertThat(m.text).isEqualTo("中")
        assertThat(m.complete).isTrue()
        assertThat(m.frames).isEqualTo(1)
    }

    @Test
    fun extensionMultiFrameSpansCharacterBoundary() {
        val message = extMessage("ABC中".toByteArray(Charsets.UTF_8))
        val parts = chunks(message)
        assertThat(parts).hasSize(2)
        val a = assembler()
        val first = a.onFrame(extFrame(0, parts[0], eom = false))
        val second = a.onFrame(extFrame(period, parts[1], eom = true))
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.text).isEqualTo("ABC中")
        assertThat(second.complete).isTrue()
        assertThat(second.frames).isEqualTo(2)
    }

    @Test
    fun extensionMissingFrameInsertsPlaceholderAndDamage() {
        val message = extMessage("ABCDEFGH".toByteArray(Charsets.UTF_8))
        val parts = chunks(message)
        assertThat(parts).hasSize(3)
        val a = assembler()
        a.onFrame(extFrame(0, parts[0], eom = false))
        // Skip parts[1]: two periods later the frame continues with a gap.
        val m = a.onFrame(extFrame(2 * period, parts[2], eom = true))
        assertThat(m.text).contains("\uFFFD")
        assertThat(m.text).startsWith("ABCD")
        assertThat(m.text).endsWith(JttyMessageAssembler.DAMAGED)
    }

    @Test
    fun extensionBadCrcAppendsDamageMarker() {
        val message = extMessage("Hi".toByteArray(Charsets.UTF_8))
        message[message.size - 1] = (message[message.size - 1].toInt() xor 0xFF).toByte()
        val m = assembler().onFrame(extFrame(0, message, eom = true))
        assertThat(m.text).isEqualTo("Hi" + JttyMessageAssembler.DAMAGED)
    }

    @Test
    fun extensionAndNativeDoNotChain() {
        val a = assembler()
        val ext = a.onFrame(extFrame(0, extMessage(byteArrayOf(0x41)), eom = false))
        val native = a.onFrame(frame(period, "W9XYZ", eom = true))
        assertThat(native.id).isNotEqualTo(ext.id)
        assertThat(native.ext).isFalse()
    }
}
