package com.musicstudio.app.audio

import com.musicstudio.app.data.ReverbPreset
import kotlin.math.*

/**
 * DSP effects chain applied after pitch processing:
 *   Equalizer (3-band shelving) → Reverb → Echo → Compressor
 *
 * All processing is 16-bit mono PCM at [sampleRate].
 */
class EffectsChain(private val sampleRate: Int) {

    // ── Parameter setters (called from UI thread) ───────────────────────
    var reverbPreset: ReverbPreset = ReverbPreset.NONE
        set(value) { field = value; applyReverbPreset(value) }

    var echoDelayMs: Int   = 0     // 0 = off
    var echoDecay:   Float = 0.4f  // 0.0 – 1.0

    var eqBassDb:    Float = 0f    // ± 12 dB
    var eqMidDb:     Float = 0f
    var eqTrebleDb:  Float = 0f

    // ── Reverb state ────────────────────────────────────────────────────
    private var reverbEnabled  = false
    private var reverbDecay    = 0.5f
    private var reverbRoomSize = 0.7f
    private val NUM_COMBS      = 8
    private val NUM_ALLPASS    = 4

    // Freeverb-style comb filter delays (in samples at 44100 Hz)
    private val COMB_TUNINGS   = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val ALLPASS_TUNINGS = intArrayOf(556, 441, 341, 225)

    private val combBuffers    = Array(NUM_COMBS)    { idx -> FloatArray(scaleDelay(COMB_TUNINGS[idx])) }
    private val allpassBuffers = Array(NUM_ALLPASS)  { idx -> FloatArray(scaleDelay(ALLPASS_TUNINGS[idx])) }
    private val combPos        = IntArray(NUM_COMBS)
    private val allpassPos     = IntArray(NUM_ALLPASS)
    private var reverbWet      = 0.3f
    private var reverbDry      = 1.0f

    // ── Echo state ──────────────────────────────────────────────────────
    private var echoBuffer: FloatArray = FloatArray(1)
    private var echoWritePos = 0
    private var echoSamples  = 0

    // ── EQ biquad coefficients ─────────────────────────────────────────
    // Low shelf, peak, high shelf
    private var bassB0 = 1f; private var bassB1 = 0f; private var bassB2 = 0f
    private var bassA1 = 0f; private var bassA2 = 0f
    private var bassX1 = 0f; private var bassX2 = 0f; private var bassY1 = 0f; private var bassY2 = 0f

    private var midB0  = 1f; private var midB1  = 0f; private var midB2  = 0f
    private var midA1  = 0f; private var midA2  = 0f
    private var midX1  = 0f; private var midX2  = 0f;  private var midY1  = 0f; private var midY2  = 0f

    private var trebB0 = 1f; private var trebB1 = 0f; private var trebB2 = 0f
    private var trebA1 = 0f; private var trebA2 = 0f
    private var trebX1 = 0f; private var trebX2 = 0f; private var trebY1 = 0f; private var trebY2 = 0f

    init {
        applyReverbPreset(ReverbPreset.NONE)
        computeEqCoeffs()
    }

    // ── Main processing ─────────────────────────────────────────────────

    /** Apply the full effects chain to [frame]. Returns same-length output. */
    fun process(frame: ShortArray): ShortArray {
        val floats = FloatArray(frame.size) { frame[it] / 32768f }

        // 1. EQ
        applyEq(floats)

        // 2. Reverb
        if (reverbEnabled) applyReverb(floats)

        // 3. Echo
        if (echoDelayMs > 0) applyEcho(floats)

        // 4. Soft clip / limiter
        for (i in floats.indices) floats[i] = softClip(floats[i])

        return ShortArray(frame.size) { (floats[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    // ── EQ ──────────────────────────────────────────────────────────────

    fun applyEqSettings(bass: Float, mid: Float, treble: Float) {
        eqBassDb = bass; eqMidDb = mid; eqTrebleDb = treble
        computeEqCoeffs()
    }

    private fun computeEqCoeffs() {
        // Low shelf at 200 Hz
        computeLowShelf(200f, eqBassDb, bassB0, bassB1, bassB2, bassA1, bassA2).let {
            bassB0=it[0]; bassB1=it[1]; bassB2=it[2]; bassA1=it[3]; bassA2=it[4]
        }
        // Peak at 1kHz
        computePeakEq(1000f, eqMidDb, 1.2f, midB0, midB1, midB2, midA1, midA2).let {
            midB0=it[0]; midB1=it[1]; midB2=it[2]; midA1=it[3]; midA2=it[4]
        }
        // High shelf at 6kHz
        computeHighShelf(6000f, eqTrebleDb, trebB0, trebB1, trebB2, trebA1, trebA2).let {
            trebB0=it[0]; trebB1=it[1]; trebB2=it[2]; trebA1=it[3]; trebA2=it[4]
        }
    }

    private fun applyEq(buf: FloatArray) {
        for (i in buf.indices) {
            val x = buf[i]
            // Bass shelf
            var y = bassB0*x + bassB1*bassX1 + bassB2*bassX2 - bassA1*bassY1 - bassA2*bassY2
            bassX2=bassX1; bassX1=x; bassY2=bassY1; bassY1=y
            // Mid peak
            val x2 = y
            y = midB0*x2 + midB1*midX1 + midB2*midX2 - midA1*midY1 - midA2*midY2
            midX2=midX1; midX1=x2; midY2=midY1; midY1=y
            // Treble shelf
            val x3 = y
            y = trebB0*x3 + trebB1*trebX1 + trebB2*trebX2 - trebA1*trebY1 - trebA2*trebY2
            trebX2=trebX1; trebX1=x3; trebY2=trebY1; trebY1=y
            buf[i] = y
        }
    }

    // Biquad coefficients

    private fun computeLowShelf(fc: Float, gainDb: Float,
        b0:Float, b1:Float, b2:Float, a1:Float, a2:Float): FloatArray {
        val A   = 10f.pow(gainDb / 40f)
        val w0  = 2 * PI.toFloat() * fc / sampleRate
        val cos = cos(w0); val sin = sin(w0)
        val beta = sqrt(A) / 1.0f
        val b0o = A * ((A+1) - (A-1)*cos + 2*sqrt(A)*beta*sin)
        val b1o = 2*A * ((A-1) - (A+1)*cos)
        val b2o = A * ((A+1) - (A-1)*cos - 2*sqrt(A)*beta*sin)
        val a0o =     (A+1) + (A-1)*cos + 2*sqrt(A)*beta*sin
        val a1o = -2*((A-1) + (A+1)*cos)
        val a2o =     (A+1) + (A-1)*cos - 2*sqrt(A)*beta*sin
        return floatArrayOf(b0o/a0o, b1o/a0o, b2o/a0o, a1o/a0o, a2o/a0o)
    }

    private fun computePeakEq(fc: Float, gainDb: Float, q: Float,
        b0:Float, b1:Float, b2:Float, a1:Float, a2:Float): FloatArray {
        val A  = 10f.pow(gainDb / 40f)
        val w0 = 2 * PI.toFloat() * fc / sampleRate
        val alpha = sin(w0) / (2 * q)
        val b0o = 1 + alpha * A; val b1o = -2*cos(w0); val b2o = 1 - alpha * A
        val a0o = 1 + alpha / A; val a1o = -2*cos(w0); val a2o = 1 - alpha / A
        return floatArrayOf(b0o/a0o, b1o/a0o, b2o/a0o, a1o/a0o, a2o/a0o)
    }

    private fun computeHighShelf(fc: Float, gainDb: Float,
        b0:Float, b1:Float, b2:Float, a1:Float, a2:Float): FloatArray {
        val A   = 10f.pow(gainDb / 40f)
        val w0  = 2 * PI.toFloat() * fc / sampleRate
        val cos = cos(w0); val sin = sin(w0)
        val beta = sqrt(A) / 1.0f
        val b0o = A * ((A+1) + (A-1)*cos + 2*sqrt(A)*beta*sin)
        val b1o = -2*A * ((A-1) + (A+1)*cos)
        val b2o = A * ((A+1) + (A-1)*cos - 2*sqrt(A)*beta*sin)
        val a0o =      (A+1) - (A-1)*cos + 2*sqrt(A)*beta*sin
        val a1o = 2 * ((A-1) - (A+1)*cos)
        val a2o =      (A+1) - (A-1)*cos - 2*sqrt(A)*beta*sin
        return floatArrayOf(b0o/a0o, b1o/a0o, b2o/a0o, a1o/a0o, a2o/a0o)
    }

    // ── Freeverb-style reverb ───────────────────────────────────────────

    private fun applyReverbPreset(preset: ReverbPreset) {
        when (preset) {
            ReverbPreset.NONE       -> { reverbEnabled=false }
            ReverbPreset.SMALL_ROOM -> { reverbEnabled=true; reverbDecay=0.4f; reverbWet=0.15f }
            ReverbPreset.LARGE_ROOM -> { reverbEnabled=true; reverbDecay=0.6f; reverbWet=0.25f }
            ReverbPreset.HALL       -> { reverbEnabled=true; reverbDecay=0.75f; reverbWet=0.35f }
            ReverbPreset.CATHEDRAL  -> { reverbEnabled=true; reverbDecay=0.9f; reverbWet=0.45f }
            ReverbPreset.BATHROOM   -> { reverbEnabled=true; reverbDecay=0.3f; reverbWet=0.4f }
            ReverbPreset.SPRING     -> { reverbEnabled=true; reverbDecay=0.55f; reverbWet=0.3f }
        }
        reverbDry = 1.0f - reverbWet * 0.5f
        resetCombFilters()
    }

    private fun resetCombFilters() {
        combBuffers.forEach { it.fill(0f) }
        allpassBuffers.forEach { it.fill(0f) }
        combPos.fill(0); allpassPos.fill(0)
    }

    private fun applyReverb(buf: FloatArray) {
        for (i in buf.indices) {
            val input = buf[i]
            var output = 0f

            // 8 comb filters in parallel
            for (c in 0 until NUM_COMBS) {
                val delayed = combBuffers[c][combPos[c]]
                val filtered = delayed * reverbDecay
                combBuffers[c][combPos[c]] = input + filtered
                combPos[c] = (combPos[c] + 1) % combBuffers[c].size
                output += filtered
            }
            output /= NUM_COMBS

            // 4 allpass filters in series
            for (a in 0 until NUM_ALLPASS) {
                val delayed = allpassBuffers[a][allpassPos[a]]
                val buffered = output + delayed * 0.5f
                allpassBuffers[a][allpassPos[a]] = buffered
                allpassPos[a] = (allpassPos[a] + 1) % allpassBuffers[a].size
                output = delayed - buffered * 0.5f
            }

            buf[i] = input * reverbDry + output * reverbWet
        }
    }

    // ── Echo ────────────────────────────────────────────────────────────

    fun updateEchoBuffer() {
        echoSamples = (sampleRate * echoDelayMs / 1000)
        if (echoSamples < 1) echoSamples = 1
        echoBuffer   = FloatArray(echoSamples)
        echoWritePos = 0
    }

    private fun applyEcho(buf: FloatArray) {
        if (echoSamples != sampleRate * echoDelayMs / 1000) updateEchoBuffer()
        for (i in buf.indices) {
            val delayed = echoBuffer[echoWritePos]
            echoBuffer[echoWritePos] = buf[i] + delayed * echoDecay
            echoWritePos = (echoWritePos + 1) % echoSamples
            buf[i] += delayed * echoDecay
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun softClip(x: Float): Float =
        if (x > 1f) 1f - (1f / (1f + (x - 1f) * 4f))
        else if (x < -1f) -1f + (1f / (1f - (x + 1f) * 4f))
        else x

    private fun scaleDelay(delay: Int): Int =
        (delay.toFloat() * sampleRate / 44100f).toInt().coerceAtLeast(1)
}
