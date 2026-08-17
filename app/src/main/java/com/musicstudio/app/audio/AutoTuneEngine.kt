package com.musicstudio.app.audio

import com.musicstudio.app.data.Scale
import kotlin.math.*

/**
 * AutoTune engine that:
 *   1. Detects fundamental pitch using the YIN algorithm
 *   2. Finds the nearest note in the chosen scale
 *   3. Shifts the pitch to that note via real-time resampling
 *
 * Process a frame of PCM with [process], get back the pitch-corrected frame.
 */
class AutoTuneEngine(private val sampleRate: Int) {

    var enabled:  Boolean = false
    var strength: Float   = 0.7f   // 0 = none, 1 = hard snap
    var scale:    Scale   = Scale.CHROMATIC
    var rootNote: Int     = 0      // 0 = C, 1 = C#, 2 = D …

    // ── YIN thresholds ─────────────────────────────────────────────────
    private val YIN_THRESHOLD   = 0.15f
    private val MIN_FREQUENCY   = 80.0          // Hz – roughly E2
    private val MAX_FREQUENCY   = 1200.0        // Hz – roughly D6
    private val minPeriod get() = (sampleRate / MAX_FREQUENCY).toInt()
    private val maxPeriod get() = (sampleRate / MIN_FREQUENCY).toInt()

    // Smoothing: avoid jumpy pitch corrections
    private var smoothedShift   = 0f
    private val SMOOTH_FACTOR   = 0.25f

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Process one frame of 16-bit mono PCM.
     * Returns the pitch-corrected frame (same length).
     */
    fun process(frame: ShortArray): ShortArray {
        if (!enabled) return frame

        val freq = detectPitch(frame)
        if (freq <= 0.0) return frame   // unpitched / silence

        val detectedMidi  = freqToMidi(freq)
        val correctedMidi = snapToScale(detectedMidi, scale, rootNote)
        val semitoneShift = (correctedMidi - detectedMidi) * strength

        // Smooth to prevent zipper noise
        smoothedShift = smoothedShift + SMOOTH_FACTOR * (semitoneShift.toFloat() - smoothedShift)

        return if (abs(smoothedShift) < 0.05f) frame
        else pitchShiftFrame(frame, smoothedShift)
    }

    // ── YIN algorithm ──────────────────────────────────────────────────

    private fun detectPitch(frame: ShortArray): Double {
        val n = frame.size
        if (n < 2 * maxPeriod) return -1.0

        // Step 1: difference function
        val diff = DoubleArray(maxPeriod)
        for (tau in 1 until maxPeriod) {
            var sum = 0.0
            for (j in 0 until (n - maxPeriod)) {
                val delta = frame[j].toDouble() - frame[j + tau].toDouble()
                sum += delta * delta
            }
            diff[tau] = sum
        }

        // Step 2: cumulative mean normalised difference
        val cmnd = DoubleArray(maxPeriod)
        cmnd[0] = 1.0
        var runningSum = 0.0
        for (tau in 1 until maxPeriod) {
            runningSum += diff[tau]
            cmnd[tau] = if (runningSum == 0.0) 1.0 else diff[tau] * tau / runningSum
        }

        // Step 3: find first dip below threshold
        var tau = minPeriod
        while (tau < maxPeriod) {
            if (cmnd[tau] < YIN_THRESHOLD) {
                // Step 4: parabolic interpolation around minimum
                if (tau + 1 < maxPeriod && cmnd[tau + 1] < cmnd[tau]) {
                    tau++; continue
                }
                val betterTau = interpolatePeak(cmnd, tau)
                return sampleRate / betterTau
            }
            tau++
        }

        // No clear pitch found — return global minimum if below 0.5
        val minIdx = cmnd.indices.minByOrNull { cmnd[it] } ?: return -1.0
        return if (cmnd[minIdx] < 0.5) sampleRate / minIdx.toDouble() else -1.0
    }

    private fun interpolatePeak(arr: DoubleArray, tau: Int): Double {
        if (tau <= 0 || tau >= arr.size - 1) return tau.toDouble()
        val prev = arr[tau - 1]
        val curr = arr[tau]
        val next = arr[tau + 1]
        val denom = 2.0 * (2.0 * curr - prev - next)
        return if (denom == 0.0) tau.toDouble()
        else tau + (next - prev) / denom
    }

    // ── Musical helpers ─────────────────────────────────────────────────

    private fun freqToMidi(freq: Double): Double =
        12.0 * log2(freq / 440.0) + 69.0

    private fun midiToFreq(midi: Double): Double =
        440.0 * 2.0.pow((midi - 69.0) / 12.0)

    /**
     * Snap a continuous MIDI note number to the nearest degree of [scale]
     * starting at [rootNote] (0=C).
     */
    private fun snapToScale(midi: Double, scale: Scale, root: Int): Double {
        val octave   = floor(midi / 12.0).toInt()
        val semitone = positiveMod(midi - octave * 12.0 - root, 12.0)

        // Find nearest scale degree
        val degrees = scale.semitones
        var closest = degrees[0]
        var minDist = Double.MAX_VALUE
        for (degree in degrees) {
            val dist = abs(semitone - degree)
            val distWrapped = min(dist, 12.0 - dist)
            if (distWrapped < minDist) { minDist = distWrapped; closest = degree }
        }
        return (octave * 12.0 + root + closest).toDouble()
    }

    // ── Pitch shifting via resampling ───────────────────────────────────

    private fun pitchShiftFrame(frame: ShortArray, semitones: Float): ShortArray {
        val factor  = 2f.pow(semitones / 12f)
        val outLen  = (frame.size / factor).roundToInt().coerceAtLeast(1)
        val out     = ShortArray(outLen)
        for (i in out.indices) {
            val src  = i * factor
            val lo   = src.toInt().coerceIn(0, frame.size - 1)
            val hi   = (lo + 1).coerceIn(0, frame.size - 1)
            val frac = src - lo
            out[i]   = (frame[lo] * (1f - frac) + frame[hi] * frac).roundToInt().toShort()
        }
        return out
    }
}

/** Positive modulo (never negative). */
private fun positiveMod(value: Double, divisor: Double): Double {
    val r = value % divisor
    return if (r < 0.0) r + divisor else r
}
