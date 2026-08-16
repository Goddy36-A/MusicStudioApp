package com.musicstudio.app.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Static utilities for audio file operations used across the app.
 */
object AudioUtils {

    // ── WAV helpers ────────────────────────────────────────────────────

    /** Returns the duration of a WAV file in milliseconds without fully decoding it. */
    fun wavDurationMs(file: File): Long {
        if (!file.exists() || file.length() < 44) return 0L
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(24);  val sampleRate  = raf.readInt().reverseBytes()
                raf.seek(28);  val byteRate     = raf.readInt().reverseBytes()
                val dataSize = file.length() - 44
                if (byteRate == 0) 0L else (dataSize * 1000L / byteRate)
            }
        } catch (e: Exception) { 0L }
    }

    /** Format milliseconds → "m:ss" */
    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /** File size in human-readable form. */
    fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024     -> "%.0f KB".format(bytes / 1_024.0)
        else               -> "$bytes B"
    }

    // ── WAV normalization ──────────────────────────────────────────────

    /**
     * Normalize the peak amplitude of a WAV file to [-0.95, 0.95] and
     * write the result to [outputFile]. Returns true on success.
     */
    fun normalizeWav(inputFile: File, outputFile: File): Boolean {
        return try {
            val samples = readWavSamples(inputFile)
            if (samples.isEmpty()) return false

            val peak = samples.maxOf { Math.abs(it.toInt()) }.toFloat()
            if (peak == 0f) return false

            val gain    = 31130f / peak   // target ≈ 0.95 * 32768
            val normed  = ShortArray(samples.size) {
                (samples[it] * gain).toInt().coerceIn(-32768, 32767).toShort()
            }
            writeWavSamples(outputFile, normed, 44100, 1, 16)
            true
        } catch (e: Exception) { false }
    }

    // ── Read / write raw WAV PCM ───────────────────────────────────────

    fun readWavSamples(file: File): ShortArray {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(40)
            val dataSize = raf.readInt().reverseBytes()
            val numSamples = dataSize / 2
            val buf = ByteArray(dataSize)
            raf.seek(44)
            raf.read(buf)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            return ShortArray(numSamples) { bb.short }
        }
    }

    fun writeWavSamples(
        file: File,
        samples: ShortArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val dataSize  = samples.size * 2
        val byteRate  = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)                          // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(dataSize)
            fos.write(header.array())

            val dataBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { dataBytes.putShort(it) }
            fos.write(dataBytes.array())
        }
    }

    // ── Media info via MediaExtractor ──────────────────────────────────

    /**
     * Extract duration of any media file (mp3, aac, ogg, wav, flac)
     * supported by the device codec.
     */
    fun getMediaDurationMs(context: Context, uri: Uri): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var duration = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    duration = format.getLong(MediaFormat.KEY_DURATION) / 1000
                    break
                }
            }
            duration
        } catch (e: Exception) { 0L }
        finally { extractor.release() }
    }

    // ── Mix two mono WAV files ─────────────────────────────────────────

    /**
     * Mix [vocals] and [backing] into [output], applying per-channel volume [vocalVol]/[trackVol].
     * Both inputs must be mono 16-bit PCM WAV at the same sample rate.
     */
    fun mixWavFiles(
        vocals:    File,
        backing:   File,
        output:    File,
        vocalVol:  Float = 1.0f,
        trackVol:  Float = 0.8f
    ): Boolean = try {
        val v = readWavSamples(vocals)
        val b = readWavSamples(backing)
        val len    = maxOf(v.size, b.size)
        val mixed  = ShortArray(len) { i ->
            val vs = if (i < v.size) v[i] * vocalVol else 0f
            val bs = if (i < b.size) b[i] * trackVol else 0f
            (vs + bs).toInt().coerceIn(-32768, 32767).toShort()
        }
        writeWavSamples(output, mixed, 44100, 1, 16)
        true
    } catch (e: Exception) { false }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun Int.reverseBytes(): Int =
        (this and 0xFF shl 24) or
        (this and 0xFF00 shl 8) or
        (this ushr 8 and 0xFF00) or
        (this ushr 24)
}
