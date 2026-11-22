package radio.ks3ckc.jttyaf.jtty

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The keyboard transmitter against a fake codec and synchronous worker: validation,
 * the key-down / play / key-up order with the un-key guaranteed, cancellation and the
 * completion callbacks. Robolectric only so the LiveData posts land on a main looper
 * the test can drain.
 */
@RunWith(RobolectricTestRunner::class)
class JttyTransmitterTest {

    /** Deliver the LiveData values the transmitter posted from the (synchronous) worker. */
    private fun drainMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    /** Packs 5 characters per native frame, 4 bytes per extension frame; refuses "BAD". */
    private class FakeCodec : JttyCodec {
        var encoded: String? = null
        var encodedBytes: ByteArray? = null
        var lastRate = 0
        var lastAmplitude = 0f
        override fun packFrames(text: String, profile: Int): Int =
            if (text.contains("BAD")) -3 else (text.length + 4) / 5
        override fun packBytes(data: ByteArray): Int =
            if (data.size > JttyTransmitter.MAX_UNICODE_BYTES) {
                -2
            } else {
                (data.size + 1 + 3) / 4
            }
        override fun encode(text: String, profile: Int, f0Hz: Float, sampleRate: Int, amplitude: Float): FloatArray {
            encoded = text
            lastRate = sampleRate
            lastAmplitude = amplitude
            return FloatArray(packFrames(text, profile) * 59 * samplesPerFrame(sampleRate))
        }
        override fun encodeBytes(data: ByteArray, f0Hz: Float, sampleRate: Int, amplitude: Float): FloatArray {
            encodedBytes = data
            lastRate = sampleRate
            lastAmplitude = amplitude
            return FloatArray(packBytes(data) * 59 * samplesPerFrame(sampleRate))
        }
        private fun samplesPerFrame(sampleRate: Int): Int = sampleRate * 32 / 1000
        override fun newReceiver(sampleRate: Int): JttyCodec.Receiver = throw UnsupportedOperationException()
    }

    private class Recorder : JttyTransmitter.Keyer, JttyTransmitter.Player {
        val events = ArrayList<String>()
        var playResult = true
        var throwOnPlay = false
        override fun keyDown() { events += "keyDown" }
        override fun keyUp() { events += "keyUp" }
        override fun play(buffer: FloatArray, sampleRate: Int): Boolean {
            events += "play(${buffer.size}@$sampleRate)"
            if (throwOnPlay) throw IllegalStateException("boom")
            return playResult
        }
        override fun cancel() { events += "cancel" }
    }

    private fun transmitter(
        codec: FakeCodec,
        rec: Recorder,
        tuneActive: Boolean = false,
        txTap: JttyTransmitter.TxTap? = null,
        log: (String) -> Unit = {},
    ) =
        JttyTransmitter(
            codec = codec,
            keyer = rec,
            player = rec,
            tuneActive = { tuneActive },
            txTap = txTap,
            sampleRateSource = { 12_000 },
            txSampleRateSource = { 12_000 },
            f0Source = { 1500f },
            profileSource = { 0 },
            settleDelayMsSource = { 0L },
            log = log,
            clock = { 0L },
            workerRunner = { it.run() },
        )

    @Test
    fun checkReportsFramesAndRejections() {
        val tx = transmitter(FakeCodec(), Recorder())
        assertThat(tx.check("")).isEqualTo(JttyTransmitter.Reject.EMPTY to 0)
        assertThat(tx.check("hello world")).isEqualTo(JttyTransmitter.Reject.NONE to 3)
        // 81 characters truncate to 80 -> 16 frames, not a rejection.
        assertThat(tx.check("x".repeat(81))).isEqualTo(JttyTransmitter.Reject.NONE to 16)
        assertThat(tx.check("BAD")).isEqualTo(JttyTransmitter.Reject.UNPACKABLE to 0)
    }

    @Test
    fun sendKeysPlaysAndUnkeysInOrderAndReportsText() {
        val codec = FakeCodec()
        val rec = Recorder()
        val tx = transmitter(codec, rec)
        var transmitted: String? = null
        var ended = 0
        tx.setOnTransmitted { transmitted = it }
        tx.setOnTxEnd { ended++ }

        assertThat(tx.send("  cq   k1abc cq ")).isEqualTo(JttyTransmitter.Reject.NONE)
        drainMainLooper()

        assertThat(codec.encoded).isEqualTo("CQ K1ABC CQ")
        assertThat(rec.events).containsExactly("keyDown", "play(${3 * 59 * 384}@12000)", "keyUp").inOrder()
        assertThat(transmitted).isEqualTo("CQ K1ABC CQ")
        assertThat(ended).isEqualTo(1)
        assertThat(tx.isTransmittingNow()).isFalse()
        assertThat(tx.txProgress.value).isEqualTo(1f)
    }

    @Test
    fun playFailureStillUnkeysAndDoesNotReportTransmitted() {
        val rec = Recorder().apply { throwOnPlay = true }
        val tx = transmitter(FakeCodec(), rec)
        var transmitted: String? = null
        var ended = 0
        tx.setOnTransmitted { transmitted = it }
        tx.setOnTxEnd { ended++ }
        tx.send("HELLO")
        drainMainLooper()
        assertThat(rec.events.last()).isEqualTo("keyUp")
        assertThat(transmitted).isNull()
        assertThat(ended).isEqualTo(1)
        assertThat(tx.txProgress.value).isEqualTo(0f)
    }

    @Test
    fun rejectedWhileTuneCarrierIsUp() {
        val rec = Recorder()
        val tx = transmitter(FakeCodec(), rec, tuneActive = true)
        assertThat(tx.send("HELLO")).isEqualTo(JttyTransmitter.Reject.TUNE_ACTIVE)
        assertThat(rec.events).isEmpty()
    }

    @Test
    fun cancelWhenIdleIsNoOp() {
        val rec = Recorder()
        val tx = transmitter(FakeCodec(), rec)
        tx.cancel()
        assertThat(rec.events).isEmpty()
    }

    @Test
    fun progressFractionIsCappedBelowOne() {
        assertThat(JttyTransmitter.txProgressFraction(0, 1000)).isEqualTo(0f)
        assertThat(JttyTransmitter.txProgressFraction(500, 1000)).isEqualTo(0.5f)
        assertThat(JttyTransmitter.txProgressFraction(5000, 1000)).isEqualTo(JttyTransmitter.MAX_TICKER_PROGRESS)
        assertThat(JttyTransmitter.txProgressFraction(10, 0)).isEqualTo(0f)
    }

    @Test
    fun normalizeUppercasesAndCollapsesWhitespace() {
        assertThat(JttyTransmitter.normalize("  cq\t k1abc   cq ")).isEqualTo("CQ K1ABC CQ")
    }

    @Test
    fun txTapSeesTheExactBufferBeforeKeying() {
        val codec = FakeCodec()
        val rec = Recorder()
        val tap = JttyTransmitter.TxTap { buffer, rate -> rec.events += "tap(${buffer.size}@$rate)" }
        val tx = transmitter(codec, rec, txTap = tap)

        assertThat(tx.send("cq k1abc cq")).isEqualTo(JttyTransmitter.Reject.NONE)
        drainMainLooper()

        val n = 3 * 59 * 384
        assertThat(rec.events).containsExactly("tap($n@12000)", "keyDown", "play($n@12000)", "keyUp").inOrder()
    }

    @Test
    fun aThrowingTxTapIsLoggedAndTheOverStillGoesOut() {
        val codec = FakeCodec()
        val rec = Recorder()
        val logs = ArrayList<String>()
        val tap = JttyTransmitter.TxTap { _, _ -> throw IllegalStateException("disk full") }
        val tx = transmitter(codec, rec, txTap = tap, log = { logs += it })
        var transmitted: String? = null
        tx.setOnTransmitted { transmitted = it }

        assertThat(tx.send("cq k1abc cq")).isEqualTo(JttyTransmitter.Reject.NONE)
        drainMainLooper()

        assertThat(rec.events).containsExactly("keyDown", "play(${3 * 59 * 384}@12000)", "keyUp").inOrder()
        assertThat(transmitted).isEqualTo("CQ K1ABC CQ")
        assertThat(logs.any { it.contains("tap failed") && it.contains("disk full") }).isTrue()
    }

    @Test
    fun noTapByDefault() {
        val rec = Recorder()
        val tx = transmitter(FakeCodec(), rec)
        tx.send("cq k1abc cq")
        drainMainLooper()
        assertThat(rec.events.none { it.startsWith("tap") }).isTrue()
    }

    @Test
    fun unicodeTextUsesBytePathAndKeepsCase() {
        val codec = FakeCodec()
        val tx = transmitter(codec, Recorder())
        assertThat(tx.check("中")).isEqualTo(JttyTransmitter.Reject.NONE to 1)
        assertThat(tx.send("中")).isEqualTo(JttyTransmitter.Reject.NONE)
        drainMainLooper()
        assertThat(codec.encoded).isNull()
        assertThat(codec.encodedBytes).isEqualTo("中".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun nativeTextStillGoesThroughPackText() {
        val codec = FakeCodec()
        val tx = transmitter(codec, Recorder())
        assertThat(tx.send("cq k1abc cq")).isEqualTo(JttyTransmitter.Reject.NONE)
        drainMainLooper()
        assertThat(codec.encoded).isEqualTo("CQ K1ABC CQ")
        assertThat(codec.encodedBytes).isNull()
    }

    @Test
    fun unicodeOver63BytesTruncates() {
        val codec = FakeCodec()
        val tx = transmitter(codec, Recorder())
        val text = "@".repeat(64)
        assertThat(tx.check(text)).isEqualTo(JttyTransmitter.Reject.NONE to 16)
        assertThat(tx.send(text)).isEqualTo(JttyTransmitter.Reject.NONE)
        drainMainLooper()
        assertThat(codec.encodedBytes!!.size).isEqualTo(63)
    }

    @Test
    fun unicodeTruncatesOnCharacterBoundary() {
        val codec = FakeCodec()
        val tx = transmitter(codec, Recorder())
        val text = "中".repeat(22) // 66 UTF-8 bytes
        assertThat(tx.send(text)).isEqualTo(JttyTransmitter.Reject.NONE)
        drainMainLooper()
        assertThat(codec.encodedBytes!!.size).isEqualTo(63)
        assertThat(codec.encodedBytes!!.toString(Charsets.UTF_8)).isEqualTo("中".repeat(21))
    }

    @Test
    fun nativeTextTruncatesTo80() {
        val codec = FakeCodec()
        val tx = transmitter(codec, Recorder())
        assertThat(tx.send("x".repeat(81))).isEqualTo(JttyTransmitter.Reject.NONE)
        drainMainLooper()
        assertThat(codec.encoded).isEqualTo("X".repeat(80))
    }

    @Test
    fun mixedTextUsesBytes() {
        val codec = FakeCodec()
        val tx = transmitter(codec, Recorder())
        assertThat(tx.send("CQ 中")).isEqualTo(JttyTransmitter.Reject.NONE)
        drainMainLooper()
        assertThat(codec.encodedBytes).isEqualTo("CQ 中".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun defaultTransmitRateIs48k() {
        val rec = Recorder()
        val codec = FakeCodec()
        val tx = JttyTransmitter(
            codec = codec,
            keyer = rec,
            player = rec,
            sampleRateSource = { 48_000 },
            f0Source = { 1500f },
            profileSource = { 0 },
            settleDelayMsSource = { 0L },
            log = {},
            clock = { 0L },
            workerRunner = { it.run() },
        )
        assertThat(tx.send("cq")).isEqualTo(JttyTransmitter.Reject.NONE)
        drainMainLooper()
        // One frame = 59 symbols x 1536 samples at 48 kHz, played without resampling.
        assertThat(rec.events).contains("play(${59 * 1536}@48000)")
        assertThat(codec.lastRate).isEqualTo(48_000)
        assertThat(codec.lastAmplitude).isEqualTo(NativeJttyCodec.ENCODE_AMPLITUDE)
    }
}
