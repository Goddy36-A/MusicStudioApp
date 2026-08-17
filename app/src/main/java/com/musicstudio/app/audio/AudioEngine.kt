package com.musicstudio.app.audio

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Process
import com.musicstudio.app.data.ReverbPreset
import com.musicstudio.app.data.Scale
import com.musicstudio.app.data.SessionSettings
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Central audio coordinator.
 *
 * Architecture:
 *   ┌──────────────────────────────────────────────────────────────┐
 *   │  Mic → AudioRecord → SonicProcessor → AutoTuneEngine         │
 *   │                    → EffectsChain → ─────────────┐           │
 *   │                                                   ▼           │
 *   │  Track → MediaPlayer → decoded PCM  → AudioMixer → AudioTrack│
 *   │                                                   │           │
 *   │                                           (optional WAV writer)│
 *   └──────────────────────────────────────────────────────────────┘
 */
class AudioEngine(private val context: Context) {

    // ── Constants ──────────────────────────────────────────────────────
    val SAMPLE_RATE  = 44100
    val CHANNEL_IN   = AudioFormat.CHANNEL_IN_MONO
    val CHANNEL_OUT  = AudioFormat.CHANNEL_OUT_MONO
    val ENCODING     = AudioFormat.ENCODING_PCM_16BIT
    val FRAME_SIZE   = 1024   // samples per processing frame

    // ── Processing pipeline ────────────────────────────────────────────
    val sonicProcessor  = SonicProcessor(SAMPLE_RATE, 1)
    val autoTuneEngine  = AutoTuneEngine(SAMPLE_RATE)
    val effectsChain    = EffectsChain(SAMPLE_RATE)

    // ── Volume controls ────────────────────────────────────────────────
    var vocalVolume: Float = 1.0f
    var trackVolume: Float = 0.8f

    // ── State ──────────────────────────────────────────────────────────
    enum class State { IDLE, MONITORING, RECORDING, PLAYING_BACK }

    @Volatile var state: State = State.IDLE; private set

    // ── Hardware handles ───────────────────────────────────────────────
    private var audioRecord:  AudioRecord?  = null
    private var audioTrack:   AudioTrack?   = null
    private var mediaPlayer:  MediaPlayer?  = null

    // ── Coroutines ─────────────────────────────────────────────────────
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null

    // ── WAV export ─────────────────────────────────────────────────────
    private var wavWriter: WavWriter? = null
    private var exportFile: File?     = null

    // ── Amplitude callback ─────────────────────────────────────────────
    var onAmplitudeUpdate: ((rms: Float) -> Unit)? = null

    // ── Settings snapshot ───────────────────────────────────────────────

    fun applySettings(s: SessionSettings) {
        vocalVolume  = s.vocalVolume
        trackVolume  = s.trackVolume
        sonicProcessor.setPitchSemitones(s.pitchSemitones)
        sonicProcessor.speed = s.tempoMultiplier
        autoTuneEngine.enabled  = s.autoTuneEnabled
        autoTuneEngine.strength = s.autoTuneStrength
        autoTuneEngine.scale    = s.autoTuneScale
        effectsChain.reverbPreset = s.reverbPreset
        effectsChain.echoDelayMs  = s.echoDelayMs
        effectsChain.echoDecay    = s.echoDecay
        effectsChain.applyEqSettings(s.eqBass, s.eqMid, s.eqTreble)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Start microphone monitoring (hear yourself with effects; no background track).
     */
    fun startMonitoring() {
        if (state != State.IDLE) return
        state = State.MONITORING
        initAudioRecord()
        initAudioTrack()
        processingJob = engineScope.launch { runProcessingLoop(recordToFile = false) }
    }

    /**
     * Load a background instrumental from [uri] and start the recording session.
     * The mixed output will be saved to [outputFile] as a WAV.
     */
    fun startRecordingSession(trackUri: Uri, outputFile: File) {
        if (state != State.IDLE) stop()
        state      = State.RECORDING
        exportFile = outputFile

        initAudioRecord()
        initAudioTrack()
        initMediaPlayer(trackUri)

        wavWriter = WavWriter(outputFile, SAMPLE_RATE, 1, 16)
        wavWriter!!.open()

        processingJob = engineScope.launch { runProcessingLoop(recordToFile = true) }
        mediaPlayer?.start()
    }

    /**
     * Stop all processing and finalise any in-progress WAV file.
     */
    fun stop() {
        processingJob?.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        wavWriter?.close()
        wavWriter = null
        sonicProcessor.reset()
        state = State.IDLE
    }

    fun release() {
        stop()
        engineScope.cancel()
    }

    // ── Processing loop (runs on IO thread) ───────────────────────────

    private suspend fun runProcessingLoop(recordToFile: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val micBuffer = ShortArray(FRAME_SIZE)
        val outBuffer = ShortArray(FRAME_SIZE)

        while (engineScope.isActive) {
            // 1. Read from microphone
            val read = audioRecord?.read(micBuffer, 0, FRAME_SIZE) ?: break
            if (read <= 0) continue

            // 2. Compute RMS for waveform display
            val rms = computeRms(micBuffer, read)
            withContext(Dispatchers.Main) { onAmplitudeUpdate?.invoke(rms) }

            // 3. Pitch / tempo via Sonic
            sonicProcessor.writePCM(micBuffer, 0, read)

            // 4. Drain Sonic output
            val available = sonicProcessor.availableSamples
            if (available == 0) continue
            val processed = ShortArray(available)
            sonicProcessor.readPCM(processed)

            // 5. AutoTune
            val tuned = autoTuneEngine.process(processed)

            // 6. Effects (EQ, reverb, echo)
            val effected = effectsChain.process(tuned)

            // 7. Apply vocal volume
            val vocal = ShortArray(effected.size) {
                (effected[it] * vocalVolume).toInt().coerceIn(-32768, 32767).toShort()
            }

            // 8. Write to AudioTrack (monitor output)
            audioTrack?.write(vocal, 0, vocal.size)

            // 9. Export mixed audio to WAV
            if (recordToFile) wavWriter?.write(vocal)
        }
    }

    // ── Init helpers ───────────────────────────────────────────────────

    private fun initAudioRecord() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val bufSize = maxOf(minBuf, FRAME_SIZE * 4)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_PERFORMANCE,
            SAMPLE_RATE, CHANNEL_IN, ENCODING, bufSize
        ).also { it.startRecording() }
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(ENCODING)
                    .setChannelMask(CHANNEL_OUT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    private fun initMediaPlayer(uri: Uri) {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(context, uri)
            setVolume(trackVolume, trackVolume)
            prepare()   // synchronous — call on background thread
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────

    private fun computeRms(samples: ShortArray, count: Int): Float {
        var sum = 0.0
        repeat(count) { sum += (samples[it].toDouble() * samples[it]) }
        return sqrt(sum / count).toFloat() / 32768f
    }

    fun setTrackPlaybackParams(pitchSemitones: Float, speedMultiplier: Float) {
        mediaPlayer?.playbackParams = PlaybackParams()
            .setPitch(2f.pow(pitchSemitones / 12f))
            .setSpeed(speedMultiplier)
    }

    private fun Float.pow(exp: Float): Float = Math.pow(this.toDouble(), exp.toDouble()).toFloat()
}

// ── WAV file writer ─────────────────────────────────────────────────────

class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int
) {
    private lateinit var fos: FileOutputStream
    private var dataSize = 0

    fun open() {
        fos = FileOutputStream(file)
        writeHeader(0)   // placeholder; updated on close
    }

    fun write(samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        val bb    = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bb.putShort(it) }
        fos.write(bytes)
        dataSize += bytes.size
    }

    fun close() {
        fos.channel.position(0)
        writeHeader(dataSize)
        fos.close()
    }

    private fun writeHeader(dataLen: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataLen)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)                      // sub-chunk size
        header.putShort(1)                     // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(dataLen)
        fos.write(header.array())
    }
}
