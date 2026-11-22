package radio.ks3ckc.jttyaf.jtty

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Transmit-chain checks against the real native codec (`libjttyaf.so`), on a device or
 * emulator. Unlike the JVM tests these generate the actual 48 kHz waveform, decimate it
 * to the receiver's 12 kHz and decode it again — so a wrong sample rate, frame length,
 * tone frequency, amplitude or clipping in the TX path fails here.
 *
 * AudioTrack playback and the speaker/microphone path are still outside this test; it
 * proves the codec output, which is what the app hands to the player.
 */
@RunWith(AndroidJUnit4::class)
class JttyTxQualityTest {

    private val codec = NativeJttyCodec()

    /** 48 kHz -> 12 kHz: 4-sample box average (the JTTY band is far below 6 kHz). */
    private fun decimate4(src: FloatArray): FloatArray {
        val n = src.size / 4
        return FloatArray(n) { i ->
            (src[4 * i] + src[4 * i + 1] + src[4 * i + 2] + src[4 * i + 3]) * 0.25f
        }
    }

    /** Decode the first native or extension frame out of a generated 48 kHz waveform. */
    private fun decode(audio48: FloatArray, ext: Boolean): JttyCodec.DecodedFrame? {
        val rx = codec.newReceiver(12_000)
        rx.use {
            it.setSearchBand(300f, 2700f)
            it.setRxFrequency(1600f, 50f)
            it.setDecodeBudget(24)
            val pcm = decimate4(audio48)
            it.push(pcm, pcm.size)
            // Four seconds of silence so the last analysis window is complete.
            val silence = FloatArray(48_000)
            it.push(silence, silence.size)
            val out = JttyCodec.DecodedFrame()
            while (it.pop(out)) {
                if (out.ext == ext) return out
            }
        }
        return null
    }

    private fun peakOf(audio: FloatArray): Float = audio.maxOf { abs(it) }

    @Test
    fun nativeWaveformAt48kDecodesCleanly() {
        val audio = codec.encode("CQ K1ABC CQ", 0, 1600f, 48_000, 0.95f)
        assertEquals(59 * 1536, audio.size)
        val peak = peakOf(audio)
        assertTrue("peak=$peak", peak in 0.5f..1.0f)
        assertTrue("clipped sample", audio.all { abs(it) <= 1.0001f })

        val frame = decode(audio, ext = false)
        assertNotNull("no native frame decoded", frame)
        assertEquals("CQ K1ABC CQ", frame!!.text)
        assertEquals(1600f, frame.freqHz, 5f)
        assertTrue("snr=${frame.snrDb}", frame.snrDb > 5f)
    }

    @Test
    fun unicodeWaveformAt48kCarriesUtf8() {
        val bytes = "中".toByteArray(Charsets.UTF_8)
        val audio = codec.encodeBytes(bytes, 1600f, 48_000, 0.95f)
        assertEquals(59 * 1536, audio.size)

        val frame = decode(audio, ext = true)
        assertNotNull("no extension frame decoded", frame)
        val data = frame!!.data
        assertNotNull("extension frame has no bytes", data)
        // E4 B8 AD plus the CRC-8 of the padded message (verified vector).
        assertArrayEquals(
            byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte(), 0xC8.toByte()),
            data!!,
        )
    }

    @Test
    fun sixteenFrameMessageHasExpectedLength() {
        val bytes = ByteArray(63) { ('A'.code + it % 26).toByte() }
        assertEquals(16, codec.packBytes(bytes))
        val audio = codec.encodeBytes(bytes, 1600f, 48_000, 0.95f)
        assertEquals(16 * 59 * 1536, audio.size)
        val peak = peakOf(audio)
        assertTrue("peak=$peak", peak in 0.5f..1.0f)
    }

    @Test
    fun transmitterPipelinePlays48kNativeWaveform() {
        var played: FloatArray? = null
        var playedRate = 0
        val keyer = object : JttyTransmitter.Keyer {
            override fun keyDown() {}
            override fun keyUp() {}
        }
        val player = object : JttyTransmitter.Player {
            override fun play(buffer: FloatArray, sampleRate: Int): Boolean {
                played = buffer
                playedRate = sampleRate
                return true
            }
            override fun cancel() {}
        }
        val tx = JttyTransmitter(
            codec = codec,
            keyer = keyer,
            player = player,
            // The app's legacy 12 kHz setting must not leak into transmission any more.
            sampleRateSource = { 12_000 },
            f0Source = { 1600f },
            profileSource = { 0 },
            settleDelayMsSource = { 0L },
            log = {},
            workerRunner = { it.run() },
        )
        assertEquals(JttyTransmitter.Reject.NONE, tx.send("CQ K1ABC CQ"))
        assertEquals(48_000, playedRate)

        val audio = played
        assertNotNull("player never received audio", audio)
        assertEquals(59 * 1536, audio!!.size)
        val peak = peakOf(audio)
        assertTrue("peak=$peak", peak in 0.5f..1.0f)

        val frame = decode(audio, ext = false)
        assertNotNull("pipeline waveform did not decode", frame)
        assertEquals("CQ K1ABC CQ", frame!!.text)
    }
}
