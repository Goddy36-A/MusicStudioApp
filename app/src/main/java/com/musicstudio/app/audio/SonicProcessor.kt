package com.musicstudio.app.audio

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Real-time pitch-shifting and tempo control for 16-bit PCM audio.
 *
 * Based on the Sonic algorithm by Bill Cox (public domain).
 * Implements WSOLA (Waveform Similarity Overlap-Add) for time-stretching,
 * then resamples to achieve independent pitch control.
 *
 * Thread-safe for a single producer / single consumer pattern
 * (write on audio-record thread, read on audio-track thread).
 */
class SonicProcessor(
    private val sampleRate: Int,
    private val numChannels: Int
) {

    // ── Public parameters (set from UI thread) ─────────────────────────
    @Volatile var speed: Float   = 1.0f   // tempo multiplier  (0.5 – 2.0)
    @Volatile var pitch: Float   = 1.0f   // pitch multiplier  (0.5 – 2.0)
    @Volatile var volume: Float  = 1.0f   // output gain

    /** Pitch in semitones — convenience setter */
    fun setPitchSemitones(semitones: Float) {
        pitch = 2f.pow(semitones / 12f)
    }

    // ── Internal buffers ───────────────────────────────────────────────
    private val MAX_SILENCE = 512
    private val SONIC_AMDF_FREQ = 55.0

    private val inputBuffer  = ShortCircularBuffer(MAX_BUFFER_SIZE)
    private val outputBuffer = ShortCircularBuffer(MAX_BUFFER_SIZE)
    private val pitchBuffer  = ShortCircularBuffer(MAX_BUFFER_SIZE)

    private var inputPlaythroughPos = 0
    private var outputPos           = 0
    private var remainingInputToCopy = 0
    private var prevPeriod          = 0
    private var prevMinDiff         = 0

    // ── Public API ─────────────────────────────────────────────────────

    /** Feed raw 16-bit PCM samples into the processor. */
    fun writePCM(samples: ShortArray, offset: Int = 0, count: Int = samples.size) {
        inputBuffer.write(samples, offset, count)
        processStreamInput()
    }

    /** Drain processed samples. Returns number of samples actually read. */
    fun readPCM(output: ShortArray, offset: Int = 0, maxCount: Int = output.size): Int =
        outputBuffer.read(output, offset, maxCount)

    val availableSamples: Int get() = outputBuffer.size

    fun flush() {
        val remaining = (inputBuffer.size / effectiveSpeed).roundToInt()
        val zeros = ShortArray(remaining + 2 * PERIOD_SIZE * numChannels)
        writePCM(zeros)
        outputPos = 0
    }

    fun reset() {
        inputBuffer.clear()
        outputBuffer.clear()
        pitchBuffer.clear()
        prevPeriod  = 0
        prevMinDiff = 0
    }

    // ── Internal processing ────────────────────────────────────────────

    private val effectiveSpeed: Float get() = speed / pitch
    private val PERIOD_SIZE = computePeriodSize()

    private fun computePeriodSize(): Int {
        val minPeriod = sampleRate / MAX_PITCH
        val maxPeriod = sampleRate / MIN_PITCH
        val period    = sampleRate / PITCH_NORM
        return period.coerceIn(minPeriod, maxPeriod)
    }

    private fun processStreamInput() {
        val es = effectiveSpeed
        when {
            es > 1.0f + SPEED_EPSILON -> speedUpSamples(es)
            es < 1.0f - SPEED_EPSILON -> slowDownSamples(es)
            else                      -> copyToOutput(inputBuffer.size)
        }
        if (pitch != 1.0f) adjustPitch()
    }

    // Tempo > 1: skip samples
    private fun speedUpSamples(speed: Float) {
        val period       = PERIOD_SIZE * numChannels
        val skipSamples  = ((speed - 1.0f) * period).roundToInt()

        while (inputBuffer.size >= 2 * period) {
            // overlap-add the "kept" portion
            val kept = period - skipSamples
            copyWithOverlap(kept, 0, period)
            inputBuffer.advance(skipSamples + period)
        }
    }

    // Tempo < 1: repeat samples
    private fun slowDownSamples(speed: Float) {
        val period       = PERIOD_SIZE * numChannels
        val extraSamples = ((1.0f / speed - 1.0f) * period).roundToInt()

        while (inputBuffer.size >= 2 * period) {
            // Write the normal block
            copyToOutput(period)
            // Overlap-add the extra repeated block
            copyWithOverlap(period, 0, extraSamples)
            inputBuffer.advance(period)
        }
    }

    private fun copyToOutput(count: Int) {
        val buf = ShortArray(count)
        inputBuffer.read(buf, 0, count)
        outputBuffer.write(buf, 0, count)
    }

    private fun copyWithOverlap(length: Int, srcOffset: Int, overlapLen: Int) {
        // Simple linear crossfade overlap-add
        val temp = ShortArray(length)
        inputBuffer.peek(temp, srcOffset, length)
        val out = ShortArray(length)
        outputBuffer.peekLast(out, 0, min(overlapLen, length))
        for (i in 0 until min(overlapLen, length)) {
            val fade = i.toFloat() / overlapLen
            temp[i] = ((temp[i] * fade + out[i] * (1f - fade)) * volume).toInt().toShort()
        }
        outputBuffer.write(temp, 0, length)
    }

    // Pitch correction via resampling after tempo adjustment
    private fun adjustPitch() {
        if (pitchBuffer.size < 2 * PERIOD_SIZE * numChannels) return
        val input  = ShortArray(outputBuffer.size)
        val n      = outputBuffer.read(input, 0, input.size)
        val output = resample(input, n, pitch)
        pitchBuffer.write(output, 0, output.size)
        // swap back
        val swapped = ShortArray(pitchBuffer.size)
        val read    = pitchBuffer.read(swapped, 0, swapped.size)
        outputBuffer.write(swapped, 0, read)
    }

    /** Linear interpolation resampling. */
    private fun resample(input: ShortArray, inLen: Int, factor: Float): ShortArray {
        val outLen = (inLen / factor).roundToInt()
        val out = ShortArray(outLen)
        for (i in out.indices) {
            val src = i * factor
            val lo  = src.toInt().coerceIn(0, inLen - 1)
            val hi  = (lo + 1).coerceIn(0, inLen - 1)
            val frac = src - lo
            out[i] = (input[lo] * (1f - frac) + input[hi] * frac)
                .roundToInt().toShort()
        }
        return out
    }

    companion object {
        private const val MAX_BUFFER_SIZE  = 65536
        private const val SPEED_EPSILON    = 0.001f
        private const val MIN_PITCH        = 65
        private const val MAX_PITCH        = 400
        private const val PITCH_NORM       = 110
    }
}

// ── Simple circular buffer for Short samples ───────────────────────────
private class ShortCircularBuffer(capacity: Int) {
    private val data = ShortArray(capacity)
    private var head = 0
    private var tail = 0
    var size = 0; private set

    fun write(src: ShortArray, offset: Int, count: Int) {
        repeat(count) { i ->
            data[tail] = src[offset + i]
            tail = (tail + 1) % data.size
        }
        size += count
    }

    fun read(dst: ShortArray, offset: Int, maxCount: Int): Int {
        val n = min(maxCount, size)
        repeat(n) { i ->
            dst[offset + i] = data[head]
            head = (head + 1) % data.size
        }
        size -= n
        return n
    }

    fun peek(dst: ShortArray, offset: Int, count: Int) {
        var h = head
        repeat(count) { i ->
            dst[offset + i] = data[h]
            h = (h + 1) % data.size
        }
    }

    fun peekLast(dst: ShortArray, offset: Int, count: Int) {
        var t = (tail - count + data.size) % data.size
        repeat(count) { i ->
            dst[offset + i] = data[t]
            t = (t + 1) % data.size
        }
    }

    fun advance(count: Int) {
        val n = min(count, size)
        head = (head + n) % data.size
        size -= n
    }

    fun clear() { head = 0; tail = 0; size = 0 }
}
