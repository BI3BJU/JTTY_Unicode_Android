package radio.ks3ckc.jttyaf.jtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Duplicate-decode collapsing: the receiver can accept one transmission at several
 * frequency estimates (the field log showed 1440 / 1500 / 1560 Hz for a 1500 Hz over),
 * and copies can arrive in different decode steps. These rules keep one row.
 */
class JttyFrameDeduperTest {

    private fun frame(
        start: Long,
        freq: Float,
        snr: Float,
        text: String = "AAAAA",
        ext: Boolean = false,
    ) = JttyCodec.DecodedFrame().apply {
        startSample = start
        freqHz = freq
        snrDb = snr
        this.text = text
        this.ext = ext
        eom = true
    }

    private fun message(
        id: Long,
        utc: Long,
        freq: Float,
        text: String,
        complete: Boolean = false,
        snr: Float = -5f,
        ext: Boolean = false,
    ) = JttyMessage(
        id = id,
        utcMillis = utc,
        freqHz = freq,
        snrDb = snr,
        text = text,
        complete = complete,
        ext = ext,
    )

    @Test
    fun sameFrameAtThreeFrequenciesKeepsTheStrongest() {
        val frames = listOf(
            frame(143_514, 1440f, -11f, "您�"),
            frame(143_514, 1560f, -10f, "您�"),
            frame(143_514, 1500f, 10f, "您好_Зд�"),
        )
        val kept = JttyFrameDeduper.dedupe(frames, 12_000)
        assertThat(kept).hasSize(1)
        assertThat(kept[0].freqHz).isEqualTo(1500f)
        assertThat(kept[0].text).isEqualTo("您好_Зд�")
    }

    @Test
    fun sameStartFarFrequencyIsStillOneSignal() {
        // Field log: one 1600 Hz frame reported at 1320 / 1600 / 1780 Hz.
        val frames = listOf(
            frame(0, 1320f, -15f),
            frame(0, 1780f, -12f),
            frame(0, 1600f, 12f),
        )
        val kept = JttyFrameDeduper.dedupe(frames, 12_000)
        assertThat(kept).hasSize(1)
        assertThat(kept[0].freqHz).isEqualTo(1600f)
    }

    @Test
    fun nextFramePeriodIsNotMerged() {
        val period = (JttyMessageAssembler.FRAME_SECONDS * 12_000).toLong()
        val frames = listOf(frame(0, 1500f, -5f), frame(period, 1500f, -5f))
        assertThat(JttyFrameDeduper.dedupe(frames, 12_000)).hasSize(2)
    }

    @Test
    fun nativeAndExtensionFramesAreNotMerged() {
        val frames = listOf(frame(0, 1500f, -5f), frame(0, 1500f, -5f, ext = true))
        assertThat(JttyFrameDeduper.dedupe(frames, 12_000)).hasSize(2)
    }

    @Test
    fun snapshotIsIndependentOfTheReusedHolder() {
        val live = frame(10, 1500f, -3f, "abc").apply { data = byteArrayOf(1, 2, 3, 4) }
        val copy = JttyFrameDeduper.snapshot(live)
        live.text = "changed"
        live.data!![0] = 9
        assertThat(copy.text).isEqualTo("abc")
        assertThat(copy.data!![0]).isEqualTo(1.toByte())
    }

    @Test
    fun sameFamilyIgnoresFrequencyOffsetWithinOneSignal() {
        val a = message(1, 1_000, 1440f, "您�")
        val b = message(2, 1_000, 1500f, "您好_Зд�")
        assertThat(JttyFrameDeduper.sameFamily(a, b)).isTrue()
    }

    @Test
    fun fragmentsAtDifferentFramesMergeByTextPrefix() {
        val first = message(1, 1_000, 1600f, "您好_Здра", complete = false)
        val later = message(2, 1_000 + 2 * 1_888, 1420f, "您好_Здра", complete = false)
        assertThat(JttyFrameDeduper.sameFamily(first, later)).isTrue()
    }

    @Test
    fun completeMessagesAreNotMergedByPrefix() {
        val a = message(1, 1_000, 1500f, "CQ K1ABC", complete = true)
        val b = message(2, 6_000, 1500f, "CQ K1ABC", complete = true)
        assertThat(JttyFrameDeduper.sameFamily(a, b)).isFalse()
    }

    @Test
    fun differentStartTimeIsADifferentMessage() {
        val a = message(1, 1_000, 1500f, "A")
        val b = message(2, 4_000, 1500f, "B")
        assertThat(JttyFrameDeduper.sameFamily(a, b)).isFalse()
    }

    @Test
    fun betterPrefersCompleteThenLongerText() {
        val partial = message(1, 0, 1500f, "您", complete = false, snr = 10f)
        val complete = message(2, 0, 1500f, "您", complete = true, snr = -10f)
        val longer = message(3, 0, 1500f, "您好", complete = false, snr = -10f)
        assertThat(JttyFrameDeduper.better(complete, partial)).isTrue()
        assertThat(JttyFrameDeduper.better(partial, complete)).isFalse()
        assertThat(JttyFrameDeduper.better(longer, partial)).isTrue()
    }
}
